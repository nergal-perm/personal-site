package dev.eugene.astroexport.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureFiles {
  private FixtureFiles() {
  }

  public static Path write(Path root, String relativePath, String content) throws IOException {
    Path target = root.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  public static String read(Path root, String relativePath) throws IOException {
    return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
  }
}
