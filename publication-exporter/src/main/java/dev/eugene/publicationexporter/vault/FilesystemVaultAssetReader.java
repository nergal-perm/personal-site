package dev.eugene.publicationexporter.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defends against structural path traversal and stable symlinks that escape the vault, but not against an adversary
 * with concurrent write access racing path validation against the subsequent read; path-based filesystem APIs cannot
 * portably provide descriptor-anchored opening, so a TOCTOU window remains. This matches the project's threat model of
 * a single local user exporting their own vault, not multi-tenant or concurrently adversarial writes.
 */
final class FilesystemVaultAssetReader implements VaultAssetReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultAssetReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    @Override
    public AssetLookup resolve(String reference) {
        Objects.requireNonNull(reference, "reference");
        if (!VaultRelativePath.of(reference).isWithinVault()) {
            return AssetLookup.unsafe();
        }
        Optional<Path> exact = resolveWithinVault(reference);
        if (exact.isPresent()) {
            return readAsset(exact.get());
        }
        return resolveByBasename(basename(reference));
    }

    private AssetLookup resolveByBasename(String basename) {
        List<Path> matches = visibleBasenameMatches(basename);
        if (matches.isEmpty()) {
            return AssetLookup.notFound();
        }
        if (matches.size() > 1) {
            return AssetLookup.ambiguous();
        }
        return readAsset(matches.get(0));
    }

    private List<Path> visibleBasenameMatches(String basename) {
        try (var paths = Files.walk(canonicalVaultRoot)) {
            List<Path> matches = new ArrayList<>();
            paths.filter(path -> path.getFileName().toString().equals(basename))
                    .map(FilesystemVaultAssetReader::realPathOf)
                    .flatMap(Optional::stream)
                    .filter(this::isInsideVault)
                    .filter(Files::isRegularFile)
                    .forEach(matches::add);
            return matches;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private AssetLookup readAsset(Path file) {
        try {
            return AssetLookup.found(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> resolveWithinVault(String reference) {
        return candidateFor(reference)
                .flatMap(FilesystemVaultAssetReader::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
    }

    private Optional<Path> candidateFor(String reference) {
        try {
            return Optional.of(canonicalVaultRoot.resolve(reference));
        } catch (InvalidPathException unrepresentable) {
            return Optional.empty();
        }
    }

    private boolean isInsideVault(Path realPath) {
        return realPath.startsWith(canonicalVaultRoot);
    }

    private static String basename(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
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
