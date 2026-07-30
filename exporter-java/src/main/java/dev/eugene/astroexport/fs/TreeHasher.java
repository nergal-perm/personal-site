package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Stable content hash for the Astro trees managed by the exporter. */
public final class TreeHasher {
  public static final List<String> PAYLOAD_ROOTS = List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages");
  public static final List<String> MANAGED_ROOTS = List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages",
      ".astro-export");

  private TreeHasher() { }

  public static List<ManagedTreeHash> hashManagedTrees(Path root) {
    return MANAGED_ROOTS.stream()
        .map(relative -> new ManagedTreeHash(relative, hashTree(root.resolve(relative))))
        .toList();
  }

  public static List<ManagedTreeHash> hashPayloadTrees(Path root) {
    return PAYLOAD_ROOTS.stream()
        .map(relative -> new ManagedTreeHash(relative, hashTree(root.resolve(relative))))
        .toList();
  }

  public static List<ManagedFileHash> hashPayloadFiles(Path root) {
    ArrayList<ManagedFileHash> files = new ArrayList<>();
    for (String relativeRoot : PAYLOAD_ROOTS) {
      Path treeRoot = root.resolve(relativeRoot);
      try (Stream<Path> paths = Files.walk(treeRoot)) {
        for (Path path : paths
            .filter(candidate -> !candidate.equals(treeRoot))
            .sorted(Comparator.comparing(candidate -> relative(root, candidate)))
            .toList()) {
          String relative = relative(root, path);
          if (Files.isSymbolicLink(path)) {
            throw new SiteWriter.WriterException("managed payload contains a symlink: " + relative);
          }
          if (Files.isDirectory(path)) {
            continue;
          }
          if (!Files.isRegularFile(path)) {
            throw new SiteWriter.WriterException("managed payload contains an unsupported entry: " + relative);
          }
          files.add(new ManagedFileHash(relative, sha256(Files.readAllBytes(path))));
        }
      } catch (IOException error) {
        throw new SiteWriter.WriterException("cannot hash managed payload " + treeRoot + ": " + error.getMessage(), error);
      }
    }
    files.sort(Comparator.comparing(ManagedFileHash::path));
    return List.copyOf(files);
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

  public static String sha256(byte[] bytes) {
    MessageDigest digest = sha256();
    digest.update(bytes);
    return HexFormat.of().formatHex(digest.digest());
  }

  public record ManagedTreeHash(String relative, String sha256) { }

  public record ManagedFileHash(String path, String sha256) { }
}
