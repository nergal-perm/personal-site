package dev.eugene.publicationexporter.cli;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationArtifactPresenceTest {

    @TempDir
    Path reviewDirectory;

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void prepareFailsClosedForMarkerOnlyMigrationState() throws Exception {
        markerOnly();
        Path vault = Files.createDirectories(reviewDirectory.resolveSibling("vault"));
        PrepareCommand command = new PrepareCommand(TranslationWorker.createNull("translated", List.of()));
        command.vaultRoot = vault;
        command.notePath = "blog/missing.md";
        command.reviewDirectory = reviewDirectory;
        command.jobsDirectory = reviewDirectory.resolveSibling("jobs");

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    @Test
    void approvalFailsClosedForMarkerOnlyMigrationState() throws Exception {
        markerOnly();
        Path vault = Files.createDirectories(reviewDirectory.resolveSibling("approval-vault"));
        MarkReviewedCommand command = new MarkReviewedCommand();
        command.vaultRoot = vault;
        command.notePath = "blog/missing.md";
        command.reviewDirectory = reviewDirectory;
        command.jobsDirectory = reviewDirectory.resolveSibling("approval-jobs");

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    @Test
    void releaseFailsClosedForMarkerOnlyMigrationState() throws Exception {
        markerOnly();
        BuildFromReviewCommand command = new BuildFromReviewCommand();
        command.reviewDirectory = reviewDirectory;
        command.outputRoot = reviewDirectory.resolveSibling("release-output");
        command.collection = "blog";
        command.contentType = "essay";
        command.publicId = "missing";

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    @Test
    void releaseFailsClosedForMalformedMarkerWithCurrentSchemaApproval() throws Exception {
        malformedMarker();
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(identity(), currentSnapshot());
        BuildFromReviewCommand command = new BuildFromReviewCommand();
        command.reviewDirectory = reviewDirectory;
        command.outputRoot = reviewDirectory.resolveSibling("malformed-marker-release-output");
        command.collection = "blog";
        command.contentType = "essay";
        command.publicId = "current";

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    @Test
    void prepareFailsClosedForMalformedMarkerWithCurrentSchemaCandidate() throws Exception {
        malformedMarker();
        CandidateWorkspace.create(reviewDirectory).install(identity(), currentSnapshot(), List.of());
        Path vault = Files.createDirectories(reviewDirectory.resolveSibling("malformed-prepare-vault"));
        PrepareCommand command = new PrepareCommand(TranslationWorker.createNull("translated", List.of()));
        command.vaultRoot = vault;
        command.notePath = "blog/missing.md";
        command.reviewDirectory = reviewDirectory;
        command.jobsDirectory = reviewDirectory.resolveSibling("malformed-prepare-jobs");

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    @Test
    void approvalFailsClosedForMalformedMarkerWithCurrentSchemaCandidate() throws Exception {
        malformedMarker();
        CandidateWorkspace.create(reviewDirectory).install(identity(), currentSnapshot(), List.of());
        Path vault = Files.createDirectories(reviewDirectory.resolveSibling("malformed-approval-vault"));
        MarkReviewedCommand command = new MarkReviewedCommand();
        command.vaultRoot = vault;
        command.notePath = "blog/missing.md";
        command.reviewDirectory = reviewDirectory;
        command.jobsDirectory = reviewDirectory.resolveSibling("malformed-approval-jobs");

        assertEquals(1, command.call());
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("roll forward"));
    }

    private void markerOnly() {
        ActivationMarkerStore.create(reviewDirectory).save(new ActivationMarker(
                1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z")));
    }

    private void malformedMarker() throws Exception {
        Path marker = reviewDirectory.resolve(".migration/schema-v1.active.json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "{\"unexpected\":true}", StandardCharsets.UTF_8);
    }

    private static PublicationIdentity identity() {
        return PublicationIdentity.of("blog", "essay", "current");
    }

    private static CandidateSnapshot currentSnapshot() {
        String ru = "current ru";
        String en = "current en";
        ReferenceMap references = ReferenceMap.of(identity(), "source-current",
                ContentHash.sha256Hex(ru), ContentHash.sha256Hex(en),
                ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())),
                ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())),
                ContentHash.sha256Hex(""), List.of(), ContentHash.sha256Hex("source body"));
        return CandidateSnapshot.of(ru, en, List.of(), List.of(), "", references);
    }
}
