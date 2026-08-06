package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemReleaseOutputStore implements ReleaseOutputStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StagedDirectoryInstall stagedInstall;

    FilesystemReleaseOutputStore(Path outputRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(outputRoot, "outputRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(provenance, "provenance");

        Path destination = releaseDirectory(identity);
        if (Files.exists(destination)) {
            throw new ReleaseAlreadyExistsException(identity);
        }
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, provenance);
            requireWithinOutputRoot(destination);
            stagedInstall.createParentDirectories(destination);
            requireWithinOutputRoot(destination);
            stagedInstall.move(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    private Path releaseDirectory(PublicationIdentity identity) {
        Path release = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("release")
                .normalize();
        requireWithinOutputRoot(release);
        return release;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("release-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinOutputRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new ReleaseOutputStoreConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReleaseProvenance provenance)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("release-provenance.json"),
                MAPPER.writeValueAsString(provenance), StandardCharsets.UTF_8);
    }
}
