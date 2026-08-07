package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String BODY_FILE_NAME = "candidate.en.md";
    private static final String TITLE_FILE_NAME = "candidate.en.title.txt";
    private static final String DESCRIPTION_FILE_NAME = "candidate.en.description.txt";
    private static final String FINGERPRINT_FILE_NAME = "job.fingerprint";

    private final TranslationCommand command;
    private final Duration timeout;
    private final Path jobRoot;

    public ProcessTranslationWorker(TranslationCommand command, Duration timeout, Path jobRoot) {
        this.command = Objects.requireNonNull(command, "command");
        this.timeout = requirePositive(timeout);
        this.jobRoot = Objects.requireNonNull(jobRoot, "jobRoot");
    }

    @Override
    public TranslationResult translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription) {
        Objects.requireNonNull(job, "job");
        JobWorkspace workspace = createScratchWorkdir(job);
        try {
            writeFingerprint(workspace, job.sourceFingerprint());
            return runAndCollect(workspace, job, prompt(ruBody, ruTitle, ruDescription));
        } finally {
            if (workspace.identityIsCurrentQuietly()) {
                deleteRecursively(workspace.path());
            }
        }
    }

    private TranslationResult runAndCollect(JobWorkspace workspace, TranslationJob job, String prompt) {
        try {
            Process process = new ProcessBuilder(command.argsFor(workspace.path(), prompt))
                    .directory(workspace.path().toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(
                    () -> drainOutput(process));
            return awaitResult(process, workspace, job, outputDrainer);
        } catch (IOException error) {
            return TranslationResult.failure("Translation worker failed to start: " + error.getMessage());
        }
    }

    private TranslationResult awaitResult(
            Process process, JobWorkspace workspace, TranslationJob job,
            CompletableFuture<Void> outputDrainer) {
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                return TranslationResult.failure(
                        "Translation worker timed out after " + timeout.getSeconds() + "s.");
            }
            if (process.exitValue() != 0) {
                return TranslationResult.failure(
                        "Translation worker exited with code " + process.exitValue() + ".");
            }
            return collectResult(workspace, job);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return TranslationResult.failure("Translation worker was interrupted.");
        } finally {
            outputDrainer.join();
        }
    }

    private static void drainOutput(Process process) {
        try (var output = process.getInputStream()) {
            output.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // The process outcome determines the translation result.
        }
    }

    private TranslationResult collectResult(JobWorkspace workspace, TranslationJob job) {
        FileRead fingerprint = readIfPresent(workspace, FINGERPRINT_FILE_NAME);
        if (fingerprint.isMissing()) {
            return missingFileFailure(FINGERPRINT_FILE_NAME);
        }
        if (fingerprint.error() != null) {
            return readFailure(FINGERPRINT_FILE_NAME, fingerprint.error());
        }
        if (!job.sourceFingerprint().equals(fingerprint.content())) {
            return TranslationResult.failure("Translation worker job fingerprint did not match the request.");
        }

        FileRead body = readIfPresent(workspace, BODY_FILE_NAME);
        if (body.isMissing()) {
            return missingFileFailure(BODY_FILE_NAME);
        }
        if (body.error() != null) {
            return readFailure(BODY_FILE_NAME, body.error());
        }

        FileRead title = readIfPresent(workspace, TITLE_FILE_NAME);
        if (title.isMissing()) {
            return missingFileFailure(TITLE_FILE_NAME);
        }
        if (title.error() != null) {
            return readFailure(TITLE_FILE_NAME, title.error());
        }

        FileRead description = readIfPresent(workspace, DESCRIPTION_FILE_NAME);
        if (description.isMissing()) {
            return missingFileFailure(DESCRIPTION_FILE_NAME);
        }
        if (description.error() != null) {
            return readFailure(DESCRIPTION_FILE_NAME, description.error());
        }

        return TranslationResult.success(body.content(), title.content(), description.content());
    }

    private FileRead readIfPresent(JobWorkspace workspace, String fileName) {
        Path file = workspace.path().resolve(fileName).normalize();
        try {
            FileSnapshot before = snapshotWithinJob(workspace, file, fileName);
            if (before == null) {
                return FileRead.missing();
            }
            byte[] content;
            try (var channel = Files.newByteChannel(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    var input = Channels.newInputStream(channel)) {
                content = input.readAllBytes();
            }
            FileSnapshot after = snapshotWithinJob(workspace, file, fileName);
            if (after == null || !before.sameFileAs(after) || after.size() != content.length) {
                return FileRead.missing();
            }
            return FileRead.present(new String(content, StandardCharsets.UTF_8));
        } catch (NoSuchFileException error) {
            return FileRead.missing();
        } catch (IOException error) {
            return Files.isSymbolicLink(file) ? FileRead.missing() : FileRead.unreadable(error);
        }
    }

    private static FileSnapshot snapshotWithinJob(
            JobWorkspace workspace, Path file, String fileName) throws IOException {
        if (!file.getParent().equals(workspace.path()) || !workspace.identityIsCurrent()) {
            return null;
        }
        Path resolved = file.toRealPath();
        if (!resolved.getParent().equals(workspace.canonicalPath())) {
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || hardLinkCount(file) != 1) {
            return null;
        }
        return new FileSnapshot(attributes.fileKey(), attributes.size());
    }

    private static int hardLinkCount(Path file) throws IOException {
        Object count = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(count instanceof Number number)) {
            throw new IOException("Could not determine hard-link count for " + file);
        }
        return number.intValue();
    }

    private static TranslationResult missingFileFailure(String fileName) {
        return TranslationResult.failure("Translation worker completed without writing " + fileName + ".");
    }

    private static TranslationResult readFailure(String fileName, IOException error) {
        return TranslationResult.failure("Could not read " + fileName + ": " + error.getMessage());
    }

    private record FileRead(String content, IOException error) {

        private static FileRead present(String content) {
            return new FileRead(content, null);
        }

        private static FileRead missing() {
            return new FileRead(null, null);
        }

        private static FileRead unreadable(IOException error) {
            return new FileRead(null, error);
        }

        private boolean isMissing() {
            return content == null && error == null;
        }
    }

    private record FileSnapshot(Object fileKey, long size) {

        private boolean sameFileAs(FileSnapshot other) {
            return fileKey.equals(other.fileKey);
        }
    }

    private record JobWorkspace(Path path, Path canonicalPath, Object directoryKey) {

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
    }

    private static String prompt(String ruBody, String ruTitle, String ruDescription) {
        return """
                # Bounded Russian-to-English publication translation

                Work only inside the current directory. Translate the Russian title, description,
                and body below to English prose of equivalent meaning and structure. Write:
                - the translated title, and only the title, to candidate.en.title.txt
                - the translated description, and only the description, to candidate.en.description.txt
                - the translated body, and only the body, to candidate.en.md
                Do not return commentary or a patch in place of those files.

                <title>
                %s
                </title>
                <description>
                %s
                </description>
                <body>
                %s
                </body>
                """.formatted(ruTitle, ruDescription, ruBody);
    }

    private JobWorkspace createScratchWorkdir(TranslationJob job) {
        try {
            Path canonicalRoot = Files.createDirectories(jobRoot).toRealPath();
            Path requestedWorkdir = canonicalRoot.resolve(job.id()).normalize();
            if (!canonicalRoot.equals(requestedWorkdir.getParent())) {
                throw new IOException("Translation job ID escapes the configured job root.");
            }
            Files.createDirectory(requestedWorkdir);
            Path canonicalWorkdir = requestedWorkdir.toRealPath();
            if (!canonicalRoot.equals(canonicalWorkdir.getParent())) {
                throw new IOException("Translation job directory escapes the configured job root.");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    requestedWorkdir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.fileKey() == null) {
                throw new IOException("Translation job directory identity is unavailable.");
            }
            return new JobWorkspace(requestedWorkdir, canonicalWorkdir, attributes.fileKey());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void writeFingerprint(JobWorkspace workspace, String fingerprint) {
        Path marker = workspace.path().resolve(FINGERPRINT_FILE_NAME);
        try (var channel = Files.newByteChannel(
                marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
                var output = Channels.newOutputStream(channel)) {
            output.write(fingerprint.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ProcessTranslationWorker::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort scratch-directory cleanup; a leftover temp dir is not a correctness failure
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see deleteRecursively
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.toMillis() == 0) {
            throw new IllegalArgumentException("timeout must be at least 1ms");
        }
        return timeout;
    }
}
