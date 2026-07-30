package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PageReferenceMapCodecTest {

  @Test
  void bindsHashesAndRoundTripsDuplicateTargetsWithoutDependingOnMapOrder() {
    ReferencePlan plan = new ReferencePlan(
        "vault-ref-page",
        "blog/A.md",
        List.of("ref-0001", "ref-0002"),
        Map.of(
            "ref-0002", new PageReferenceMap.Reference(
                "vault-ref-target", "Target", "Second"),
            "ref-0001", new PageReferenceMap.Reference(
                "vault-ref-target", "Target", null)));

    byte[] ru = bytes("[первый](ref:ref-0001) [второй](ref:ref-0002)\n");
    byte[] en = bytes("[first](ref:ref-0001) [second](ref:ref-0002)\n");

    PageReferenceMap bound = PageReferenceMap.bind(plan, ru, en);
    byte[] json = PageReferenceMapCodec.write(bound);
    PageReferenceMap decoded = PageReferenceMapCodec.read(json, "references.json");

    PageReferenceMapCodec.validate(decoded, ru, en);
    assertEquals(plan.order(), decoded.order());
    assertEquals("vault-ref-target", decoded.references().get("ref-0002").targetRef());
  }

  @Test
  void rejectsAnEnglishOrderSwapEvenWhenTheSameIdsExist() {
    ReferencePlan plan = plan("ref-0001", "ref-0002");
    byte[] ru = bytes("[один](ref:ref-0001) [два](ref:ref-0002)");
    byte[] en = bytes("[two](ref:ref-0002) [one](ref:ref-0001)");
    PageReferenceMap map = PageReferenceMap.bind(plan, ru, en);

    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.validate(map, ru, en));
    assertEquals("reference-order-mismatch", error.code());
  }

  @Test
  void detectsDuplicateIdsInSidecarOrder() {
    ReferencePlan plan = new ReferencePlan(
        "page",
        "blog/A.md",
        List.of("ref-0001", "ref-0001"),
        Map.of("ref-0001", reference("target", null, null)));
    byte[] ru = bytes("[a](ref:ref-0001) [b](ref:ref-0001)");
    byte[] en = bytes("[a](ref:ref-0001) [b](ref:ref-0001)");
    PageReferenceMap map = PageReferenceMap.bind(plan, ru, en);

    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.validate(map, ru, en));
    assertEquals("reference-duplicate-id", error.code());
  }

  @Test
  void detectsUnknownReferencesInOrderAndLanguage() {
    ReferencePlan plan = new ReferencePlan(
        "page",
        "blog/A.md",
        List.of("ref-0001", "ref-0002"),
        Map.of("ref-0001", reference("target", null, null)));
    byte[] ru = bytes("[a](ref:ref-0001) [b](ref:ref-0002)");
    byte[] en = bytes("[a](ref:ref-0001) [b](ref:ref-0002)");
    PageReferenceMap map = PageReferenceMap.bind(plan, ru, en);

    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.validate(map, ru, en));
    assertEquals("reference-not-found", error.code());
  }

  @Test
  void detectsUnusedReferencesInSidecar() {
    ReferencePlan plan = new ReferencePlan(
        "page",
        "blog/A.md",
        List.of("ref-0001"),
        Map.of(
            "ref-0001", reference("target", null, null),
            "ref-0002", reference("unused", null, null)));
    byte[] ru = bytes("[a](ref:ref-0001)");
    byte[] en = bytes("[a](ref:ref-0001)");
    PageReferenceMap map = PageReferenceMap.bind(plan, ru, en);

    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.validate(map, ru, en));
    assertEquals("reference-missing", error.code());
  }

  @Test
  void rejectsInvalidSourcePath() {
    ReferencePlan plan = plan("ref-0001");
    byte[] ru = bytes("[a](ref:ref-0001)");
    byte[] en = bytes("[a](ref:ref-0001)");
    PageReferenceMap map = new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "page",
        "../outside.md",
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en),
        plan.order(),
        plan.references());

    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.validate(map, ru, en));
    assertEquals("invalid-source-path", error.code());
  }

  @Test
  void rejectsDuplicateJsonKeys() {
    String payload = """
        {"schemaVersion":1,"pageRef":"page","sourcePath":"blog/A.md","ruSha256":"%s","enSha256":"%s",
        "order":["a"],"references":{"dup":{"targetRef":"t","authoredTarget":"a","heading":null},"dup":{"targetRef":"t","authoredTarget":"a","heading":null}}}
        """.formatted(PageReferenceMapCodec.sha256(bytes("x")), PageReferenceMapCodec.sha256(bytes("y")));
    PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
        PageReferenceMapCodec.ReferenceValidationException.class,
        () -> PageReferenceMapCodec.read(payload.getBytes(StandardCharsets.UTF_8), "references.json"));
    assertEquals("duplicate-key", error.code());
  }

  private static ReferencePlan plan(String... references) {
    LinkedHashMap<String, PageReferenceMap.Reference> mapped = new LinkedHashMap<>();
    for (String reference : references) {
      mapped.put(reference, reference(reference, reference, null));
    }
    return new ReferencePlan("page", "blog/A.md", List.copyOf(List.of(references)), mapped);
  }

  private static PageReferenceMap.Reference reference(String targetRef, String authoredTarget, String heading) {
    return new PageReferenceMap.Reference(targetRef, authoredTarget, heading);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
