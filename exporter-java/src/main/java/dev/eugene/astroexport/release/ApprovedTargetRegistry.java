package dev.eugene.astroexport.release;

import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable page-ref to public route registry for approved release projection. */
public final class ApprovedTargetRegistry {
  private final Map<String, Target> byPageRef;

  private ApprovedTargetRegistry(Map<String, Target> byPageRef) {
    this.byPageRef = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byPageRef));
  }

  public static ApprovedTargetRegistry from(List<ApprovedPageSnapshot> snapshots) {
    Objects.requireNonNull(snapshots, "snapshots");
    LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
    java.util.HashSet<String> publicIds = new java.util.HashSet<>();
    java.util.HashSet<String> ruRoutes = new java.util.HashSet<>();
    java.util.HashSet<String> enRoutes = new java.util.HashSet<>();
    for (ApprovedPageSnapshot snapshot : snapshots) {
      Target target = new Target(
          required(snapshot.pageRef(), "pageRef"),
          required(snapshot.publicId(), "publicId"),
          required(snapshot.russian().route(), "ruRoute"),
          required(snapshot.english().route(), "enRoute"));
      Target previous = targets.putIfAbsent(target.pageRef(), target);
      if (previous != null && !previous.equals(target)) {
        throw new ApprovedReleaseException(
            "duplicate-target",
            snapshot.sourcePath(),
            "duplicate approved target pageRef: " + target.pageRef());
      }
      requireUnique(publicIds, target.publicId(), snapshot.sourcePath(), "publicId");
      requireUnique(ruRoutes, target.ruRoute(), snapshot.sourcePath(), "ruRoute");
      requireUnique(enRoutes, target.enRoute(), snapshot.sourcePath(), "enRoute");
    }
    return new ApprovedTargetRegistry(targets);
  }

  public Optional<Target> find(String pageRef) {
    return Optional.ofNullable(byPageRef.get(pageRef));
  }

  public List<Target> targets() {
    return List.copyOf(byPageRef.values());
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ApprovedReleaseException("invalid-approved-target", null, field + " is required");
    }
    return value;
  }

  private static void requireUnique(
      java.util.Set<String> seen,
      String value,
      String sourcePath,
      String field) {
    if (!seen.add(value)) {
      throw new ApprovedReleaseException(
          "duplicate-target",
          sourcePath,
          "duplicate approved target " + field + ": " + value);
    }
  }

  public record Target(
      String pageRef,
      String publicId,
      String ruRoute,
      String enRoute) {
    public String route(String language) {
      return switch (language) {
        case "ru" -> ruRoute;
        case "en" -> enRoute;
        default -> throw new IllegalArgumentException("unsupported language: " + language);
      };
    }
  }
}
