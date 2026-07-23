package dev.eugene.astroexport.translation;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.review.ReviewWorkspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates reviewed English patches and merges them into localized manifest entries. */
public final class TranslationValidator {
  private static final Set<String> CONTROL_FIELDS = Set.of(
      "translationStatus", "translatedAt", "translationProfile");
  private static final Set<String> OBJECT_REFERENCE_FIELDS = Set.of("paths", "routes");
  private static final Pattern INTERNAL_RU_ROUTE = Pattern.compile("(?<![\\p{Alnum}_])/ru/");

  public static ManifestResult buildEnglishManifest(ManifestResult russian, Path reviewRoot) {
    List<ManifestEntry> entries = new ArrayList<>();
    for (ManifestEntry entry : russian.entries()) {
      Target target = target(entry);
      validateRussianEnvelope(entry, target.publicId());
      TranslationPatch patch;
      try {
        patch = ReviewWorkspace.loadEnglishPatch(reviewRoot, entry);
      } catch (RuntimeException error) {
        if (error instanceof TranslationValidationException validation) {
          throw validation;
        }
        fail(entry, target.publicId(), error.getMessage());
        throw new AssertionError("unreachable");
      }
      if (!patch.sourceHash().equals(TranslationProjection.translationSourceHash(entry))) {
        fail(entry, target.publicId(), "stale review: sourceHash does not match translation sourceHash");
      }
      entries.add(englishEntry(entry, target, patch));
    }
    return new ManifestResult(
        entries, russian.retainedLinks(), russian.strippedLinks(), russian.assets());
  }

  private static ManifestEntry englishEntry(
      ManifestEntry entry,
      Target target,
      TranslationPatch patch) {
    String body = MarkdownScanner.stripObsidianComments(patch.body());
    Map<String, Object> translated = materializeReferences(entry, target.publicId(), patch);
    Map<String, Object> metadata = mergeMap(
        entry, target.publicId(), entry.metadata(), translated, "metadata");
    metadata.put("language", "en");
    metadata.put("sourceLanguage", "ru");
    metadata.put("translationOf", target.publicId());
    metadata.put("sourceHash", entry.metadata().get("sourceHash"));
    metadata.put("translationStatus", patch.translationStatus());
    metadata.put("translatedAt", patch.translatedAt());
    metadata.put("translationProfile", patch.translationProfile());

    if (Set.of("blog", "bibliography", "concepts").contains(target.collection())
        && !entry.body().isBlank()
        && body.isBlank()) {
      fail(entry, target.publicId(), target.collection() + " body must be non-empty");
    }
    if ("concepts".equals(target.collection()) && !hasVisibleDefinition(body)) {
      fail(entry, target.publicId(), "concept body must contain a non-empty Definition section");
    }

    String targetPath = localizeRequired(
        entry, target.publicId(), entry.targetPath(), "target path");
    String route = localizeRequired(entry, target.publicId(), entry.route(), "route");
    rejectInternalRussianRoute(entry, target.publicId(), targetPath, "targetPath");
    rejectInternalRussianRoute(entry, target.publicId(), route, "route");
    rejectInternalRussianRoute(entry, target.publicId(), metadata, "metadata");
    rejectInternalRussianRoute(entry, target.publicId(), body, "body");
    return new ManifestEntry(entry.sourcePath(), targetPath, route, metadata, body);
  }

  private static Map<String, Object> materializeReferences(
      ManifestEntry entry,
      String publicId,
      TranslationPatch patch) {
    LinkedHashMap<String, Object> translated = deepMap(patch.metadata());
    for (String field : OBJECT_REFERENCE_FIELDS) {
      if (!entry.metadata().containsKey(field)) {
        continue;
      }
      Object sourceValue = entry.metadata().get(field);
      if (!TranslationProjection.hasTranslationLeaf(sourceValue, field)) {
        translated.remove(field);
        continue;
      }
      if (translated.containsKey(field)) {
        fail(
            entry,
            publicId,
            "metadata." + field + " must be stored in referenceTranslations");
      }
      Object catalogValue = patch.referenceTranslations().get(field);
      if (!(catalogValue instanceof Map<?, ?>)) {
        fail(entry, publicId, "referenceTranslations has missing fields: " + field);
      }
      Map<?, ?> catalog = (Map<?, ?>) catalogValue;
      if (!(sourceValue instanceof List<?>)) {
        fail(entry, publicId, "metadata." + field + " must be a list");
      }
      List<?> items = (List<?>) sourceValue;
      List<Object> visible = new ArrayList<>();
      for (Object itemValue : items) {
        if (!(itemValue instanceof Map<?, ?>)) {
          fail(entry, publicId, "metadata." + field + " items must be objects");
        }
        Map<?, ?> item = (Map<?, ?>) itemValue;
        Object reference = item.get("route");
        if (!(reference instanceof String)) {
          fail(entry, publicId, "metadata." + field + " route must be a string");
        }
        String route = (String) reference;
        if (!catalog.containsKey(route)) {
          fail(
              entry,
              publicId,
              "referenceTranslations." + field + " has missing references: " + route);
        }
        visible.add(mergeMap(
            entry,
            publicId,
            stringMap(item, "metadata." + field),
            catalog.get(route),
            "referenceTranslations." + field + "." + route));
      }
      translated.put(field, visible);
    }
    return translated;
  }

  private static Map<String, Object> mergeMap(
      ManifestEntry entry,
      String publicId,
      Map<String, Object> source,
      Object translatedValue,
      String path) {
    if (!(translatedValue instanceof Map<?, ?>)) {
      fail(entry, publicId, path + " must be an object");
    }
    Map<?, ?> translatedRaw = (Map<?, ?>) translatedValue;
    Map<String, Object> translated = stringMap(translatedRaw, path);
    LinkedHashSet<String> expected = new LinkedHashSet<>();
    for (Map.Entry<String, Object> sourceEntry : source.entrySet()) {
      String key = sourceEntry.getKey();
      if ((TranslationProjection.hasTranslationLeaf(sourceEntry.getValue(), key)
          || containsReferenceToken(sourceEntry.getValue()))
          && (!OBJECT_REFERENCE_FIELDS.contains(key) || translated.containsKey(key))) {
        expected.add(key);
      }
    }
    LinkedHashSet<String> missing = new LinkedHashSet<>(expected);
    missing.removeAll(translated.keySet());
    LinkedHashSet<String> unexpected = new LinkedHashSet<>(translated.keySet());
    unexpected.removeAll(expected);
    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      List<String> problems = new ArrayList<>();
      if (!missing.isEmpty()) {
        problems.add("missing fields: " + String.join(", ", missing.stream().sorted().toList()));
      }
      if (!unexpected.isEmpty()) {
        problems.add("unexpected fields: " + String.join(", ", unexpected.stream().sorted().toList()));
      }
      fail(entry, publicId, path + " has " + String.join("; ", problems));
    }

    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    for (Map.Entry<String, Object> sourceEntry : source.entrySet()) {
      String key = sourceEntry.getKey();
      Object sourceValue = sourceEntry.getValue();
      if (OBJECT_REFERENCE_FIELDS.contains(key) && translated.containsKey(key)) {
        merged.put(key, localizeInherited(translated.get(key)));
      } else if (expected.contains(key)) {
        merged.put(
            key,
            mergeValue(
                entry, publicId, sourceValue, translated.get(key), path + "." + key));
      } else {
        merged.put(key, localizeInherited(sourceValue));
      }
    }
    return merged;
  }

  private static Object mergeValue(
      ManifestEntry entry,
      String publicId,
      Object source,
      Object translated,
      String path) {
    if (TranslationProjection.isTextToken(source)) {
      if (!TranslationProjection.isTextToken(translated)
          || ((String) ((Map<?, ?>) translated).get("value")).isEmpty()) {
        fail(entry, publicId, path + " must provide a non-empty translated text token");
      }
      return deepCopy(translated);
    }
    if (TranslationProjection.isReferenceToken(source)) {
      if (!TranslationProjection.isReferenceToken(translated)
          || !Objects.equals(
              ((Map<?, ?>) source).get("target"),
              ((Map<?, ?>) translated).get("target"))) {
        fail(entry, publicId, path + ".target must remain invariant");
      }
      return localizeInherited(source);
    }
    if (source instanceof Map<?, ?> sourceMap) {
      return mergeMap(
          entry, publicId, stringMap(sourceMap, path), translated, path);
    }
    if (source instanceof List<?> sourceList) {
      if (!(translated instanceof List<?>)) {
        fail(entry, publicId, path + " must be a list");
      }
      List<?> translatedList = (List<?>) translated;
      if (sourceList.size() != translatedList.size()) {
        fail(entry, publicId, path + " must keep the same length as RU metadata");
      }
      List<Object> merged = new ArrayList<>();
      for (int index = 0; index < sourceList.size(); index++) {
        merged.add(mergeValue(
            entry,
            publicId,
            sourceList.get(index),
            translatedList.get(index),
            path + "[" + index + "]"));
      }
      return List.copyOf(merged);
    }
    if (!sameScalarType(source, translated)) {
      fail(entry, publicId, path + " must keep type " + source.getClass().getSimpleName());
    }
    return deepCopy(translated);
  }

  private static boolean sameScalarType(Object source, Object translated) {
    if (source == null || translated == null) {
      return source == translated;
    }
    if (source instanceof Number && translated instanceof Number) {
      return true;
    }
    return source.getClass().equals(translated.getClass());
  }

  private static boolean containsReferenceToken(Object value) {
    if (TranslationProjection.isReferenceToken(value)) {
      return true;
    }
    if (value instanceof Map<?, ?> map) {
      return map.values().stream().anyMatch(TranslationValidator::containsReferenceToken);
    }
    if (value instanceof List<?> list) {
      return list.stream().anyMatch(TranslationValidator::containsReferenceToken);
    }
    return false;
  }

  private static Object localizeInherited(Object value) {
    if (value instanceof String string) {
      return string.replace("/ru/", "/en/");
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> localized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        localized.put(String.valueOf(entry.getKey()), localizeInherited(entry.getValue()));
      }
      return localized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(TranslationValidator::localizeInherited).toList();
    }
    return value;
  }

  private static void rejectInternalRussianRoute(
      ManifestEntry entry,
      String publicId,
      Object value,
      String path) {
    String found = internalRussianRoutePath(value, path);
    if (found != null) {
      fail(entry, publicId, found + " contains an internal /ru/ route");
    }
  }

  private static String internalRussianRoutePath(Object value, String path) {
    if (value instanceof String string) {
      return INTERNAL_RU_ROUTE.matcher(string).find() ? path : null;
    }
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String found = internalRussianRoutePath(
            entry.getValue(), path + "." + entry.getKey());
        if (found != null) {
          return found;
        }
      }
    }
    if (value instanceof List<?> list) {
      for (int index = 0; index < list.size(); index++) {
        String found = internalRussianRoutePath(list.get(index), path + "[" + index + "]");
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static boolean hasVisibleDefinition(String body) {
    return MarkdownScanner.section(body, "Definition")
        .map(MarkdownScanner::maskProtectedContexts)
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .isPresent();
  }

  private static String localizeRequired(
      ManifestEntry entry,
      String publicId,
      String value,
      String label) {
    String localized = value.replace("/ru/", "/en/");
    if (localized.equals(value)) {
      fail(entry, publicId, "RU " + label + " must contain /ru/");
    }
    return localized;
  }

  private static void validateRussianEnvelope(ManifestEntry entry, String publicId) {
    for (String field : List.of("language", "sourceLanguage")) {
      if (!"ru".equals(entry.metadata().get(field))) {
        fail(entry, publicId, "RU " + field + " must be ru");
      }
    }
  }

  private static Target target(ManifestEntry entry) {
    Object id = entry.metadata().get("id");
    if (!(id instanceof String publicId) || publicId.isBlank()) {
      fail(entry, String.valueOf(id == null ? "<missing>" : id), "RU id must be a non-empty string");
    }
    String[] parts = entry.targetPath().split("/");
    if (parts.length == 5
        && "src".equals(parts[0])
        && "content".equals(parts[1])
        && "ru".equals(parts[3])) {
      return new Target(((String) id).strip(), parts[2]);
    }
    if (parts.length == 5
        && "src".equals(parts[0])
        && "data".equals(parts[1])
        && "pages".equals(parts[2])
        && "ru".equals(parts[3])) {
      return new Target(((String) id).strip(), "editorial");
    }
    fail(entry, (String) id, "unsupported RU target path " + entry.targetPath());
    throw new AssertionError("unreachable");
  }

  private static Map<String, Object> stringMap(Map<?, ?> map, String path) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(path + " keys must be strings");
      }
      values.put(key, entry.getValue());
    }
    return values;
  }

  private static LinkedHashMap<String, Object> deepMap(Map<String, Object> source) {
    @SuppressWarnings("unchecked")
    LinkedHashMap<String, Object> copy = (LinkedHashMap<String, Object>) deepCopy(source);
    return copy;
  }

  private static Object deepCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
      }
      return copy;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(TranslationValidator::deepCopy).toList();
    }
    return value;
  }

  private static void fail(ManifestEntry entry, String publicId, String reason) {
    throw new TranslationValidationException(entry.sourcePath(), publicId, reason);
  }

  private record Target(String publicId, String collection) { }

  public static final class TranslationValidationException extends IllegalArgumentException {
    private final String sourcePath;
    private final String publicId;
    private final String reason;

    TranslationValidationException(String sourcePath, String publicId, String reason) {
      super(sourcePath + ": " + publicId + ": " + reason);
      this.sourcePath = sourcePath;
      this.publicId = publicId;
      this.reason = reason;
    }

    public String sourcePath() {
      return sourcePath;
    }

    public String publicId() {
      return publicId;
    }

    public String reason() {
      return reason;
    }
  }
}
