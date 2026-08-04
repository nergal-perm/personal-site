package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Objects;

public final class VaultRelativePath {

    private final String value;

    private VaultRelativePath(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static VaultRelativePath of(String rawPath) {
        return new VaultRelativePath(rawPath);
    }

    public boolean isWithinVault() {
        if (isEmpty()) {
            return false;
        }
        if (isAbsolute() || usesWindowsSeparator()) {
            return false;
        }
        return hasOnlyOrdinarySegments();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VaultRelativePath that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "VaultRelativePath[value=" + value + "]";
    }

    private boolean isEmpty() {
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
