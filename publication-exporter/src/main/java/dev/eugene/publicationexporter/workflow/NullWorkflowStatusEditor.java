package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NullWorkflowStatusEditor implements WorkflowStatusEditor {

    private final Map<String, String> sourceByPath;

    public NullWorkflowStatusEditor() {
        this(Map.of());
    }

    public NullWorkflowStatusEditor(Map<VaultRelativePath, String> sourceByPath) {
        Map<String, String> bySourcePath = new LinkedHashMap<>();
        sourceByPath.forEach((path, source) -> bySourcePath.put(path.value(), source));
        this.sourceByPath = new LinkedHashMap<>(bySourcePath);
    }

    @Override
    public Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue) {
        Objects.requireNonNull(notePath, "notePath");
        Objects.requireNonNull(expectedSourceHash, "expectedSourceHash");
        Objects.requireNonNull(newValue, "newValue");
        String current = sourceByPath.get(notePath.value());
        if (current == null || !ContentHash.sha256Hex(current).equals(expectedSourceHash)) {
            return Result.blocked("Source changed since it was validated.");
        }
        String updated = Frontmatter.parse(current).withScalarSet("workflowStatus", newValue);
        sourceByPath.put(notePath.value(), updated);
        return Result.written();
    }

    public String currentValue(VaultRelativePath notePath, String key) {
        String current = sourceByPath.get(notePath.value());
        return current == null ? null : Frontmatter.parse(current).string(key).orElse(null);
    }
}
