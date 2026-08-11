package dev.eugene.publicationexporter.prepare;

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

    public static Result validate(String ruBody, String enBody, String enTitle, String enDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(enDescription, "enDescription");

        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(blankFieldDiagnostics(enBody, enTitle, enDescription));
        diagnostics.addAll(internalRouteDiagnostics(enBody, enTitle, enDescription));
        diagnostics.addAll(droppedUrlDiagnostics(ruBody, enBody));
        diagnostics.addAll(droppedAssetReferenceDiagnostics(ruBody, enBody));
        return diagnostics.isEmpty() ? Result.ok() : Result.invalid(diagnostics);
    }

    private static List<String> blankFieldDiagnostics(String enBody, String enTitle, String enDescription) {
        List<String> diagnostics = new ArrayList<>();
        if (enBody.isBlank()) {
            diagnostics.add("Translation worker produced a blank body.");
        }
        if (enTitle.isBlank()) {
            diagnostics.add("Translation worker produced a blank title.");
        }
        if (enDescription.isBlank()) {
            diagnostics.add("Translation worker produced a blank description.");
        }
        return diagnostics;
    }

    private static List<String> internalRouteDiagnostics(
            String enBody, String enTitle, String enDescription) {
        boolean internalRoutePresent = List.of(enBody, enTitle, enDescription).stream()
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

    private static List<String> droppedUrlDiagnostics(String ruBody, String enBody) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedUrl : droppedMatches(ruBody, enBody, EXTERNAL_URL)) {
            diagnostics.add("English candidate dropped external URL " + droppedUrl + ".");
        }
        return diagnostics;
    }

    private static List<String> droppedAssetReferenceDiagnostics(String ruBody, String enBody) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedReference : droppedMatches(
                withoutExternalUrls(ruBody), withoutExternalUrls(enBody), ASSET_REFERENCE)) {
            diagnostics.add("English candidate dropped asset reference " + droppedReference + ".");
        }
        return diagnostics;
    }

    private static Set<String> droppedMatches(String ruBody, String enBody, Pattern pattern) {
        Set<String> ruMatches = extractMatches(ruBody, pattern);
        Set<String> enMatches = extractMatches(enBody, pattern);
        Set<String> matches = new LinkedHashSet<>(ruMatches);
        matches.removeAll(enMatches);
        return matches;
    }

    private static Set<String> extractMatches(String text, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
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
