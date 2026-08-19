package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FilesystemActivationMarkerStore implements ActivationMarkerStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Path markerFile;
    private final Path root;

    FilesystemActivationMarkerStore(Path reviewRoot) {
        this.root = FilesystemMigrationPath.safeRoot(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        this.markerFile = root.resolve(".migration").resolve("schema-v1.active.json");
    }

    @Override
    public void preflight() {
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, markerFile.getParent());
        FilesystemMigrationPath.requireRegularFileOrAbsent(root, markerFile);
    }

    @Override
    public boolean exists() {
        preflight();
        return Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Optional<ActivationMarker> read() {
        return inspect().marker();
    }

    @Override
    public Inspection inspect() {
        FilesystemMigrationPath.requireSafe(root, markerFile);
        if (!Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)) {
            return Inspection.absent();
        }
        try {
            FilesystemMigrationPath.requireSafe(root, markerFile);
            String markerJson = loadMarkerJson();
            return parseMarker(markerJson).map(Inspection::parsed).orElseGet(Inspection::invalidPresent);
        } catch (MigrationRecoveryException unsafePath) {
            throw unsafePath;
        } catch (IOException | RuntimeException invalidMarker) {
            return Inspection.invalidPresent();
        }
    }

    @Override
    public void save(ActivationMarker marker) {
        Objects.requireNonNull(marker, "marker");
        if (!marker.isValid()) throw new IllegalArgumentException("Activation marker is invalid.");
        try {
            FilesystemMigrationPath.requireSafe(root, markerFile);
            Path parent = markerFile.getParent();
            FilesystemMigrationPath.requireSafe(root, parent);
            Files.createDirectories(parent);
            FilesystemMigrationPath.requireSafe(root, parent);
            var node = MAPPER.createObjectNode();
            node.put("schemaVersion", marker.schemaVersion());
            node.put("inventorySha256", marker.inventorySha256());
            node.put("activatedAt", marker.activatedAt().toString());
            Path temporary = markerFile.resolveSibling(markerFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                FilesystemMigrationPath.requireSafe(root, temporary);
                Files.writeString(temporary, node.toString(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FilesystemMigrationPath.requireSafe(root, markerFile);
                Files.move(temporary, markerFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    @Override
    public void clear() {
        try {
            FilesystemMigrationPath.requireSafe(root, markerFile);
            Files.deleteIfExists(markerFile);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private String loadMarkerJson() throws IOException {
        return Files.readString(markerFile, StandardCharsets.UTF_8);
    }

    private static Optional<ActivationMarker> parseMarker(String markerJson) throws IOException {
            return activationMarkerFrom(MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(markerJson));
    }

    private static Optional<ActivationMarker> activationMarkerFrom(JsonNode root) {
        java.util.Set<String> fields = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(java.util.Set.of("schemaVersion", "inventorySha256", "activatedAt"))) return Optional.empty();
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
