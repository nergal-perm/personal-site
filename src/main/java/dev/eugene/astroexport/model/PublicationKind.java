package dev.eugene.astroexport.model;

import dev.eugene.astroexport.validation.RequirementValidator;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public enum PublicationKind {
  BLOG_ESSAY("blog", "essay", selectionRequirements()),
  BLOG_CLAIM("blog", "claim", with(selectionRequirements(), requirement(List.of("statement", "description"),
      "must be a non-empty string", "непустая строка хотя бы в одном из этих полей", "frontmatter",
      RequirementValidator.ONE_NON_EMPTY_STRING))),
  BLOG_NOTE("blog", "note", selectionRequirements()),
  BIBLIOGRAPHY_BOOK("bibliography", "book", with(selectionRequirements(), requirement(List.of("authors", "author"),
      "must contain at least one non-empty string", "хотя бы одно непустое имя автора", "frontmatter",
      RequirementValidator.ONE_NON_EMPTY_STRING_OR_LIST))),
  MUSIC_ALBUM("music", "album", with(selectionRequirements(),
      requirement(List.of("artist"), "must be a non-empty string", "непустое имя исполнителя", "frontmatter",
          RequirementValidator.NON_EMPTY_STRING),
      requirement(List.of("work", "albumTitle"), "must be a non-empty string", "непустое название хотя бы в одном из этих полей", "frontmatter",
          RequirementValidator.ONE_NON_EMPTY_STRING),
      requirement(List.of("Контекст записи"), "must be a non-empty string", "одноимённый раздел с непустым текстом", "body",
          RequirementValidator.BODY_SECTION),
      requirement(List.of("Личная связь"), "must be a non-empty string", "одноимённый раздел с непустым текстом", "body",
          RequirementValidator.BODY_SECTION))),
  CONCEPTS_CONCEPT("concepts", "concept", with(selectionRequirements(),
      requirement(List.of("description"), "must be a non-empty string", "непустая строка с кратким публичным описанием", "frontmatter",
          RequirementValidator.NON_EMPTY_STRING),
      requirement(List.of("Определение"), "must be a non-empty section", "непустой раздел `Определение` с публичным определением", "body",
          RequirementValidator.BODY_SECTION))),
  EDITORIAL_CURATED_PAGE("editorial", "curated_page", with(selectionRequirements(),
      requirement(List.of("editorialPage"), "must name an editorial page", "имя поддерживаемой editorial-страницы", "frontmatter",
          RequirementValidator.EDITORIAL_PAGE),
      requirement(List.of("editorial body"), "validated by editorial.py grammar", "структура по грамматике editorial-страницы", "body",
          RequirementValidator.EDITORIAL_BODY)));

  private final String collection;
  private final String contentType;
  private final List<PublicationRequirement> requirements;

  PublicationKind(String collection, String contentType, List<PublicationRequirement> requirements) {
    this.collection = collection;
    this.contentType = contentType;
    this.requirements = List.copyOf(requirements);
  }

  public String collection() { return collection; }
  public String contentType() { return contentType; }
  public List<PublicationRequirement> requirements() { return requirements; }

  public static List<PublicationKind> all() { return List.of(values()); }

  public static List<PublicationRequirement> requirementsFor(String collection, String contentType) {
    return Arrays.stream(values())
        .filter(kind -> kind.collection.equals(collection) && kind.contentType.equals(contentType))
        .findFirst().map(PublicationKind::requirements).orElse(List.of());
  }

  public static Set<String> allowedCollections() {
    Set<String> collections = new TreeSet<>();
    for (PublicationKind kind : values()) collections.add(kind.collection);
    return Set.copyOf(collections);
  }

  public static Set<String> allowedContentTypes(String collection) {
    Set<String> contentTypes = new TreeSet<>();
    for (PublicationKind kind : values()) if (kind.collection.equals(collection)) contentTypes.add(kind.contentType);
    return Set.copyOf(contentTypes);
  }

  private static List<PublicationRequirement> selectionRequirements() {
    return List.of(
        requirement(List.of("publish"), "must be true", "логическое значение `true`; намерение публиковать задаёт только автор", "frontmatter", RequirementValidator.REQUIRED_TRUE),
        requirement(List.of("publicId"), "lowercase route slug", "уникальный slug из строчных латинских букв, цифр и внутренних дефисов", "frontmatter", RequirementValidator.ROUTE_SLUG),
        requirement(List.of("publicCollection"), "must match the publication collection", "точное имя коллекции из заголовка этого раздела", "frontmatter", RequirementValidator.COLLECTION),
        requirement(List.of("publicContentType"), "must match the publication kind", "точный тип материала из заголовка этого раздела", "frontmatter", RequirementValidator.CONTENT_TYPE));
  }

  private static List<PublicationRequirement> with(List<PublicationRequirement> base, PublicationRequirement... additions) {
    return java.util.stream.Stream.concat(base.stream(), Arrays.stream(additions)).toList();
  }

  private static PublicationRequirement requirement(List<String> fields, String expectation, String authorExpectation,
      String source, RequirementValidator validator) {
    return new PublicationRequirement(fields, expectation, authorExpectation, source, validator);
  }
}
