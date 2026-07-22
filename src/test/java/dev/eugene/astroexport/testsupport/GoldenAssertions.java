package dev.eugene.astroexport.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

public final class GoldenAssertions {
  private GoldenAssertions() {
  }

  public static void assertTreeHash(Path root, String expectedHash) throws IOException {
    assertEquals(expectedHash, treeHash(root));
  }

  private static String treeHash(Path root) throws IOException {
    MessageDigest digest = sha256();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths
          .filter(candidate -> !candidate.equals(root))
          .sorted()
          .toList()) {
        String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        byte[] relativeBytes = relative.getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        byte kind;
        if (Files.isSymbolicLink(path)) {
          throw new IOException("managed tree contains a symlink: " + relative);
        } else if (Files.isDirectory(path)) {
          kind = 'D';
          payload = new byte[0];
        } else if (Files.isRegularFile(path)) {
          kind = 'F';
          payload = Files.readAllBytes(path);
        } else {
          throw new IOException("managed tree contains an unsupported entry: " + relative);
        }
        digest.update(kind);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(relativeBytes.length).array());
        digest.update(relativeBytes);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(payload.length).array());
        digest.update(payload);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
