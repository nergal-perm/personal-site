package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

public final class VaultRelativePath {

    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

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
        if (isAbsolute() || isDriveQualified() || usesWindowsSeparator()) {
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

    /**
     * Rejects Windows drive-qualified forms such as {@code C:/etc/passwd.md} (drive-absolute) and
     * {@code c:blog/note.md} (drive-relative). Checked as plain text rather than through
     * {@link java.nio.file.Path} so the verdict is identical on every platform, and so a colon
     * elsewhere in a segment — legal in an Obsidian note name — stays allowed.
     */
    private boolean isDriveQualified() {
        return DRIVE_PREFIX.matcher(value).find();
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
