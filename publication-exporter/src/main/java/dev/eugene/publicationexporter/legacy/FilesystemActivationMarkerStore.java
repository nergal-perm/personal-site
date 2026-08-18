package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class FilesystemActivationMarkerStore implements ActivationMarkerStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Path markerFile;

    FilesystemActivationMarkerStore(Path reviewRoot) {
        this.markerFile = Objects.requireNonNull(reviewRoot, "reviewRoot")
                .resolve(".migration").resolve("schema-v1.active.json");
    }

    @Override
    public Optional<ActivationMarker> read() {
        if (!Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            String markerJson = loadMarkerJson();
            return parseMarker(markerJson);
        } catch (IOException | RuntimeException invalidMarker) {
            return Optional.empty();
        }
    }

    private String loadMarkerJson() throws IOException {
        return Files.readString(markerFile, StandardCharsets.UTF_8);
    }

    private static Optional<ActivationMarker> parseMarker(String markerJson) throws IOException {
        return activationMarkerFrom(MAPPER.readTree(markerJson));
    }

    private static Optional<ActivationMarker> activationMarkerFrom(JsonNode root) {
        JsonNode schemaVersion = root.get("schemaVersion");
        JsonNode inventorySha256 = root.get("inventorySha256");
        JsonNode activatedAt = root.get("activatedAt");
        if (schemaVersion == null || !schemaVersion.isInt()
                || inventorySha256 == null || !inventorySha256.isTextual()
                || activatedAt == null || !activatedAt.isTextual()) {
            return Optional.empty();
        }
        return Optional.of(new ActivationMarker(
                schemaVersion.asInt(), inventorySha256.asText(), Instant.parse(activatedAt.asText())));
    }
}
