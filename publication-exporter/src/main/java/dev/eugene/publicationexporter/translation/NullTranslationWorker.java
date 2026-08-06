package dev.eugene.publicationexporter.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullTranslationWorker implements TranslationWorker {

    private final TranslationResult result;
    private final List<RequestedTranslation> requested = new ArrayList<>();

    public NullTranslationWorker(TranslationResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public TranslationResult translate(String ruBody, String ruTitle, String ruDescription) {
        requested.add(RequestedTranslation.of(ruBody, ruTitle, ruDescription));
        return result;
    }

    public List<RequestedTranslation> requested() {
        return List.copyOf(requested);
    }

    public record RequestedTranslation(String ruBody, String ruTitle, String ruDescription) {
        public static RequestedTranslation of(String ruBody, String ruTitle, String ruDescription) {
            return new RequestedTranslation(ruBody, ruTitle, ruDescription);
        }
    }
}
