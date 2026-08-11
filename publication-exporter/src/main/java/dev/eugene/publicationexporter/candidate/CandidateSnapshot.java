package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.List;
import java.util.Objects;

public final class CandidateSnapshot {

    private final String ruBody;
    private final String enBody;
    private final List<PublicField> ruFields;
    private final List<PublicField> enFields;
    private final String structuredData;
    private final ReferenceMap referenceMap;

    private CandidateSnapshot(String ruBody, String enBody, List<PublicField> ruFields,
            List<PublicField> enFields, String structuredData, ReferenceMap referenceMap) {
        this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
        this.enBody = Objects.requireNonNull(enBody, "enBody");
        this.ruFields = List.copyOf(Objects.requireNonNull(ruFields, "ruFields"));
        this.enFields = List.copyOf(Objects.requireNonNull(enFields, "enFields"));
        this.structuredData = Objects.requireNonNull(structuredData, "structuredData");
        this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
    }

    public static CandidateSnapshot of(String ruBody, String enBody, List<PublicField> ruFields,
            List<PublicField> enFields, String structuredData, ReferenceMap referenceMap) {
        return new CandidateSnapshot(ruBody, enBody, ruFields, enFields, structuredData, referenceMap);
    }

    public String ruBody() {
        return ruBody;
    }

    public String enBody() {
        return enBody;
    }

    public List<PublicField> ruFields() {
        return ruFields;
    }

    public List<PublicField> enFields() {
        return enFields;
    }

    public String structuredData() {
        return structuredData;
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
                && ruFields.equals(that.ruFields) && enFields.equals(that.enFields)
                && structuredData.equals(that.structuredData)
                && referenceMap.equals(that.referenceMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruBody, enBody, ruFields, enFields, structuredData, referenceMap);
    }

    @Override
    public String toString() {
        return "CandidateSnapshot[ruBody=" + ruBody + ", enBody=" + enBody
                + ", ruFields=" + ruFields + ", enFields=" + enFields
                + ", structuredData=" + structuredData
                + ", referenceMap=" + referenceMap + "]";
    }
}
