package dev.eugene.astroexport.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/** Byte-exact guard for files that must not change between release staging and install. */
public final class ReleaseInputGuard {
  private final Map<Path, byte[]> guardedBytes;

  private ReleaseInputGuard(Map<Path, byte[]> guardedBytes) {
    this.guardedBytes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(guardedBytes));
  }

  public static Builder builder() {
    return new Builder();
  }

  public void verify() {
    for (Map.Entry<Path, byte[]> entry : guardedBytes.entrySet()) {
      byte[] current = readSafeRegularFile(entry.getKey());
      if (!java.util.Arrays.equals(entry.getValue(), current)) {
        throw new ApprovedReleaseException(
            "release-input-changed",
            entry.getKey().toString(),
            "release input changed: " + entry.getKey());
      }
    }
  }

  public int size() {
    return guardedBytes.size();
  }

  static byte[] readSafeRegularFile(Path path) {
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        throw new IOException("missing or symbolic file");
      }
      BasicFileAttributes attributes = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw new IOException("not a regular file");
      }
      return Files.readAllBytes(path);
    } catch (IOException | RuntimeException error) {
      throw new ApprovedReleaseException(
          "release-input-changed",
          path.toString(),
          "unsafe release input: " + path + ": " + error.getMessage(),
          error);
    }
  }

  public static final class Builder {
    private final LinkedHashMap<Path, byte[]> bytes = new LinkedHashMap<>();

    public Builder captureRequired(Path path) {
      Path normalized = path.toAbsolutePath().normalize();
      bytes.put(normalized, readSafeRegularFile(normalized));
      return this;
    }

    public Builder captureIfPresent(Path path) {
      if (path == null) {
        return this;
      }
      Path normalized = path.toAbsolutePath().normalize();
      if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
        bytes.put(normalized, readSafeRegularFile(normalized));
      }
      return this;
    }

    public ReleaseInputGuard build() {
      return new ReleaseInputGuard(bytes);
    }
  }
}
