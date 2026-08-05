package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.Objects;

public final class CandidateSnapshot {

    private final String ruBody;
    private final String enBody;
    private final ReferenceMap referenceMap;

    private CandidateSnapshot(String ruBody, String enBody, ReferenceMap referenceMap) {
        this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
        this.enBody = Objects.requireNonNull(enBody, "enBody");
        this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
    }

    public static CandidateSnapshot of(String ruBody, String enBody, ReferenceMap referenceMap) {
        return new CandidateSnapshot(ruBody, enBody, referenceMap);
    }

    public String ruBody() {
        return ruBody;
    }

    public String enBody() {
        return enBody;
    }

    public ReferenceMap referenceMap() {
        return referenceMap;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidateSnapshot that)) {
            return false;
        }
        return ruBody.equals(that.ruBody) && enBody.equals(that.enBody) && referenceMap.equals(that.referenceMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruBody, enBody, referenceMap);
    }

    @Override
    public String toString() {
        return "CandidateSnapshot[ruBody=" + ruBody + ", enBody=" + enBody + ", referenceMap=" + referenceMap + "]";
    }
}
