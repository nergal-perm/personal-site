package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Objects;

public record VaultRelativePath(String value) {

    public VaultRelativePath {
        Objects.requireNonNull(value, "value");
    }

    public static VaultRelativePath of(String rawPath) {
        return new VaultRelativePath(rawPath);
    }

    public boolean isWithinVault() {
        if (isBlank()) {
            return false;
        }
        if (isAbsolute() || usesWindowsSeparator()) {
            return false;
        }
        return hasOnlyOrdinarySegments();
    }

    private boolean isBlank() {
        return value.isEmpty();
    }

    private boolean isAbsolute() {
        return value.startsWith("/");
    }

    private boolean usesWindowsSeparator() {
        return value.contains("\\");
    }

    private boolean hasOnlyOrdinarySegments() {
        return Arrays.stream(value.split("/", -1)).noneMatch(this::isTraversalOrEmptySegment);
    }

    private boolean isTraversalOrEmptySegment(String segment) {
        return segment.isEmpty() || segment.equals(".") || segment.equals("..");
    }
}
