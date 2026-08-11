package dev.eugene.publicationexporter.manifest;

import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PublicationManifestHandler {

    private final NoteIntake noteIntake;

    public PublicationManifestHandler(NoteIntake noteIntake) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
    }

    public PublicationManifest manifest(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        List<ManifestEntry> entries = new ArrayList<>();
        for (VaultRelativePath path : vaultReader.listPublishCandidates()) {
            entries.add(entryFor(path, vaultReader));
        }
        return PublicationManifest.of(entries);
    }

    private ManifestEntry entryFor(VaultRelativePath path, VaultReader vaultReader) {
        NoteIntake.Result intake = noteIntake.admit(path, vaultReader);
        return intake.accepted()
                ? ManifestEntry.admitted(path.value(), intake.identity())
                : ManifestEntry.blocked(path.value(), intake.diagnostics());
    }
}
