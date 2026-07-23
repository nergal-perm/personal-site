package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Stable content hash for the Astro trees managed by the exporter. */
public final class TreeHasher {
  public static final List<String> MANAGED_ROOTS = List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages");

  private TreeHasher() { }

  public static List<ManagedTreeHash> hashManagedTrees(Path root) {
    return MANAGED_ROOTS.stream()
        .map(relative -> new ManagedTreeHash(relative, hashTree(root.resolve(relative))))
        .toList();
  }

  static String hashTree(Path root) {
    MessageDigest digest = sha256();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths
          .filter(candidate -> !candidate.equals(root))
          .sorted((left, right) -> relative(root, left).compareTo(relative(root, right)))
          .toList()) {
        String relative = relative(root, path);
        byte[] relativeBytes = relative.getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        byte kind;
        if (Files.isSymbolicLink(path)) {
          throw new SiteWriter.WriterException("managed tree contains a symlink: " + relative);
        } else if (Files.isDirectory(path)) {
          kind = 'D';
          payload = new byte[0];
        } else if (Files.isRegularFile(path)) {
          kind = 'F';
          payload = Files.readAllBytes(path);
        } else {
          throw new SiteWriter.WriterException("managed tree contains an unsupported entry: " + relative);
        }
        digest.update(kind);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(relativeBytes.length).array());
        digest.update(relativeBytes);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(payload.length).array());
        digest.update(payload);
      }
    } catch (IOException error) {
      throw new SiteWriter.WriterException("cannot hash managed tree " + root + ": " + error.getMessage(), error);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String relative(Path root, Path path) {
    return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record ManagedTreeHash(String relative, String sha256) { }
}
