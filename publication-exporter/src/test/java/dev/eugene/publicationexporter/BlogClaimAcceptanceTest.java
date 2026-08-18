package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.FilesystemManagedSiteInstaller;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogClaimAcceptanceTest {

    private static final String VALID_CLAIM = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: claim
            publicId: latency-budget-is-fiction
            id: 91aa-latency-claim
            title: A fixed latency budget is fiction
            description: Why "p99 < 100ms" is usually the wrong abstraction.
            statement: A fixed "p99 < 100ms" latency budget is usually the wrong abstraction.
            supports:
              - label: "Queueing theory: tail latency compounds across hops"
                target: measuring-tail-latency
            sources:
              - link:
                  label: Queueing theory
                  target: measuring-tail-latency
                evidence:
                  - kind: text
                    value: Tail latency compounds.
                  - kind: reference
                    target: measuring-tail-latency
                locator:
                  - kind: text
                    value: Section 3
            ---
            Body prose discussing the claim.""";

    @TempDir
    Path siteRoot;

    @Test
    void blogClaimCompletesAdmissionThroughSiteInstallation() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("blog/latency-budget-is-fiction.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_CLAIM));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "Translated body",
                List.of(
                        PublicField.of("title", "A fixed latency budget is fiction"),
                        PublicField.of("description", "Why a fixed p99 budget is the wrong abstraction."),
                        PublicField.of("statement", "A fixed latency budget is usually the wrong abstraction.")));
        PrepareHandlerFixture handlers = new PrepareHandlerFixture(
                path, noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse prepareResponse = handlers.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity =
                PublicationIdentity.of("blog", "claim", "latency-budget-is-fiction");
        assertEquals(identity, prepareResponse.identity());

        BridgeResponse approveResponse = handlers.markReviewed(path, vaultReader);

        assertTrue(approveResponse.ok(), approveResponse.diagnostics().toString());
        CandidateSnapshot approved = approvedSnapshotWorkspace.read(identity).orElseThrow();

        ReleaseResult releaseResult = handlers.buildFromReview(identity);

        assertTrue(releaseResult.ok(), releaseResult.message());

        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

        String installed = Files.readString(
                siteRoot.resolve("src/content/blog/ru/latency-budget-is-fiction.md"));
        assertTrue(installed.contains(
                "statement: \"A fixed \\\"p99 < 100ms\\\" latency budget is usually the wrong abstraction.\"\n"));
        assertTrue(installed.contains("""
                supports:
                  - label: "Queueing theory: tail latency compounds across hops"
                    target: "measuring-tail-latency"
                """));
        assertTrue(installed.contains("""
                sources:
                  - link:
                      label: Queueing theory
                      target: measuring-tail-latency
                    evidence:
                      - kind: text
                        value: Tail latency compounds.
                      - kind: reference
                        target: measuring-tail-latency
                    locator:
                      - kind: text
                        value: Section 3
                """));
    }

    private static final class PrepareHandlerFixture {

        private final PrepareHandler prepareHandler;
        private final MarkReviewedHandler markReviewedHandler;
        private final BuildFromReviewHandler buildFromReviewHandler;

        private PrepareHandlerFixture(
                VaultRelativePath path,
                NoteIntake noteIntake,
                TranslationWorker translationWorker,
                CandidateWorkspace candidateWorkspace,
                ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
            this.prepareHandler = new PrepareHandler(
                    noteIntake,
                    translationWorker,
                    candidateWorkspace,
                    approvedSnapshotWorkspace,
                    WorkflowStatusEditor.createNull());
            this.markReviewedHandler = new MarkReviewedHandler(
                    noteIntake,
                    candidateWorkspace,
                    approvedSnapshotWorkspace,
                    new NullWorkflowStatusEditor(Map.of(path, VALID_CLAIM)));
            this.buildFromReviewHandler = new BuildFromReviewHandler(
                    approvedSnapshotWorkspace,
                    ReleaseOutputStore.createNull(), ActivationMarkerTestFixtures.activatedMarkerStore());
        }

        private BridgeResponse prepare(
                VaultRelativePath path, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
            return prepareHandler.prepare(path, vaultReader, vaultAssetReader);
        }

        private BridgeResponse markReviewed(VaultRelativePath path, VaultReader vaultReader) {
            return markReviewedHandler.markReviewed(path, vaultReader);
        }

        private ReleaseResult buildFromReview(PublicationIdentity identity) {
            return buildFromReviewHandler.buildFromReview(identity);
        }

    }
}
