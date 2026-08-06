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
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String BODY_FILE_NAME = "candidate.en.md";
    private static final String TITLE_FILE_NAME = "candidate.en.title.txt";
    private static final String DESCRIPTION_FILE_NAME = "candidate.en.description.txt";

    private final TranslationCommand command;
    private final Duration timeout;

    public ProcessTranslationWorker(TranslationCommand command, Duration timeout) {
        this.command = Objects.requireNonNull(command, "command");
        this.timeout = requirePositive(timeout);
    }

    @Override
    public TranslationResult translate(String ruBody, String ruTitle, String ruDescription) {
        Path workdir = createScratchWorkdir();
        try {
            return runAndCollect(workdir, prompt(ruBody, ruTitle, ruDescription));
        } finally {
            deleteRecursively(workdir);
        }
    }

    private TranslationResult runAndCollect(Path workdir, String prompt) {
        try {
            Process process = new ProcessBuilder(command.argsFor(workdir, prompt))
                    .directory(workdir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(
                    () -> drainOutput(process));
            return awaitResult(process, workdir, outputDrainer);
        } catch (IOException error) {
            return TranslationResult.failure("Translation worker failed to start: " + error.getMessage());
        }
    }

    private TranslationResult awaitResult(
            Process process, Path workdir, CompletableFuture<Void> outputDrainer) {
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
            return collectResult(workdir);
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

    private TranslationResult collectResult(Path workdir) {
        FileRead body = readIfPresent(workdir, BODY_FILE_NAME);
        if (body.isMissing()) {
            return missingFileFailure(BODY_FILE_NAME);
        }
        if (body.error() != null) {
            return readFailure(BODY_FILE_NAME, body.error());
        }

        FileRead title = readIfPresent(workdir, TITLE_FILE_NAME);
        if (title.isMissing()) {
            return missingFileFailure(TITLE_FILE_NAME);
        }
        if (title.error() != null) {
            return readFailure(TITLE_FILE_NAME, title.error());
        }

        FileRead description = readIfPresent(workdir, DESCRIPTION_FILE_NAME);
        if (description.isMissing()) {
            return missingFileFailure(DESCRIPTION_FILE_NAME);
        }
        if (description.error() != null) {
            return readFailure(DESCRIPTION_FILE_NAME, description.error());
        }

        return TranslationResult.success(body.content(), title.content(), description.content());
    }

    private FileRead readIfPresent(Path workdir, String fileName) {
        Path file = workdir.resolve(fileName);
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var input = Channels.newInputStream(channel)) {
            return FileRead.present(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (NoSuchFileException error) {
            return FileRead.missing();
        } catch (IOException error) {
            return Files.isSymbolicLink(file) ? FileRead.missing() : FileRead.unreadable(error);
        }
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

    private static Path createScratchWorkdir() {
        try {
            return Files.createTempDirectory("publication-exporter-translate-");
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
