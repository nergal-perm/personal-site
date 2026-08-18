package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OccurrenceMarkerResolver {

    private static final Pattern MARKER = Pattern.compile("\\[(?<label>[^\\]]*)]\\(ref:(?<sourceId>[^)]+)\\)");

    private OccurrenceMarkerResolver() {
    }

    public static OccurrenceResolution resolve(
            String body, ApprovedTargetRegistry registry, List<Occurrence> occurrences, String language) {
        Map<String, Occurrence> occurrencesByTargetSourceId = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            occurrencesByTargetSourceId.putIfAbsent(occurrence.targetSourceId(), occurrence);
        }
        Matcher matcher = MARKER.matcher(body);
        StringBuilder rewritten = new StringBuilder(body.length());
        int cursor = 0;
        int activated = 0;
        int deactivated = 0;
        while (matcher.find()) {
            rewritten.append(body, cursor, matcher.start());
            String targetSourceId = matcher.group("sourceId");
            String label = storedLabel(occurrencesByTargetSourceId, targetSourceId, language, matcher.group("label"));
            var target = registry.find(targetSourceId);
            if (target.isPresent()) {
                String route = "ru".equals(language) ? target.get().ruRoute() : target.get().enRoute();
                rewritten.append('[').append(label).append("](").append(route).append(')');
                activated++;
            } else {
                rewritten.append(label);
                deactivated++;
            }
            cursor = matcher.end();
        }
        rewritten.append(body, cursor, body.length());
        return OccurrenceResolution.of(rewritten.toString(), activated, deactivated);
    }

    private static String storedLabel(
            Map<String, Occurrence> occurrencesByTargetSourceId, String targetSourceId, String language,
            String fallbackLabel) {
        Occurrence occurrence = occurrencesByTargetSourceId.get(targetSourceId);
        if (occurrence == null) {
            return fallbackLabel;
        }
        return "ru".equals(language) ? occurrence.ruLabel() : occurrence.enLabel();
    }
}
