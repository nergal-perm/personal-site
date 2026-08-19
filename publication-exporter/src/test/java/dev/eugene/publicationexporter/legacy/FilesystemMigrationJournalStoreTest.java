package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FilesystemMigrationJournalStoreTest {

    @TempDir
    Path reviewRoot;

    @Test
    void savesAndReadsRunningGenerationWithPreimage() {
        PublicationIdentity identity = identity("one");
        MigrationGeneration generation = generation(List.of(identity));
        CandidateSnapshot snapshot = snapshot(identity);
        MigrationPreimage preimage = new MigrationPreimage(
                generation, Map.of(identity, snapshot), Map.of());

        MigrationJournalStore store = MigrationJournalStore.create(reviewRoot);
        store.save(generation, preimage);

        assertEquals(generation, store.read().orElseThrow());
        assertEquals(preimage, store.preimage().orElseThrow());
    }

    @Test
    void rejectsDuplicateJsonFields() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest,
                "{\"schemaVersion\":1,\"schemaVersion\":1}", StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class,
                () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\"schemaVersion\":1,\"unexpected\":true}", StandardCharsets.UTF_8);
        assertThrows(MigrationJournalException.class, () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void rejectsTrailingJsonTokens() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{} {}", StandardCharsets.UTF_8);
        assertThrows(MigrationJournalException.class, () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void rejectsWrongIdentityNodeShape() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, journalWith("{}", "0"), StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class,
                () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void rejectsCoercedCompletedSteps() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, journalWith("[]", "\"0\""), StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class,
                () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void readRejectsMalformedNestedSnapshotManifest() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        Files.createDirectories(manifest.getParent());
        String identity = "{\"publicCollection\":\"notes\",\"publicContentType\":\"article\",\"publicId\":\"one\"}";
        Files.writeString(manifest,
                journalWith("[" + identity + "]", "0").replace(
                        "\"candidateSnapshots\":[]", "\"candidateSnapshots\":[false]"),
                StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class,
                () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void readRejectsJournaledSnapshotWhoseHashDoesNotMatchContent() throws Exception {
        PublicationIdentity identity = identity("one");
        MigrationGeneration generation = generation(List.of(identity));
        MigrationJournalStore store = MigrationJournalStore.create(reviewRoot);
        store.save(generation, new MigrationPreimage(generation, Map.of(identity, snapshot(identity)), Map.of()));
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        String corrupted = Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"ruBody\":\"ru\"", "\"ruBody\":\"tampered\"");
        Files.writeString(manifest, corrupted, StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsUnsupportedNestedReferenceMapSchemaVersion() throws Exception {
        PublicationIdentity identity = identity("one");
        MigrationGeneration generation = generation(List.of(identity));
        MigrationJournalStore store = MigrationJournalStore.create(reviewRoot);
        store.save(generation, new MigrationPreimage(generation, Map.of(identity, snapshot(identity)), Map.of()));
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        String foreignSchema = Files.readString(manifest, StandardCharsets.UTF_8).replace(
                "\"referenceMap\":{\"schemaVersion\":1",
                "\"referenceMap\":{\"schemaVersion\":2");
        assertTrue(foreignSchema.contains("\"referenceMap\":{\"schemaVersion\":2"));
        Files.writeString(manifest, foreignSchema, StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsMissingNestedReferenceMapSchemaVersion() throws Exception {
        MigrationJournalStore store = savedJournal();
        mutateJournal("\"referenceMap\":{\"schemaVersion\":1,", "\"referenceMap\":{");

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsUnknownNestedReferenceMapField() throws Exception {
        MigrationJournalStore store = savedJournal();
        mutateJournal("\"referenceMap\":{", "\"referenceMap\":{\"foreign\":true,");

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsNonTextPublicFieldMember() throws Exception {
        MigrationJournalStore store = savedJournal();
        mutateJournal("\"ruFields\":[]", "\"ruFields\":[{\"key\":false,\"value\":\"title\"}]");

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsExtraPublicFieldMember() throws Exception {
        MigrationJournalStore store = savedJournal();
        mutateJournal("\"ruFields\":[]",
                "\"ruFields\":[{\"key\":\"title\",\"value\":\"Title\",\"foreign\":true}]");

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void readRejectsExtraOccurrenceMember() throws Exception {
        MigrationJournalStore store = savedJournal();
        mutateJournal("\"occurrences\":[]",
                "\"occurrences\":[{\"id\":\"link-0\",\"order\":0,\"targetSourceId\":\"target\","
                        + "\"ruLabel\":\"RU\",\"enLabel\":\"EN\",\"foreign\":true}]");

        assertThrows(MigrationJournalException.class, store::read);
    }

    @Test
    void rejectsMalformedCatalogNodeShapes() throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-catalog.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest,
                "{\"schemaVersion\":1,\"inventorySha256\":\"%s\",\"identities\":{},\"completedSteps\":0,\"state\":\"SEALED\"}"
                        .formatted("a".repeat(64)), StandardCharsets.UTF_8);

        assertThrows(MigrationJournalException.class,
                () -> MigrationCatalogStore.create(reviewRoot).read());
    }

    @Test
    void rejectsSymlinkedMigrationDirectory() throws Exception {
        Path outside = reviewRoot.resolveSibling("migration-outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(reviewRoot.resolve(".migration"), outside);
        assertThrows(MigrationRecoveryException.class, () -> MigrationJournalStore.create(reviewRoot).read());
    }

    @Test
    void catalogRejectsSymlinkedMigrationDirectory() throws Exception {
        Path outside = reviewRoot.resolveSibling("catalog-outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(reviewRoot.resolve(".migration"), outside);
        assertThrows(MigrationRecoveryException.class, () -> MigrationCatalogStore.create(reviewRoot).read());
    }

    private static MigrationGeneration generation(List<PublicationIdentity> identities) {
        return new MigrationGeneration("a".repeat(64), identities, 0, MigrationState.RUNNING);
    }

    private static PublicationIdentity identity(String id) {
        return PublicationIdentity.of("notes", "article", id);
    }

    private static CandidateSnapshot snapshot(PublicationIdentity identity) {
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "{}",
                ReferenceMap.empty(identity, ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())), ContentHash.sha256Hex("{}")));
    }

    private MigrationJournalStore savedJournal() {
        PublicationIdentity identity = identity("one");
        MigrationGeneration generation = generation(List.of(identity));
        MigrationJournalStore store = MigrationJournalStore.create(reviewRoot);
        store.save(generation, new MigrationPreimage(generation, Map.of(identity, snapshot(identity)), Map.of()));
        return store;
    }

    private void mutateJournal(String original, String replacement) throws Exception {
        Path manifest = reviewRoot.resolve(".migration/migration-journal.json");
        String current = Files.readString(manifest, StandardCharsets.UTF_8);
        String mutated = current.replace(original, replacement);
        assertTrue(mutated.contains(replacement));
        Files.writeString(manifest, mutated, StandardCharsets.UTF_8);
    }

    private static String journalWith(String identities, String completedSteps) {
        return "{\"schemaVersion\":1,\"inventorySha256\":\"%s\",\"identities\":%s,\"completedSteps\":%s,"
                .formatted("a".repeat(64), identities, completedSteps)
                + "\"state\":\"RUNNING\",\"candidateSnapshots\":[],\"approvedSnapshots\":[],\"candidateAssets\":[]}";
    }
}
