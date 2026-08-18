package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PublicNoteIndex {

    private final Map<String, NoteReference> referencesByFilenameStem;

    private PublicNoteIndex(NoteReferenceIndex referencesByFilenameStem) {
        this.referencesByFilenameStem = Map.copyOf(Objects.requireNonNull(
                referencesByFilenameStem.values(), "referencesByFilenameStem"));
    }

    static PublicNoteIndex empty() {
        return new PublicNoteIndex(new NoteReferenceIndex(Map.of()));
    }

    static PublicNoteIndex from(VaultReader vaultReader, NoteIntake noteIntake) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Objects.requireNonNull(noteIntake, "noteIntake");
        Map<String, NoteReference> references = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            registerIfAdmitted(vaultReader, candidate, noteIntake, references, ambiguousStems);
        }
        ambiguousStems.forEach(references::remove);
        return new PublicNoteIndex(new NoteReferenceIndex(references));
    }

    Optional<NoteReference> referenceFor(String linkTarget) {
        return Optional.ofNullable(referencesByFilenameStem.get(linkTarget));
    }

    Optional<String> routeFor(String linkTarget) {
        return referenceFor(linkTarget).map(NoteReference::route);
    }

    Optional<String> sourceIdFor(String linkTarget) {
        return referenceFor(linkTarget).map(NoteReference::sourceId);
    }

    private static void registerIfAdmitted(
            VaultReader vaultReader, VaultRelativePath candidate, NoteIntake noteIntake,
            Map<String, NoteReference> references, Set<String> ambiguousStems) {
        NoteIntake.Result intake = noteIntake.admit(candidate, vaultReader);
        if (!intake.accepted()) {
            return;
        }
        String stem = filenameStem(candidate);
        if (references.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        String routePrefix = intake.kind().routePrefix();
        String route = routePrefix == null
            ? "/" + intake.identity().publicId() + "/"
            : "/" + routePrefix + "/" + intake.identity().publicId() + "/";
        references.put(stem, new NoteReference(route, intake.sourceId()));
    }

    static String filenameStem(VaultRelativePath path) {
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    record NoteReference(String route, String sourceId) {
        NoteReference {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(sourceId, "sourceId");
        }
    }

    private record NoteReferenceIndex(Map<String, NoteReference> values) {
    }
}
