package dev.eugene.astroexport.fs;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;

/** Native macOS/Linux atomic path exchange implemented through JNA. */
public final class JnaAtomicExchange implements AtomicExchange {
  private static final int RENAME_EXCHANGE = 0x00000002;
  private static final int AT_FDCWD = -100;
  private static final Set<Integer> MACOS_UNSUPPORTED_ERRNOS = Set.of(
      18, 22, 45, 78, 102);
  private static final Set<Integer> LINUX_UNSUPPORTED_ERRNOS = Set.of(
      18, 22, 38, 95);

  @Override
  public void exchange(Path first, Path second) throws IOException {
    if (first.getFileSystem() != FileSystems.getDefault()
        || second.getFileSystem() != FileSystems.getDefault()) {
      throw new AtomicExchangeUnavailableException(
          "atomic path exchange requires the default local filesystem");
    }
    String firstPath = first.toAbsolutePath().toString();
    String secondPath = second.toAbsolutePath().toString();
    int result;
    try {
      NativeLibrary libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
      if (Platform.isMac()) {
        Function exchange = libc.getFunction("renamex_np");
        result = exchange.invokeInt(new Object[] {
            firstPath, secondPath, RENAME_EXCHANGE
        });
      } else if (Platform.isLinux()) {
        Function exchange = libc.getFunction("renameat2");
        result = exchange.invokeInt(new Object[] {
            AT_FDCWD, firstPath, AT_FDCWD, secondPath, RENAME_EXCHANGE
        });
      } else {
        throw new AtomicExchangeUnavailableException(
            "atomic path exchange is unsupported on " + System.getProperty("os.name"));
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError error) {
      throw new AtomicExchangeUnavailableException(
          "atomic path exchange is unavailable", error);
    }
    if (result == 0) {
      return;
    }
    int errorNumber = Native.getLastError();
    throw exchangeFailure(Platform.isMac(), errorNumber);
  }

  static IOException exchangeFailure(boolean macOs, int errorNumber) {
    Set<Integer> unsupported = macOs
        ? MACOS_UNSUPPORTED_ERRNOS
        : LINUX_UNSUPPORTED_ERRNOS;
    if (unsupported.contains(errorNumber)) {
      return new AtomicExchangeUnavailableException(
          "filesystem does not support atomic path exchange (errno " + errorNumber + ")");
    }
    return new IOException("atomic path exchange failed with errno " + errorNumber);
  }
}
