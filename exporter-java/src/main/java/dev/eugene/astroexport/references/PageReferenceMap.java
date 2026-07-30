package dev.eugene.astroexport.references;

import java.util.List;
import java.util.Map;

/** Immutable semantic-reference sidecar schema. */
public record PageReferenceMap(
    int schemaVersion,
    String pageRef,
    String sourcePath,
    String ruSha256,
    String enSha256,
    List<String> order,
    Map<String, Reference> references) {

  public static final int SCHEMA_VERSION = 1;

  public PageReferenceMap {
    order = List.copyOf(order);
    references = Map.copyOf(references);
  }

  public PageReferenceMap withHashes(String ruSha256, String enSha256) {
    return new PageReferenceMap(
        SCHEMA_VERSION,
        pageRef,
        sourcePath,
        ruSha256,
        enSha256,
        order,
        references);
  }

  public static PageReferenceMap bind(ReferencePlan plan, byte[] russian, byte[] english) {
    String ruSha256 = PageReferenceMapCodec.sha256(russian);
    String enSha256 = PageReferenceMapCodec.sha256(english);
    return new PageReferenceMap(
        SCHEMA_VERSION,
        plan.pageRef(),
        plan.sourcePath(),
        ruSha256,
        enSha256,
        plan.order(),
        plan.references())
        .withHashes(ruSha256, enSha256);
  }

  public record Reference(
      String targetRef,
      String authoredTarget,
      String heading) { }
}
