package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationEngineConfigurationTest {

    @TempDir
    Path exporterRoot;

    @Test
    void defaultsToCodexWhenNoConfigurationExists() {
        TranslationCommand command = TranslationEngineConfiguration.commandFor(exporterRoot, Map.of());

        assertInstanceOf(CodexTranslationCommand.class, command);
    }

    @Test
    void selectsAntigravityFromTheExporterRootConfigurationFile() throws IOException {
        writeConfiguration("[translation]\nengine = \"antigravity\"\n");

        TranslationCommand command = TranslationEngineConfiguration.commandFor(exporterRoot, Map.of());

        assertInstanceOf(AntigravityTranslationCommand.class, command);
    }

    @Test
    void environmentSelectionOverridesTheExporterRootConfigurationFile() throws IOException {
        writeConfiguration("[translation]\nengine = \"antigravity\"\n");

        TranslationCommand command = TranslationEngineConfiguration.commandFor(
                exporterRoot, Map.of("PUBLICATION_EXPORTER_TRANSLATION_ENGINE", "codex"));

        assertInstanceOf(CodexTranslationCommand.class, command);
    }

    @Test
    void blankEnvironmentSelectionUsesTheExporterRootConfigurationFile() throws IOException {
        writeConfiguration("[translation]\nengine = \"antigravity\"\n");

        TranslationCommand command = TranslationEngineConfiguration.commandFor(
                exporterRoot, Map.of("PUBLICATION_EXPORTER_TRANSLATION_ENGINE", "   "));

        assertInstanceOf(AntigravityTranslationCommand.class, command);
    }

    @Test
    void reportsAnUnsupportedEnvironmentSelection() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TranslationEngineConfiguration.commandFor(
                        exporterRoot, Map.of("PUBLICATION_EXPORTER_TRANSLATION_ENGINE", "other")));

        assertTrue(failure.getMessage().contains("PUBLICATION_EXPORTER_TRANSLATION_ENGINE"));
        assertTrue(failure.getMessage().contains("other"));
    }

    @Test
    void reportsAMissingEngineInAnExistingConfigurationFile() throws IOException {
        writeConfiguration("[translation]\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TranslationEngineConfiguration.commandFor(exporterRoot, Map.of()));

        assertTrue(failure.getMessage().contains("publication-exporter.toml"));
        assertTrue(failure.getMessage().contains("engine"));
    }

    @Test
    void reportsADuplicateEngineInTheConfigurationFile() throws IOException {
        writeConfiguration("[translation]\nengine = \"codex\"\nengine = \"antigravity\"\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TranslationEngineConfiguration.commandFor(exporterRoot, Map.of()));

        assertTrue(failure.getMessage().contains("publication-exporter.toml"));
        assertTrue(failure.getMessage().contains("duplicate"));
    }

    @Test
    void reportsUnsupportedSyntaxInTheConfigurationFile() throws IOException {
        writeConfiguration("engine = \"codex\"\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TranslationEngineConfiguration.commandFor(exporterRoot, Map.of()));

        assertTrue(failure.getMessage().contains("publication-exporter.toml"));
        assertTrue(failure.getMessage().contains("[translation]"));
    }

    @Test
    void validEnvironmentSelectionDoesNotReadAnInvalidConfigurationFile() throws IOException {
        writeConfiguration("this is not TOML\n");

        TranslationCommand command = TranslationEngineConfiguration.commandFor(
                exporterRoot, Map.of("PUBLICATION_EXPORTER_TRANSLATION_ENGINE", "antigravity"));

        assertInstanceOf(AntigravityTranslationCommand.class, command);
    }

    private void writeConfiguration(String contents) throws IOException {
        Files.writeString(exporterRoot.resolve("publication-exporter.toml"), contents);
    }
}
