package dev.eugene.publicationexporter.candidate;

import java.nio.file.Path;
import java.util.Objects;

public final class CandidatePaths {

    private final Path ruPath;
    private final Path enPath;

    private CandidatePaths(Path ruPath, Path enPath) {
        this.ruPath = Objects.requireNonNull(ruPath, "ruPath");
        this.enPath = Objects.requireNonNull(enPath, "enPath");
    }

    public static CandidatePaths of(Path ruPath, Path enPath) {
        return new CandidatePaths(ruPath, enPath);
    }

    public Path ruPath() {
        return ruPath;
    }

    public Path enPath() {
        return enPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidatePaths that)) {
            return false;
        }
        return ruPath.equals(that.ruPath) && enPath.equals(that.enPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruPath, enPath);
    }

    @Override
    public String toString() {
        return "CandidatePaths[ruPath=" + ruPath + ", enPath=" + enPath + "]";
    }
}
