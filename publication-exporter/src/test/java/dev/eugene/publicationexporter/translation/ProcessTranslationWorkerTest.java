package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTranslationWorkerTest {

    @Test
    void resultFileWrittenByTheProcessIsReturnedAsSuccess() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesFixedResult("Translated text"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertTrue(result.succeeded());
        assertEquals("Translated text", result.enBody());
    }

    @Test
    void missingResultFileIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "true"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("candidate.en.md"));
    }

    @Test
    void nonZeroExitIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "exit 3"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("3"));
    }

    @Test
    void timeoutIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "sleep 5"), Duration.ofMillis(200));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().toLowerCase().contains("timed out"));
    }

    @Test
    void largeCombinedOutputDoesNotPreventProcessCompletion() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c",
                        "yes | head -c 200000; printf '%s' 'Translated text' > candidate.en.md"),
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertTrue(result.succeeded());
        assertEquals("Translated text", result.enBody());
    }

    @Test
    void timeoutUnderOneMillisecondIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ProcessTranslationWorker(writesFixedResult("Translated text"),
                        Duration.ofNanos(500)));

        assertTrue(error.getMessage().contains("at least 1ms"));
    }

    private static TranslationCommand writesFixedResult(String content) {
        return (Path workdir, String prompt) -> List.of("sh", "-c",
                "printf '%s' " + shellQuote(content) + " > candidate.en.md");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
