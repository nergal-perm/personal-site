package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateAsset;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.Occurrence;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class FilesystemMigrationJournalStore implements MigrationJournalStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path journalFile;
    private final Path root;

    FilesystemMigrationJournalStore(Path reviewRoot) {
        this.root = FilesystemMigrationPath.safeRoot(root(reviewRoot));
        this.journalFile = this.root.resolve(".migration/migration-journal.json");
    }

    @Override
    public void preflight() {
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, journalFile.getParent());
        FilesystemMigrationPath.requireRegularFileOrAbsent(root, journalFile);
    }

    @Override
    public boolean exists() {
        preflight();
        return Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Optional<MigrationGeneration> read() {
        return load().map(FilesystemMigrationJournalStore::manifestFrom).map(MigrationPreimage::generation);
    }

    @Override
    public Optional<MigrationPreimage> preimage() {
        return load().map(FilesystemMigrationJournalStore::manifestFrom);
    }

    @Override
    public void save(MigrationGeneration generation) {
        MigrationPreimage preimage = preimage().orElseThrow(() ->
                new MigrationJournalException("A preimage is required before saving a journal transition."));
        if (!preimage.belongsTo(generation)) throw new MigrationJournalException("Journal preimage does not belong to generation.");
        save(generation, preimage);
    }

    @Override
    public void save(MigrationGeneration generation, MigrationPreimage preimage) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(preimage, "preimage");
        if (!preimage.belongsTo(generation)) throw new MigrationJournalException("Journal preimage does not belong to generation.");
        var root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("inventorySha256", generation.inventorySha256());
        root.set("identities", MAPPER.valueToTree(generation.identities()));
        root.put("completedSteps", generation.completedSteps());
        root.put("state", generation.state().name());
        root.set("candidateSnapshots", snapshots(preimage.candidateSnapshots()));
        root.set("approvedSnapshots", snapshots(preimage.approvedSnapshots()));
        root.set("candidateAssets", assets(preimage.candidateAssets()));
        atomicWrite(root.toString());
    }

    private Optional<JsonNode> load() {
        FilesystemMigrationPath.requireSafe(root, journalFile);
        if (!Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readTree(Files.readString(journalFile, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException error) {
            throw new MigrationJournalException("Migration journal is unreadable.", error);
        }
    }

    private void atomicWrite(String json) {
        try {
            FilesystemMigrationPath.requireSafe(root, journalFile);
            Path parent = journalFile.getParent();
            FilesystemMigrationPath.requireSafe(root, parent);
            Files.createDirectories(parent);
            FilesystemMigrationPath.requireSafe(root, parent);
            Path temporary = journalFile.resolveSibling(journalFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                FilesystemMigrationPath.requireSafe(root, temporary);
                Files.writeString(temporary, json, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                FilesystemMigrationPath.requireSafe(root, journalFile);
                Files.move(temporary, journalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new MigrationJournalException("Cannot atomically write migration journal.", error); }
    }

    private static JsonNode snapshots(Map<PublicationIdentity, CandidateSnapshot> values) {
        var array = MAPPER.createArrayNode();
        values.forEach((identity, snapshot) -> array.add(snapshotEntry(identity, snapshot)));
        return array;
    }

    private static JsonNode snapshotEntry(PublicationIdentity identity, CandidateSnapshot snapshot) {
        var entry = MAPPER.createObjectNode();
        entry.set("identity", MAPPER.valueToTree(identity));
        entry.set("snapshot", snapshotNode(snapshot));
        return entry;
    }

    private static JsonNode snapshotNode(CandidateSnapshot snapshot) {
        var node = MAPPER.createObjectNode();
        node.put("ruBody", snapshot.ruBody());
        node.put("enBody", snapshot.enBody());
        node.set("ruFields", fieldsNode(snapshot.ruFields()));
        node.set("enFields", fieldsNode(snapshot.enFields()));
        node.put("structuredData", snapshot.structuredData());
        node.set("referenceMap", referenceMapNode(snapshot));
        return node;
    }

    private static JsonNode fieldsNode(List<dev.eugene.publicationexporter.reference.PublicField> fields) {
        return parse(PublicFieldsCodec.write(fields));
    }

    private static JsonNode referenceMapNode(CandidateSnapshot snapshot) {
        return parse(ReferenceMapCodec.write(snapshot.referenceMap()));
    }

    private static MigrationGeneration generationFrom(JsonNode root) {
        try {
            requireJournalFields(root);
            return readGeneration(root);
        } catch (RuntimeException error) { throw new MigrationJournalException("Migration journal generation is invalid.", error); }
    }

    private static void requireJournalFields(JsonNode journalNode) {
        requireObject(journalNode, "journal");
        requireFields(journalNode, Set.of("schemaVersion", "inventorySha256", "identities", "completedSteps", "state", "candidateSnapshots", "approvedSnapshots", "candidateAssets"));
        requireInteger(journalNode, "schemaVersion");
        if (journalNode.get("schemaVersion").intValue() != 1) throw new IllegalArgumentException("unsupported schema");
        requireText(journalNode, "inventorySha256");
        requireArray(journalNode, "identities");
        requireInteger(journalNode, "completedSteps");
        requireText(journalNode, "state");
        requireArray(journalNode, "candidateSnapshots");
        requireArray(journalNode, "approvedSnapshots");
        requireArray(journalNode, "candidateAssets");
    }

    private static MigrationGeneration readGeneration(JsonNode journalNode) {
        List<PublicationIdentity> identities = new ArrayList<>();
        journalNode.get("identities").forEach(identity -> identities.add(readIdentity(identity)));
        return new MigrationGeneration(journalNode.get("inventorySha256").textValue(), identities,
                journalNode.get("completedSteps").intValue(), MigrationState.valueOf(journalNode.get("state").textValue()));
    }

    private static MigrationPreimage preimageFrom(JsonNode root) {
        return new MigrationPreimage(generationFrom(root), snapshotsFrom(root.get("candidateSnapshots")),
                snapshotsFrom(root.get("approvedSnapshots")), assetsFrom(root.get("candidateAssets")));
    }

    private static MigrationPreimage manifestFrom(JsonNode root) {
        try {
            return preimageFrom(root);
        } catch (MigrationJournalException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new MigrationJournalException("Migration journal manifest is invalid.", error);
        }
    }

    private static JsonNode assets(Map<PublicationIdentity, List<CandidateAsset>> values) {
        var array = MAPPER.createArrayNode();
        values.forEach((identity, entries) -> array.add(assetEntry(identity, entries)));
        return array;
    }

    private static JsonNode assetEntry(PublicationIdentity identity, List<CandidateAsset> entries) {
        var item = MAPPER.createObjectNode();
        item.set("identity", MAPPER.valueToTree(identity));
        var files = MAPPER.createArrayNode();
        entries.forEach(asset -> files.add(assetNode(asset)));
        item.set("files", files);
        return item;
    }

    private static JsonNode assetNode(CandidateAsset asset) {
        var file = MAPPER.createObjectNode();
        file.put("publicName", asset.publicName());
        file.put("content", java.util.Base64.getEncoder().encodeToString(asset.content()));
        return file;
    }

    private static Map<PublicationIdentity, List<CandidateAsset>> assetsFrom(JsonNode array) {
        requireArrayNode(array, "candidateAssets");
        Map<PublicationIdentity, List<CandidateAsset>> values = new LinkedHashMap<>();
        array.forEach(item -> {
            AssetEntry asset = readAssetEntry(item);
            PublicationIdentity identity = asset.identity();
            if (values.containsKey(identity)) throw new MigrationJournalException("Duplicate asset identity.");
            values.put(identity, asset.assets());
        });
        return values;
    }

    private static AssetEntry readAssetEntry(JsonNode item) {
        requireObject(item, "candidate asset entry");
        requireFields(item, Set.of("identity", "files"));
        requireArrayNode(item.get("files"), "candidate asset files");
        List<CandidateAsset> files = new ArrayList<>();
        item.get("files").forEach(file -> files.add(assetFrom(file)));
        return new AssetEntry(readIdentity(item.get("identity")), files);
    }

    private static CandidateAsset assetFrom(JsonNode file) {
        requireObject(file, "candidate asset");
        requireFields(file, Set.of("publicName", "content"));
        requireText(file, "publicName");
        requireText(file, "content");
        try {
            return CandidateAsset.of(file.get("publicName").textValue(),
                    java.util.Base64.getDecoder().decode(file.get("content").textValue()));
        } catch (IllegalArgumentException error) {
            throw new MigrationJournalException("Candidate asset content is not valid base64.", error);
        }
    }

    private static Map<PublicationIdentity, CandidateSnapshot> snapshotsFrom(JsonNode array) {
        requireArrayNode(array, "snapshots");
        Map<PublicationIdentity, CandidateSnapshot> values = new LinkedHashMap<>();
        array.forEach(entry -> {
            SnapshotEntry snapshot = readSnapshotEntry(entry);
            PublicationIdentity identity = snapshot.identity();
            if (values.containsKey(identity)) throw new MigrationJournalException("Duplicate snapshot identity.");
            values.put(identity, snapshot.snapshot());
        });
        return values;
    }

    private static SnapshotEntry readSnapshotEntry(JsonNode entry) {
        requireObject(entry, "snapshot entry");
        requireFields(entry, Set.of("identity", "snapshot"));
        return new SnapshotEntry(readIdentity(entry.get("identity")), snapshotFrom(entry.get("snapshot")));
    }

    private static void requireFields(JsonNode root, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Unexpected or missing journal fields.");
    }

    private static CandidateSnapshot snapshotFrom(JsonNode node) {
        requireObject(node, "snapshot");
        requireFields(node, Set.of("ruBody", "enBody", "ruFields", "enFields", "structuredData", "referenceMap"));
        requireText(node, "ruBody");
        requireText(node, "enBody");
        requireText(node, "structuredData");
        return CandidateSnapshot.of(node.get("ruBody").textValue(), node.get("enBody").textValue(),
                publicFieldsFrom(node.get("ruFields")), publicFieldsFrom(node.get("enFields")),
                node.get("structuredData").textValue(), referenceMapFrom(node.get("referenceMap")));
    }

    private static List<PublicField> publicFieldsFrom(JsonNode node) {
        requireArrayNode(node, "public fields");
        List<PublicField> fields = new ArrayList<>();
        node.forEach(field -> {
            requireObject(field, "public field");
            requireFields(field, Set.of("key", "value"));
            requireText(field, "key");
            requireText(field, "value");
            fields.add(PublicField.of(field.get("key").textValue(), field.get("value").textValue()));
        });
        return List.copyOf(fields);
    }

    private static ReferenceMap referenceMapFrom(JsonNode node) {
        requireObject(node, "referenceMap");
        Set<String> required = Set.of("schemaVersion", "publicationIdentity", "ruHash", "enHash",
                "ruFieldsHash", "enFieldsHash", "structuredDataHash", "occurrences", "sourceBodyHash");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required) && !actual.equals(union(required, "sourceId"))) {
            throw new IllegalArgumentException("Unexpected or missing reference-map fields.");
        }
        requireInteger(node, "schemaVersion");
        if (node.get("schemaVersion").intValue() != 1) {
            throw new IllegalArgumentException("Unsupported reference-map schema.");
        }
        PublicationIdentity identity = readIdentity(node.get("publicationIdentity"));
        requireText(node, "ruHash");
        requireText(node, "enHash");
        requireText(node, "ruFieldsHash");
        requireText(node, "enFieldsHash");
        requireText(node, "structuredDataHash");
        requireText(node, "sourceBodyHash");
        List<Occurrence> occurrences = occurrencesFrom(node.get("occurrences"));
        if (node.has("sourceId")) {
            requireText(node, "sourceId");
            return ReferenceMap.of(identity, node.get("sourceId").textValue(), node.get("ruHash").textValue(),
                    node.get("enHash").textValue(), node.get("ruFieldsHash").textValue(),
                    node.get("enFieldsHash").textValue(), node.get("structuredDataHash").textValue(),
                    occurrences, node.get("sourceBodyHash").textValue());
        }
        if (!node.get("sourceBodyHash").textValue().isEmpty()) {
            throw new IllegalArgumentException("Reference map without sourceId cannot have sourceBodyHash.");
        }
        return ReferenceMap.of(identity, node.get("ruHash").textValue(), node.get("enHash").textValue(),
                node.get("ruFieldsHash").textValue(), node.get("enFieldsHash").textValue(),
                node.get("structuredDataHash").textValue(), occurrences);
    }

    private static List<Occurrence> occurrencesFrom(JsonNode node) {
        requireArrayNode(node, "reference-map occurrences");
        List<Occurrence> occurrences = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int expectedOrder = 0;
        for (JsonNode occurrence : node) {
            requireObject(occurrence, "reference-map occurrence");
            requireFields(occurrence, Set.of("id", "order", "targetSourceId", "ruLabel", "enLabel"));
            requireText(occurrence, "id");
            requireInteger(occurrence, "order");
            requireText(occurrence, "targetSourceId");
            requireText(occurrence, "ruLabel");
            requireText(occurrence, "enLabel");
            String id = occurrence.get("id").textValue();
            if (id.isBlank() || occurrence.get("targetSourceId").textValue().isBlank()
                    || occurrence.get("ruLabel").textValue().isBlank()
                    || occurrence.get("enLabel").textValue().isBlank()) {
                throw new IllegalArgumentException("Occurrence text fields must be non-blank.");
            }
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate occurrence id.");
            int order = occurrence.get("order").intValue();
            if (order != expectedOrder) throw new IllegalArgumentException("Occurrence order does not match position.");
            occurrences.add(new Occurrence(id, order, occurrence.get("targetSourceId").textValue(),
                    occurrence.get("ruLabel").textValue(), occurrence.get("enLabel").textValue()));
            expectedOrder++;
        }
        return List.copyOf(occurrences);
    }

    private static Set<String> union(Set<String> values, String extra) {
        Set<String> result = new HashSet<>(values);
        result.add(extra);
        return result;
    }

    private static PublicationIdentity readIdentity(JsonNode node) {
        requireObject(node, "identity");
        requireFields(node, Set.of("publicCollection", "publicContentType", "publicId"));
        requireText(node, "publicCollection");
        requireText(node, "publicContentType");
        requireText(node, "publicId");
        return PublicationIdentity.of(node.get("publicCollection").textValue(),
                node.get("publicContentType").textValue(), node.get("publicId").textValue());
    }

    private static void requireObject(JsonNode node, String role) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(role + " must be an object");
    }

    private static void requireArray(JsonNode root, String field) {
        requireArrayNode(root.get(field), field);
    }

    private static void requireArrayNode(JsonNode node, String role) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException(role + " must be an array");
    }

    private static void requireInteger(JsonNode root, String field) {
        if (root.get(field) == null || !root.get(field).isInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static void requireText(JsonNode root, String field) {
        if (root.get(field) == null || !root.get(field).isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); } catch (IOException error) { throw new MigrationJournalException("Snapshot JSON is invalid.", error); }
    }

    private static Path root(Path reviewRoot) { return Objects.requireNonNull(reviewRoot, "reviewRoot").toAbsolutePath().normalize(); }

    private record SnapshotEntry(PublicationIdentity identity, CandidateSnapshot snapshot) { }

    private record AssetEntry(PublicationIdentity identity, List<CandidateAsset> assets) { }
}
