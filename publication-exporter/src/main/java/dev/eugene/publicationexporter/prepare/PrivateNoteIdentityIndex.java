package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PrivateNoteIdentityIndex {

    private final Map<String, TargetIdentity> identitiesByFilenameStem;

    PrivateNoteIdentityIndex(Map<String, TargetIdentity> identitiesByFilenameStem) {
        this.identitiesByFilenameStem =
                Map.copyOf(Objects.requireNonNull(identitiesByFilenameStem, "identitiesByFilenameStem"));
    }

    static PrivateNoteIdentityIndex from(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Map<String, TargetIdentity> identities = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath path : vaultReader.listAllNotePaths()) {
            registerOrMarkAmbiguous(vaultReader, path, identities, ambiguousStems);
        }
        ambiguousStems.forEach(identities::remove);
        return new PrivateNoteIdentityIndex(identities);
    }

    Optional<TargetIdentity> identityFor(String filenameStem) {
        return Optional.ofNullable(identitiesByFilenameStem.get(filenameStem));
    }

    private static void registerOrMarkAmbiguous(
            VaultReader vaultReader, VaultRelativePath path,
            Map<String, TargetIdentity> identities, Set<String> ambiguousStems) {
        String stem = PublicNoteIndex.filenameStem(path);
        if (identities.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        Optional<String> sourceId = MarkdownNote.parse(vaultReader.readSource(path)).string("id");
        identities.put(stem, new TargetIdentity(sourceId));
    }
}

record TargetIdentity(Optional<String> sourceId) {
}
