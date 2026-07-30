package dev.eugene.astroexport.review;

import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CandidateSnapshotStore {
  private static final Set<String> CANDIDATE_FILES =
      Set.of("ru.md", "en.md", "references.json");

  private final AtomicExchange atomicExchange;
  private final IoHooks ioHooks;

  public CandidateSnapshotStore() {
    this(new JnaAtomicExchange(), new IoHooks() { });
  }

  CandidateSnapshotStore(AtomicExchange atomicExchange) {
    this(atomicExchange, new IoHooks() { });
  }

  CandidateSnapshotStore(AtomicExchange atomicExchange, IoHooks ioHooks) {
    this.atomicExchange = Objects.requireNonNull(atomicExchange, "atomicExchange");
    this.ioHooks = Objects.requireNonNull(ioHooks, "ioHooks");
  }

  public PendingCandidate stage(
      Path pageDirectory,
      byte[] russian,
      byte[] english,
      byte[] references) {
    Objects.requireNonNull(russian, "russian");
    Objects.requireNonNull(english, "english");
    Objects.requireNonNull(references, "references");
    Path page = Objects.requireNonNull(pageDirectory, "pageDirectory")
        .toAbsolutePath()
        .normalize();
    if (Files.isSymbolicLink(page)
        || !Files.isDirectory(page, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "page directory must be a non-symbolic directory: " + page);
    }
    Path staging = null;
    try {
      staging = Files.createTempDirectory(page, ".candidate-stage-");
      byte[] stagedRussian = russian.clone();
      byte[] stagedEnglish = english.clone();
      byte[] stagedReferences = references.clone();
      ioHooks.beforeWrite(staging.resolve("ru.md"));
      writeForced(staging.resolve("ru.md"), stagedRussian);
      ioHooks.beforeWrite(staging.resolve("en.md"));
      writeForced(staging.resolve("en.md"), stagedEnglish);
      ioHooks.beforeWrite(staging.resolve("references.json"));
      writeForced(staging.resolve("references.json"), stagedReferences);
      forceDirectory(staging);
      return new FilePendingCandidate(
          page.resolve("candidate"),
          staging,
          stagedRussian,
          stagedEnglish,
          stagedReferences);
    } catch (IOException | RuntimeException error) {
      if (staging != null) {
        try {
          ioHooks.deleteTree(staging);
        } catch (IOException cleanupError) {
          cleanupError.addSuppressed(error);
          throw new CandidateSnapshotRecoveryException(
              "cannot clean failed staged candidate snapshot " + staging,
              RecoveryDisposition.STAGED_CANDIDATE,
              page.resolve("candidate"),
              List.of(staging),
              cleanupError);
        }
      }
      if (error instanceof RuntimeException runtimeError) {
        throw runtimeError;
      }
      throw new IllegalStateException("cannot stage candidate snapshot " + page, error);
    }
  }

  public interface PendingCandidate extends AutoCloseable {
    CommitResult commit(List<WorkflowStateService.SnapshotGuard> guards);

    CommitResult commit(
        List<WorkflowStateService.SnapshotGuard> preSwapGuards,
        List<WorkflowStateService.SnapshotGuard> postSwapGuards);

    @Override
    void close();
  }

  interface IoHooks {
    default void beforeWrite(Path path) throws IOException { }

    default void deleteTree(Path root) throws IOException {
      CandidateSnapshotStore.deleteTree(root);
    }
  }

  public record CommitResult(List<Path> recoveryPaths) {
    public CommitResult {
      recoveryPaths = List.copyOf(recoveryPaths);
    }
  }

  public static final class ConcurrentCandidateSnapshotException
      extends IllegalStateException {
    public ConcurrentCandidateSnapshotException(String message) {
      super(message);
    }
  }

  public enum RecoveryDisposition {
    CANDIDATE_VISIBLE,
    STAGED_CANDIDATE
  }

  public static final class CandidateSnapshotRecoveryException
      extends IllegalStateException {
    private final RecoveryDisposition disposition;
    private final Path candidatePath;
    private final List<Path> recoveryPaths;

    public CandidateSnapshotRecoveryException(
        String message,
        RecoveryDisposition disposition,
        Path candidatePath,
        List<Path> recoveryPaths,
        Throwable cause) {
      super(message, cause);
      this.disposition = Objects.requireNonNull(disposition, "disposition");
      this.candidatePath = Objects.requireNonNull(candidatePath, "candidatePath");
      this.recoveryPaths = List.copyOf(recoveryPaths);
    }

    public RecoveryDisposition disposition() {
      return disposition;
    }

    public Path candidatePath() {
      return candidatePath;
    }

    public List<Path> recoveryPaths() {
      return recoveryPaths;
    }
  }

  private final class FilePendingCandidate implements PendingCandidate {
    private enum OwnershipState {
      STAGED_NEW,
      FIRST_CANDIDATE_VISIBLE,
      REPLACEMENT_VISIBLE_WITH_DISPLACED,
      COMMITTED
    }

    private final Path candidate;
    private final Path staging;
    private final byte[] russian;
    private final byte[] english;
    private final byte[] references;
    private OwnershipState ownershipState = OwnershipState.STAGED_NEW;
    private boolean closed;

    private FilePendingCandidate(
        Path candidate,
        Path staging,
        byte[] russian,
        byte[] english,
        byte[] references) {
      this.candidate = candidate;
      this.staging = staging;
      this.russian = russian;
      this.english = english;
      this.references = references;
    }

    @Override
    public CommitResult commit(List<WorkflowStateService.SnapshotGuard> guards) {
      return commit(guards, guards);
    }

    @Override
    public CommitResult commit(
        List<WorkflowStateService.SnapshotGuard> preSwapGuards,
        List<WorkflowStateService.SnapshotGuard> postSwapGuards) {
      if (closed) {
        throw new IllegalStateException("candidate snapshot staging is closed");
      }
      if (ownershipState == OwnershipState.COMMITTED) {
        throw new IllegalStateException("candidate snapshot is already committed");
      }
      if (ownershipState != OwnershipState.STAGED_NEW) {
        throw new IllegalStateException(
            "candidate snapshot commit cannot be retried after an incomplete rollback");
      }
      List<WorkflowStateService.SnapshotGuard> checkedPreSwapGuards =
          List.copyOf(Objects.requireNonNull(preSwapGuards, "preSwapGuards"));
      List<WorkflowStateService.SnapshotGuard> checkedPostSwapGuards =
          List.copyOf(Objects.requireNonNull(postSwapGuards, "postSwapGuards"));
      if (!guardsMatch(checkedPreSwapGuards)
          || !guardsMatch(checkedPostSwapGuards)) {
        throw concurrentUpdate();
      }

      boolean replacement = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
      try {
        if (replacement) {
          validateCandidateLayout(candidate);
          atomicExchange.exchange(candidate, staging);
          ownershipState = OwnershipState.REPLACEMENT_VISIBLE_WITH_DISPLACED;
        } else {
          Files.move(staging, candidate, StandardCopyOption.ATOMIC_MOVE);
          ownershipState = OwnershipState.FIRST_CANDIDATE_VISIBLE;
        }
        if (!preSwapGuardsStillMatchDisplacedSnapshot(checkedPreSwapGuards)
            || !guardsMatch(checkedPostSwapGuards)
            || !visibleTripleMatches(candidate, russian, english, references)) {
          rollback(concurrentUpdate());
          throw concurrentUpdate();
        }
        try {
          forceDirectory(candidate.getParent());
        } catch (IOException | RuntimeException error) {
          rollback(error);
          throw commitFailure(error);
        }
        ownershipState = OwnershipState.COMMITTED;
        if (replacement) {
          try {
            ioHooks.deleteTree(staging);
          } catch (IOException cleanupError) {
            return new CommitResult(List.of(staging));
          }
        }
        return new CommitResult(List.of());
      } catch (ConcurrentCandidateSnapshotException
          | CandidateSnapshotRecoveryException error) {
        throw error;
      } catch (IOException error) {
        throw commitFailure(error);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (ownershipState != OwnershipState.STAGED_NEW
          || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      try {
        ioHooks.deleteTree(staging);
      } catch (IOException error) {
        throw new CandidateSnapshotRecoveryException(
            "cannot clean staged candidate snapshot " + staging,
            RecoveryDisposition.STAGED_CANDIDATE,
            candidate,
            List.of(staging),
            error);
      }
    }

    private void rollback(Throwable failure) {
      try {
        switch (ownershipState) {
          case REPLACEMENT_VISIBLE_WITH_DISPLACED ->
              atomicExchange.exchange(candidate, staging);
          case FIRST_CANDIDATE_VISIBLE ->
              Files.move(candidate, staging, StandardCopyOption.ATOMIC_MOVE);
          default -> throw new IllegalStateException(
              "candidate snapshot rollback has no visible candidate");
        }
        forceDirectory(candidate.getParent());
        ownershipState = OwnershipState.STAGED_NEW;
      } catch (IOException | RuntimeException rollbackError) {
        rollbackError.addSuppressed(failure);
        List<Path> recoveryPaths = Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
            ? List.of(candidate, staging)
            : List.of(candidate);
        throw new CandidateSnapshotRecoveryException(
            "candidate snapshot rollback incomplete: " + candidate,
            RecoveryDisposition.CANDIDATE_VISIBLE,
            candidate,
            recoveryPaths,
            rollbackError);
      }
    }

    private ConcurrentCandidateSnapshotException concurrentUpdate() {
      return new ConcurrentCandidateSnapshotException(
          "candidate snapshot inputs changed during commit: " + candidate);
    }

    private boolean preSwapGuardsStillMatchDisplacedSnapshot(
        List<WorkflowStateService.SnapshotGuard> guards) {
      for (WorkflowStateService.SnapshotGuard guard : guards) {
        Objects.requireNonNull(guard, "guard");
        Path path = guard.path();
        Path checked = path;
        if (path != null && path.normalize().startsWith(candidate)) {
          checked = staging.resolve(candidate.relativize(path.normalize()));
        }
        if (!guardMatches(checked, guard.expectedContent())) {
          return false;
        }
      }
      return true;
    }

    private RuntimeException commitFailure(Exception error) {
      if (error instanceof RuntimeException runtimeError) {
        return runtimeError;
      }
      return new IllegalStateException(
          "cannot commit candidate snapshot " + candidate + ": " + error.getMessage(),
          error);
    }
  }

  private static boolean visibleTripleMatches(
      Path candidate,
      byte[] russian,
      byte[] english,
      byte[] references) {
    try {
      validateCandidateLayout(candidate);
      return Arrays.equals(russian, Files.readAllBytes(candidate.resolve("ru.md")))
          && Arrays.equals(english, Files.readAllBytes(candidate.resolve("en.md")))
          && Arrays.equals(references, Files.readAllBytes(candidate.resolve("references.json")));
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  private static boolean guardsMatch(List<WorkflowStateService.SnapshotGuard> guards) {
    for (WorkflowStateService.SnapshotGuard guard : guards) {
      Objects.requireNonNull(guard, "guard");
      if (!guardMatches(guard.path(), guard.expectedContent())) {
        return false;
      }
    }
    return true;
  }

  private static boolean guardMatches(Path path, byte[] expectedContent) {
    if (path == null
        || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      return Arrays.equals(expectedContent, Files.readAllBytes(path));
    } catch (IOException error) {
      return false;
    }
  }

  private static void validateCandidateLayout(Path candidate) throws IOException {
    if (Files.isSymbolicLink(candidate)
        || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "candidate snapshot must be a non-symbolic directory: " + candidate);
    }
    Set<String> entries;
    try (var paths = Files.list(candidate)) {
      entries = paths
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    if (!entries.equals(CANDIDATE_FILES)) {
      throw new IllegalArgumentException(
          "candidate snapshot must contain only ru.md, en.md, and references.json: "
              + candidate);
    }
    for (String file : CANDIDATE_FILES) {
      validateCandidateLeaf(candidate.resolve(file));
    }
  }

  private static void validateCandidateLeaf(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "candidate snapshot leaf must be a regular file: " + path);
    }
    try {
      Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
      if (links instanceof Number count && count.longValue() != 1) {
        throw new IllegalArgumentException(
            "candidate snapshot leaf must have one hard link: " + path);
      }
    } catch (UnsupportedOperationException ignored) {
      // Supported macOS/Linux filesystems expose nlink; type checks still apply elsewhere.
    }
  }

  private static void writeForced(Path path, byte[] content) throws IOException {
    try (FileChannel channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
          throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException error)
          throws IOException {
        if (error != null) {
          throw error;
        }
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
