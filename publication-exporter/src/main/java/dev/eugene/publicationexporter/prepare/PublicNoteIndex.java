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

    private final Map<String, String> routesByFilenameStem;
    private final Map<String, String> sourceIdsByFilenameStem;

    PublicNoteIndex(Map<String, String> routesByFilenameStem) {
        this(routesByFilenameStem, Map.of());
    }

    PublicNoteIndex(Map<String, String> routesByFilenameStem, Map<String, String> sourceIdsByFilenameStem) {
        this.routesByFilenameStem = Map.copyOf(Objects.requireNonNull(routesByFilenameStem, "routesByFilenameStem"));
        this.sourceIdsByFilenameStem =
                Map.copyOf(Objects.requireNonNull(sourceIdsByFilenameStem, "sourceIdsByFilenameStem"));
    }

    static PublicNoteIndex from(VaultReader vaultReader, NoteIntake noteIntake) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Objects.requireNonNull(noteIntake, "noteIntake");
        Map<String, String> routes = new LinkedHashMap<>();
        Map<String, String> sourceIds = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            registerIfAdmitted(vaultReader, candidate, noteIntake, routes, sourceIds, ambiguousStems);
        }
        ambiguousStems.forEach(routes::remove);
        ambiguousStems.forEach(sourceIds::remove);
        return new PublicNoteIndex(routes, sourceIds);
    }

    Optional<String> routeFor(String linkTarget) {
        return Optional.ofNullable(routesByFilenameStem.get(linkTarget));
    }

    Optional<String> sourceIdFor(String linkTarget) {
        return Optional.ofNullable(sourceIdsByFilenameStem.get(linkTarget));
    }

    private static void registerIfAdmitted(
            VaultReader vaultReader, VaultRelativePath candidate, NoteIntake noteIntake,
            Map<String, String> routes, Map<String, String> sourceIds, Set<String> ambiguousStems) {
        NoteIntake.Result intake = noteIntake.admit(candidate, vaultReader);
        if (!intake.accepted()) {
            return;
        }
        String stem = filenameStem(candidate);
        if (routes.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        String routePrefix = intake.kind().routePrefix();
        String route = routePrefix == null
            ? "/" + intake.identity().publicId() + "/"
            : "/" + routePrefix + "/" + intake.identity().publicId() + "/";
        routes.put(stem, route);
        sourceIds.put(stem, intake.sourceId());
    }

    static String filenameStem(VaultRelativePath path) {
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
