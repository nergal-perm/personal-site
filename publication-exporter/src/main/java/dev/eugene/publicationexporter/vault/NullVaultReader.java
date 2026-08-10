package dev.eugene.publicationexporter.vault;

import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

final class NullVaultReader implements VaultReader {

    private final Map<String, String> sourceByPath;

    NullVaultReader(VaultRelativePath... existing) {
        this(defaultToEmptySource(existing));
    }

    NullVaultReader(Map<VaultRelativePath, String> notesBySource) {
        Map<String, String> bySourcePath = new LinkedHashMap<>();
        notesBySource.forEach((path, source) -> bySourcePath.put(path.value(), source));
        this.sourceByPath = Map.copyOf(bySourcePath);
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return sourceByPath.containsKey(notePath.value());
    }

    @Override
    public String readSource(VaultRelativePath notePath) {
        String source = sourceByPath.get(notePath.value());
        if (source == null) {
            throw new NoSuchElementException("Note not found: " + notePath.value());
        }
        return source;
    }

    @Override
    public List<VaultRelativePath> listPublishCandidates() {
        return sourceByPath.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".md"))
                .filter(entry -> MarkdownNote.parse(entry.getValue()).flag("publish"))
                .map(entry -> VaultRelativePath.of(entry.getKey()))
                .toList();
    }

    private static Map<VaultRelativePath, String> defaultToEmptySource(VaultRelativePath... paths) {
        Map<VaultRelativePath, String> notesBySource = new LinkedHashMap<>();
        Arrays.stream(paths).forEach(path -> notesBySource.put(path, ""));
        return notesBySource;
    }
}
