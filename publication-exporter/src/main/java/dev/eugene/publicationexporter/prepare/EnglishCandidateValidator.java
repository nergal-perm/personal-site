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
            Pattern.compile("\\]\\(/ru/[^)]*\\)");

    private EnglishCandidateValidator() {
    }

    public static Result validate(String ruBody, String enBody, String enTitle, String enDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(enDescription, "enDescription");

        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(blankFieldDiagnostics(enBody, enTitle, enDescription));
        diagnostics.addAll(internalRouteDiagnostics(enBody));
        diagnostics.addAll(droppedUrlDiagnostics(ruBody, enBody));
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

    private static List<String> internalRouteDiagnostics(String enBody) {
        return INTERNAL_RU_ROUTE.matcher(enBody).find()
                ? List.of("English candidate contains an internal /ru/ route.")
                : List.of();
    }

    private static List<String> droppedUrlDiagnostics(String ruBody, String enBody) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedUrl : droppedExternalUrls(ruBody, enBody)) {
            diagnostics.add("English candidate dropped external URL " + droppedUrl + ".");
        }
        return diagnostics;
    }

    private static Set<String> droppedExternalUrls(String ruBody, String enBody) {
        Set<String> ruUrls = extractUrls(ruBody);
        Set<String> enUrls = extractUrls(enBody);
        Set<String> dropped = new LinkedHashSet<>(ruUrls);
        dropped.removeAll(enUrls);
        return dropped;
    }

    private static Set<String> extractUrls(String text) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = EXTERNAL_URL.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
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
