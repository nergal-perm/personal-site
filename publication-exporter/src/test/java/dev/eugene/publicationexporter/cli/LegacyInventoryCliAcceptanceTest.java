package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyInventoryCliAcceptanceTest {

    private static final PublicationIdentity IDENTITY =
            PublicationIdentity.of("blog", "essay", "legacy-essay");

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
    void approvedSnapshotWithoutRecordedSourceIdPrintsNamedBlocker() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());

        int exitCode = new CommandLine(new Main()).execute(
                "legacy-inventory", "--review", reviewDirectory.toString());

        assertEquals(0, exitCode);
        JsonNode inventory = new ObjectMapper().readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(inventory.get("blockers").isEmpty());
        assertTrue(inventory.get("blockers").get(0).asText().contains(IDENTITY.toString()));
    }

    private static CandidateSnapshot snapshotWithoutSourceId() {
        String body = "Legacy body";
        String fields = PublicFieldsCodec.write(List.of());
        ReferenceMap referenceMap = ReferenceMap.empty(
                IDENTITY,
                ContentHash.sha256Hex(body), ContentHash.sha256Hex(body),
                ContentHash.sha256Hex(fields), ContentHash.sha256Hex(fields),
                ContentHash.sha256Hex(""));
        return CandidateSnapshot.of(body, body, List.of(), List.of(), "", referenceMap);
    }
}
