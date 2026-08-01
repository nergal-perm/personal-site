package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Production {@link FileSystem}: delegates straight to {@link Files}, following links as before. */
final class RealFileSystem implements FileSystem {

    @Override
    public boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return path.toRealPath();
    }

    @Override
    public String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
