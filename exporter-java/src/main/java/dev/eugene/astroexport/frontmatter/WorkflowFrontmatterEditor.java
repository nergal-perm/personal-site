package dev.eugene.astroexport.frontmatter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;

/** Patches only owned workflow scalar lines while preserving all other Markdown bytes. */
public final class WorkflowFrontmatterEditor {
  public static final List<String> FIELDS = List.of(
      "publicWorkflowStatus",
      "publicTranslationStatus",
      "publicWorkflowUpdated",
      "publicWorkflowDiagnostic");
  private static final Set<String> FIELD_SET = Set.copyOf(FIELDS);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ANCHORED_WORKFLOW_VALUE = Pattern.compile(
      "(?m)^[ \\t]*[^#\\r\\n:]+:[ \\t]*&([A-Za-z0-9_-]+)[ \\t]+"
          + "(?:([\"'])(publicWorkflowStatus|publicTranslationStatus"
          + "|publicWorkflowUpdated|publicWorkflowDiagnostic)\\2"
          + "|(publicWorkflowStatus|publicTranslationStatus"
          + "|publicWorkflowUpdated|publicWorkflowDiagnostic))"
          + "[ \\t]*(?:#.*)?$");

  private WorkflowFrontmatterEditor() { }

  public static String patch(String content, Map<String, String> values) {
    if (!values.keySet().equals(FIELD_SET)) {
      throw new IllegalArgumentException("all workflow fields must be supplied");
    }
    Parts parts = parts(content);
    String header = String.join("", parts.headerLines());
    rejectAliasWorkflowKeys(header);
    MappingNode mapping = parseMapping(header);
    Map<Integer, Range> ranges = workflowRanges(mapping);
    Map<String, String> rendered = new LinkedHashMap<>();
    for (String field : FIELDS) {
      rendered.put(field, field + ": " + quote(values.get(field)));
    }

    Set<String> seen = new LinkedHashSet<>();
    List<String> patched = new ArrayList<>();
    int line = 0;
    while (line < parts.headerLines().size()) {
      Range range = ranges.get(line);
      if (range == null) {
        patched.add(parts.headerLines().get(line++));
        continue;
      }
      seen.add(range.field());
      String ownedLastLine = parts.headerLines().get(range.lastLine());
      patched.add(rendered.get(range.field()) + lineEnding(ownedLastLine));
      line = range.lastLine() + 1;
    }
    if (!patched.isEmpty() && lineEnding(patched.getLast()).isEmpty()) {
      throw new IllegalArgumentException("YAML frontmatter must end each field with a newline");
    }
    for (String field : FIELDS) {
      if (!seen.contains(field)) {
        patched.add(rendered.get(field) + parts.newline());
      }
    }
    String result = parts.opening() + String.join("", patched) + parts.remainder();
    Parts verifiedParts = parts(result);
    Object verified = load(String.join("", verifiedParts.headerLines()));
    if (!(verified instanceof Map<?, ?> metadata)) {
      throw new IllegalArgumentException("invalid YAML frontmatter: expected a mapping");
    }
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
        throw new IllegalArgumentException(
            "invalid YAML frontmatter: could not set " + entry.getKey());
      }
    }
    return result;
  }

  private static Parts parts(String content) {
    List<String> lines = splitLines(content);
    if (lines.isEmpty() || !"---".equals(stripLineEnding(lines.getFirst()))) {
      throw new IllegalArgumentException("source note must contain YAML frontmatter");
    }
    int closing = -1;
    for (int index = 1; index < lines.size(); index++) {
      if ("---".equals(stripLineEnding(lines.get(index)))) {
        closing = index;
        break;
      }
    }
    if (closing < 0) {
      throw new IllegalArgumentException("source note must contain closed YAML frontmatter");
    }
    String opening = lines.getFirst();
    String newline = opening.endsWith("\r\n") ? "\r\n" : "\n";
    return new Parts(
        opening,
        List.copyOf(lines.subList(1, closing)),
        String.join("", lines.subList(closing, lines.size())),
        newline);
  }

  private static MappingNode parseMapping(String header) {
    Object loaded = load(header);
    if (loaded == null) {
      return null;
    }
    if (!(loaded instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("invalid YAML frontmatter: expected a mapping");
    }
    try {
      Node node = new Compose(settings()).composeString(header).orElse(null);
      if (node == null) {
        return null;
      }
      if (!(node instanceof MappingNode mapping)) {
        throw new IllegalArgumentException("invalid YAML frontmatter: expected a mapping");
      }
      return mapping;
    } catch (RuntimeException error) {
      throw yamlError(error);
    }
  }

  private static Object load(String header) {
    try {
      return new Load(settings()).loadFromString(header);
    } catch (RuntimeException error) {
      throw yamlError(error);
    }
  }

  private static LoadSettings settings() {
    return LoadSettings.builder()
        .setLabel("workflow frontmatter")
        .setAllowDuplicateKeys(false)
        .build();
  }

  private static IllegalArgumentException yamlError(RuntimeException error) {
    if (error instanceof IllegalArgumentException argument
        && argument.getMessage() != null
        && argument.getMessage().startsWith("invalid YAML frontmatter")) {
      return argument;
    }
    return new IllegalArgumentException(
        "invalid YAML frontmatter: " + error.getMessage(), error);
  }

  private static Map<Integer, Range> workflowRanges(MappingNode mapping) {
    if (mapping == null) {
      return Map.of();
    }
    Map<Integer, Range> ranges = new LinkedHashMap<>();
    for (NodeTuple tuple : mapping.getValue()) {
      if (!(tuple.getKeyNode() instanceof ScalarNode key)
          || !FIELD_SET.contains(key.getValue())) {
        continue;
      }
      if (!(tuple.getValueNode() instanceof ScalarNode value)) {
        throw new IllegalArgumentException(
            "workflow field must be a scalar: " + key.getValue());
      }
      int firstLine = key.getStartMark().orElseThrow().getLine();
      int lastLine = value.getEndMark().orElseThrow().getLine();
      if (lastLine > firstLine && value.getEndMark().orElseThrow().getColumn() == 0) {
        lastLine--;
      }
      ranges.put(firstLine, new Range(key.getValue(), Math.max(firstLine, lastLine)));
    }
    return ranges;
  }

  private static void rejectAliasWorkflowKeys(String header) {
    rejectSimpleAliasWorkflowKeys(header);
    try {
      Iterator<Event> events = new Parse(settings()).parseString(header).iterator();
      Event root = nextNodeEvent(events);
      if (root == null) {
        return;
      }
      if (!(root instanceof MappingStartEvent)) {
        return;
      }
      Map<String, String> scalarAnchors = new LinkedHashMap<>();
      while (events.hasNext()) {
        Event key = nextEvent(events);
        if (key instanceof MappingEndEvent) {
          return;
        }
        if (key instanceof AliasEvent alias) {
          String field = scalarAnchors.get(alias.getAlias().getValue());
          if (FIELD_SET.contains(field)) {
            throw new IllegalArgumentException(
                "alias-based workflow key " + field
                    + " is not supported; use an explicit key");
          }
        }
        consumeNode(key, events, scalarAnchors);
        consumeNode(nextEvent(events), events, scalarAnchors);
      }
    } catch (RuntimeException error) {
      if (error instanceof IllegalArgumentException argument
          && argument.getMessage() != null
          && argument.getMessage().startsWith("alias-based workflow key")) {
        throw argument;
      }
      throw yamlError(error);
    }
  }

  private static void rejectSimpleAliasWorkflowKeys(String header) {
    Map<String, String> anchors = new LinkedHashMap<>();
    Matcher anchor = ANCHORED_WORKFLOW_VALUE.matcher(header);
    while (anchor.find()) {
      anchors.put(anchor.group(1), anchor.group(3) == null ? anchor.group(4) : anchor.group(3));
    }
    for (Map.Entry<String, String> entry : anchors.entrySet()) {
      Pattern aliasKey = Pattern.compile(
          "(?m)^[ \\t]*\\*" + Pattern.quote(entry.getKey()) + "[ \\t]*:");
      if (aliasKey.matcher(header).find()) {
        throw new IllegalArgumentException(
            "alias-based workflow key " + entry.getValue()
                + " is not supported; use an explicit key");
      }
    }
  }

  private static Event nextNodeEvent(Iterator<Event> events) {
    while (events.hasNext()) {
      Event event = events.next();
      if (event instanceof AliasEvent
          || event instanceof MappingStartEvent
          || event instanceof ScalarEvent
          || event instanceof SequenceStartEvent) {
        return event;
      }
    }
    return null;
  }

  private static Event nextEvent(Iterator<Event> events) {
    if (!events.hasNext()) {
      throw new IllegalArgumentException("invalid YAML frontmatter: incomplete event stream");
    }
    return events.next();
  }

  private static void consumeNode(
      Event event,
      Iterator<Event> events,
      Map<String, String> scalarAnchors) {
    if (event instanceof ScalarEvent scalar) {
      if (scalar.getAnchor().isPresent()
          && (scalar.getTag().isEmpty()
              || Tag.STR.getValue().equals(scalar.getTag().orElseThrow()))) {
        scalarAnchors.put(scalar.getAnchor().orElseThrow().getValue(), scalar.getValue());
      }
      return;
    }
    if (event instanceof AliasEvent) {
      return;
    }
    if (event instanceof SequenceStartEvent) {
      while (true) {
        Event item = nextEvent(events);
        if (item instanceof SequenceEndEvent) {
          return;
        }
        consumeNode(item, events, scalarAnchors);
      }
    }
    if (event instanceof MappingStartEvent) {
      while (true) {
        Event key = nextEvent(events);
        if (key instanceof MappingEndEvent) {
          return;
        }
        consumeNode(key, events, scalarAnchors);
        consumeNode(nextEvent(events), events, scalarAnchors);
      }
    }
    throw new IllegalArgumentException(
        "invalid YAML frontmatter: unexpected " + event.getEventId() + " event");
  }

  private static List<String> splitLines(String content) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < content.length(); index++) {
      if (content.charAt(index) == '\n') {
        lines.add(content.substring(start, index + 1));
        start = index + 1;
      }
    }
    if (start < content.length()) {
      lines.add(content.substring(start));
    }
    return lines;
  }

  private static String stripLineEnding(String line) {
    if (line.endsWith("\r\n")) {
      return line.substring(0, line.length() - 2);
    }
    return line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
  }

  private static String lineEnding(String line) {
    if (line.endsWith("\r\n")) {
      return "\r\n";
    }
    return line.endsWith("\n") ? "\n" : "";
  }

  private static String quote(String value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("workflow value cannot be quoted", error);
    }
  }

  private record Parts(
      String opening,
      List<String> headerLines,
      String remainder,
      String newline) { }

  private record Range(String field, int lastLine) { }
}
