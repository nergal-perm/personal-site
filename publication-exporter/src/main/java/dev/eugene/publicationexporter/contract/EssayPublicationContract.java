package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.admission.FieldRule;

import java.util.ArrayList;
import java.util.List;

final class EssayPublicationContract {

    private EssayPublicationContract() {
    }

    static KindContract kind() {
        List<FieldContract> requiredFields = new ArrayList<>();
        requiredFields.add(FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"));
        for (FieldRule rule : EssayAdmission.fieldRules()) {
            requiredFields.add(toFieldContract(rule));
        }
        return KindContract.of("blog", "essay", requiredFields, List.of());
    }

    private static FieldContract toFieldContract(FieldRule rule) {
        return switch (rule.kind()) {
            case MUST_EQUAL ->
                    FieldContract.allowedValue(rule.field(), FieldContract.Type.STRING, rule.literalValue());
            case MUST_MATCH -> FieldContract.matchingPattern(rule.field(), rule.pattern().pattern());
            case NON_BLANK -> FieldContract.nonBlank(rule.field());
        };
    }
}
