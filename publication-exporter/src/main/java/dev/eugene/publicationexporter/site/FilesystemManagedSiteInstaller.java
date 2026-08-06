package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FilesystemManagedSiteInstaller implements ManagedSiteInstaller {

    private static final List<String> PAYLOAD_ROOTS =
            List.of("public/assets/vault", "src/content", "src/data/pages");

    private final StagedDirectoryInstall stagedInstall;

    public FilesystemManagedSiteInstaller(Path siteRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(siteRoot, "siteRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");

        Path ruDestination = markdownFile(identity, "ru");
        Path enDestination = markdownFile(identity, "en");
        if (exists(ruDestination) || exists(enDestination)) {
            throw new SiteAlreadyInstalledException(identity);
        }

        Path staging = null;
        try {
            confined(stagedInstall.canonicalRoot());
            staging = confined(stagedInstall.createStagingDirectory("site-install-"));
            writeStagedFile(staging, "ru.md", frontmatter(identity, approvedSnapshot, "ru")
                    + approvedSnapshot.ruBody());
            writeStagedFile(staging, "en.md", frontmatter(identity, approvedSnapshot, "en")
                    + approvedSnapshot.enBody());

            moveFile(stagedFile(staging, "ru.md"), ruDestination);
            moveFile(stagedFile(staging, "en.md"), enDestination);

            ensurePayloadRoots();
            SiteReleaseManifest manifest = computeManifest();
            writeStagedFile(staging, "release-provenance.json", manifest.toCanonicalJson());
            moveFile(stagedFile(staging, "release-provenance.json"), manifestPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            if (staging != null) {
                StagedDirectoryInstall.deleteRecursively(staging);
            }
        }
    }

    private void writeStagedFile(Path staging, String fileName, String content) throws IOException {
        Files.writeString(stagedFile(staging, fileName), content, StandardCharsets.UTF_8);
    }

    private void moveFile(Path source, Path destination, StandardCopyOption... additionalOptions)
            throws IOException {
        Path confinedSource = confined(source);
        Path confinedDestination = confined(destination);
        Path parent = confinedDestination.getParent();
        if (parent == null) {
            throw new ManagedSiteInstallerConfinementException(
                    confinedDestination, confinedDestination, stagedInstall.canonicalRoot());
        }
        confined(parent);
        stagedInstall.createParentDirectories(confinedDestination);
        confined(parent);
        confinedDestination = confined(confinedDestination);

        StandardCopyOption[] options = new StandardCopyOption[additionalOptions.length + 1];
        options[0] = StandardCopyOption.ATOMIC_MOVE;
        System.arraycopy(additionalOptions, 0, options, 1, additionalOptions.length);
        Files.move(confinedSource, confinedDestination, options);
    }

    private void ensurePayloadRoots() throws IOException {
        for (String relativeRoot : PAYLOAD_ROOTS) {
            Path payloadRoot = confined(stagedInstall.canonicalRoot().resolve(relativeRoot));
            Path marker = confined(payloadRoot.resolve(".keep"));
            stagedInstall.createParentDirectories(marker);
            confined(payloadRoot);
        }
    }

    private SiteReleaseManifest computeManifest() {
        confined(stagedInstall.canonicalRoot());
        for (String relativeRoot : PAYLOAD_ROOTS) {
            confined(stagedInstall.canonicalRoot().resolve(relativeRoot));
        }
        return SiteReleaseManifest.computeOver(stagedInstall.canonicalRoot(), PAYLOAD_ROOTS);
    }

    private boolean exists(Path candidate) {
        return Files.exists(confined(candidate), LinkOption.NOFOLLOW_LINKS);
    }

    private Path markdownFile(PublicationIdentity identity, String locale) {
        Path file = stagedInstall.canonicalRoot()
                .resolve("src/content")
                .resolve(identity.publicCollection())
                .resolve(locale)
                .resolve(identity.publicId() + ".md")
                .normalize();
        return confined(file);
    }

    private Path manifestPath() {
        return confined(stagedInstall.canonicalRoot().resolve(".astro-export/release-provenance.json"));
    }

    private Path stagedFile(Path staging, String fileName) {
        return confined(staging.resolve(fileName));
    }

    private Path confined(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(normalized);
        if (resolved.isEmpty() || !resolved.get().equals(normalized)) {
            throw new ManagedSiteInstallerConfinementException(
                    normalized, resolved.orElse(normalized), stagedInstall.canonicalRoot());
        }
        return normalized;
    }

    private static String frontmatter(PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        boolean isRu = "ru".equals(locale);
        StringBuilder yaml = new StringBuilder("---\n");
        yaml.append("id: ").append(identity.publicId()).append('\n');
        yaml.append("title: ").append(isRu ? approved.ruTitle() : approved.enTitle()).append('\n');
        yaml.append("description: ").append(isRu ? approved.ruDescription() : approved.enDescription()).append('\n');
        yaml.append("publish: true\n");
        yaml.append("contentType: ").append(identity.publicContentType()).append('\n');
        yaml.append("language: ").append(locale).append('\n');
        yaml.append("sourceLanguage: ru\n");
        yaml.append("sourceHash: ").append(approved.referenceMap().ruHash()).append('\n');
        yaml.append("translationStatus: ").append(isRu ? "source" : "generated").append('\n');
        if (!isRu) {
            yaml.append("translationOf: ").append(identity.publicId()).append('\n');
        }
        return yaml.append("---\n").toString();
    }
}
