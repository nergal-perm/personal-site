package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String RESULT_FILE_NAME = "candidate.en.md";

    private final TranslationCommand command;
    private final Duration timeout;

    public ProcessTranslationWorker(TranslationCommand command, Duration timeout) {
        this.command = Objects.requireNonNull(command, "command");
        this.timeout = requirePositive(timeout);
    }

    @Override
    public TranslationResult translate(String ruBody) {
        Path workdir = createScratchWorkdir();
        try {
            return runAndCollect(workdir, prompt(ruBody));
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
            return awaitResult(process, workdir);
        } catch (IOException error) {
            return TranslationResult.failure("Translation worker failed to start: " + error.getMessage());
        }
    }

    private TranslationResult awaitResult(Process process, Path workdir) {
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
            Thread.currentThread().interrupt();
            return TranslationResult.failure("Translation worker was interrupted.");
        }
    }

    private TranslationResult collectResult(Path workdir) {
        Path resultFile = workdir.resolve(RESULT_FILE_NAME);
        if (!Files.isRegularFile(resultFile)) {
            return TranslationResult.failure(
                    "Translation worker completed without writing " + RESULT_FILE_NAME + ".");
        }
        try {
            return TranslationResult.success(Files.readString(resultFile, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return TranslationResult.failure("Could not read " + RESULT_FILE_NAME + ": " + error.getMessage());
        }
    }

    private static String prompt(String ruBody) {
        return """
                # Bounded Russian-to-English publication translation

                Work only inside the current directory. Translate the Russian text below to
                English prose of equivalent meaning and structure. Write the complete
                translation, and only the translation, to a file named candidate.en.md in the
                current directory. Do not return commentary or a patch in place of that file.

                <source>
                %s
                </source>
                """.formatted(ruBody);
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
        return timeout;
    }
}
