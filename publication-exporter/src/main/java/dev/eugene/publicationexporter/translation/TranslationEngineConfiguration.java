package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TranslationEngineConfiguration {

    private static final String ENVIRONMENT_VARIABLE = "PUBLICATION_EXPORTER_TRANSLATION_ENGINE";
    private static final String FILE_NAME = "publication-exporter.toml";
    private static final Pattern ENGINE_ASSIGNMENT = Pattern.compile("engine\\s*=\\s*\\\"([^\\\"]*)\\\"");

    private TranslationEngineConfiguration() {
    }

    public static TranslationCommand commandFor(Path exporterRoot, Map<String, String> environment) {
        Objects.requireNonNull(exporterRoot, "exporterRoot");
        Objects.requireNonNull(environment, "environment");

        Optional<String> environmentEngine = nonBlank(environment.get(ENVIRONMENT_VARIABLE));
        if (environmentEngine.isPresent()) {
            return commandForEngine(environmentEngine.get(), ENVIRONMENT_VARIABLE);
        }

        Path configurationFile = exporterRoot.resolve(FILE_NAME);
        if (!Files.exists(configurationFile)) {
            return new CodexTranslationCommand();
        }
        return commandForEngine(parseConfiguration(configurationFile), configurationFile.toString());
    }

    private static Optional<String> nonBlank(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static TranslationCommand commandForEngine(String engine, String source) {
        return switch (engine) {
            case "codex" -> new CodexTranslationCommand();
            case "antigravity" -> new AntigravityTranslationCommand();
            default -> throw new IllegalArgumentException(
                    "Unsupported translation engine '" + engine + "' from " + source
                            + "; expected 'codex' or 'antigravity'.");
        };
    }

    private static String parseConfiguration(Path configurationFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(configurationFile);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot read translation engine configuration " + configurationFile + ".", failure);
        }

        boolean translationSectionSeen = false;
        String engine = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if ("[translation]".equals(trimmed)) {
                if (translationSectionSeen) {
                    throw malformed(configurationFile, "duplicate [translation] section");
                }
                translationSectionSeen = true;
                continue;
            }
            if (!translationSectionSeen) {
                throw malformed(configurationFile, "expected [translation] before settings");
            }
            Matcher assignment = ENGINE_ASSIGNMENT.matcher(trimmed);
            if (!assignment.matches()) {
                throw malformed(configurationFile, "expected engine = \"codex\" or engine = \"antigravity\"");
            }
            if (engine != null) {
                throw malformed(configurationFile, "duplicate engine setting");
            }
            engine = assignment.group(1);
        }
        if (!translationSectionSeen) {
            throw malformed(configurationFile, "missing [translation] section");
        }
        if (engine == null) {
            throw malformed(configurationFile, "missing engine setting");
        }
        return engine;
    }

    private static IllegalArgumentException malformed(Path configurationFile, String detail) {
        return new IllegalArgumentException(
                "Invalid translation engine configuration " + configurationFile + ": " + detail + ".");
    }
}
