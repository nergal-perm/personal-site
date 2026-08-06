package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FilesystemManagedSiteInstaller implements ManagedSiteInstaller {

    private static final List<String> PAYLOAD_ROOTS =
            List.of("public/assets/vault", "src/content", "src/data/pages");

    private final StagedDirectoryInstall stagedInstall;

    public FilesystemManagedSiteInstaller(Path siteRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(
                canonicalizeThroughNearestExistingAncestor(Objects.requireNonNull(siteRoot, "siteRoot")));
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        requireInstallationInputs(identity, approvedSnapshot);
        Path ruDestination = markdownFile(identity, "ru");
        Path enDestination = markdownFile(identity, "en");
        rejectIfAlreadyInstalled(identity, ruDestination, enDestination);
        installFromStaging(identity, approvedSnapshot, ruDestination, enDestination);
    }

    private void installFromStaging(PublicationIdentity identity, CandidateSnapshot approvedSnapshot,
            Path ruDestination, Path enDestination) {
        Path staging = createStagingDirectory();
        try {
            stageLocaleFiles(staging, identity, approvedSnapshot);
            installLocaleFiles(staging, identity, ruDestination, enDestination);
            ensurePayloadRoots();
            writeProvenance(staging);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            deleteStagingDirectory(staging);
        }
    }

    private static void requireInstallationInputs(
            PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");
    }

    private void rejectIfAlreadyInstalled(
            PublicationIdentity identity, Path ruDestination, Path enDestination) {
        if (exists(ruDestination) || exists(enDestination)) {
            throw new SiteAlreadyInstalledException(identity);
        }
    }

    private Path createStagingDirectory() {
        try {
            Path resolvedRoot = resolveWithinSiteRoot(stagedInstall.canonicalRoot());
            Files.createDirectories(resolvedRoot);
            Path verifiedRoot = resolveWithinSiteRoot(resolvedRoot);
            return resolveWithinSiteRoot(Files.createTempDirectory(verifiedRoot, "site-install-"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void stageLocaleFiles(
            Path staging, PublicationIdentity identity, CandidateSnapshot approvedSnapshot) throws IOException {
        writeLocaleFile(staging, identity, approvedSnapshot, "ru", approvedSnapshot.ruBody());
        writeLocaleFile(staging, identity, approvedSnapshot, "en", approvedSnapshot.enBody());
    }

    private void writeLocaleFile(Path staging, PublicationIdentity identity,
            CandidateSnapshot approvedSnapshot, String locale, String body) throws IOException {
        writeStagedFile(staging, locale + ".md", frontmatter(identity, approvedSnapshot, locale) + body);
    }

    private void installLocaleFiles(
            Path staging, PublicationIdentity identity, Path ruDestination, Path enDestination) throws IOException {
        Path installationLock = acquireInstallationLock(ruDestination, identity);
        try {
            rejectIfAlreadyInstalled(identity, ruDestination, enDestination);
            moveNewLocaleFile(stagedFile(staging, "ru.md"), ruDestination, identity);
            moveNewLocaleFile(stagedFile(staging, "en.md"), enDestination, identity);
        } finally {
            releaseInstallationLock(installationLock);
        }
    }

    private Path acquireInstallationLock(Path ruDestination, PublicationIdentity identity) throws IOException {
        Path resolvedLock = createAndResolveParentDirectories(installationLock(ruDestination));
        try {
            return Files.createFile(resolvedLock);
        } catch (FileAlreadyExistsException collision) {
            throw new SiteAlreadyInstalledException(identity);
        }
    }

    private void releaseInstallationLock(Path installationLock) throws IOException {
        Path resolvedParent = resolveWithinSiteRoot(installationLock.getParent());
        Files.deleteIfExists(resolvedParent.resolve(installationLock.getFileName()));
    }

    private Path installationLock(Path ruDestination) {
        return ruDestination.resolveSibling("." + ruDestination.getFileName() + ".installing").normalize();
    }

    private void moveNewLocaleFile(Path source, Path destination, PublicationIdentity identity)
            throws IOException {
        Path resolvedDestination = createAndResolveParentDirectories(destination);
        Path resolvedSource = resolveWithinSiteRoot(source);
        try {
            Files.move(resolvedSource, resolvedDestination, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException collision) {
            throw new SiteAlreadyInstalledException(identity);
        }
    }

    private void writeProvenance(Path staging) throws IOException {
        SiteReleaseManifest manifest = computeManifest();
        writeStagedFile(staging, "release-provenance.json", manifest.toCanonicalJson());
        replaceFile(stagedFile(staging, "release-provenance.json"), manifestPath());
    }

    private void writeStagedFile(Path staging, String fileName, String content) throws IOException {
        Path resolvedFile = resolveWithinSiteRoot(stagedFile(staging, fileName));
        Files.writeString(resolvedFile, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void replaceFile(Path source, Path destination) throws IOException {
        Path resolvedDestination = createAndResolveParentDirectories(destination);
        Path resolvedSource = resolveWithinSiteRoot(source);
        Files.move(resolvedSource, resolvedDestination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path createAndResolveParentDirectories(Path destination) throws IOException {
        Path resolvedDestination = resolveWithinSiteRoot(destination);
        Path parent = resolvedDestination.getParent();
        if (parent == null) {
            throw new ManagedSiteInstallerConfinementException(
                    resolvedDestination, resolvedDestination, stagedInstall.canonicalRoot());
        }
        stagedInstall.createParentDirectories(resolvedDestination);
        return resolveWithinSiteRoot(resolvedDestination);
    }

    private void deleteStagingDirectory(Path staging) {
        StagedDirectoryInstall.deleteRecursively(resolveWithinSiteRoot(staging));
    }

    private void ensurePayloadRoots() throws IOException {
        for (String relativeRoot : PAYLOAD_ROOTS) {
            Path payloadRoot = resolveWithinSiteRoot(stagedInstall.canonicalRoot().resolve(relativeRoot));
            Path marker = resolveWithinSiteRoot(payloadRoot.resolve(".keep"));
            stagedInstall.createParentDirectories(marker);
            resolveWithinSiteRoot(payloadRoot);
        }
    }

    private SiteReleaseManifest computeManifest() {
        resolveWithinSiteRoot(stagedInstall.canonicalRoot());
        for (String relativeRoot : PAYLOAD_ROOTS) {
            resolveWithinSiteRoot(stagedInstall.canonicalRoot().resolve(relativeRoot));
        }
        return SiteReleaseManifest.computeOver(stagedInstall.canonicalRoot(), PAYLOAD_ROOTS);
    }

    private boolean exists(Path candidate) {
        return Files.exists(resolveWithinSiteRoot(candidate), LinkOption.NOFOLLOW_LINKS);
    }

    private Path markdownFile(PublicationIdentity identity, String locale) {
        return stagedInstall.canonicalRoot()
                .resolve("src/content")
                .resolve(identity.publicCollection())
                .resolve(locale)
                .resolve(identity.publicId() + ".md")
                .normalize();
    }

    private Path manifestPath() {
        return stagedInstall.canonicalRoot().resolve(".astro-export/release-provenance.json").normalize();
    }

    private Path stagedFile(Path staging, String fileName) {
        return staging.resolve(fileName).normalize();
    }

    private Path resolveWithinSiteRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(normalized);
        if (resolved.isEmpty() || !resolved.get().startsWith(stagedInstall.canonicalRoot())) {
            throw new ManagedSiteInstallerConfinementException(
                    normalized, resolved.orElse(normalized), stagedInstall.canonicalRoot());
        }
        /*
         * The constructor resolves the nearest existing ancestor, so the canonical root stays a
         * stable trust boundary even when the root itself is initially absent. StagedDirectoryInstall
         * re-resolves candidate and root on every call; this stable comparison additionally rejects
         * replacing the root itself with an outside symlink.
         * Callers re-resolve after directory creation and immediately use this returned real path.
         * A small pathname race remains because portable java.nio.file has no directory-fd-relative
         * create/rename API, but no known symlink alias is carried from validation into the write.
         */
        return resolved.get();
    }

    private static Path canonicalizeThroughNearestExistingAncestor(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Path existingAncestor = normalized;
        while (existingAncestor != null
                && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return normalized;
        }
        try {
            return existingAncestor.toRealPath()
                    .resolve(existingAncestor.relativize(normalized))
                    .normalize();
        } catch (IOException | SecurityException unresolvable) {
            return normalized;
        }
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
