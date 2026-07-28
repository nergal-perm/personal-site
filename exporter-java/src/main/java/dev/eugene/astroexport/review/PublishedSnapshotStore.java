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

public final class PublishedSnapshotStore {
  private static final Set<String> PUBLISHED_FILES = Set.of("ru.md", "en.md");

  private final AtomicExchange atomicExchange;
  private final IoHooks ioHooks;

  PublishedSnapshotStore() {
    this(new JnaAtomicExchange(), new IoHooks() { });
  }

  PublishedSnapshotStore(AtomicExchange atomicExchange, IoHooks ioHooks) {
    this.atomicExchange = Objects.requireNonNull(atomicExchange, "atomicExchange");
    this.ioHooks = Objects.requireNonNull(ioHooks, "ioHooks");
  }

  PendingSnapshot stage(Path pageDirectory, byte[] russian, byte[] english) {
    Objects.requireNonNull(russian, "russian");
    Objects.requireNonNull(english, "english");
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
      staging = Files.createTempDirectory(page, ".published-stage-");
      byte[] stagedRussian = russian.clone();
      byte[] stagedEnglish = english.clone();
      writeForced(staging.resolve("ru.md"), stagedRussian);
      writeForced(staging.resolve("en.md"), stagedEnglish);
      return new FilePendingSnapshot(
          page.resolve("published"),
          staging,
          stagedRussian,
          stagedEnglish);
    } catch (IOException error) {
      if (staging != null) {
        try {
          deleteTree(staging);
        } catch (IOException cleanupError) {
          error.addSuppressed(cleanupError);
        }
      }
      throw new IllegalStateException("cannot stage published snapshot " + page, error);
    }
  }

  interface PendingSnapshot extends AutoCloseable {
    CommitResult commit(List<WorkflowStateService.SnapshotGuard> guards);

    @Override
    void close();
  }

  record CommitResult(List<Path> recoveryPaths) {
    CommitResult {
      recoveryPaths = List.copyOf(recoveryPaths);
    }
  }

  interface IoHooks {
    default void afterVisibleCommit(Path published) throws IOException { }

    default void deleteTree(Path root) throws IOException {
      PublishedSnapshotStore.deleteTree(root);
    }
  }

  public static final class ConcurrentPublishedSnapshotException
      extends IllegalStateException {
    public ConcurrentPublishedSnapshotException(String message) {
      super(message);
    }
  }

  private final class FilePendingSnapshot implements PendingSnapshot {
    private enum OwnershipState {
      STAGED_NEW,
      FIRST_PUBLICATION_VISIBLE,
      REPLACEMENT_VISIBLE_WITH_DISPLACED,
      COMMITTED
    }

    private final Path published;
    private final Path staging;
    private final byte[] russian;
    private final byte[] english;
    private OwnershipState ownershipState = OwnershipState.STAGED_NEW;
    private boolean closed;

    private FilePendingSnapshot(
        Path published,
        Path staging,
        byte[] russian,
        byte[] english) {
      this.published = published;
      this.staging = staging;
      this.russian = russian;
      this.english = english;
    }

    @Override
    public CommitResult commit(List<WorkflowStateService.SnapshotGuard> guards) {
      if (closed) {
        throw new IllegalStateException("published snapshot staging is closed");
      }
      if (ownershipState == OwnershipState.COMMITTED) {
        throw new IllegalStateException("published snapshot is already committed");
      }
      if (ownershipState != OwnershipState.STAGED_NEW) {
        throw new IllegalStateException(
            "published snapshot commit cannot be retried after an incomplete rollback");
      }
      List<WorkflowStateService.SnapshotGuard> checkedGuards =
          List.copyOf(Objects.requireNonNull(guards, "guards"));
      if (!guardsMatch(checkedGuards)) {
        throw concurrentUpdate();
      }

      boolean replacement = Files.exists(published, LinkOption.NOFOLLOW_LINKS);
      try {
        if (replacement) {
          validatePublishedLayout(published);
          atomicExchange.exchange(published, staging);
          ownershipState = OwnershipState.REPLACEMENT_VISIBLE_WITH_DISPLACED;
        } else {
          Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
          ownershipState = OwnershipState.FIRST_PUBLICATION_VISIBLE;
        }

        try {
          ioHooks.afterVisibleCommit(published);
        } catch (IOException error) {
          rollback(error);
          throw commitFailure(published, error);
        } catch (RuntimeException error) {
          rollback(error);
          throw error;
        }

        if (!guardsMatch(checkedGuards)
            || !visiblePairMatches(published, russian, english)) {
          ConcurrentPublishedSnapshotException error = concurrentUpdate();
          rollback(error);
          throw error;
        }
        ownershipState = OwnershipState.COMMITTED;

        if (!replacement) {
          return new CommitResult(List.of());
        }
        try {
          ioHooks.deleteTree(staging);
          return new CommitResult(List.of());
        } catch (IOException cleanupError) {
          return new CommitResult(List.of(staging));
        }
      } catch (ConcurrentPublishedSnapshotException error) {
        throw error;
      } catch (IOException error) {
        throw commitFailure(published, error);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (ownershipState != OwnershipState.STAGED_NEW) {
        return;
      }
      try {
        ioHooks.deleteTree(staging);
      } catch (IOException error) {
        throw new IllegalStateException(
            "cannot clean staged published snapshot " + staging, error);
      }
    }

    private void rollback(Throwable failure) {
      try {
        switch (ownershipState) {
          case REPLACEMENT_VISIBLE_WITH_DISPLACED ->
              atomicExchange.exchange(published, staging);
          case FIRST_PUBLICATION_VISIBLE ->
              Files.move(published, staging, StandardCopyOption.ATOMIC_MOVE);
          default -> throw new IllegalStateException(
              "published snapshot rollback has no visible candidate");
        }
        ownershipState = OwnershipState.STAGED_NEW;
      } catch (IOException rollbackError) {
        rollbackError.addSuppressed(failure);
        throw commitFailure(published, rollbackError);
      }
    }

    private ConcurrentPublishedSnapshotException concurrentUpdate() {
      return new ConcurrentPublishedSnapshotException(
          "published snapshot inputs changed during commit: " + published);
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

  private static boolean guardsMatch(
      List<WorkflowStateService.SnapshotGuard> guards) {
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

  private static boolean visiblePairMatches(
      Path published,
      byte[] russian,
      byte[] english) {
    try {
      validatePublishedLayout(published);
      return Arrays.equals(russian, Files.readAllBytes(published.resolve("ru.md")))
          && Arrays.equals(english, Files.readAllBytes(published.resolve("en.md")));
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  private static void validatePublishedLayout(Path published) throws IOException {
    if (Files.isSymbolicLink(published)
        || !Files.isDirectory(published, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "published snapshot must be a non-symbolic directory: " + published);
    }
    Set<String> entries;
    try (var paths = Files.list(published)) {
      entries = paths
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    if (!entries.equals(PUBLISHED_FILES)) {
      throw new IllegalArgumentException(
          "published snapshot must contain only ru.md and en.md: " + published);
    }
    validatePublishedLeaf(published.resolve("ru.md"));
    validatePublishedLeaf(published.resolve("en.md"));
  }

  private static void validatePublishedLeaf(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          "published snapshot leaf must be a regular file: " + path);
    }
    try {
      Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
      if (links instanceof Number count && count.longValue() != 1) {
        throw new IllegalArgumentException(
            "published snapshot leaf must have one hard link: " + path);
      }
    } catch (UnsupportedOperationException ignored) {
      // Supported macOS/Linux filesystems expose nlink; type checks still apply elsewhere.
    }
  }

  private static IllegalStateException commitFailure(Path published, IOException error) {
    return new IllegalStateException(
        "cannot commit published snapshot " + published + ": " + error.getMessage(),
        error);
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
