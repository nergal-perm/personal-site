package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String BODY_FILE_NAME = "candidate.en.md";
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
    public TranslationOutcome translate(TranslationJob job, String ruBody, List<PublicField> ruFields) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(ruFields, "ruFields");
        JobWorkspace workspace = JobWorkspace.createAt(jobRoot, job);
        try {
            workspace.writeFingerprint(job.sourceFingerprint());
            return runAndCollect(workspace, job, prompt(ruBody, ruFields), ruFields);
        } finally {
            workspace.cleanup();
        }
    }

    private TranslationOutcome runAndCollect(
            JobWorkspace workspace, TranslationJob job, String prompt, List<PublicField> ruFields) {
        try {
            Process process = new ProcessBuilder(command.argsFor(workspace.path(), prompt))
                    .directory(workspace.path().toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(
                    () -> drainOutput(process));
            return awaitResult(process, workspace, job, ruFields, outputDrainer);
        } catch (IOException error) {
            return TranslationOutcome.failure("Translation worker failed to start: " + error.getMessage());
        }
    }

    private TranslationOutcome awaitResult(
            Process process, JobWorkspace workspace, TranslationJob job,
            List<PublicField> ruFields, CompletableFuture<Void> outputDrainer) {
        TranslationOutcome processResult;
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                processResult = TranslationOutcome.failure(
                        "Translation worker timed out after " + timeout.getSeconds() + "s.");
            } else if (process.exitValue() != 0) {
                processResult = TranslationOutcome.failure(
                        "Translation worker exited with code " + process.exitValue() + ".");
            } else {
                processResult = collectResult(workspace, job, ruFields);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            processResult = TranslationOutcome.failure("Translation worker was interrupted.");
        }
        return afterBoundedOutputDrain(process, outputDrainer, processResult);
    }

    private static TranslationOutcome afterBoundedOutputDrain(
            Process process, CompletableFuture<Void> outputDrainer, TranslationOutcome processResult) {
        try {
            outputDrainer.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return processResult;
        } catch (TimeoutException timeout) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            return TranslationOutcome.failure(
                    "Translation worker output stream did not close within "
                            + OUTPUT_DRAIN_TIMEOUT_SECONDS + "s after process completion.");
        } catch (InterruptedException interrupted) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            Thread.currentThread().interrupt();
            return TranslationOutcome.failure("Translation worker output drain was interrupted.");
        } catch (ExecutionException failure) {
            return TranslationOutcome.failure(
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

    private TranslationOutcome collectResult(
            JobWorkspace workspace, TranslationJob job, List<PublicField> ruFields) {
        try {
            workspace.requireMatchingFingerprint(job.sourceFingerprint());
            return validatedResultFrom(workspace, ruFields);
        } catch (JobWorkspace.FingerprintMismatchException mismatch) {
            return TranslationOutcome.failure("Translation worker job fingerprint did not match the request.");
        } catch (JobWorkspace.MissingFileException missing) {
            return missingFileFailure(missing.fileName());
        } catch (JobWorkspace.UnreadableFileException unreadable) {
            return readFailure(unreadable.fileName(), unreadable.error());
        }
    }

    private TranslationOutcome validatedResultFrom(JobWorkspace workspace, List<PublicField> ruFields)
            throws JobWorkspace.MissingFileException, JobWorkspace.UnreadableFileException {
        String translatedBody = workspace.readRequiredResult(BODY_FILE_NAME);
        List<PublicField> translatedFields = new ArrayList<>();
        for (PublicField ruField : ruFields) {
            translatedFields.add(PublicField.of(
                    ruField.key(), workspace.readRequiredResult(translatedFieldFileName(ruField.key()))));
        }
        return TranslationOutcome.success(translatedBody, translatedFields);
    }

    private static TranslationOutcome missingFileFailure(String fileName) {
        return TranslationOutcome.failure("Translation worker completed without writing " + fileName + ".");
    }

    private static TranslationOutcome readFailure(String fileName, IOException error) {
        return TranslationOutcome.failure("Could not read " + fileName + ": " + error.getMessage());
    }

    private static String prompt(String ruBody, List<PublicField> ruFields) {
        StringBuilder prompt = new StringBuilder("""
                # Bounded Russian-to-English publication translation

                Work only inside the current directory. Translate every labeled field and the body
                below to English prose of equivalent meaning and structure. Write:
                """);
        for (PublicField ruField : ruFields) {
            prompt.append("- the translated ")
                    .append(ruField.key())
                    .append(", and only that field, to ")
                    .append(translatedFieldFileName(ruField.key()))
                    .append('\n');
        }
        prompt.append("- the translated body, and only the body, to ")
                .append(BODY_FILE_NAME)
                .append("\n")
                .append("Do not return commentary or a patch in place of those files.\n");
        for (PublicField ruField : ruFields) {
            prompt.append('\n')
                    .append('<').append(ruField.key()).append(">\n")
                    .append(ruField.value())
                    .append("\n</").append(ruField.key()).append(">\n");
        }
        return prompt.append("\n<body>\n")
                .append(ruBody)
                .append("\n</body>\n")
                .toString();
    }

    private static String translatedFieldFileName(String key) {
        return "candidate.en." + key + ".txt";
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
