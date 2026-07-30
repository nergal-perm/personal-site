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

  public CandidateSnapshotStore() {
    this(new JnaAtomicExchange());
  }

  CandidateSnapshotStore(AtomicExchange atomicExchange) {
    this.atomicExchange = Objects.requireNonNull(atomicExchange, "atomicExchange");
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
      writeForced(staging.resolve("ru.md"), stagedRussian);
      writeForced(staging.resolve("en.md"), stagedEnglish);
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
          deleteTree(staging);
        } catch (IOException cleanupError) {
          error.addSuppressed(cleanupError);
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

    @Override
    void close();
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

  private final class FilePendingCandidate implements PendingCandidate {
    private final Path candidate;
    private final Path staging;
    private final byte[] russian;
    private final byte[] english;
    private final byte[] references;
    private boolean committed;
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
      if (closed) {
        throw new IllegalStateException("candidate snapshot staging is closed");
      }
      if (committed) {
        throw new IllegalStateException("candidate snapshot is already committed");
      }
      List<WorkflowStateService.SnapshotGuard> checkedGuards =
          List.copyOf(Objects.requireNonNull(guards, "guards"));
      if (!guardsMatch(checkedGuards)) {
        throw concurrentUpdate();
      }

      boolean replacement = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
      try {
        if (replacement) {
          validateCandidateLayout(candidate);
          atomicExchange.exchange(candidate, staging);
        } else {
          Files.move(staging, candidate, StandardCopyOption.ATOMIC_MOVE);
        }
        if (!guardsMatch(checkedGuards)
            || !visibleTripleMatches(candidate, russian, english, references)) {
          rollback(replacement);
          throw concurrentUpdate();
        }
        forceDirectory(candidate.getParent());
        committed = true;
        if (replacement) {
          deleteTree(staging);
        }
        return new CommitResult(List.of());
      } catch (IOException error) {
        throw new IllegalStateException("cannot commit candidate snapshot " + candidate, error);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (committed || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      try {
        deleteTree(staging);
      } catch (IOException error) {
        throw new IllegalStateException("cannot clean staged candidate snapshot " + staging, error);
      }
    }

    private void rollback(boolean replacement) throws IOException {
      if (replacement) {
        atomicExchange.exchange(candidate, staging);
      } else if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(candidate, staging, StandardCopyOption.ATOMIC_MOVE);
      }
      forceDirectory(candidate.getParent());
    }

    private ConcurrentCandidateSnapshotException concurrentUpdate() {
      return new ConcurrentCandidateSnapshotException(
          "candidate snapshot inputs changed during commit: " + candidate);
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
      Path path = guard.path();
      if (path == null
          || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(path)
          || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      try {
        if (!Arrays.equals(guard.expectedContent(), Files.readAllBytes(path))) {
          return false;
        }
      } catch (IOException error) {
        return false;
      }
    }
    return true;
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
