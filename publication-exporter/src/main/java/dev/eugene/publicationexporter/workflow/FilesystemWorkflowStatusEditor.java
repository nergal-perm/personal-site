package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FilesystemWorkflowStatusEditor implements WorkflowStatusEditor {

    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";

    private final Path canonicalVaultRoot;

    FilesystemWorkflowStatusEditor(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    @Override
    public Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue) {
        Objects.requireNonNull(notePath, "notePath");
        Objects.requireNonNull(expectedSourceHash, "expectedSourceHash");
        Objects.requireNonNull(newValue, "newValue");
        Path real = resolveWithinVault(notePath)
                .orElseThrow(() -> new IllegalStateException("Note not found: " + notePath.value()));
        String current = readUtf8(real);
        if (!ContentHash.sha256Hex(current).equals(expectedSourceHash)) {
            return Result.blocked("Source changed since it was validated.");
        }
        String updated = Frontmatter.parse(current).withScalarSet(WORKFLOW_STATUS_KEY, newValue);
        atomicReplace(real, updated);
        return Result.written();
    }

    private void atomicReplace(Path target, String newContent) {
        Path temp = target.resolveSibling(target.getFileName() + ".workflow-" + UUID.randomUUID());
        try {
            Files.writeString(temp, newContent, StandardCharsets.UTF_8);
            copyPosixPermissionsIfSupported(target, temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            deleteQuietly(temp);
            throw new UncheckedIOException(error);
        }
    }

    private static void copyPosixPermissionsIfSupported(Path source, Path temp) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        if (view != null) {
            Files.setPosixFilePermissions(temp, view.readAttributes().permissions());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of the temp file after a failed write; the ATOMIC_MOVE never ran
        }
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> resolveWithinVault(VaultRelativePath notePath) {
        return candidateFor(notePath)
                .flatMap(FilesystemWorkflowStatusEditor::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
    }

    private Optional<Path> candidateFor(VaultRelativePath notePath) {
        try {
            return Optional.of(canonicalVaultRoot.resolve(notePath.value()));
        } catch (InvalidPathException unrepresentable) {
            return Optional.empty();
        }
    }

    private boolean isInsideVault(Path realNotePath) {
        return realNotePath.startsWith(canonicalVaultRoot);
    }

    private static Path canonicalize(Path vaultRoot) {
        return realPathOf(vaultRoot).orElseGet(() -> vaultRoot.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
        }
    }
}
