package dev.eugene.astroexport.release;

import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Derived reverse index from target page refs to approved referrer occurrences. */
public final class ReferenceImpactIndex {
  private final Map<String, List<InboundReference>> inboundByTargetRef;

  private ReferenceImpactIndex(Map<String, List<InboundReference>> inboundByTargetRef) {
    this.inboundByTargetRef = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inboundByTargetRef));
  }

  public static ReferenceImpactIndex from(List<ApprovedPageSnapshot> snapshots) {
    Objects.requireNonNull(snapshots, "snapshots");
    LinkedHashMap<String, java.util.ArrayList<InboundReference>> index = new LinkedHashMap<>();
    for (ApprovedPageSnapshot snapshot : snapshots) {
      PageReferenceMap map = snapshot.references();
      for (int orderIndex = 0; orderIndex < map.order().size(); orderIndex++) {
        String occurrenceId = map.order().get(orderIndex);
        PageReferenceMap.Reference reference = map.references().get(occurrenceId);
        if (reference == null) {
          throw new ApprovedReleaseException(
              "invalid-reference-map",
              snapshot.sourcePath(),
              "sidecar order references missing occurrence: " + occurrenceId);
        }
        index.computeIfAbsent(reference.targetRef(), ignored -> new java.util.ArrayList<>())
            .add(new InboundReference(
                snapshot.pageRef(),
                snapshot.publicId(),
                occurrenceId,
                orderIndex));
      }
    }
    LinkedHashMap<String, List<InboundReference>> immutable = new LinkedHashMap<>();
    for (Map.Entry<String, java.util.ArrayList<InboundReference>> entry : index.entrySet()) {
      immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return new ReferenceImpactIndex(immutable);
  }

  public List<InboundReference> inboundTo(String targetRef) {
    return inboundByTargetRef.getOrDefault(targetRef, List.of());
  }

  public int affectedCount(String targetRef) {
    return inboundTo(targetRef).size();
  }

  public Map<String, List<InboundReference>> asMap() {
    return inboundByTargetRef;
  }

  public record InboundReference(
      String pageRef,
      String publicId,
      String occurrenceId,
      int orderIndex) { }
}
