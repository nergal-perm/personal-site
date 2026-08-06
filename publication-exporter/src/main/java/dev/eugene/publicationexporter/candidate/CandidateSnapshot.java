package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.Objects;

public final class CandidateSnapshot {

    private final String ruBody;
    private final String enBody;
    private final String ruTitle;
    private final String enTitle;
    private final String ruDescription;
    private final String enDescription;
    private final ReferenceMap referenceMap;

    private CandidateSnapshot(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
        this.enBody = Objects.requireNonNull(enBody, "enBody");
        this.ruTitle = Objects.requireNonNull(ruTitle, "ruTitle");
        this.enTitle = Objects.requireNonNull(enTitle, "enTitle");
        this.ruDescription = Objects.requireNonNull(ruDescription, "ruDescription");
        this.enDescription = Objects.requireNonNull(enDescription, "enDescription");
        this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
    }

    public static CandidateSnapshot of(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        return new CandidateSnapshot(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
    }

    public String ruBody() {
        return ruBody;
    }

    public String enBody() {
        return enBody;
    }

    public String ruTitle() {
        return ruTitle;
    }

    public String enTitle() {
        return enTitle;
    }

    public String ruDescription() {
        return ruDescription;
    }

    public String enDescription() {
        return enDescription;
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
        return ruBody.equals(that.ruBody) && enBody.equals(that.enBody)
                && ruTitle.equals(that.ruTitle) && enTitle.equals(that.enTitle)
                && ruDescription.equals(that.ruDescription) && enDescription.equals(that.enDescription)
                && referenceMap.equals(that.referenceMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
    }

    @Override
    public String toString() {
        return "CandidateSnapshot[ruBody=" + ruBody + ", enBody=" + enBody
                + ", ruTitle=" + ruTitle + ", enTitle=" + enTitle
                + ", ruDescription=" + ruDescription + ", enDescription=" + enDescription
                + ", referenceMap=" + referenceMap + "]";
    }
}
