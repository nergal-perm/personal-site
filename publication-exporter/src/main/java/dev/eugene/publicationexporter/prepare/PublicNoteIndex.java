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

    PublicNoteIndex(Map<String, String> routesByFilenameStem) {
        this.referencesByFilenameStem = toReferences(routesByFilenameStem);
    }

    private PublicNoteIndex(NoteReferenceIndex referencesByFilenameStem) {
        this.referencesByFilenameStem = Map.copyOf(Objects.requireNonNull(
                referencesByFilenameStem.values(), "referencesByFilenameStem"));
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

    Optional<String> routeFor(String linkTarget) {
        NoteReference reference = referencesByFilenameStem.get(linkTarget);
        return reference == null ? Optional.empty() : Optional.of(reference.route());
    }

    Optional<String> sourceIdFor(String linkTarget) {
        NoteReference reference = referencesByFilenameStem.get(linkTarget);
        return reference == null ? Optional.empty() : Optional.ofNullable(reference.sourceId());
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

    private record NoteReference(String route, String sourceId) {
    }

    private record NoteReferenceIndex(Map<String, NoteReference> values) {
    }

    private static Map<String, NoteReference> toReferences(Map<String, String> routesByFilenameStem) {
        Map<String, NoteReference> references = new LinkedHashMap<>();
        for (var entry : routesByFilenameStem.entrySet()) {
            references.put(entry.getKey(), new NoteReference(entry.getValue(), null));
        }
        return Map.copyOf(references);
    }
}
