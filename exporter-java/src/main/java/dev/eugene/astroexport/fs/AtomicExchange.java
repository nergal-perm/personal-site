package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.file.Path;

/** Atomically swaps two existing filesystem paths. */
@FunctionalInterface
public interface AtomicExchange {
  void exchange(Path first, Path second) throws IOException;

  /** The platform or filesystem cannot provide a lossless path exchange. */
  final class AtomicExchangeUnavailableException extends IOException {
    public AtomicExchangeUnavailableException(String message) {
      super(message);
    }

    public AtomicExchangeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
