package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BracketIndexedFields {

    private static final Pattern LIST_ITEM = Pattern.compile("^(\\w+)\\[(\\d+)\\](?:\\.(\\w+))?$");

    private BracketIndexedFields() {
    }

    static String render(List<PublicField> fields, Consumer<PublicField> scalarFieldWriter) {
        StringBuilder yaml = new StringBuilder();
        LinkedHashMap<String, LinkedHashMap<Integer, LinkedHashMap<String, String>>> grouped = new LinkedHashMap<>();
        for (PublicField field : fields) {
            Matcher match = LIST_ITEM.matcher(field.key());
            if (!match.matches()) {
                scalarFieldWriter.accept(field);
                continue;
            }
            groupListItem(grouped, match, field.value());
        }
        grouped.forEach((name, items) -> appendListBlock(yaml, name, items));
        return yaml.toString();
    }

    private static void groupListItem(
            Map<String, LinkedHashMap<Integer, LinkedHashMap<String, String>>> grouped,
            Matcher match,
            String value) {
        String field = match.group(1);
        int index = Integer.parseInt(match.group(2));
        String subfield = match.group(3);
        grouped.computeIfAbsent(field, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                .put(subfield == null ? "" : subfield, value);
    }

    private static void appendListBlock(
            StringBuilder yaml, String field, Map<Integer, LinkedHashMap<String, String>> items) {
        yaml.append(field).append(":\n");
        items.values().forEach(item -> appendListItem(yaml, item));
    }

    private static void appendListItem(StringBuilder yaml, Map<String, String> item) {
        if (item.containsKey("")) {
            yaml.append("  - ").append(YamlScalar.doubleQuoted(item.get(""))).append('\n');
            return;
        }
        boolean first = true;
        for (Map.Entry<String, String> member : item.entrySet()) {
            yaml.append(first ? "  - " : "    ")
                    .append(member.getKey()).append(": ")
                    .append(YamlScalar.doubleQuoted(member.getValue())).append('\n');
            first = false;
        }
    }
}
