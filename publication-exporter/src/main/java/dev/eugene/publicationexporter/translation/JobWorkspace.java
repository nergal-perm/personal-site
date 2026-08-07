package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Objects;

final class JobWorkspace {

    private static final String FINGERPRINT_FILE_NAME = "job.fingerprint";

    private final Path path;
    private final Path canonicalPath;
    private final Object directoryKey;

    private JobWorkspace(Path path, Path canonicalPath, Object directoryKey) {
        this.path = path;
        this.canonicalPath = canonicalPath;
        this.directoryKey = directoryKey;
    }

    static JobWorkspace createAt(Path jobRoot, TranslationJob job) {
        Objects.requireNonNull(jobRoot, "jobRoot");
        Objects.requireNonNull(job, "job");
        try {
            Path canonicalRoot = Files.createDirectories(jobRoot).toRealPath();
            Path requestedWorkspace = canonicalRoot.resolve(job.id()).normalize();
            requireDirectChild(canonicalRoot, requestedWorkspace,
                    "Translation job ID escapes the configured job root.");
            Files.createDirectory(requestedWorkspace);
            Path canonicalWorkspace = requestedWorkspace.toRealPath();
            requireDirectChild(canonicalRoot, canonicalWorkspace,
                    "Translation job directory escapes the configured job root.");
            BasicFileAttributes attributes = Files.readAttributes(
                    requestedWorkspace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.fileKey() == null) {
                throw new IOException("Translation job directory identity is unavailable.");
            }
            return new JobWorkspace(requestedWorkspace, canonicalWorkspace, attributes.fileKey());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    Path path() {
        return path;
    }

    void writeFingerprint(String fingerprint) {
        Path marker = path.resolve(FINGERPRINT_FILE_NAME);
        try (var channel = Files.newByteChannel(
                marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
                var output = Channels.newOutputStream(channel)) {
            output.write(fingerprint.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    void requireMatchingFingerprint(String expectedFingerprint)
            throws MissingFileException, UnreadableFileException, FingerprintMismatchException {
        String actualFingerprint = readRequiredResult(FINGERPRINT_FILE_NAME);
        if (!expectedFingerprint.equals(actualFingerprint)) {
            throw new FingerprintMismatchException();
        }
    }

    String readRequiredResult(String fileName)
            throws MissingFileException, UnreadableFileException {
        Path file = path.resolve(fileName).normalize();
        try {
            FileSnapshot before = snapshotWithinWorkspace(file);
            if (before == null) {
                throw new MissingFileException(fileName);
            }
            byte[] content = readBytesWithoutFollowingLinks(file);
            FileSnapshot after = snapshotWithinWorkspace(file);
            if (after == null || !before.sameFileAs(after) || after.size() != content.length) {
                throw new MissingFileException(fileName);
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (MissingFileException error) {
            throw error;
        } catch (NoSuchFileException error) {
            throw new MissingFileException(fileName);
        } catch (IOException error) {
            if (Files.isSymbolicLink(file)) {
                throw new MissingFileException(fileName);
            }
            throw new UnreadableFileException(fileName, error);
        }
    }

    void cleanup() {
        if (!identityIsCurrentQuietly()) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(JobWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort scratch-directory cleanup; a leftover job directory is not a correctness failure
        }
    }

    private static void requireDirectChild(Path root, Path child, String message) throws IOException {
        if (!root.equals(child.getParent())) {
            throw new IOException(message);
        }
    }

    private byte[] readBytesWithoutFollowingLinks(Path file) throws IOException {
        try (var channel = Files.newByteChannel(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var input = Channels.newInputStream(channel)) {
            return input.readAllBytes();
        }
    }

    private FileSnapshot snapshotWithinWorkspace(Path file) throws IOException {
        if (!file.getParent().equals(path) || !identityIsCurrent()) {
            return null;
        }
        Path resolved = file.toRealPath();
        if (!resolved.getParent().equals(canonicalPath)) {
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || hardLinkCount(file) != 1) {
            return null;
        }
        return new FileSnapshot(attributes.fileKey(), attributes.size());
    }

    private boolean identityIsCurrent() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && directoryKey.equals(attributes.fileKey());
    }

    private boolean identityIsCurrentQuietly() {
        try {
            return identityIsCurrent();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int hardLinkCount(Path file) throws IOException {
        Object count = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(count instanceof Number number)) {
            throw new IOException("Could not determine hard-link count for " + file);
        }
        return number.intValue();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see cleanup
        }
    }

    private record FileSnapshot(Object fileKey, long size) {

        private boolean sameFileAs(FileSnapshot other) {
            return fileKey.equals(other.fileKey);
        }
    }

    static final class MissingFileException extends Exception {

        private final String fileName;

        private MissingFileException(String fileName) {
            this.fileName = fileName;
        }

        String fileName() {
            return fileName;
        }
    }

    static final class UnreadableFileException extends Exception {

        private final String fileName;
        private final IOException error;

        private UnreadableFileException(String fileName, IOException error) {
            this.fileName = fileName;
            this.error = error;
        }

        String fileName() {
            return fileName;
        }

        IOException error() {
            return error;
        }
    }

    static final class FingerprintMismatchException extends Exception {
    }
}
