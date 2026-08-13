package dev.eugene.publicationexporter.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.PublicField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CuratedPageJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CuratedPageJson() {
    }

    static String render(PublicationIdentity identity, List<PublicField> fields, String structuredData,
            String locale) {
        try {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", identity.publicId());
            document.put("type", "about");
            document.put("contentType", identity.publicContentType());
            document.put("language", locale);
            document.put("title", fieldValue(fields, "title"));
            document.put("summary", fieldValue(fields, "summary"));
            document.put("eyebrow", fieldValue(fields, "eyebrow"));
            document.put("lead", fieldValue(fields, "lead"));
            document.put("principles", principlesFrom(fields));
            document.put("colophon", fieldValue(fields, "colophon"));
            document.put("searchable", structuredData.contains("\"searchable\":true"));
            return MAPPER.writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Curated page JSON serialization failed", impossible);
        }
    }

    private static String fieldValue(List<PublicField> fields, String key) {
        return PublicField.value(fields, key).orElse("");
    }

    private static List<List<String>> principlesFrom(List<PublicField> fields) {
        List<List<String>> principles = new ArrayList<>();
        int index = 0;
        while (true) {
            String title = PublicField.value(fields, "principles[" + index + "].title").orElse(null);
            if (title == null) {
                break;
            }
            String text = PublicField.value(fields, "principles[" + index + "].text").orElse("");
            principles.add(List.of(title, text));
            index++;
        }
        return principles;
    }
}
