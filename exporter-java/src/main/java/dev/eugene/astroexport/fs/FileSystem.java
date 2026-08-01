package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Low-level wrapper around the one technology we touch here: the filesystem.
 *
 * <p>{@link #create()} wires the real {@link java.nio.file.Files} calls; {@link #createNull()}
 * wires an embedded stub that does no I/O and answers from configured responses. Everything above
 * this seam (path resolution, traversal checks, containment checks) stays pure and runs for real.
 */
public interface FileSystem {

    boolean isRegularFile(Path path);

    Path toRealPath(Path path) throws IOException;

    String readString(Path path) throws IOException;

    static FileSystem create() {
        return new RealFileSystem();
    }

    static NullFileSystem createNull() {
        return new NullFileSystem();
    }
}
