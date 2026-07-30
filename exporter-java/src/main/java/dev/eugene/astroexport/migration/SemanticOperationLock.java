package dev.eugene.astroexport.migration;

import dev.eugene.astroexport.fs.JnaFileDescriptor;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class SemanticOperationLock {
  private SemanticOperationLock() { }

  public static Lease acquireShared(Path reviewRoot) throws IOException, LockBusyException {
    return acquire(reviewRoot, true);
  }

  public static Lease acquireExclusive(Path reviewRoot) throws IOException, LockBusyException {
    return acquire(reviewRoot, false);
  }

  private static Lease acquire(Path reviewRoot, boolean shared) throws IOException, LockBusyException {
    Path path = reviewRoot.resolve(".semantic-links/operations.lock");
    Files.createDirectories(path.getParent());
    try {
      Files.createFile(
          path,
          PosixFilePermissions.asFileAttribute(Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE)));
    } catch (FileAlreadyExistsException ignored) {
      // Existing lock leaves are opened without following links below.
    } catch (UnsupportedOperationException error) {
      try {
        Files.createFile(path);
      } catch (FileAlreadyExistsException ignored) {
        // Existing lock leaves are opened without following links below.
      }
    }
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("semantic operation lock must be a non-symbolic regular file");
    }
    JnaFileDescriptor descriptor = JnaFileDescriptor.openLockNoFollow(path);
    boolean locked = shared ? descriptor.trySharedLock() : descriptor.tryExclusiveLock();
    if (!locked) {
      descriptor.close();
      throw new LockBusyException();
    }
    return new Lease(descriptor);
  }

  public record Lease(JnaFileDescriptor descriptor) implements AutoCloseable {
    @Override
    public void close() {
      try {
        descriptor.close();
      } catch (IOException error) {
        throw new IllegalStateException("cannot release semantic operation lock", error);
      }
    }
  }

  public static final class LockBusyException extends Exception { }
}
