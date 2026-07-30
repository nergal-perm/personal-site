package dev.eugene.astroexport.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import dev.eugene.astroexport.review.SnapshotHashes;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReferenceImpactIndexTest {

  @Test
  void derivesInboundReferencesWithoutWritingReferrers() {
    ApprovedPageSnapshot a = snapshot("A", "vault-ref-a", "a",
        List.of("ref-0001", "ref-0002"),
        references(
            reference("ref-0001", "vault-ref-b"),
            reference("ref-0002", "vault-ref-c")));
    ApprovedPageSnapshot d = snapshot("D", "vault-ref-d", "d",
        List.of("ref-0003"),
        references(reference("ref-0003", "vault-ref-b")));

    ReferenceImpactIndex index = ReferenceImpactIndex.from(List.of(a, d));

    assertEquals(List.of(
        new ReferenceImpactIndex.InboundReference("vault-ref-a", "a", "ref-0001", 0),
        new ReferenceImpactIndex.InboundReference("vault-ref-d", "d", "ref-0003", 0)),
        index.inboundTo("vault-ref-b"));
    assertEquals(2, index.affectedCount("vault-ref-b"));
    assertEquals(1, index.affectedCount("vault-ref-c"));
    assertEquals(0, index.affectedCount("vault-ref-missing"));
  }

  private static ApprovedPageSnapshot snapshot(
      String title,
      String pageRef,
      String publicId,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references) {
    String body = order.stream()
        .map(id -> "[" + id + "](ref:" + id + ")")
        .reduce("", (left, right) -> left + right + " ");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    PageReferenceMap map = new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        pageRef,
        "blog/" + title + ".md",
        PageReferenceMapCodec.sha256(bytes),
        PageReferenceMapCodec.sha256(bytes),
        order,
        references);
    return new ApprovedPageSnapshot(
        "blog",
        publicId,
        pageRef,
        "blog/" + title + ".md",
        entry(publicId, body),
        entry(publicId, body),
        map,
        new SnapshotHashes(map.ruSha256(), map.enSha256()));
  }

  private static ManifestEntry entry(String publicId, String body) {
    return new ManifestEntry(
        "blog/" + publicId + ".md",
        "src/content/blog/ru/" + publicId + ".md",
        "/ru/notes/" + publicId + "/",
        Map.of("id", publicId),
        body);
  }

  private static Map.Entry<String, PageReferenceMap.Reference> reference(String id, String targetRef) {
    return Map.entry(id, new PageReferenceMap.Reference(targetRef, targetRef, "", targetRef));
  }

  @SafeVarargs
  private static Map<String, PageReferenceMap.Reference> references(
      Map.Entry<String, PageReferenceMap.Reference>... entries) {
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    for (Map.Entry<String, PageReferenceMap.Reference> entry : entries) {
      references.put(entry.getKey(), entry.getValue());
    }
    return references;
  }
}
