package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OccurrenceMarkerResolver {

    private static final Pattern MARKER = Pattern.compile("\\[(?<label>[^\\]]*)]\\(ref:(?<sourceId>[^)]+)\\)");

    private OccurrenceMarkerResolver() {
    }

    public static OccurrenceResolution resolve(
            String body, ApprovedTargetRegistry registry, List<Occurrence> occurrences, String language) {
        Map<String, Occurrence> occurrencesByTargetSourceId = indexOccurrencesByTargetSourceId(occurrences);
        Matcher matcher = MARKER.matcher(body);
        StringBuilder rewritten = new StringBuilder(body.length());
        int cursor = 0;
        int activated = 0;
        int deactivated = 0;
        while (matcher.find()) {
            rewritten.append(body, cursor, matcher.start());
            MarkerSubstitution substitution = substituteMarker(
                    matcher, occurrencesByTargetSourceId, registry, language);
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

    private static Map<String, Occurrence> indexOccurrencesByTargetSourceId(List<Occurrence> occurrences) {
        Map<String, Occurrence> occurrencesByTargetSourceId = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            occurrencesByTargetSourceId.putIfAbsent(occurrence.targetSourceId(), occurrence);
        }
        return occurrencesByTargetSourceId;
    }

    private static MarkerSubstitution substituteMarker(
            Matcher matcher, Map<String, Occurrence> occurrencesByTargetSourceId,
            ApprovedTargetRegistry registry, String language) {
        String targetSourceId = matcher.group("sourceId");
        String label = storedLabel(occurrencesByTargetSourceId, targetSourceId, language, matcher.group("label"));
        return registry.find(targetSourceId)
                .map(target -> new MarkerSubstitution(
                        "[" + label + "](" + routeFor(target, language) + ")", true))
                .orElseGet(() -> new MarkerSubstitution(label, false));
    }

    private static String routeFor(ApprovedTargetRegistry.Target target, String language) {
        return "ru".equals(language) ? target.ruRoute() : target.enRoute();
    }

    private static String storedLabel(
            Map<String, Occurrence> occurrencesByTargetSourceId, String targetSourceId, String language,
            String fallbackLabel) {
        return Optional.ofNullable(occurrencesByTargetSourceId.get(targetSourceId))
                .map(occurrence -> "ru".equals(language) ? occurrence.ruLabel() : occurrence.enLabel())
                .orElse(fallbackLabel);
    }

    private record MarkerSubstitution(String body, boolean activated) {
    }
}
