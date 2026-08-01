package dev.eugene.astroexport.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Shared fail-closed checks for non-migration outputs near the review workspace. */
public final class SemanticOutputSafety {
  private SemanticOutputSafety() { }

  public static Path preflight(Path output, Path reviewRoot, String kind) {
    Objects.requireNonNull(output, kind);
    Objects.requireNonNull(reviewRoot, "reviewRoot");
    Path destination = output.toAbsolutePath().normalize();
    Path review = reviewRoot.toAbsolutePath().normalize();
    Path reviewReal;
    try {
      reviewReal = review.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException("review root cannot be resolved safely", error);
    }
    if (destination.startsWith(review)) {
      throw new IllegalArgumentException(kind + " must be outside the review root");
    }
    try {
      rejectSymlinkComponents(destination, kind);
      Path existing = destination;
      List<Path> unresolved = new ArrayList<>();
      while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        Path name = existing.getFileName();
        if (name == null || existing.getParent() == null) {
          throw new IllegalArgumentException(kind + " path cannot be resolved safely");
        }
        unresolved.add(name);
        existing = existing.getParent();
      }
      Path candidate = existing.toRealPath();
      for (int index = unresolved.size() - 1; index >= 0; index--) {
        candidate = candidate.resolve(unresolved.get(index));
      }
      if (candidate.startsWith(reviewReal)) {
        throw new IllegalArgumentException(kind + " resolves inside the review root");
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(kind + " path cannot be resolved safely", error);
    }
    return destination;
  }

  private static void rejectSymlinkComponents(Path destination, String kind) throws IOException {
    Path current = destination.getRoot();
    for (Path component : destination) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current) && !isSystemVarAlias(current)) {
        throw new IllegalArgumentException(kind + " contains a symlink component");
      }
    }
  }

  private static boolean isSystemVarAlias(Path path) throws IOException {
    return path.equals(Path.of("/var")) && path.toRealPath().equals(Path.of("/private/var"));
  }

  static void createDirectories(Path directory, Path reviewRoot, String kind) throws IOException {
    preflight(directory, reviewRoot, kind);
    Files.createDirectories(directory);
    preflight(directory, reviewRoot, kind);
  }

  static void rejectConflict(Path output, byte[] expected, String kind) throws IOException {
    if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
        || !Arrays.equals(expected, Files.readAllBytes(output))) {
      throw new IllegalArgumentException(kind + " already exists with different content");
    }
  }

}
