package dev.eugene.publicationexporter.legacy;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class FilesystemMigrationPath {
    private FilesystemMigrationPath() { }

    static Path safeRoot(Path configured) {
        Path root = configured.toAbsolutePath().normalize();
        rejectLexicalSymbolicAncestors(root);
        Path canonical = resolveCanonicalRoot(root);
        rejectCanonicalSymbolicAncestors(canonical);
        return canonical;
    }

    private static void rejectLexicalSymbolicAncestors(Path root) {
        rejectSymbolicAncestors(root);
    }

    private static Path resolveCanonicalRoot(Path root) {
        try {
            return Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : root;
        } catch (java.io.IOException error) {
            throw new MigrationRecoveryException("Cannot resolve migration root: " + root);
        }
    }

    private static void rejectCanonicalSymbolicAncestors(Path root) {
        rejectSymbolicAncestors(root);
    }

    private static void rejectSymbolicAncestors(Path path) {
        Path current = path;
        while (current != null) {
            if (Files.isSymbolicLink(current) && !isSystemAlias(current)) {
                throw new MigrationRecoveryException("Migration root contains symbolic ancestor: " + current);
            }
            current = current.getParent();
        }
    }

    private static boolean isSystemAlias(Path path) {
        return path.equals(Path.of("/var")) || path.equals(Path.of("/tmp"));
    }

    static void requireSafe(Path root, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        requireWithinRoot(root, normalized, target);
        rejectSymbolicComponents(root, normalized);
    }

    static void requireRegularFileOrAbsent(Path root, Path target) {
        requireSafe(root, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationRecoveryException("Migration target is not a regular file: " + target);
        }
    }

    static void requireDirectoryOrAbsent(Path root, Path target) {
        requireSafe(root, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationRecoveryException("Migration target is not a directory: " + target);
        }
    }

    private static void requireWithinRoot(Path root, Path normalized, Path target) {
        if (Files.isSymbolicLink(root) || !normalized.startsWith(root)) {
            throw new MigrationRecoveryException("Migration path escapes review root: " + target);
        }
    }

    private static void rejectSymbolicComponents(Path root, Path normalized) {
        Path current = root;
        Path relative = root.relativize(normalized);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new MigrationRecoveryException("Migration path contains a symbolic link: " + current);
        }
    }
}
