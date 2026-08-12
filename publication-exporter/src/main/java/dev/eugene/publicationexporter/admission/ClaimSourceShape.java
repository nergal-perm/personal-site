package dev.eugene.publicationexporter.admission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ClaimSourceShape {

    private static final Set<String> SOURCE_FIELDS =
            Set.of("link", "attestation", "evidence", "locator", "confidence");
    private static final Set<String> REFERENCE_FIELDS = Set.of("label", "target");
    private static final Set<String> TEXT_TOKEN_FIELDS = Set.of("kind", "value");
    private static final Set<String> REFERENCE_TOKEN_FIELDS = Set.of("kind", "target");
    private static final Pattern YAML_NUMBER = Pattern.compile(
            "[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");

    private ClaimSourceShape() {
    }

    static boolean matches(String yaml) {
        List<String> lines = yaml.lines().toList();
        if (lines.size() < 2 || !"sources:".equals(lines.get(0).strip())) {
            return false;
        }
        Optional<Node> parsed = Parser.parse(lines.subList(1, lines.size()));
        return parsed.filter(SequenceNode.class::isInstance)
                .map(SequenceNode.class::cast)
                .filter(sources -> sources.values().stream().allMatch(ClaimSourceShape::validSource))
                .isPresent();
    }

    private static boolean validSource(Node node) {
        if (!(node instanceof MappingNode source) || !onlyDeclared(source, SOURCE_FIELDS)) {
            return false;
        }
        for (Map.Entry<String, Node> field : source.values().entrySet()) {
            boolean valid = switch (field.getKey()) {
                case "link" -> validReference(field.getValue());
                case "attestation", "confidence" -> field.getValue() instanceof ScalarNode;
                case "evidence", "locator" -> validRichText(field.getValue());
                default -> false;
            };
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static boolean validReference(Node node) {
        if (!(node instanceof MappingNode reference)
                || !onlyDeclared(reference, REFERENCE_FIELDS)
                || !(reference.values().get("label") instanceof ScalarNode)) {
            return false;
        }
        Node target = reference.values().get("target");
        return target == null || target instanceof ScalarNode;
    }

    private static boolean validRichText(Node node) {
        if (node instanceof ScalarNode) {
            return true;
        }
        return node instanceof SequenceNode tokens
                && tokens.values().stream().allMatch(ClaimSourceShape::validRichTextToken);
    }

    private static boolean validRichTextToken(Node node) {
        if (!(node instanceof MappingNode token)
                || !(token.values().get("kind") instanceof ScalarNode kind)) {
            return false;
        }
        return switch (kind.value()) {
            case "text" -> exactFields(token, TEXT_TOKEN_FIELDS)
                    && token.values().get("value") instanceof ScalarNode;
            case "reference" -> exactFields(token, REFERENCE_TOKEN_FIELDS)
                    && token.values().get("target") instanceof ScalarNode;
            default -> false;
        };
    }

    private static boolean onlyDeclared(MappingNode mapping, Set<String> declared) {
        return declared.containsAll(mapping.values().keySet());
    }

    private static boolean exactFields(MappingNode mapping, Set<String> expected) {
        return mapping.values().keySet().equals(expected);
    }

    private sealed interface Node permits ScalarNode, MappingNode, SequenceNode {
    }

    private record ScalarNode(String value) implements Node {
    }

    private record MappingNode(Map<String, Node> values) implements Node {
        private MappingNode {
            values = Map.copyOf(values);
        }
    }

    private record SequenceNode(List<Node> values) implements Node {
        private SequenceNode {
            values = List.copyOf(values);
        }
    }

    private static final class Parser {

        private final List<Line> lines;
        private int index;
        private boolean valid;

        private Parser(List<Line> lines) {
            this.lines = List.copyOf(lines);
            this.index = 0;
            this.valid = true;
        }

        private static Optional<Node> parse(List<String> sourceLines) {
            List<Line> lines = new ArrayList<>();
            for (String sourceLine : sourceLines) {
                Optional<Line> line = Line.from(sourceLine);
                if (line.isEmpty()) {
                    return Optional.empty();
                }
                lines.add(line.get());
            }
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            Parser parser = new Parser(lines);
            Node parsed = parser.block(lines.get(0).indentation());
            return parser.valid && parser.index == lines.size()
                    ? Optional.of(parsed)
                    : Optional.empty();
        }

        private Node block(int indentation) {
            if (atEnd() || current().indentation() != indentation) {
                return invalid();
            }
            return current().sequenceItem()
                    ? sequence(indentation)
                    : mapping(indentation);
        }

        private Node sequence(int indentation) {
            List<Node> values = new ArrayList<>();
            while (!atEnd() && current().indentation() == indentation) {
                if (!current().sequenceItem()) {
                    return invalid();
                }
                String item = current().content().substring(2).strip();
                index++;
                values.add(sequenceItem(indentation, item));
                if (!valid) {
                    return invalid();
                }
                if (!atEnd() && current().indentation() > indentation) {
                    return invalid();
                }
            }
            return new SequenceNode(values);
        }

        private Node sequenceItem(int indentation, String item) {
            if ("{}".equals(item)) {
                return new MappingNode(Map.of());
            }
            Optional<Entry> first = Entry.from(item);
            if (first.isEmpty()) {
                return invalid();
            }
            int fieldIndentation = indentation + 2;
            Map<String, Node> values = new LinkedHashMap<>();
            put(values, first.get(), fieldIndentation);
            while (valid && !atEnd()
                    && current().indentation() == fieldIndentation
                    && !current().sequenceItem()) {
                Optional<Entry> entry = Entry.from(current().content());
                index++;
                if (entry.isEmpty()) {
                    return invalid();
                }
                put(values, entry.get(), fieldIndentation);
            }
            return new MappingNode(values);
        }

        private Node mapping(int indentation) {
            Map<String, Node> values = new LinkedHashMap<>();
            while (!atEnd()
                    && current().indentation() == indentation
                    && !current().sequenceItem()) {
                Optional<Entry> entry = Entry.from(current().content());
                index++;
                if (entry.isEmpty()) {
                    return invalid();
                }
                put(values, entry.get(), indentation);
                if (!valid) {
                    return invalid();
                }
            }
            if (!atEnd() && current().indentation() > indentation) {
                return invalid();
            }
            return new MappingNode(values);
        }

        private void put(Map<String, Node> values, Entry entry, int indentation) {
            if (values.containsKey(entry.key())) {
                invalid();
                return;
            }
            values.put(entry.key(), value(entry.token(), indentation));
        }

        private Node value(String token, int indentation) {
            if (!token.isEmpty()) {
                if ("[]".equals(token)) {
                    return new SequenceNode(List.of());
                }
                if ("{}".equals(token)) {
                    return new MappingNode(Map.of());
                }
                return scalar(token).<Node>map(ScalarNode::new).orElseGet(this::invalid);
            }
            if (atEnd() || current().indentation() != indentation + 2) {
                return invalid();
            }
            return block(indentation + 2);
        }

        private static Optional<String> scalar(String token) {
            String value = token.strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                return Optional.of(value.substring(1, value.length() - 1));
            }
            if (value.startsWith("\"") || value.endsWith("\"")
                    || value.startsWith("'") || value.endsWith("'")
                    || value.startsWith("[") || value.startsWith("{")
                    || Set.of("", "null", "~", "true", "false").contains(value)
                    || YAML_NUMBER.matcher(value).matches()) {
                return Optional.empty();
            }
            return Optional.of(value);
        }

        private Node invalid() {
            valid = false;
            return new ScalarNode("");
        }

        private boolean atEnd() {
            return index >= lines.size();
        }

        private Line current() {
            return lines.get(index);
        }
    }

    private record Line(int indentation, String content) {

        private static Optional<Line> from(String source) {
            if (source.isBlank() || source.indexOf('\t') >= 0) {
                return Optional.empty();
            }
            int indentation = 0;
            while (indentation < source.length() && source.charAt(indentation) == ' ') {
                indentation++;
            }
            String content = source.substring(indentation);
            return content.isBlank() ? Optional.empty() : Optional.of(new Line(indentation, content));
        }

        private boolean sequenceItem() {
            return content.startsWith("- ");
        }
    }

    private record Entry(String key, String token) {

        private static Optional<Entry> from(String content) {
            int colon = content.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            String key = content.substring(0, colon).strip();
            return key.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Entry(key, content.substring(colon + 1).strip()));
        }
    }
}
