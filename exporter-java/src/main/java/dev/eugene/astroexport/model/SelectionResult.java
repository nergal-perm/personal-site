package dev.eugene.astroexport.model;

import java.nio.file.Path;
import java.util.List;

public record SelectionResult(
    List<Note> included,
    List<Exclusion> excluded,
    int matched,
    int confirmed) {
  public SelectionResult {
    included = List.copyOf(included);
    excluded = List.copyOf(excluded);
  }

  public List<String> unqualifiedVaultPaths() {
    return excluded.stream().map(Exclusion::vaultPath).toList();
  }

  public record Exclusion(Path path, String reason, String vaultPath, String field) {
    public Exclusion(Path path, String reason, String vaultPath) {
      this(path, reason, vaultPath, null);
    }
  }
}
