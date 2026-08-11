package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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

    PublicNoteIndex(Map<String, String> routesByFilenameStem) {
        this.routesByFilenameStem = Map.copyOf(Objects.requireNonNull(routesByFilenameStem, "routesByFilenameStem"));
    }

    static PublicNoteIndex from(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            registerIfAdmitted(vaultReader, candidate, routes, ambiguousStems);
        }
        ambiguousStems.forEach(routes::remove);
        return new PublicNoteIndex(routes);
    }

    Optional<String> routeFor(String linkTarget) {
        return Optional.ofNullable(routesByFilenameStem.get(linkTarget));
    }

    private static void registerIfAdmitted(
            VaultReader vaultReader, VaultRelativePath candidate,
            Map<String, String> routes, Set<String> ambiguousStems) {
        NoteIntake.Result intake = new NoteIntake(PublicationKinds.installed()).admit(candidate, vaultReader);
        if (!intake.accepted()) {
            return;
        }
        String stem = filenameStem(candidate);
        if (routes.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        routes.put(stem, routeFor(intake.identity()));
    }

    private static String routeFor(PublicationIdentity identity) {
        return "/essays/" + identity.publicId() + "/";
    }

    private static String filenameStem(VaultRelativePath path) {
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
