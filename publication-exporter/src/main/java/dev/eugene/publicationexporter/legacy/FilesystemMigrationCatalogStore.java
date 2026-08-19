package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemMigrationCatalogStore implements MigrationCatalogStore {
    private final Path catalogFile;
    private final Path root;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    FilesystemMigrationCatalogStore(Path reviewRoot) {
        this.root = FilesystemMigrationPath.safeRoot(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        catalogFile = this.root.resolve(".migration/migration-catalog.json");
    }

    @Override public void preflight() {
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, catalogFile.getParent());
        FilesystemMigrationPath.requireRegularFileOrAbsent(root, catalogFile);
    }

    @Override public boolean exists() {
        preflight();
        return Files.exists(catalogFile, LinkOption.NOFOLLOW_LINKS);
    }

    @Override public Optional<MigrationGeneration> read() {
        FilesystemMigrationPath.requireSafe(root, catalogFile);
        if (!Files.exists(catalogFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            FilesystemMigrationPath.requireSafe(root, catalogFile);
            return Optional.of(parseCatalog(MAPPER.readTree(Files.readString(catalogFile, StandardCharsets.UTF_8))));
        } catch (Exception error) { throw new MigrationJournalException("Migration catalog is unreadable.", error); }
    }

    @Override public void save(MigrationGeneration generation) {
        try {
            FilesystemMigrationPath.requireSafe(root, catalogFile);
            Path parent = catalogFile.getParent();
            FilesystemMigrationPath.requireSafe(root, parent);
            Files.createDirectories(parent);
            FilesystemMigrationPath.requireSafe(root, parent);
            var node = MAPPER.createObjectNode();
            node.put("schemaVersion", 1);
            node.put("inventorySha256", generation.inventorySha256()); node.set("identities", MAPPER.valueToTree(generation.identities()));
            node.put("completedSteps", generation.completedSteps()); node.put("state", generation.state().name());
            Path temporary = catalogFile.resolveSibling(catalogFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                FilesystemMigrationPath.requireSafe(root, temporary);
                Files.writeString(temporary, node.toString(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FilesystemMigrationPath.requireSafe(root, catalogFile);
                Files.move(temporary, catalogFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception error) { throw new MigrationJournalException("Cannot write migration catalog.", error); }
    }

    private static MigrationGeneration parseCatalog(com.fasterxml.jackson.databind.JsonNode node) {
        requireObject(node);
        requireFields(node, java.util.Set.of("schemaVersion", "inventorySha256", "identities", "completedSteps", "state"));
        requireInteger(node, "schemaVersion");
        if (node.get("schemaVersion").intValue() != 1) throw new IllegalArgumentException("Unsupported catalog schema.");
        requireText(node, "inventorySha256");
        requireArray(node, "identities");
        requireInteger(node, "completedSteps");
        requireText(node, "state");
        List<PublicationIdentity> identities = new ArrayList<>();
        node.get("identities").forEach(identity -> identities.add(parseIdentity(identity)));
        return new MigrationGeneration(node.get("inventorySha256").textValue(), identities,
                node.get("completedSteps").intValue(), MigrationState.valueOf(node.get("state").textValue()));
    }

    private static PublicationIdentity parseIdentity(com.fasterxml.jackson.databind.JsonNode node) {
        requireObject(node);
        requireFields(node, java.util.Set.of("publicCollection", "publicContentType", "publicId"));
        requireText(node, "publicCollection"); requireText(node, "publicContentType"); requireText(node, "publicId");
        return PublicationIdentity.of(node.get("publicCollection").textValue(), node.get("publicContentType").textValue(), node.get("publicId").textValue());
    }

    private static void requireObject(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Catalog must be an object.");
    }

    private static void requireFields(com.fasterxml.jackson.databind.JsonNode node, java.util.Set<String> expected) {
        java.util.Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Unexpected or missing catalog fields.");
    }

    private static void requireArray(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.get(field) == null || !node.get(field).isArray()) throw new IllegalArgumentException(field + " must be an array.");
    }

    private static void requireInteger(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.get(field) == null || !node.get(field).isInt()) throw new IllegalArgumentException(field + " must be an integer.");
    }

    private static void requireText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.get(field) == null || !node.get(field).isTextual()) throw new IllegalArgumentException(field + " must be text.");
    }
}
