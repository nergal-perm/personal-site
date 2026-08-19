package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemActivationMarkerStoreTest {

    @Test
    void readIsAbsentWhenNoMarkerFileExists(@TempDir Path reviewRoot) {
        ActivationMarkerStore store = ActivationMarkerStore.create(reviewRoot);

        assertEquals(Optional.empty(), store.read());
    }

    @Test
    void readRejectsSymlinkedMarkerDirectory(@TempDir Path reviewRoot) throws IOException {
        Path outside = reviewRoot.resolveSibling("marker-outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(reviewRoot.resolve(".migration"), outside);

        assertThrows(MigrationRecoveryException.class,
                () -> ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readParsesAValidMarkerFile(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":1,"inventorySha256":"%s","activatedAt":"2026-08-18T00:00:00Z"}
                """.formatted("a".repeat(64)));

        Optional<ActivationMarker> marker = ActivationMarkerStore.create(reviewRoot).read();

        assertTrue(marker.isPresent());
        assertTrue(marker.get().isValid());
    }

    @Test
    void readIsAbsentForMalformedJson(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, "not json");
        ActivationMarkerStore store = ActivationMarkerStore.create(reviewRoot);

        assertEquals(Optional.empty(), store.read());
        assertEquals(ActivationMarkerStore.Status.INVALID_PRESENT, store.inspect().status());
    }

    @Test
    void readIsAbsentForUnparseableActivatedAt(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":1,"inventorySha256":"%s","activatedAt":"not-a-date"}
                """.formatted("a".repeat(64)));

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readIsAbsentWhenRequiredFieldsAreMissing(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, "{\"schemaVersion\":1}");

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readIsAbsentWhenSchemaVersionIsNotAnIntegerNode(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":"1","inventorySha256":"%s","activatedAt":"2026-08-18T00:00:00Z"}
                """.formatted("a".repeat(64)));

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readIsAbsentWhenInventoryHashIsNotATextNode(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":1,"inventorySha256":1,"activatedAt":"2026-08-18T00:00:00Z"}
                """);

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readIsAbsentWhenActivationTimeIsNotATextNode(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":1,"inventorySha256":"%s","activatedAt":0}
                """.formatted("a".repeat(64)));

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    private static void writeMarker(Path reviewRoot, String json) throws IOException {
        Path markerFile = reviewRoot.resolve(".migration").resolve("schema-v1.active.json");
        Files.createDirectories(markerFile.getParent());
        Files.writeString(markerFile, json, StandardCharsets.UTF_8);
    }
}
