package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.List;
import java.util.Optional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OccurrenceMarkerResolver {

    private static final Pattern MARKER = Pattern.compile("\\[(?<label>[^\\]]*)]\\(ref:(?<sourceId>[^)]+)\\)");

    private OccurrenceMarkerResolver() {
    }

    public static OccurrenceResolution resolve(
            String body, ApprovedTargetRegistry registry, List<Occurrence> occurrences, String language) {
        Matcher matcher = MARKER.matcher(body);
        StringBuilder rewritten = new StringBuilder(body.length());
        int cursor = 0;
        int activated = 0;
        int deactivated = 0;
        int occurrenceIndex = 0;
        while (matcher.find()) {
            rewritten.append(body, cursor, matcher.start());
            String targetSourceId = URLDecoder.decode(matcher.group("sourceId"), StandardCharsets.UTF_8);
            MatchedOccurrence matched = occurrenceFollowing(occurrences, occurrenceIndex, targetSourceId);
            occurrenceIndex = matched.nextIndex();
            MarkerSubstitution substitution = substituteMarker(
                    matcher, targetSourceId, matched.occurrence(), registry, language);
            rewritten.append(substitution.body());
            if (substitution.activated()) {
                activated++;
            } else {
                deactivated++;
            }
            cursor = matcher.end();
        }
        rewritten.append(body, cursor, body.length());
        return OccurrenceResolution.of(rewritten.toString(), activated, deactivated);
    }

    private static MatchedOccurrence occurrenceFollowing(
            List<Occurrence> occurrences, int occurrenceIndex, String targetSourceId) {
        for (int index = occurrenceIndex; index < occurrences.size(); index++) {
            Occurrence occurrence = occurrences.get(index);
            if (occurrence.targetSourceId().equals(targetSourceId)) {
                return new MatchedOccurrence(Optional.of(occurrence), index + 1);
            }
        }
        return new MatchedOccurrence(Optional.empty(), occurrenceIndex);
    }

    private static MarkerSubstitution substituteMarker(
            Matcher matcher, String targetSourceId, Optional<Occurrence> occurrence,
            ApprovedTargetRegistry registry, String language) {
        String label = storedLabel(occurrence, targetSourceId, language, matcher.group("label"));
        return registry.find(targetSourceId)
                .map(target -> new MarkerSubstitution(
                        "[" + label + "](" + routeFor(target, language) + ")", true))
                .orElseGet(() -> new MarkerSubstitution(label, false));
    }

    private static String routeFor(ApprovedTargetRegistry.Target target, String language) {
        return "ru".equals(language) ? target.ruRoute() : target.enRoute();
    }

    private static String storedLabel(
            Optional<Occurrence> occurrence, String targetSourceId, String language,
            String fallbackLabel) {
        return occurrence
                .filter(value -> value.targetSourceId().equals(targetSourceId))
                .map(value -> "ru".equals(language) ? value.ruLabel() : value.enLabel())
                .orElse(fallbackLabel);
    }

    private record MarkerSubstitution(String body, boolean activated) {
    }

    private record MatchedOccurrence(Optional<Occurrence> occurrence, int nextIndex) {
    }
}
