package dev.eugene.astroexport.fs;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** No-follow native file descriptor with identity, read, and advisory-lock operations. */
public final class JnaFileDescriptor implements AutoCloseable {
  private static final int O_RDONLY = 0;
  private static final int O_RDWR = 2;
  private static final int LOCK_EX = 2;
  private static final int LOCK_NB = 4;
  private static final int LOCK_UN = 8;
  private static final int EINTR = 4;
  private static final int EAGAIN_LINUX = 11;
  private static final int EWOULDBLOCK_MACOS = 35;
  private static final int READ_BUFFER_SIZE = 1024 * 1024;

  private final int descriptor;
  private final Function read;
  private final Function fstat;
  private final Function flock;
  private final Function close;
  private boolean locked;
  private boolean closed;

  private JnaFileDescriptor(
      int descriptor,
      Function read,
      Function fstat,
      Function flock,
      Function close) {
    this.descriptor = descriptor;
    this.read = read;
    this.fstat = fstat;
    this.flock = flock;
    this.close = close;
  }

  public static JnaFileDescriptor openReadNoFollow(Path path) throws IOException {
    return open(path, O_RDONLY);
  }

  public static JnaFileDescriptor openLockNoFollow(Path path) throws IOException {
    return open(path, O_RDWR);
  }

  private static JnaFileDescriptor open(Path path, int accessFlags)
      throws IOException {
    if (path.getFileSystem() != FileSystems.getDefault()) {
      throw new IOException("native file descriptors require the default local filesystem");
    }
    int noFollow;
    int closeOnExec;
    if (Platform.isMac()) {
      noFollow = 0x0100;
      closeOnExec = 0x01000000;
    } else if (Platform.isLinux()) {
      noFollow = 0x00020000;
      closeOnExec = 0x00080000;
    } else {
      throw new IOException(
          "native file descriptors are unsupported on " + System.getProperty("os.name"));
    }

    try {
      NativeLibrary libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
      Function open = libc.getFunction("open");
      int flags = accessFlags | noFollow | closeOnExec;
      int descriptor = open.invokeInt(new Object[] {
          path.toAbsolutePath().toString(), flags
      });
      if (descriptor < 0) {
        throw new IOException("native no-follow open failed with errno " + Native.getLastError());
      }
      return new JnaFileDescriptor(
          descriptor,
          libc.getFunction("read"),
          libc.getFunction("fstat"),
          libc.getFunction("flock"),
          libc.getFunction("close"));
    } catch (UnsatisfiedLinkError | NoClassDefFoundError error) {
      throw new IOException("native file descriptor operations are unavailable", error);
    }
  }

  public Snapshot snapshot() throws IOException {
    Path descriptorPath = descriptorPath();
    BasicFileAttributes attributes = Files.readAttributes(
        descriptorPath, BasicFileAttributes.class);
    long linkCount = -1;
    try {
      Object value = Files.getAttribute(descriptorPath, "unix:nlink");
      if (value instanceof Number number) {
        linkCount = number.longValue();
      }
    } catch (UnsupportedOperationException ignored) {
      // The default Unix providers expose nlink; other providers fail closed elsewhere.
    }
    Memory stat = new Memory(256);
    if (fstat.invokeInt(new Object[] {descriptor, stat}) != 0) {
      throw new IOException("native fstat failed with errno " + Native.getLastError());
    }
    return new Snapshot(attributes, linkCount, identity(stat));
  }

  public static FileIdentity pathIdentityNoFollow(Path path) throws IOException {
    if (path.getFileSystem() != FileSystems.getDefault()) {
      throw new IOException("native file identity requires the default local filesystem");
    }
    Memory stat = new Memory(256);
    try {
      Function lstat = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME).getFunction("lstat");
      if (lstat.invokeInt(new Object[] {path.toAbsolutePath().toString(), stat}) != 0) {
        throw new IOException("native lstat failed with errno " + Native.getLastError());
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError error) {
      throw new IOException("native file identity is unavailable", error);
    }
    return identity(stat);
  }

  private static FileIdentity identity(Memory stat) throws IOException {
    if (!Platform.is64Bit()) {
      throw new IOException("native file identity requires a 64-bit platform");
    }
    // Darwin pads its 32-bit st_dev to eight bytes; 64-bit Linux stores st_dev directly.
    // Both layouts place the 64-bit st_ino at offset eight.
    long device = Platform.isMac()
        ? Integer.toUnsignedLong(stat.getInt(0))
        : stat.getLong(0);
    return new FileIdentity(device, stat.getLong(8));
  }

  public byte[] readAllBytes() throws IOException {
    Memory buffer = new Memory(READ_BUFFER_SIZE);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (true) {
      long count = read.invokeLong(new Object[] {descriptor, buffer, (long) READ_BUFFER_SIZE});
      if (count == 0) {
        return output.toByteArray();
      }
      if (count < 0) {
        int errorNumber = Native.getLastError();
        if (errorNumber == EINTR) {
          continue;
        }
        throw new IOException("native descriptor read failed with errno " + errorNumber);
      }
      output.write(buffer.getByteArray(0, Math.toIntExact(count)));
    }
  }

  public boolean tryExclusiveLock() throws IOException {
    int result = flock.invokeInt(new Object[] {descriptor, LOCK_EX | LOCK_NB});
    if (result == 0) {
      locked = true;
      return true;
    }
    int errorNumber = Native.getLastError();
    if (errorNumber == EAGAIN_LINUX || errorNumber == EWOULDBLOCK_MACOS) {
      return false;
    }
    throw new IOException("native publication lock failed with errno " + errorNumber);
  }

  private Path descriptorPath() throws IOException {
    IOException failure = null;
    for (Path root : new Path[] {Path.of("/dev/fd"), Path.of("/proc/self/fd")}) {
      Path candidate = root.resolve(Integer.toString(descriptor));
      try {
        Files.readAttributes(candidate, BasicFileAttributes.class);
        return candidate;
      } catch (IOException error) {
        failure = error;
      }
    }
    throw new IOException("could not inspect the opened file descriptor", failure);
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    IOException failure = null;
    if (locked && flock.invokeInt(new Object[] {descriptor, LOCK_UN}) != 0) {
      failure = new IOException(
          "native publication unlock failed with errno " + Native.getLastError());
    }
    if (close.invokeInt(new Object[] {descriptor}) != 0 && failure == null) {
      failure = new IOException(
          "native file descriptor close failed with errno " + Native.getLastError());
    }
    closed = true;
    if (failure != null) {
      throw failure;
    }
  }

  public record Snapshot(
      BasicFileAttributes attributes,
      long linkCount,
      FileIdentity identity) { }

  public record FileIdentity(long device, long inode) { }
}
