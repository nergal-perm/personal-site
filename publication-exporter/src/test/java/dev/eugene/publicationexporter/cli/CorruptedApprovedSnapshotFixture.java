package dev.eugene.publicationexporter.cli;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class CorruptedApprovedSnapshotFixture {

    private CorruptedApprovedSnapshotFixture() {
    }

    static Path write(Path reviewRoot, PublicationIdentity identity) throws IOException {
        Path approved = reviewRoot.resolve(identity.publicCollection())
                .resolve(identity.publicId()).resolve("approved");
        Files.createDirectories(approved);
        Files.writeString(approved.resolve("ru.md"), "RU", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("en.md"), "EN", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("ru.title"), "RU title", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("en.title"), "EN title", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("ru.description"), "RU description", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("en.description"), "EN description", StandardCharsets.UTF_8);
        Files.writeString(approved.resolve("references.json"), "not-json", StandardCharsets.UTF_8);
        return approved;
    }
}
