package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.MigrationCatalogStore;
import dev.eugene.publicationexporter.legacy.MigrationGeneration;
import dev.eugene.publicationexporter.legacy.MigrationJournalStore;
import dev.eugene.publicationexporter.legacy.MigrationPreimage;
import dev.eugene.publicationexporter.legacy.MigrationState;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAwareSemanticAdmissionTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "blocked");

    @Test
    void prepareBlocksWhenMarkerIsValidButJournalIsUnsealed() {
        PrepareHandler handler = new PrepareHandler(new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("translated", List.of()), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull(), marker(),
                incompleteJournal(), MigrationCatalogStore.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("blog/blocked.md"),
                VaultReader.createNull(Map.of()), VaultAssetReader.createNull());

        assertFalse(response.ok());
    }

    @Test
    void approvalBlocksWhenMarkerIsValidButJournalIsUnsealed() {
        MarkReviewedHandler handler = new MarkReviewedHandler(new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull(), marker(), incompleteJournal(), MigrationCatalogStore.createNull());

        BridgeResponse response = handler.markReviewed(VaultRelativePath.of("blog/blocked.md"),
                VaultReader.createNull(Map.of()));

        assertFalse(response.ok());
    }

    @Test
    void releaseBlocksWhenMarkerIsValidButJournalIsUnsealed() {
        BuildFromReviewHandler handler = new BuildFromReviewHandler(ApprovedSnapshotWorkspace.createNull(),
                CandidateWorkspace.createNull(), ReleaseOutputStore.createNull(), marker(),
                incompleteJournal(), MigrationCatalogStore.createNull());

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertFalse(result.ok());
    }

    @Test
    void reapprovalOfMigratedIdentityKeepsSubsequentReleaseAdmissible() {
        VaultRelativePath path = VaultRelativePath.of("blog/blocked.md");
        String source = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: blocked
                id: source-blocked
                title: Current title
                description: Current description.
                ---
                # Current body""";
        VaultReader vault = VaultReader.createNull(Map.of(path, source));
        CandidateWorkspace candidates = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        CandidateSnapshot migrated = migratedApprovedSnapshot();
        approved.install(IDENTITY, migrated);
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        MigrationJournalStore journal = MigrationJournalStore.createNull();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                Map.of(), Map.of(IDENTITY, migrated)));
        MigrationCatalogStore catalog = MigrationCatalogStore.createNull();
        catalog.save(sealed);
        NoteIntake intake = new NoteIntake(PublicationKinds.installed());

        BridgeResponse prepared = new PrepareHandler(intake,
                TranslationWorker.createNull("# Current body in English", List.of(
                        PublicField.of("title", "Current title in English"),
                        PublicField.of("description", "Current description in English."))),
                candidates, approved, WorkflowStatusEditor.createNull(), marker(), journal, catalog)
                .prepare(path, vault, VaultAssetReader.createNull());
        BridgeResponse reapproved = new MarkReviewedHandler(intake, candidates, approved,
                new NullWorkflowStatusEditor(Map.of(path, source)), marker(), journal, catalog)
                .markReviewed(path, vault);
        ReleaseResult released = new BuildFromReviewHandler(approved, candidates,
                ReleaseOutputStore.createNull(), marker(), journal, catalog).buildFromReview(IDENTITY);

        assertTrue(prepared.ok(), prepared.toString());
        assertTrue(reapproved.ok(), reapproved.toString());
        assertTrue(approved.read(IDENTITY).orElseThrow().referenceMap().sourceBodyHash()
                .matches("[0-9a-f]{64}"));
        assertTrue(released.ok(), released.toString());
    }

    private static ActivationMarkerStore marker() {
        return ActivationMarkerStore.createNull(new ActivationMarker(1, "a".repeat(64),
                Instant.parse("2026-08-18T00:00:00Z")));
    }

    private static MigrationJournalStore incompleteJournal() {
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(), 0, MigrationState.RUNNING);
        MigrationJournalStore journal = MigrationJournalStore.createNull();
        journal.save(generation, new MigrationPreimage(generation, Map.of(), Map.of()));
        return journal;
    }

    private static CandidateSnapshot migratedApprovedSnapshot() {
        String ru = "# Migrated body";
        String en = "# Migrated body in English";
        List<PublicField> ruFields = List.of(
                PublicField.of("title", "Migrated title"),
                PublicField.of("description", "Migrated description."));
        List<PublicField> enFields = List.of(
                PublicField.of("title", "Migrated title in English"),
                PublicField.of("description", "Migrated description in English."));
        ReferenceMap map = ReferenceMap.of(IDENTITY, "source-blocked",
                ContentHash.sha256Hex(ru), ContentHash.sha256Hex(en),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(""), List.of());
        return CandidateSnapshot.of(ru, en, ruFields, enFields, "", map);
    }
}
