package dev.eugene.publicationexporter.vault;

public final class VaultRelativePath {

    private final String value;

    private VaultRelativePath(String value) {
        this.value = value;
    }

    public static VaultRelativePath of(String rawPath) {
        return new VaultRelativePath(rawPath);
    }

    public boolean isWithinVault() {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.contains("\\") || value.startsWith("/")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public String value() {
        return value;
    }
}
