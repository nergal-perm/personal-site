package dev.eugene.astroexport.model;

import java.util.List;

public record SelectionResult(
    List<Note> included,
    List<String> unqualifiedVaultPaths,
    int matched,
    int confirmed) {
}
