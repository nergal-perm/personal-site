package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.admission.ManagedArtifact;
import dev.eugene.publicationexporter.admission.PublicationKind;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class FilesystemManagedSiteInstaller implements ManagedSiteInstaller {

    private static final List<String> PAYLOAD_ROOTS =
            List.of("public/assets/vault", "src/content", "src/data/pages");
    private static final Path MANAGED_BACKUP_ROOT = Path.of(".astro-export/managed-backups");

    private final StagedDirectoryInstall stagedInstall;
    private final PublicationKinds publicationKinds;

    public FilesystemManagedSiteInstaller(Path siteRoot) {
        this(siteRoot, PublicationKinds.installed());
    }

    public FilesystemManagedSiteInstaller(Path siteRoot, PublicationKinds publicationKinds) {
        this.stagedInstall = StagedDirectoryInstall.rootedAtCanonical(
                canonicalizeThroughNearestExistingAncestor(Objects.requireNonNull(siteRoot, "siteRoot")));
        this.publicationKinds = Objects.requireNonNull(publicationKinds, "publicationKinds");
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        installWithOutcome(identity, approvedSnapshot);
    }

    @Override
    public ManagedSiteInstallOutcome installWithOutcome(
            PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        requireInstallationInputs(identity, approvedSnapshot);
        requireIdentityConfinement(identity);
        PublicationKind kind = requireKind(identity);
        ManagedArtifact ruArtifact = kind.projectManagedArtifact(identity, approvedSnapshot, "ru");
        ManagedArtifact enArtifact = kind.projectManagedArtifact(identity, approvedSnapshot, "en");
        Path ruDestination = destinationFile(ruArtifact);
        Path enDestination = destinationFile(enArtifact);
        return withInstallationLock(identity, () -> {
            boolean recovered = recoverIfNeeded(ruDestination, enDestination);
            requireNoKindCollision(identity, ruDestination, ruArtifact.collisionMarkerLine());
            requireNoKindCollision(identity, enDestination, enArtifact.collisionMarkerLine());
            try {
                installFromStaging(ruArtifact, enArtifact, ruDestination, enDestination);
            } catch (RuntimeException failure) {
                if (recovered) {
                    throw ManagedSiteInstallationFailedAfterRecoveryException.afterRecovery(failure);
                }
                throw failure;
            }
            return recovered
                    ? ManagedSiteInstallOutcome.RECOVERED_AND_INSTALLED
                    : ManagedSiteInstallOutcome.INSTALLED;
        });
    }

    private void installFromStaging(ManagedArtifact ruArtifact, ManagedArtifact enArtifact,
            Path ruDestination, Path enDestination) {
        Path staging = createStagingDirectory();
        try {
            stageLocaleFiles(staging, ruArtifact, enArtifact);
            installManagedGeneration(staging, ruDestination, enDestination);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            deleteStagingDirectory(staging);
        }
    }

    /**
     * The real destination path is keyed by (collection, locale, publicId) only, so a different
     * kind sharing that triple would otherwise silently overwrite this one's site file on
     * replace. The artifact identifies its exact kind-marker line, so the existing file's kind
     * is checked against the incoming one before any write happens.
     */
    private void requireNoKindCollision(
            PublicationIdentity identity, Path destination, String collisionMarkerLine) {
        Path resolved = resolveWithinSiteRoot(destination);
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return;
        }
        boolean matchesIncomingKind = lines.stream().anyMatch(collisionMarkerLine::equals);
        boolean hasAnyMarkerLine = lines.stream().anyMatch(candidateLine -> {
            String stripped = candidateLine.strip();
            return stripped.startsWith("contentType") || stripped.startsWith("\"contentType\"");
        });
        if (hasAnyMarkerLine && !matchesIncomingKind) {
            throw new ManagedSiteKindCollisionException(identity, resolved);
        }
    }

    private static void requireInstallationInputs(
            PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");
    }

    private void requireIdentityConfinement(PublicationIdentity identity) {
        resolveWithinSiteRoot(stagedInstall.canonicalRoot()
                .resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .normalize());
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

    private void stageLocaleFiles(Path staging, ManagedArtifact ruArtifact, ManagedArtifact enArtifact)
            throws IOException {
        writeLocaleFile(staging, ruArtifact, "ru");
        writeLocaleFile(staging, enArtifact, "en");
    }

    private void writeLocaleFile(Path staging, ManagedArtifact artifact, String locale) throws IOException {
        writeStagedFile(staging, locale + ".md", artifact.content());
    }

    private void installManagedGeneration(Path staging, Path ruDestination, Path enDestination) throws IOException {
        Path ruBackup = replaceLocaleFile(stagedFile(staging, "ru.md"), ruDestination);
        try {
            Path enBackup = replaceLocaleFile(stagedFile(staging, "en.md"), enDestination);
            try {
                writeProvenanceForCurrentState(ruBackup, enBackup);
            } catch (IOException | RuntimeException failure) {
                rollbackLocaleReplacement(enDestination, enBackup, failure);
                throw failure;
            }
            deleteLocaleBackupIfPresent(enBackup);
        } catch (IOException | RuntimeException failure) {
            rollbackLocaleReplacement(ruDestination, ruBackup, failure);
            throw failure;
        }
        deleteLocaleBackupIfPresent(ruBackup);
    }

    private <T> T withInstallationLock(PublicationIdentity identity, Supplier<T> operation) {
        Path lockFile = installationLock();
        try {
            Path resolvedLock = createAndResolveParentDirectories(lockFile);
            try (FileChannel channel = FileChannel.open(resolvedLock,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = tryAcquire(channel, identity)) {
                return operation.get();
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private FileLock tryAcquire(FileChannel channel, PublicationIdentity identity) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new SiteAlreadyInstalledException(identity);
            }
            return lock;
        } catch (OverlappingFileLockException collision) {
            throw new SiteAlreadyInstalledException(identity);
        }
    }

    private Path installationLock() {
        return stagedInstall.canonicalRoot()
                .resolve(".astro-export/install-locks/.site.installing")
                .normalize();
    }

    private Path replaceLocaleFile(Path source, Path destination) throws IOException {
        Path resolvedDestination = createAndResolveParentDirectories(destination);
        Path resolvedSource = resolveWithinSiteRoot(source);
        Path backup = null;
        if (Files.exists(resolvedDestination, LinkOption.NOFOLLOW_LINKS)) {
            Path mirroredDestination = managedBackupMirror(resolvedDestination);
            backup = resolveWithinSiteRoot(mirroredDestination.resolveSibling(
                    mirroredDestination.getFileName() + ".backup-" + UUID.randomUUID()).normalize());
            createAndResolveParentDirectories(backup);
            moveLocaleFile(resolvedDestination, backup);
        }
        try {
            moveLocaleFile(resolvedSource, resolvedDestination);
        } catch (IOException | RuntimeException failure) {
            if (backup != null) {
                rollbackLocaleReplacement(resolvedDestination, backup, failure);
            }
            throw failure;
        }
        return backup;
    }

    private void rollbackLocaleReplacement(Path destination, Path backup, Throwable installationFailure) {
        try {
            Path resolvedDestination = resolveWithinSiteRoot(destination);
            Files.deleteIfExists(resolvedDestination);
            // A null backup means this replacement created a previously absent canonical file.
            if (backup != null) {
                moveLocaleFile(backup, destination);
            }
        } catch (IOException | RuntimeException restoreFailure) {
            installationFailure.addSuppressed(restoreFailure);
        }
    }

    private void deleteLocaleBackupIfPresent(Path backup) {
        if (backup == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolveWithinSiteRoot(backup));
        } catch (IOException error) {
            System.err.println("WARNING: could not remove stale locale backup " + backup + ": " + error);
        }
    }

    private boolean recoverIfNeeded(Path ruDestination, Path enDestination) {
        Optional<Path> ruBackup = findLocaleBackup(ruDestination);
        Optional<Path> enBackup = findLocaleBackup(enDestination);
        boolean backupExists = ruBackup.isPresent() || enBackup.isPresent();
        if (!backupExists && freshInstallationState(ruDestination, enDestination)) {
            return false;
        }

        SiteReleaseManifest currentManifest;
        try {
            ensurePayloadRoots();
            currentManifest = computeManifest(ruBackup.orElse(null), enBackup.orElse(null));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        if (provenanceMatches(currentManifest)) {
            ruBackup.ifPresent(this::deleteLocaleBackupIfPresent);
            enBackup.ifPresent(this::deleteLocaleBackupIfPresent);
            return backupExists;
        }
        if (!backupExists) {
            throw ManagedSiteRecoveryException.provenanceMismatchWithoutBackups(manifestPath());
        }

        ruBackup.ifPresent(backup -> restoreInterruptedLocale(ruDestination, backup));
        enBackup.ifPresent(backup -> restoreInterruptedLocale(enDestination, backup));
        try {
            writeProvenanceForCurrentState(ruBackup.orElse(null), enBackup.orElse(null));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        ruBackup.ifPresent(this::deleteLocaleBackupIfPresent);
        enBackup.ifPresent(this::deleteLocaleBackupIfPresent);
        return true;
    }

    private boolean freshInstallationState(Path ruDestination, Path enDestination) {
        return !Files.exists(resolveWithinSiteRoot(ruDestination), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(resolveWithinSiteRoot(enDestination), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(resolveWithinSiteRoot(manifestPath()), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean provenanceMatches(SiteReleaseManifest currentManifest) {
        try {
            Path recordedProvenance = resolveWithinSiteRoot(manifestPath());
            return Files.isRegularFile(recordedProvenance, LinkOption.NOFOLLOW_LINKS)
                    && Files.readString(recordedProvenance, StandardCharsets.UTF_8)
                            .equals(currentManifest.toCanonicalJson());
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    private void restoreInterruptedLocale(Path destination, Path backup) {
        try {
            Files.deleteIfExists(resolveWithinSiteRoot(destination));
            Files.copy(resolveWithinSiteRoot(backup), resolveWithinSiteRoot(destination));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> findLocaleBackup(Path destination) {
        Path resolvedDestination = resolveWithinSiteRoot(destination);
        Path parent = managedBackupMirror(resolvedDestination).getParent();
        if (parent == null) {
            throw new ManagedSiteInstallerConfinementException(
                    resolvedDestination, resolvedDestination, stagedInstall.canonicalRoot());
        }
        Path resolvedParent = resolveWithinSiteRoot(parent);
        if (!Files.isDirectory(resolvedParent, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String prefix = resolvedDestination.getFileName() + ".backup-";
        try (var entries = Files.list(resolvedParent)) {
            List<Path> backups = entries
                    .filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> validBackupMarker(entry.getFileName().toString(), prefix))
                    .map(this::resolveWithinSiteRoot)
                    .toList();
            if (backups.size() > 1) {
                throw new UncheckedIOException(new IOException(
                        "Multiple locale recovery backups exist for " + resolvedDestination + ": " + backups));
            }
            return backups.stream().findFirst();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Path managedBackupMirror(Path destination) {
        Path resolvedDestination = resolveWithinSiteRoot(destination);
        Path relativeDestination = stagedInstall.canonicalRoot().relativize(resolvedDestination);
        return resolveWithinSiteRoot(stagedInstall.canonicalRoot()
                .resolve(MANAGED_BACKUP_ROOT)
                .resolve(relativeDestination)
                .normalize());
    }

    private static boolean validBackupMarker(String fileName, String prefix) {
        if (!fileName.startsWith(prefix)) {
            return false;
        }
        String suffix = fileName.substring(prefix.length());
        try {
            return UUID.fromString(suffix).toString().equalsIgnoreCase(suffix);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }

    private void moveLocaleFile(Path source, Path destination) throws IOException {
        Files.move(resolveWithinSiteRoot(source), resolveWithinSiteRoot(destination),
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeProvenanceForCurrentState(Path... ignoredBackups) throws IOException {
        ensurePayloadRoots();
        SiteReleaseManifest manifest = computeManifest(ignoredBackups);
        Path destination = createAndResolveParentDirectories(manifestPath());
        Path temporary = resolveWithinSiteRoot(Files.createTempFile(
                destination.getParent(), "release-provenance-", ".tmp"));
        try {
            Files.writeString(temporary, manifest.toCanonicalJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            replaceFile(temporary, destination);
        } finally {
            Files.deleteIfExists(resolveWithinSiteRoot(temporary));
        }
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

    private SiteReleaseManifest computeManifest(Path... ignoredBackups) {
        resolveWithinSiteRoot(stagedInstall.canonicalRoot());
        for (String relativeRoot : PAYLOAD_ROOTS) {
            resolveWithinSiteRoot(stagedInstall.canonicalRoot().resolve(relativeRoot));
        }
        List<Path> ignored = java.util.Arrays.stream(ignoredBackups)
                .filter(Objects::nonNull)
                .map(this::resolveWithinSiteRoot)
                .toList();
        return SiteReleaseManifest.computeOver(stagedInstall.canonicalRoot(), PAYLOAD_ROOTS, ignored);
    }

    private Path destinationFile(ManagedArtifact artifact) {
        return stagedInstall.canonicalRoot().resolve(artifact.relativePath()).normalize();
    }

    private PublicationKind requireKind(PublicationIdentity identity) {
        return publicationKinds.forIdentity(identity.publicCollection(), identity.publicContentType())
                .orElseThrow(() -> new IllegalStateException(
                        "No installed PublicationKind for " + identity.publicCollection()
                                + "/" + identity.publicContentType()));
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
         * stable trust boundary even when the root itself is initially absent, and passes that
         * already-canonical path to StagedDirectoryInstall without a second filesystem resolution.
         * StagedDirectoryInstall re-resolves candidate and root on every confinement check; this
         * stable comparison additionally rejects replacing the root itself with an outside symlink.
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

}
