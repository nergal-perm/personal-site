package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String BODY_FILE_NAME = "candidate.en.md";
    private static final String TITLE_FILE_NAME = "candidate.en.title.txt";
    private static final String DESCRIPTION_FILE_NAME = "candidate.en.description.txt";
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 1;
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
        JobWorkspace workspace = JobWorkspace.createAt(jobRoot, job);
        try {
            workspace.writeFingerprint(job.sourceFingerprint());
            return runAndCollect(workspace, job, prompt(ruBody, ruTitle, ruDescription));
        } finally {
            workspace.cleanup();
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
        TranslationResult processResult;
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                processResult = TranslationResult.failure(
                        "Translation worker timed out after " + timeout.getSeconds() + "s.");
            } else if (process.exitValue() != 0) {
                processResult = TranslationResult.failure(
                        "Translation worker exited with code " + process.exitValue() + ".");
            } else {
                processResult = collectResult(workspace, job);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            processResult = TranslationResult.failure("Translation worker was interrupted.");
        }
        return afterBoundedOutputDrain(process, outputDrainer, processResult);
    }

    private static TranslationResult afterBoundedOutputDrain(
            Process process, CompletableFuture<Void> outputDrainer, TranslationResult processResult) {
        try {
            outputDrainer.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return processResult;
        } catch (TimeoutException timeout) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            return TranslationResult.failure(
                    "Translation worker output stream did not close within "
                            + OUTPUT_DRAIN_TIMEOUT_SECONDS + "s after process completion.");
        } catch (InterruptedException interrupted) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            Thread.currentThread().interrupt();
            return TranslationResult.failure("Translation worker output drain was interrupted.");
        } catch (ExecutionException failure) {
            return TranslationResult.failure(
                    "Translation worker output could not be drained: " + failure.getCause().getMessage());
        }
    }

    private static void closeProcessOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // The bounded-drain failure already describes the worker outcome.
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
        try {
            workspace.requireMatchingFingerprint(job.sourceFingerprint());
            return validatedResultFrom(workspace);
        } catch (JobWorkspace.FingerprintMismatchException mismatch) {
            return TranslationResult.failure("Translation worker job fingerprint did not match the request.");
        } catch (JobWorkspace.MissingFileException missing) {
            return missingFileFailure(missing.fileName());
        } catch (JobWorkspace.UnreadableFileException unreadable) {
            return readFailure(unreadable.fileName(), unreadable.error());
        }
    }

    private TranslationResult validatedResultFrom(JobWorkspace workspace)
            throws JobWorkspace.MissingFileException, JobWorkspace.UnreadableFileException {
        return TranslationResult.success(
                workspace.readRequiredResult(BODY_FILE_NAME),
                workspace.readRequiredResult(TITLE_FILE_NAME),
                workspace.readRequiredResult(DESCRIPTION_FILE_NAME));
    }

    private static TranslationResult missingFileFailure(String fileName) {
        return TranslationResult.failure("Translation worker completed without writing " + fileName + ".");
    }

    private static TranslationResult readFailure(String fileName, IOException error) {
        return TranslationResult.failure("Could not read " + fileName + ": " + error.getMessage());
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
