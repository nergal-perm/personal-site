package dev.eugene.astroexport.workflow;

import dev.eugene.astroexport.frontmatter.WorkflowFrontmatterEditor;
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable, snapshot-guarded workflow frontmatter updates. */
public final class WorkflowStateService {
  private static final Set<String> WORKFLOW_STATUSES = Set.of(
      "metadata_blocked",
      "translating",
      "ready_for_review",
      "ready_to_publish",
      "translation_failed",
      "stale");
  private static final Set<String> TRANSLATION_STATUSES = Set.of("generated", "reviewed");
  private final AtomicExchange atomicExchange;
  private final IoHooks ioHooks;

  public WorkflowStateService() {
    this(new JnaAtomicExchange(), new IoHooks() { });
  }

  public WorkflowStateService(AtomicExchange atomicExchange) {
    this(atomicExchange, new IoHooks() { });
  }

  WorkflowStateService(AtomicExchange atomicExchange, IoHooks ioHooks) {
    this.atomicExchange = atomicExchange;
    this.ioHooks = ioHooks;
  }

  public void updateWorkflowState(
      Path source,
      WorkflowUpdate update,
      Instant updatedAt,
      SnapshotGuard... guards) throws IOException {
    validate(update, updatedAt);
    byte[] original = Files.readAllBytes(source);
    SnapshotGuard sourceGuard = Arrays.stream(guards)
        .filter(guard -> samePath(source, guard.path()))
        .findFirst()
        .orElse(new SnapshotGuard(source, original));
    if (!Arrays.equals(original, sourceGuard.expectedContent())) {
      throw new ConcurrentFileUpdateException("source note content changed");
    }
    List<SnapshotGuard> companions = Arrays.stream(guards)
        .filter(guard -> !samePath(source, guard.path()))
        .toList();
    String content = decode(original);
    Map<String, String> values = new LinkedHashMap<>();
    values.put("publicWorkflowStatus", update.status());
    values.put("publicTranslationStatus",
        update.translationStatus() == null ? "" : update.translationStatus());
    values.put("publicWorkflowUpdated", updatedAt.toString());
    values.put("publicWorkflowDiagnostic", update.diagnostic());
    byte[] payload = WorkflowFrontmatterEditor.patch(content, values)
        .getBytes(StandardCharsets.UTF_8);
    guardedReplace(source, payload, sourceGuard.expectedContent(), companions);
  }

  public void clearWorkflowState(Path source, Instant updatedAt) throws IOException {
    updateFields(source, Map.of(
        "publicWorkflowStatus", "",
        "publicTranslationStatus", "",
        "publicWorkflowUpdated", updatedAt.toString(),
        "publicWorkflowDiagnostic", ""));
  }

  private void updateFields(Path source, Map<String, String> values) throws IOException {
    byte[] original = Files.readAllBytes(source);
    byte[] payload = WorkflowFrontmatterEditor.patch(decode(original), values)
        .getBytes(StandardCharsets.UTF_8);
    guardedReplace(source, payload, original, List.of());
  }

  private void guardedReplace(
      Path target,
      byte[] payload,
      byte[] expected,
      List<SnapshotGuard> companions) throws IOException {
    Path temporary = Files.createTempFile(
        target.getParent(), "." + target.getFileName() + ".", ".tmp");
    boolean retainTemporary = false;
    try {
      copyPermissions(target, temporary);
      ioHooks.beforeTemporaryWrite(temporary);
      writeDurably(temporary, payload);
      assertSnapshot(target, expected, "source note content changed");
      for (SnapshotGuard guard : companions) {
        assertSnapshot(guard.path(), guard.expectedContent(), "guarded file content changed");
      }
      atomicExchange.exchange(target, temporary);
      byte[] displaced;
      try {
        displaced = Files.readAllBytes(temporary);
      } catch (IOException error) {
        Path preserved = preserveRecovery(temporary, target);
        retainTemporary = preserved.equals(temporary);
        throw new ConcurrentFileUpdateException(
            "displaced target could not be verified after atomic exchange",
            true,
            preserved,
            error);
      }
      if (!Arrays.equals(displaced, expected) || !snapshotsMatch(companions)) {
        Path preserved = rollback(target, temporary, payload);
        retainTemporary = temporary.equals(preserved);
        throw new ConcurrentFileUpdateException(
            "guarded file changed at the atomic commit boundary",
            false,
            preserved);
      }
      boolean targetIsNew = matches(target, payload);
      boolean displacedIsExpected = matches(temporary, expected);
      boolean companionsCurrent = snapshotsMatch(companions);
      if (targetIsNew && displacedIsExpected && companionsCurrent) {
        forceDirectory(target.getParent());
        return;
      }
      if (targetIsNew) {
        Path preserved = rollback(target, temporary, payload);
        retainTemporary = temporary.equals(preserved);
        String message = displacedIsExpected
            ? "companion file changed immediately after atomic exchange"
            : "displaced target changed before final commit boundary verification";
        throw new ConcurrentFileUpdateException(message, false, preserved);
      }
      Path preserved = preserveRecovery(temporary, target);
      retainTemporary = preserved.equals(temporary);
      throw new ConcurrentFileUpdateException(
          "target changed immediately after atomic exchange; displaced bytes were preserved",
          true,
          preserved);
    } catch (ConcurrentFileUpdateException error) {
      retainTemporary = temporary.equals(error.preservedPath());
      throw error;
    } finally {
      if (!retainTemporary) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The guarded result is already determined; cleanup is best effort.
        }
      }
      forceDirectory(target.getParent());
    }
  }

  private Path rollback(Path target, Path temporary, byte[] payload) throws IOException {
    try {
      atomicExchange.exchange(target, temporary);
    } catch (IOException rollbackError) {
      Path preserved = preserveRecovery(temporary, target);
      throw new ConcurrentFileUpdateException(
          "guarded write conflicted and atomic rollback failed",
          true,
          preserved,
          rollbackError);
    }
    return matches(temporary, payload) ? null : preserveRecovery(temporary, target);
  }

  private Path preserveRecovery(Path temporary, Path target) throws IOException {
    try {
      return ioHooks.preserve(temporary, target);
    } catch (ConcurrentFileUpdateException error) {
      throw error;
    } catch (IOException error) {
      throw new ConcurrentFileUpdateException(
          "conflicting bytes remain in the staged temporary file",
          true,
          temporary,
          error);
    }
  }

  private static Path preserve(Path temporary, Path target) throws IOException {
    try {
      Path directory = Files.createTempDirectory(
          target.getParent(), "." + target.getFileName() + ".astro-export-conflict-");
      Path preserved = directory.resolve(target.getFileName());
      Files.move(temporary, preserved, StandardCopyOption.ATOMIC_MOVE);
      return preserved;
    } catch (IOException error) {
      throw new ConcurrentFileUpdateException(
          "conflicting bytes remain in the staged temporary file",
          true,
          temporary,
          error);
    }
  }

  private static void writeDurably(Path path, byte[] payload) throws IOException {
    try (FileChannel channel = FileChannel.open(
        path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer pending = ByteBuffer.wrap(payload);
      while (pending.hasRemaining()) {
        channel.write(pending);
      }
      channel.force(true);
    }
    forceDirectory(path.getParent());
  }

  private static void copyPermissions(Path source, Path target) throws IOException {
    try {
      Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
    } catch (UnsupportedOperationException ignored) {
      // POSIX permissions are preserved on the supported macOS/Linux filesystems.
    }
  }

  private static void forceDirectory(Path directory) {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException | UnsupportedOperationException ignored) {
      // Directory fsync is not exposed by every Java filesystem provider.
    }
  }

  private static void assertSnapshot(Path path, byte[] expected, String message)
      throws ConcurrentFileUpdateException {
    if (!matches(path, expected)) {
      throw new ConcurrentFileUpdateException(message);
    }
  }

  private static boolean snapshotsMatch(List<SnapshotGuard> guards) {
    return guards.stream().allMatch(guard -> matches(guard.path(), guard.expectedContent()));
  }

  private static boolean matches(Path path, byte[] expected) {
    try {
      return Arrays.equals(Files.readAllBytes(path), expected);
    } catch (IOException error) {
      return false;
    }
  }

  private static String decode(byte[] content) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("source note must be valid UTF-8", error);
    }
  }

  private static void validate(WorkflowUpdate update, Instant updatedAt) {
    if (!WORKFLOW_STATUSES.contains(update.status())) {
      throw new IllegalArgumentException("unsupported workflow status: " + update.status());
    }
    if (update.translationStatus() != null
        && !TRANSLATION_STATUSES.contains(update.translationStatus())) {
      throw new IllegalArgumentException(
          "unsupported translation status: " + update.translationStatus());
    }
    if (update.diagnostic() == null) {
      throw new IllegalArgumentException("workflow diagnostic must be a string");
    }
    if (updatedAt == null) {
      throw new IllegalArgumentException("workflow updatedAt must be an instant");
    }
  }

  private static boolean samePath(Path first, Path second) {
    return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
  }

  public record WorkflowUpdate(
      String status,
      String translationStatus,
      String diagnostic) { }

  public record SnapshotGuard(Path path, byte[] expectedContent) {
    public SnapshotGuard {
      expectedContent = expectedContent.clone();
    }

    @Override
    public byte[] expectedContent() {
      return expectedContent.clone();
    }
  }

  interface IoHooks {
    default void beforeTemporaryWrite(Path temporary) throws IOException { }

    default Path preserve(Path temporary, Path target) throws IOException {
      return WorkflowStateService.preserve(temporary, target);
    }
  }

  public static final class ConcurrentFileUpdateException extends IOException {
    private final boolean committed;
    private final Path preservedPath;

    public ConcurrentFileUpdateException(String message) {
      this(message, false, null, null);
    }

    public ConcurrentFileUpdateException(
        String message,
        boolean committed,
        Path preservedPath) {
      this(message, committed, preservedPath, null);
    }

    public ConcurrentFileUpdateException(
        String message,
        boolean committed,
        Path preservedPath,
        Throwable cause) {
      super(message, cause);
      this.committed = committed;
      this.preservedPath = preservedPath;
    }

    public boolean committed() {
      return committed;
    }

    public Path preservedPath() {
      return preservedPath;
    }
  }
}
