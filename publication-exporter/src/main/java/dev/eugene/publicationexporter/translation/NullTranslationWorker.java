package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullTranslationWorker implements TranslationWorker {

    private final TranslationOutcome result;
    private final List<RequestedTranslation> requested = new ArrayList<>();

    public NullTranslationWorker(TranslationOutcome result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public TranslationOutcome translate(TranslationJob job, String ruBody, List<PublicField> ruFields) {
        Objects.requireNonNull(job, "job");
        requested.add(RequestedTranslation.of(ruBody, ruFields));
        return result;
    }

    public List<RequestedTranslation> requested() {
        return List.copyOf(requested);
    }

    public record RequestedTranslation(String ruBody, List<PublicField> ruFields) {
        public RequestedTranslation {
            ruFields = List.copyOf(ruFields);
        }

        public static RequestedTranslation of(String ruBody, List<PublicField> ruFields) {
            return new RequestedTranslation(ruBody, ruFields);
        }
    }
}
