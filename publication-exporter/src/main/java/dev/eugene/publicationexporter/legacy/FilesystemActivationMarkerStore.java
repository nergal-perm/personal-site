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
            JsonNode root = MAPPER.readTree(Files.readString(markerFile, StandardCharsets.UTF_8));
            return markerFrom(root);
        } catch (IOException | RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<ActivationMarker> markerFrom(JsonNode root) {
        JsonNode schemaVersion = root.get("schemaVersion");
        JsonNode inventorySha256 = root.get("inventorySha256");
        JsonNode activatedAt = root.get("activatedAt");
        if (schemaVersion == null || inventorySha256 == null || activatedAt == null) {
            return Optional.empty();
        }
        return Optional.of(new ActivationMarker(
                schemaVersion.asInt(), inventorySha256.asText(), Instant.parse(activatedAt.asText())));
    }
}
