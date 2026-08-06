package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTranslationWorkerTest {

    @TempDir
    Path externalRoot;

    @Test
    void resultFileWrittenByTheProcessIsReturnedAsSuccess() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesFixedResult("Translated text", "Translated title", "Translated description."),
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertTrue(result.succeeded());
        assertEquals("Translated text", result.enBody());
        assertEquals("Translated title", result.enTitle());
        assertEquals("Translated description.", result.enDescription());
    }

    @Test
    void missingResultFileIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "true"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("candidate.en.md"));
    }

    @Test
    void missingTitleFileIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c",
                        "printf '%s' 'body' > candidate.en.md && printf '%s' 'desc' > candidate.en.description.txt"),
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored", "ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("candidate.en.title.txt"));
    }

    @Test
    void unreadableTitleFileIsReportedAsFailure() throws Exception {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> {
                    writeString(workdir.resolve("candidate.en.md"), "body");
                    Path title = workdir.resolve("candidate.en.title.txt");
                    writeString(title, "title");
                    writeString(workdir.resolve("candidate.en.description.txt"), "description");
                    try {
                        Files.setPosixFilePermissions(title, Set.of());
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                    return List.of("sh", "-c", "true");
                },
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored", "ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("Could not read candidate.en.title.txt:"));
    }

    @Test
    void nonZeroExitIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "exit 3"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("3"));
    }

    @Test
    void timeoutIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "sleep 5"), Duration.ofMillis(200));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().toLowerCase().contains("timed out"));
    }

    @Test
    void largeCombinedOutputDoesNotPreventProcessCompletion() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c",
                        "yes | head -c 200000; "
                                + "printf '%s' 'Translated text' > candidate.en.md && "
                                + "printf '%s' 'Translated title' > candidate.en.title.txt && "
                                + "printf '%s' 'Translated description.' > candidate.en.description.txt"),
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertTrue(result.succeeded());
        assertEquals("Translated text", result.enBody());
        assertEquals("Translated title", result.enTitle());
        assertEquals("Translated description.", result.enDescription());
    }

    @Test
    void timeoutUnderOneMillisecondIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ProcessTranslationWorker(
                        writesFixedResult("Translated text", "Translated title", "Translated description."),
                        Duration.ofNanos(500)));

        assertTrue(error.getMessage().contains("at least 1ms"));
    }

    @Test
    void symlinkedResultFileIsReportedAsMissing() throws Exception {
        Path externalResult = externalRoot.resolve("external-candidate.md");
        Files.writeString(externalResult, "Content outside the worker scratch directory");
        TranslationCommand writesSymlinkedResult = (workdir, prompt) -> {
            try {
                Files.createSymbolicLink(workdir.resolve("candidate.en.md"), externalResult);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return List.of("sh", "-c", "true");
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesSymlinkedResult, Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("without writing candidate.en.md"));
    }

    @Test
    void symlinkedTitleFileIsReportedAsMissing() throws Exception {
        Path externalTitle = externalRoot.resolve("external-candidate-title.txt");
        Files.writeString(externalTitle, "Title outside the worker scratch directory");
        TranslationCommand writesSymlinkedTitle = (workdir, prompt) -> {
            writeString(workdir.resolve("candidate.en.md"), "body");
            writeString(workdir.resolve("candidate.en.description.txt"), "description");
            try {
                Files.createSymbolicLink(workdir.resolve("candidate.en.title.txt"), externalTitle);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return List.of("sh", "-c", "true");
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesSymlinkedTitle, Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("without writing candidate.en.title.txt"));
    }

    @Test
    void symlinkedDescriptionFileIsReportedAsMissing() throws Exception {
        Path externalDescription = externalRoot.resolve("external-candidate-description.txt");
        Files.writeString(externalDescription, "Description outside the worker scratch directory");
        TranslationCommand writesSymlinkedDescription = (workdir, prompt) -> {
            writeString(workdir.resolve("candidate.en.md"), "body");
            writeString(workdir.resolve("candidate.en.title.txt"), "title");
            try {
                Files.createSymbolicLink(workdir.resolve("candidate.en.description.txt"), externalDescription);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return List.of("sh", "-c", "true");
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesSymlinkedDescription, Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored title", "ignored description");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("without writing candidate.en.description.txt"));
    }

    private static void writeString(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static TranslationCommand writesFixedResult(String body, String title, String description) {
        return (Path workdir, String prompt) -> List.of("sh", "-c",
                "printf '%s' " + shellQuote(body) + " > candidate.en.md && "
                        + "printf '%s' " + shellQuote(title) + " > candidate.en.title.txt && "
                        + "printf '%s' " + shellQuote(description) + " > candidate.en.description.txt");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
