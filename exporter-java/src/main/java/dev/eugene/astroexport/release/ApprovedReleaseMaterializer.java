package dev.eugene.astroexport.release;

import dev.eugene.astroexport.links.LinkProcessor;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.SemanticReferenceMarkdown;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects approved semantic snapshots into public release entries without mutating snapshots. */
public final class ApprovedReleaseMaterializer {
  private static final Pattern SEMANTIC_DESTINATION = Pattern.compile("\\]\\(ref:[^)]+\\)");
  private static final Pattern VAULT_REF = Pattern.compile("\\bvault-ref-[A-Za-z0-9-]+\\b");
  private static final Pattern CATALOG_PATH = Pattern.compile("catalog-v\\d+\\.json|\\.semantic-links/");
  private static final Pattern ABSOLUTE_PATH_TOKEN = Pattern.compile("(?<![\\w:])/[^\\s<>\"')]+");
  private static final Pattern PATH_LIKE_LEAF = Pattern.compile("(?i).+\\.(md|markdown|json|canvas|base|yaml|yml|png|jpe?g|gif|svg|webp|mp3|mp4)$");

  private final LinkProcessor linkProcessor = new LinkProcessor();

  public MaterializedRelease materialize(List<ApprovedPageSnapshot> snapshots, Path vaultRoot) {
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(vaultRoot, "vaultRoot");
    ApprovedTargetRegistry registry = ApprovedTargetRegistry.from(snapshots);
    ReferenceImpactIndex impactIndex = ReferenceImpactIndex.from(snapshots);
    ReleaseInputGuard.Builder guard = ReleaseInputGuard.builder();
    List<ManifestEntry> russian = new ArrayList<>();
    List<ManifestEntry> english = new ArrayList<>();
    List<IgnoredDraft> ignoredDrafts = new ArrayList<>();
    LinkedHashSet<String> assets = new LinkedHashSet<>();
    LinkedHashMap<String, List<Activation>> activations = new LinkedHashMap<>();

    for (ApprovedPageSnapshot snapshot : snapshots) {
      guard.captureRequired(vaultRoot.resolve(snapshot.sourcePath()));
      guard.capture(snapshot.inputFiles().approvedRussian());
      guard.capture(snapshot.inputFiles().approvedEnglish());
      guard.capture(snapshot.inputFiles().approvedReferences());
      guard.capture(snapshot.inputFiles().catalog());
      validateActivationSequence(snapshot, "ru", snapshot.russian().body());
      validateActivationSequence(snapshot, "en", snapshot.english().body());
      Projection ru = project(snapshot, "ru", registry);
      Projection en = project(snapshot, "en", registry);
      russian.add(projectedEntry(snapshot.russian(), ru.body()));
      english.add(projectedEntry(snapshot.english(), en.body()));
      activations.put(snapshot.pageRef(), ru.activations());
      collectAssets(snapshot, assets);
      if (!ru.ignoredDrafts().isEmpty() || !en.ignoredDrafts().isEmpty()) {
        ignoredDrafts.addAll(ru.ignoredDrafts());
        ignoredDrafts.addAll(en.ignoredDrafts());
      }
    }

    ManifestResult manifest = new ManifestResult(
        russian,
        english,
        List.of(),
        List.of(),
        assets.stream().sorted().toList(),
        List.of(),
        List.of(),
        Map.of());
    rejectInvalidOutput(manifest, vaultRoot);
    ReleaseInputGuard inputGuard = guard.build();
    return new MaterializedRelease(
        manifest,
        new ActivationAudit(activations, impactIndex),
        ignoredDrafts,
        registry,
        inputGuard);
  }

  private Projection project(
      ApprovedPageSnapshot snapshot,
      String language,
      ApprovedTargetRegistry registry) {
    String body = "ru".equals(language) ? snapshot.russian().body() : snapshot.english().body();
    List<Activation> activations = new ArrayList<>();
    List<IgnoredDraft> ignoredDrafts = new ArrayList<>();
    Map<String, SemanticReferenceMarkdown.Occurrence> occurrences = occurrencesById(body);
    int[] cursor = {0};
    String projected = SemanticReferenceMarkdown.project(
        body,
        snapshot.references(),
        reference -> {
          String occurrenceId = snapshot.references().order().get(cursor[0]++);
          Optional<ApprovedTargetRegistry.Target> target = registry.find(reference.targetRef());
          if (target.isEmpty()) {
            ignoredDrafts.add(new IgnoredDraft(
                snapshot.pageRef(),
                snapshot.publicId(),
                reference.targetRef(),
                language));
            return Optional.empty();
          }
          SemanticReferenceMarkdown.Occurrence occurrence = occurrences.get(occurrenceId);
          String heading = occurrence == null ? reference.heading() : occurrence.heading();
          String href = target.get().route(language)
              + SemanticReferenceMarkdown.normalizeHeadingFragment(heading);
          if ("ru".equals(language)) {
            activations.add(new Activation(
                snapshot.pageRef(),
                occurrenceId,
                reference.targetRef(),
                target.get().publicId(),
                href,
                cursor[0] - 1));
          }
          return Optional.of(href);
        });
    return new Projection(projected, List.copyOf(activations), List.copyOf(ignoredDrafts));
  }

  private static Map<String, SemanticReferenceMarkdown.Occurrence> occurrencesById(String body) {
    LinkedHashMap<String, SemanticReferenceMarkdown.Occurrence> occurrences = new LinkedHashMap<>();
    for (SemanticReferenceMarkdown.Occurrence occurrence : SemanticReferenceMarkdown.occurrences(body)) {
      occurrences.putIfAbsent(occurrence.id(), occurrence);
    }
    return occurrences;
  }

  private static void validateActivationSequence(
      ApprovedPageSnapshot snapshot,
      String language,
      String body) {
    List<String> actual = SemanticReferenceMarkdown.occurrences(body).stream()
        .map(SemanticReferenceMarkdown.Occurrence::id)
        .toList();
    if (!actual.equals(snapshot.references().order())) {
      throw new ApprovedReleaseException(
          "invalid-activation-sequence",
          snapshot.sourcePath(),
          "semantic occurrence sequence differs from sidecar order in " + language);
    }
  }

  private static ManifestEntry projectedEntry(ManifestEntry entry, String body) {
    return new ManifestEntry(
        entry.sourcePath(),
        entry.targetPath(),
        entry.route(),
        entry.metadata(),
        body,
        entry.translationSourceHash(),
        entry.translationSourceMetadata());
  }

  private void collectAssets(ApprovedPageSnapshot snapshot, Set<String> assets) {
    Note note = new Note(
        Path.of(snapshot.sourcePath()),
        snapshot.sourcePath(),
        snapshot.publicId(),
        snapshot.russian().metadata(),
        snapshot.russian().body(),
        true,
        snapshot.publicId(),
        snapshot.collection(),
        String.valueOf(snapshot.russian().metadata().getOrDefault("reviewType", "note")),
        List.of());
    assets.addAll(linkProcessor.processSemanticEmbedsAndAssets(note).assets());
  }

  private static void rejectInvalidOutput(ManifestResult manifest, Path vaultRoot) {
    Path normalizedVaultRoot = vaultRoot.toAbsolutePath().normalize();
    validateEntries(manifest.entries(), "ru", normalizedVaultRoot);
    validateEntries(manifest.englishEntries(), "en", normalizedVaultRoot);
  }

  private static void validateEntries(List<ManifestEntry> entries, String language, Path vaultRoot) {
    for (ManifestEntry entry : entries) {
      validateLanguageRoute(entry, language);
      validateNoPrivatePayload(entry, language, vaultRoot);
    }
  }

  private static void validateLanguageRoute(ManifestEntry entry, String language) {
    if ("ru".equals(language) && entry.body().contains("](/en/")) {
      throw invalidOutput(entry, "RU body contains EN route");
    }
    if ("en".equals(language) && entry.body().contains("](/ru/")) {
      throw invalidOutput(entry, "EN body contains RU route");
    }
    if ("ru".equals(language) && entry.route().startsWith("/en/")) {
      throw invalidOutput(entry, "RU entry has EN route");
    }
    if ("en".equals(language) && entry.route().startsWith("/ru/")) {
      throw invalidOutput(entry, "EN entry has RU route");
    }
  }

  private static void validateNoPrivatePayload(ManifestEntry entry, String language, Path vaultRoot) {
    String metadata = entry.metadata().toString();
    if (SEMANTIC_DESTINATION.matcher(entry.body()).find()
        || VAULT_REF.matcher(entry.body()).find()
        || VAULT_REF.matcher(metadata).find()
        || CATALOG_PATH.matcher(entry.body()).find()
        || CATALOG_PATH.matcher(metadata).find()
        || containsVaultPath(entry.body(), vaultRoot)
        || containsVaultPath(metadata, vaultRoot)
        || metadata.contains("authoredTarget")) {
      throw invalidOutput(entry, language + " output contains private semantic payload");
    }
  }

  private static boolean containsVaultPath(String value, Path vaultRoot) {
    java.util.regex.Matcher matcher = ABSOLUTE_PATH_TOKEN.matcher(value);
    while (matcher.find()) {
      String token = trimTrailingPunctuation(matcher.group());
      if (token.isBlank() || !PATH_LIKE_LEAF.matcher(token).matches()) {
        continue;
      }
      try {
        Path path = Path.of(token).toAbsolutePath().normalize();
        if (path.startsWith(vaultRoot)) {
          return true;
        }
      } catch (RuntimeException ignored) {
        // Malformed path-looking text is handled by other public-output guards.
      }
    }
    return false;
  }

  private static String trimTrailingPunctuation(String value) {
    int end = value.length();
    while (end > 0 && ".,;:!?".indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return value.substring(0, end);
  }

  private static ApprovedReleaseException invalidOutput(ManifestEntry entry, String message) {
    return new ApprovedReleaseException("invalid-release-output", entry.sourcePath(), message);
  }

  private record Projection(
      String body,
      List<Activation> activations,
      List<IgnoredDraft> ignoredDrafts) { }

  public record MaterializedRelease(
      ManifestResult manifest,
      ActivationAudit audit,
      List<IgnoredDraft> ignoredDrafts,
      ApprovedTargetRegistry registry,
      ReleaseInputGuard inputGuard) {
    public MaterializedRelease {
      ignoredDrafts = List.copyOf(ignoredDrafts);
    }
  }

  public record ActivationAudit(
      Map<String, List<Activation>> byPageRef,
      ReferenceImpactIndex impactIndex) {
    public ActivationAudit {
      LinkedHashMap<String, List<Activation>> copy = new LinkedHashMap<>();
      for (Map.Entry<String, List<Activation>> entry : byPageRef.entrySet()) {
        copy.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
      byPageRef = java.util.Collections.unmodifiableMap(copy);
    }

    public List<Activation> forPage(String pageRef) {
      return byPageRef.getOrDefault(pageRef, List.of());
    }
  }

  public record Activation(
      String pageRef,
      String occurrenceId,
      String targetRef,
      String targetPublicId,
      String href,
      int orderIndex) { }

  public record IgnoredDraft(
      String pageRef,
      String publicId,
      String targetRef,
      String language) { }
}
