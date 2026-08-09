package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AntigravityTranslationCommandTest {

    @Test
    void invokesAgyInPrintModeWithTheTranslationPrompt() {
        List<String> args = new AntigravityTranslationCommand().argsFor(
                Path.of("/tmp/job-42"), "Translate this");

        assertEquals(List.of(
                "agy", "--print", "--mode", "accept-edits", "--prompt", "Translate this"), args);
    }
}
