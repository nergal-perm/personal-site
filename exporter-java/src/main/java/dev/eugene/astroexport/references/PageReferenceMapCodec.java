package dev.eugene.astroexport.references;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.exc.StreamReadException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable codec for semantic-reference sidecar documents. */
public final class PageReferenceMapCodec {
  private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

  private PageReferenceMapCodec() { }

  public static PageReferenceMap read(byte[] content, String source) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(content, Map.class);
      return new PageReferenceMap(
          requiredInt(payload.get("schemaVersion"), "schemaVersion"),
          requiredString(payload.get("pageRef"), "pageRef"),
          requiredString(payload.get("sourcePath"), "sourcePath"),
          requiredString(payload.get("ruSha256"), "ruSha256"),
          requiredString(payload.get("enSha256"), "enSha256"),
          requiredStringList(payload.get("order"), "order"),
          requiredReferenceMap(payload.get("references"), "references"));
    } catch (StreamReadException error) {
      throw new ReferenceValidationException("duplicate-key", source + ": " + error.getOriginalMessage());
    } catch (Exception error) {
      throw new ReferenceValidationException("invalid-json", source + ": " + error.getMessage());
    }
  }

  public static byte[] write(PageReferenceMap map) {
    try {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", map.schemaVersion());
      payload.put("pageRef", map.pageRef());
      payload.put("sourcePath", map.sourcePath());
      payload.put("ruSha256", map.ruSha256());
      payload.put("enSha256", map.enSha256());
      payload.put("order", map.order());
      payload.put("references", writeReferences(map));
      return JSON.writeValueAsBytes(payload);
    } catch (Exception error) {
      throw new IllegalStateException("cannot serialize reference map: " + error.getMessage(), error);
    }
  }

  public static void validate(PageReferenceMap map, byte[] russian, byte[] english) {
    if (map.schemaVersion() != PageReferenceMap.SCHEMA_VERSION) {
      throw new ReferenceValidationException("invalid-schema-version", "schemaVersion must be " + PageReferenceMap.SCHEMA_VERSION);
    }
    if (map.pageRef() == null || map.pageRef().isBlank()) {
      throw new ReferenceValidationException("missing-page-ref", "pageRef must be a non-blank string");
    }
    if (!normalizedRelativePath(map.sourcePath())) {
      throw new ReferenceValidationException("invalid-source-path", "sourcePath must be a normalized relative path");
    }
    if (!sha256(russian).equals(map.ruSha256())) {
      throw new ReferenceValidationException("ru-sha256-mismatch", "ruSha256 does not match Russian payload");
    }
    if (!sha256(english).equals(map.enSha256())) {
      throw new ReferenceValidationException("en-sha256-mismatch", "enSha256 does not match English payload");
    }

    List<String> ruOrder = SemanticReferenceMarkdown.occurrences(bytesToString(russian)).stream()
        .map(SemanticReferenceMarkdown.Occurrence::id)
        .toList();
    List<String> enOrder = SemanticReferenceMarkdown.occurrences(bytesToString(english)).stream()
        .map(SemanticReferenceMarkdown.Occurrence::id)
        .toList();
    if (!map.order().equals(ruOrder) || !map.order().equals(enOrder)) {
      throw new ReferenceValidationException("reference-order-mismatch", "reference order must match both Russian and English bodies");
    }

    requireSidecarIdsPresentAndUnique(map.order(), map.references(), "reference-duplicate-id");
    requireSidecarIdsUniqueInOrder(ruOrder, map.references().keySet(), "reference-duplicate-id");
    requireSidecarIdsUniqueInOrder(enOrder, map.references().keySet(), "reference-duplicate-id");
    requireNoUnknownReference(map.order(), map.references(), "reference-not-found");
  }

  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception error) {
      throw new IllegalStateException("cannot compute SHA-256", error);
    }
  }

  private static void requireSidecarIdsPresentAndUnique(
      List<String> sidecarOrder,
      Map<String, PageReferenceMap.Reference> references,
      String duplicateCode) {
    Set<String> required = new LinkedHashSet<>();
    for (String id : sidecarOrder) {
      if (!required.add(id)) {
        throw new ReferenceValidationException(duplicateCode, "reference id appears multiple times in sidecar order: " + id);
      }
      if (!references.containsKey(id)) {
        throw new ReferenceValidationException("reference-not-found", "reference order contains unknown id: " + id);
      }
    }
    for (String id : references.keySet()) {
      if (!sidecarOrder.contains(id)) {
        throw new ReferenceValidationException("reference-missing", "reference map contains unused id: " + id);
      }
    }
  }

  private static void requireSidecarIdsUniqueInOrder(
      List<String> languageOrder,
      Set<String> references,
      String duplicateCode) {
    Set<String> seen = new LinkedHashSet<>();
    for (String id : languageOrder) {
      if (!references.contains(id)) {
        throw new ReferenceValidationException("reference-not-found", "reference id is missing from sidecar: " + id);
      }
      if (!seen.add(id)) {
        throw new ReferenceValidationException(duplicateCode, "reference id appears multiple times in language body: " + id);
      }
    }
  }

  private static void requireNoUnknownReference(
      List<String> order,
      Map<String, PageReferenceMap.Reference> references,
      String missingCode) {
    for (String id : order) {
      if (!references.containsKey(id)) {
        throw new ReferenceValidationException(missingCode, "reference id not declared in map: " + id);
      }
    }
  }

  private static Map<String, Object> writeReferences(PageReferenceMap map) {
    LinkedHashMap<String, Object> references = new LinkedHashMap<>();
    Set<String> written = new LinkedHashSet<>();
    for (String id : map.order()) {
      PageReferenceMap.Reference reference = map.references().get(id);
      if (reference == null || !written.add(id)) continue;
      references.put(id, referencePayload(reference));
    }
    for (String id : map.references().keySet().stream()
        .filter(id -> !written.contains(id))
        .sorted(Comparator.naturalOrder())
        .toList()) {
      references.put(id, referencePayload(map.references().get(id)));
    }
    return references;
  }

  private static Map<String, String> referencePayload(PageReferenceMap.Reference reference) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("targetRef", reference.targetRef());
    payload.put("authoredTarget", reference.authoredTarget());
    payload.put("heading", reference.heading());
    return payload;
  }

  private static Map<String, PageReferenceMap.Reference> requiredReferenceMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new ReferenceValidationException("invalid-json", field + " must be an object");
    }
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String id = requiredString(entry.getKey(), "references key");
      if (entry.getValue() == null) {
        throw new ReferenceValidationException("invalid-json", "reference payload must be an object");
      }
      if (!(entry.getValue() instanceof Map<?, ?> referencePayload)) {
        throw new ReferenceValidationException("invalid-json", "reference payload must be an object");
      }
      references.put(id, new PageReferenceMap.Reference(
          requiredString(referencePayload.get("targetRef"), "reference.targetRef"),
          optionalString(referencePayload.get("authoredTarget")),
          optionalString(referencePayload.get("heading"))));
    }
    return references;
  }

  private static List<String> requiredStringList(Object value, String field) {
    if (!(value instanceof List<?> values)) {
      throw new ReferenceValidationException("invalid-json", field + " must be an array");
    }
    return values.stream().map(item -> requiredString(item, field)).toList();
  }

  private static int requiredInt(Object value, String field) {
    if (value instanceof Integer valueAsInt) {
      return valueAsInt;
    }
    if (value instanceof Long valueAsLong) {
      if (valueAsLong < Integer.MIN_VALUE || valueAsLong > Integer.MAX_VALUE) {
        throw new ReferenceValidationException("invalid-json", field + " must be within 32-bit integer range");
      }
      return valueAsLong.intValue();
    }
    if (value instanceof BigInteger valueAsBigInteger) {
      if (valueAsBigInteger.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
          || valueAsBigInteger.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
        throw new ReferenceValidationException("invalid-json", field + " must be within 32-bit integer range");
      }
      return valueAsBigInteger.intValue();
    }
    if (value instanceof Short valueAsShort) {
      return valueAsShort.intValue();
    }
    if (value instanceof Byte valueAsByte) {
      return valueAsByte.intValue();
    }
    if (value instanceof Float || value instanceof Double) {
      throw new ReferenceValidationException("invalid-json", field + " must be an integer");
    }
    throw new ReferenceValidationException("invalid-json", field + " must be an integer");
  }

  private static String requiredString(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new ReferenceValidationException("invalid-json", field + " must be a non-blank string");
    }
    return text;
  }

  private static String optionalString(Object value) {
    return value instanceof String text ? text : null;
  }

  private static boolean normalizedRelativePath(String sourcePath) {
    if (!(sourcePath instanceof String value) || value.isBlank()) return false;
    if (value.startsWith("/") || value.contains("\\")) return false;
    if (value.startsWith("../") || value.endsWith("/..") || value.contains("/../")) return false;
    Path normalized = Path.of(value).normalize();
    return normalized.equals(Path.of(value)) && !normalized.isAbsolute();
  }

  private static String bytesToString(byte[] content) {
    return new String(content, StandardCharsets.UTF_8);
  }

  public static final class ReferenceValidationException extends RuntimeException {
    private final String code;

    public ReferenceValidationException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
