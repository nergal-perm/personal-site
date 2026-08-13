package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnglishCandidateValidator {

    private static final Pattern EXTERNAL_URL =
            Pattern.compile("https?://[^\\s)\\]]+");
    private static final Pattern INTERNAL_RU_ROUTE =
            Pattern.compile("/ru/");
    private static final Pattern ASSET_REFERENCE =
            Pattern.compile("(?<![\\p{L}\\p{N}./=?&:-])/assets/vault/[^\\s)\\]]+");

    private EnglishCandidateValidator() {
    }

    public static Result validate(String ruBody, String enBody, List<PublicField> enFields) {
        return validate(ruBody, enFields, enBody, enFields);
    }

    public static Result validate(
            String ruBody,
            List<PublicField> ruFields,
            String enBody,
            List<PublicField> enFields) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(ruFields, "ruFields");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(enFields, "enFields");

        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(translatedFieldStructureDiagnostics(ruFields, enFields));
        diagnostics.addAll(blankFieldDiagnostics(ruBody, enBody, enFields));
        diagnostics.addAll(internalRouteDiagnostics(enBody, enFields));
        diagnostics.addAll(droppedUrlDiagnostics(ruBody, ruFields, enBody, enFields));
        diagnostics.addAll(droppedAssetReferenceDiagnostics(ruBody, ruFields, enBody, enFields));
        return diagnostics.isEmpty() ? Result.ok() : Result.invalid(diagnostics);
    }

    private static List<String> translatedFieldStructureDiagnostics(
            List<PublicField> ruFields,
            List<PublicField> enFields) {
        List<String> expectedKeys = fieldKeys(ruFields);
        List<String> actualKeys = fieldKeys(enFields);
        return expectedKeys.equals(actualKeys)
                ? List.of()
                : List.of("English candidate changed translated field structure: expected "
                + expectedKeys + " but got " + actualKeys + ".");
    }

    private static List<String> fieldKeys(List<PublicField> fields) {
        return fields.stream().map(PublicField::key).toList();
    }

    private static List<String> blankFieldDiagnostics(String ruBody, String enBody, List<PublicField> enFields) {
        List<String> diagnostics = new ArrayList<>();
        if (!ruBody.isBlank() && enBody.isBlank()) {
            diagnostics.add("Translation worker produced a blank body.");
        }
        for (PublicField field : enFields) {
            if (field.value().isBlank()) {
                diagnostics.add("Translation worker produced a blank " + field.key() + ".");
            }
        }
        return diagnostics;
    }

    private static List<String> internalRouteDiagnostics(String enBody, List<PublicField> enFields) {
        boolean internalRoutePresent = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(enBody),
                        enFields.stream().map(PublicField::value))
                .anyMatch(EnglishCandidateValidator::containsInternalRuRoute);
        return internalRoutePresent
                ? List.of("English candidate contains an internal /ru/ route.")
                : List.of();
    }

    private static boolean containsInternalRuRoute(String text) {
        return INTERNAL_RU_ROUTE.matcher(withoutExternalUrls(text)).find();
    }

    private static String withoutExternalUrls(String text) {
        Matcher urlMatcher = EXTERNAL_URL.matcher(text);
        StringBuilder withUrlsRemoved = new StringBuilder();
        int lastEnd = 0;
        while (urlMatcher.find()) {
            withUrlsRemoved.append(text, lastEnd, urlMatcher.start());
            lastEnd = urlMatcher.end();
        }
        withUrlsRemoved.append(text, lastEnd, text.length());
        return withUrlsRemoved.toString();
    }

    private static List<String> droppedUrlDiagnostics(
            String ruBody,
            List<PublicField> ruFields,
            String enBody,
            List<PublicField> enFields) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedUrl : droppedMatches(candidateTexts(ruBody, ruFields), candidateTexts(enBody, enFields), EXTERNAL_URL)) {
            diagnostics.add("English candidate dropped external URL " + droppedUrl + ".");
        }
        return diagnostics;
    }

    private static List<String> droppedAssetReferenceDiagnostics(
            String ruBody,
            List<PublicField> ruFields,
            String enBody,
            List<PublicField> enFields) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedReference : droppedMatches(
                textsWithoutExternalUrls(candidateTexts(ruBody, ruFields)),
                textsWithoutExternalUrls(candidateTexts(enBody, enFields)),
                ASSET_REFERENCE)) {
            diagnostics.add("English candidate dropped asset reference " + droppedReference + ".");
        }
        return diagnostics;
    }

    private static List<String> candidateTexts(String body, List<PublicField> fields) {
        List<String> texts = new ArrayList<>();
        texts.add(body);
        fields.stream().map(PublicField::value).forEach(texts::add);
        return List.copyOf(texts);
    }

    private static List<String> textsWithoutExternalUrls(List<String> texts) {
        return texts.stream().map(EnglishCandidateValidator::withoutExternalUrls).toList();
    }

    private static Set<String> droppedMatches(List<String> ruTexts, List<String> enTexts, Pattern pattern) {
        Set<String> ruMatches = extractMatches(ruTexts, pattern);
        Set<String> enMatches = extractMatches(enTexts, pattern);
        Set<String> matches = new LinkedHashSet<>(ruMatches);
        matches.removeAll(enMatches);
        return matches;
    }

    private static Set<String> extractMatches(List<String> texts, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        for (String text : texts) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                matches.add(matcher.group());
            }
        }
        return matches;
    }

    public static final class Result {
        private final boolean valid;
        private final List<String> diagnostics;

        private Result(boolean valid, List<String> diagnostics) {
            this.valid = valid;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result ok() {
            return new Result(true, List.of());
        }

        static Result invalid(List<String> diagnostics) {
            return new Result(false, diagnostics);
        }

        public boolean valid() {
            return valid;
        }

        public List<String> diagnostics() {
            return diagnostics;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result that)) {
                return false;
            }
            return valid == that.valid && diagnostics.equals(that.diagnostics);
        }

        @Override
        public int hashCode() {
            return Objects.hash(valid, diagnostics);
        }
    }
}
