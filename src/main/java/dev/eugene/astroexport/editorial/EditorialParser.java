package dev.eugene.astroexport.editorial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the deliberately small Markdown grammar used by curated editorial pages. */
public final class EditorialParser {
  private static final Set<String> PAGES = Set.of("home", "essays", "claims", "notes", "music", "library", "concepts", "now", "about");
  private static final Pattern H2 = Pattern.compile("^##[ \\t]+(.+?)[ \\t]*$");
  private static final Pattern H3 = Pattern.compile("^###[ \\t]+(.+?)[ \\t]*$");
  private static final Pattern INLINE = Pattern.compile("^[ \\t]*([^:\\n]+?)[ \\t]*::[ \\t]*(.*?)[ \\t]*$");
  private static final Pattern LIST = Pattern.compile("^[ \\t]*-[ \\t]+(.+?)[ \\t]*$");
  private static final Pattern WIKILINK = Pattern.compile("^(!?)\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]$");

  public Map<String, Object> normalize(String sourcePath, String publicId, Map<String, Object> frontmatter,
                                       String body, Map<String, Object> common) {
    Object pageValue = frontmatter.get("editorialPage");
    if (!(pageValue instanceof String page) || !PAGES.contains(page)) fail(sourcePath, "editorialPage", "must be one of: about, claims, concepts, essays, home, library, music, notes, now");
    String page = (String) pageValue;
    if (!page.equals(publicId)) fail(sourcePath, "editorialPage", "must match publicId");
    Object searchableValue = frontmatter.getOrDefault("publicSearchable", false);
    if (!(searchableValue instanceof Boolean)) fail(sourcePath, "searchable", "must be a YAML boolean");

    Document document = parse(body);
    Map<String, Object> result = new LinkedHashMap<>(common);
    result.put("type", switch (page) { case "home" -> "home"; case "now" -> "now"; case "about" -> "about"; default -> "index"; });
    result.put("searchable", searchableValue);
    result.put("summary", text(section(document, "Кратко", sourcePath, "summary").leading, sourcePath, "summary"));
    result.put("eyebrow", text(section(document, "Eyebrow", sourcePath, "eyebrow").leading, sourcePath, "eyebrow"));
    switch (page) {
      case "home" -> result.putAll(home(document, sourcePath));
      case "essays" -> result.putAll(essays(document, sourcePath));
      case "claims" -> result.putAll(showcase(document, sourcePath));
      case "notes" -> result.putAll(notes(document, sourcePath));
      case "music" -> result.putAll(music(document, sourcePath));
      case "library" -> result.putAll(showcase(document, sourcePath));
      case "concepts" -> result.putAll(concepts(document, sourcePath));
      case "now" -> result.putAll(now(document, sourcePath, frontmatter));
      case "about" -> result.putAll(about(document, sourcePath));
      default -> throw new IllegalStateException(page);
    }
    return result;
  }

  private static Map<String, Object> home(Document document, String path) {
    Section hero = section(document, "Hero", path, "heroTitle");
    Section current = section(document, "Сейчас", path, "current");
    if (hasContent(current.leading)) fail(path, "current", "must contain only card subsections");
    String[][] cards = {{"Изучаю", "studying", "text"}, {"Создаю", "building", "text"}, {"Читаю", "reading", "book"}, {"Слушаю", "listening", "album"}};
    if (current.children.size() != cards.length) fail(path, "current", "must contain exactly four card subsections");
    List<Map<String, Object>> values = new ArrayList<>();
    for (int i = 0; i < cards.length; i++) {
      Child child = current.children.get(i);
      if (!cards[i][0].equals(child.title)) fail(path, "current[" + i + "]", "requires heading `### " + cards[i][0] + "`");
      List<String> paragraphs = paragraphs(child.lines);
      if (paragraphs.size() != 2) fail(path, "current[" + i + "]", "must contain exactly a title paragraph and a description paragraph");
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("key", cards[i][1]); item.put("label", cards[i][0]); item.put("layout", cards[i][2]);
      Matcher link = WIKILINK.matcher(paragraphs.getFirst());
      if (link.matches() && !link.group(1).isEmpty()) fail(path, "current[" + i + "].title", "must not be an embedded wikilink");
      item.put("title", link.matches() && link.group(3) != null ? link.group(3).strip() : link.matches() ? link.group(2).strip() : paragraphs.getFirst());
      item.put("text", paragraphs.get(1));
      if (link.matches()) item.put("target", link.group(2).strip());
      values.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("heroTitle", text(child(hero, "Заголовок", path, "heroTitle").lines, path, "heroTitle"));
    result.put("lead", text(child(hero, "Лид", path, "lead").lines, path, "lead"));
    result.put("heroImageAlt", text(child(hero, "Описание изображения", path, "heroImageAlt").lines, path, "heroImageAlt"));
    result.put("currentTitle", current.title); result.put("current", values);
    return result;
  }

  private static Map<String, Object> essays(Document document, String path) {
    Section principle = section(document, "Принцип списка", path, "listPrincipleTitle");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("listPrincipleTitle", principle.title);
    result.put("listPrincipleText", text(principle.leading, path, "listPrincipleText"));
    result.put("searchPlaceholder", inline(principle.allLines(), "Подсказка поиска", path, "searchPlaceholder"));
    result.putAll(showcase(document, path));
    List<Section> starts = document.named("Начать здесь");
    if (starts.size() > 1) fail(path, "startTitle", "requires at most one heading `## Начать здесь`");
    if (!starts.isEmpty()) { result.put("startTitle", starts.getFirst().title); if (hasContent(starts.getFirst().leading)) result.put("startText", text(starts.getFirst().leading, path, "startText")); }
    return result;
  }

  private static Map<String, Object> notes(Document document, String path) {
    Section groups = section(document, "Три типа заметок", path, "groups");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("heading", groups.title); result.put("groups", list(groups, path, "groups")); result.putAll(showcase(document, path));
    result.put("libraryAction", inline(groups.allLines(), "Действие библиотеки", path, "libraryAction"));
    result.put("conceptsAction", inline(groups.allLines(), "Действие концептов", path, "conceptsAction"));
    return result;
  }

  private static Map<String, Object> music(Document document, String path) {
    Map<String, Object> result = new LinkedHashMap<>(); result.put("intro", text(section(document, "Введение", path, "intro").leading, path, "intro")); result.putAll(showcase(document, path)); return result;
  }

  private static Map<String, Object> concepts(Document document, String path) {
    Section primary = section(document, "Базовый концепт", path, "primary");
    Map<String, Object> result = new LinkedHashMap<>(); result.put("primaryLabel", inline(primary.allLines(), "Метка", path, "primaryLabel")); result.putAll(showcase(document, path));
    String material = optionalInline(primary.allLines(), "Материал", path, "primary"); if (material != null) result.put("primary", material); return result;
  }

  private static Map<String, Object> now(Document document, String path, Map<String, Object> frontmatter) {
    Section updated = section(document, "Обновлено", path, "updatedLabel"); int start = document.sections.indexOf(updated) + 1;
    if (start == document.sections.size()) fail(path, "sections", "must contain at least one section after `## Обновлено`");
    List<Map<String, Object>> sections = new ArrayList<>(); List<String> actionLines = new ArrayList<>();
    for (int i = start; i < document.sections.size(); i++) {
      Section item = document.sections.get(i); actionLines.addAll(item.allLines());
      if (item.children.size() != 1) fail(path, "sections[" + (i - start) + "].title", "requires exactly one H3 heading");
      Child child = item.children.getFirst(); if (child.title.isEmpty()) fail(path, "sections[" + (i - start) + "].title", "must be non-empty");
      sections.add(Map.of("label", item.title, "title", child.title, "text", text(child.lines, path, "sections[" + (i - start) + "].text")));
    }
    String date = normalizeDate(frontmatter.get("date")); if (date == null) fail(path, "date", "must be provided for the now page");
    Object status = frontmatter.get("status"); if (!(status instanceof String value) || value.strip().isEmpty()) fail(path, "status", "must be a non-empty string for the now page");
    Map<String, Object> result = new LinkedHashMap<>(); result.put("date", date); result.put("status", ((String) status).strip()); result.put("updatedLabel", text(updated.leading, path, "updatedLabel")); result.put("sections", sections);
    result.put("questionAction", inline(actionLines, "Действие вопроса", path, "questionAction")); result.put("listeningAction", inline(actionLines, "Действие записи", path, "listeningAction")); return result;
  }

  private static Map<String, Object> about(Document document, String path) {
    Section principles = section(document, "Принципы", path, "principles"); if (principles.children.isEmpty()) fail(path, "principles", "must contain at least one H3 and prose pair");
    List<List<String>> values = new ArrayList<>(); for (int i = 0; i < principles.children.size(); i++) { Child child = principles.children.get(i); values.add(List.of(child.title, text(child.lines, path, "principles[" + i + "][1]"))); }
    return Map.of("lead", text(section(document, "Лид", path, "lead").leading, path, "lead"), "principles", values, "colophon", text(section(document, "Колофон", path, "colophon").leading, path, "colophon"));
  }

  private static Map<String, Object> showcase(Document document, String path) {
    List<Section> found = document.named("Витрина"); if (found.isEmpty()) return Map.of("showcase", List.of(), "pinned", List.of()); if (found.size() != 1) fail(path, "showcase", "requires at most one ## Витрина section");
    Section section = found.getFirst(); if (hasContent(section.leading)) fail(path, "showcase", "must contain only `### [[target]]` entries"); if (section.children.isEmpty()) fail(path, "showcase", "must contain at least one `### [[target]]` entry");
    List<Map<String, String>> values = new ArrayList<>(); List<String> pinned = new ArrayList<>();
    for (int i = 0; i < section.children.size(); i++) { Child child = section.children.get(i); Matcher match = WIKILINK.matcher(child.title); if (!match.matches() || !match.group(1).isEmpty()) fail(path, "showcase[" + i + "].target", "must be one non-embedded wikilink");
      for (String line : child.lines) if (line.strip().matches("^(?:[-+*]\\s+|\\d+[.)]\\s+|#{1,6}(?:\\s|$)|>|`{3,}|~{3,}|\\|.*\\||(?:---|\\*\\*\\*|___)\\s*$).*")) fail(path, "showcase[" + i + "].text", "must contain prose only");
      String target = match.group(2).strip(); values.add(Map.of("target", target, "text", text(child.lines, path, "showcase[" + i + "].text"))); pinned.add(target); }
    Map<String, Object> result = new LinkedHashMap<>(); result.put("showcase", values); result.put("pinned", pinned); return result;
  }

  private static Document parse(String body) { List<Section> sections = new ArrayList<>(); Section current = null; Child child = null; for (String line : body.split("\\R", -1)) { Matcher h2 = H2.matcher(line), h3 = H3.matcher(line); if (h2.matches()) { current = new Section(h2.group(1).strip()); sections.add(current); child = null; } else if (h3.matches() && current != null) { child = new Child(h3.group(1).strip()); current.children.add(child); } else if (child != null) child.lines.add(line); else if (current != null) current.leading.add(line); } return new Document(sections); }
  private static Section section(Document document, String heading, String path, String target) { List<Section> found = document.named(heading); if (found.isEmpty()) fail(path, target, "requires heading `## " + heading + "`"); if (found.size() != 1) fail(path, target, "requires exactly one heading `## " + heading + "`"); return found.getFirst(); }
  private static Child child(Section section, String heading, String path, String target) { List<Child> found = section.children.stream().filter(child -> child.title.equals(heading)).toList(); if (found.isEmpty()) fail(path, target, "requires heading `### " + heading + "`"); if (found.size() != 1) fail(path, target, "requires exactly one heading `### " + heading + "`"); return found.getFirst(); }
  private static String text(List<String> lines, String path, String target) { String value = String.join("\n\n", paragraphs(lines)).strip(); if (value.isEmpty()) fail(path, target, "must contain non-empty prose"); return value; }
  private static List<String> paragraphs(List<String> lines) { List<String> result = new ArrayList<>(), current = new ArrayList<>(); for (String line : lines) { if (line.strip().isEmpty()) { finish(result, current); } else if (INLINE.matcher(line).matches()) { finish(result, current); } else current.add(line.strip()); } finish(result, current); return result; }
  private static void finish(List<String> result, List<String> current) { if (!current.isEmpty()) { result.add(String.join(" ", current)); current.clear(); } }
  private static String inline(List<String> lines, String name, String path, String target) { List<String> values = inlineValues(lines, name); if (values.isEmpty()) fail(path, target, "requires inline field `" + name + "::`"); if (values.size() != 1) fail(path, target, "requires exactly one inline field `" + name + "::`"); if (values.getFirst().isEmpty()) fail(path, target, "inline field `" + name + "::` must be non-empty"); return values.getFirst(); }
  private static String optionalInline(List<String> lines, String name, String path, String target) { List<String> values = inlineValues(lines, name); if (values.isEmpty()) return null; if (values.size() != 1) fail(path, target, "requires at most one inline field `" + name + "::`"); if (values.getFirst().isEmpty()) fail(path, target, "inline field `" + name + "::` must be non-empty"); return values.getFirst(); }
  private static List<String> inlineValues(List<String> lines, String name) { List<String> values = new ArrayList<>(); for (String line : lines) { Matcher match = INLINE.matcher(line); if (match.matches() && match.group(1).strip().equals(name)) values.add(match.group(2).strip()); } return values; }
  private static List<String> list(Section section, String path, String target) { if (!section.children.isEmpty()) fail(path, target, "must be a flat bullet list"); List<String> result = new ArrayList<>(); int index = 0; for (String line : section.leading) { if (line.strip().isEmpty() || INLINE.matcher(line).matches()) continue; Matcher match = LIST.matcher(line); if (!match.matches() || match.group(1).strip().isEmpty()) fail(path, target + "[" + index + "]", "must be a non-empty bullet list row"); result.add(match.group(1).strip()); index++; } if (result.isEmpty()) fail(path, target, "must contain at least one bullet list row"); return result; }
  private static boolean hasContent(List<String> lines) { return lines.stream().anyMatch(line -> !line.strip().isEmpty()); }
  private static String normalizeDate(Object value) {
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    String text = value.toString().strip();
    if (text.startsWith("[[") && text.endsWith("]]")) {
      text = text.substring(2, text.length() - 2).split("[|#]", 2)[0].strip();
    }
    return text;
  }
  private static void fail(String path, String field, String reason) { throw new ManifestValidationException(path, field, reason); }
  private record Document(List<Section> sections) { List<Section> named(String title) { return sections.stream().filter(section -> section.title.equals(title)).toList(); } }
  private static final class Section { final String title; final List<String> leading = new ArrayList<>(); final List<Child> children = new ArrayList<>(); Section(String title) { this.title = title; } List<String> allLines() { List<String> result = new ArrayList<>(leading); for (Child child : children) result.addAll(child.lines); return result; } }
  private static final class Child { final String title; final List<String> lines = new ArrayList<>(); Child(String title) { this.title = title; } }
  public static final class ManifestValidationException extends IllegalArgumentException { private final String sourcePath; private final String fieldName; private final String reason; public ManifestValidationException(String sourcePath, String fieldName, String reason) { super(sourcePath + ": " + fieldName + " " + reason); this.sourcePath = sourcePath; this.fieldName = fieldName; this.reason = reason; } public String sourcePath() { return sourcePath; } public String fieldName() { return fieldName; } public String reason() { return reason; } }
}
