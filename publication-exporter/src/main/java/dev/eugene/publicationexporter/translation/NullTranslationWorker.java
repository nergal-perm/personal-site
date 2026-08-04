package dev.eugene.publicationexporter.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullTranslationWorker implements TranslationWorker {

    private final TranslationResult result;
    private final List<String> requestedBodies = new ArrayList<>();

    public NullTranslationWorker(TranslationResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public TranslationResult translate(String ruBody) {
        requestedBodies.add(ruBody);
        return result;
    }

    public List<String> requestedBodies() {
        return List.copyOf(requestedBodies);
    }
}
