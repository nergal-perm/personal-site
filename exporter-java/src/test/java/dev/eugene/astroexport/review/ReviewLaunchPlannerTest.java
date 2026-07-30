package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ReviewLaunchPlannerTest {
  @TempDir
  Path temp;

  @Test
  void plansTwoPlainTargetsWhenPublishedPairIsAbsent() throws Exception {
    Fixture fixture = fixture();

    ReviewLaunchPlanner.ReviewPlan plan = new ReviewLaunchPlanner().plan(
        fixture.reviewRoot(),
        fixture.page(),
        fixture.entry(),
        fixture.english());

    assertEquals("absent", plan.baselineState());
    assertEquals(List.of("ru", "en"),
        plan.targets().stream().map(ReviewLaunchPlanner.ReviewTarget::language).toList());
    assertEquals(fixture.page().toRealPath().resolve("ru.md"),
        plan.targets().get(0).proposedPath());
    assertEquals(null, plan.targets().get(0).publishedPath());
    assertEquals(fixture.page().toRealPath().resolve("en.md"),
        plan.targets().get(1).proposedPath());
    assertEquals(null, plan.targets().get(1).publishedPath());
  }

  @Test
  void semanticPlanReadsProposedRuEnFromCandidateDirectoryAndValidatesReferences() throws Exception {
    Fixture fixture = fixture();
    Files.delete(fixture.page().resolve("ru.md"));
    Files.delete(fixture.page().resolve("en.md"));
    byte[] ru = ReviewWorkspace.renderRuReview(fixture.entry()).getBytes(StandardCharsets.UTF_8);
    byte[] en = fixture.english();
    Path candidate = fixture.page().resolve("candidate");
    Files.createDirectories(candidate);
    Files.write(candidate.resolve("ru.md"), ru);
    Files.write(candidate.resolve("en.md"), en);
    Files.write(candidate.resolve("references.json"), PageReferenceMapCodec.write(new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "vault-ref-page",
        fixture.entry().sourcePath(),
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en),
        List.of(),
        Map.of())));

    ReviewLaunchPlanner.ReviewPlan plan = new ReviewLaunchPlanner().plan(
        fixture.reviewRoot(),
        fixture.page(),
        fixture.entry(),
        en);

    assertEquals(candidate.toRealPath().resolve("ru.md"), plan.targets().get(0).proposedPath());
    assertEquals(candidate.toRealPath().resolve("en.md"), plan.targets().get(1).proposedPath());
  }

  @Test
  void activatedSemanticModeRejectsLegacyProposalWhenCandidateTripleIsAbsent() throws Exception {
    Fixture fixture = fixture();
    writeSemanticMarker(fixture.reviewRoot());

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(),
            fixture.page(),
            fixture.entry(),
            fixture.english()));

    assertEquals("stale", error.status());
    assertTrue(error.getMessage().contains("candidate"));
  }

  @Test
  void incompleteSemanticMigrationRejectsLegacyProposalFallback() throws Exception {
    Fixture fixture = fixture();
    Path journal = fixture.reviewRoot().resolve(".semantic-links/migration-v1.journal.json");
    Files.createDirectories(journal.getParent());
    Files.writeString(journal, "{\"state\":\"installed\"}");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(),
            fixture.page(),
            fixture.entry(),
            fixture.english()));

    assertEquals("stale", error.status());
    assertTrue(error.getMessage().contains("migration"));
  }

  @Test
  void plansPublishedToProposedDiffTargetsWhenBothSnapshotsExist() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.writeString(published.resolve("ru.md"), "approved Russian\n");
    Files.writeString(published.resolve("en.md"), "approved English\n");

    ReviewLaunchPlanner.ReviewPlan plan = new ReviewLaunchPlanner().plan(
        fixture.reviewRoot(),
        fixture.page(),
        fixture.entry(),
        fixture.english());

    assertEquals("complete", plan.baselineState());
    assertEquals(published.toRealPath().resolve("ru.md"),
        plan.targets().get(0).publishedPath());
    assertEquals(published.toRealPath().resolve("en.md"),
        plan.targets().get(1).publishedPath());
  }

  @Test
  void semanticPlanValidatesPublishedReferenceMapWithoutExposingItAsTarget()
      throws Exception {
    Fixture fixture = fixture();
    writeSemanticMarker(fixture.reviewRoot());
    byte[] ru = ReviewWorkspace.renderRuReview(fixture.entry()).getBytes(StandardCharsets.UTF_8);
    byte[] en = fixture.english();
    Path candidate = fixture.page().resolve("candidate");
    Files.createDirectories(candidate);
    Files.write(candidate.resolve("ru.md"), ru);
    Files.write(candidate.resolve("en.md"), en);
    Files.write(candidate.resolve("references.json"), referencesFor(ru, en));
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.write(published.resolve("ru.md"), ru);
    Files.write(published.resolve("en.md"), en);
    Files.write(published.resolve("references.json"), referencesFor(ru, en));

    ReviewLaunchPlanner.ReviewPlan plan = new ReviewLaunchPlanner().plan(
        fixture.reviewRoot(),
        fixture.page(),
        fixture.entry(),
        en);

    assertEquals("complete", plan.baselineState());
    assertEquals(List.of("ru", "en"),
        plan.targets().stream().map(ReviewLaunchPlanner.ReviewTarget::language).toList());
  }

  @Test
  void semanticPlanRejectsPublishedReferenceMapWithWrongHashes() throws Exception {
    Fixture fixture = fixture();
    writeSemanticMarker(fixture.reviewRoot());
    byte[] ru = ReviewWorkspace.renderRuReview(fixture.entry()).getBytes(StandardCharsets.UTF_8);
    byte[] en = fixture.english();
    Path candidate = fixture.page().resolve("candidate");
    Files.createDirectories(candidate);
    Files.write(candidate.resolve("ru.md"), ru);
    Files.write(candidate.resolve("en.md"), en);
    Files.write(candidate.resolve("references.json"), referencesFor(ru, en));
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.write(published.resolve("ru.md"), ru);
    Files.write(published.resolve("en.md"), en);
    Files.write(published.resolve("references.json"), referencesFor(
        "other".getBytes(StandardCharsets.UTF_8), en));

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(),
            fixture.page(),
            fixture.entry(),
            en));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertEquals("published-snapshot", error.field());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ru", "en"})
  void rejectsAPartialPublishedPair(String language) throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.writeString(published.resolve(language + ".md"), "approved\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(),
            fixture.page(),
            fixture.entry(),
            fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertEquals("published-snapshot", error.field());
    assertTrue(error.getMessage().contains("published"));
  }

  @Test
  void rejectsTamperedProposedRussianReview() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.page().resolve("ru.md"), "tampered\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertEquals("translation", error.field());
    assertTrue(error.getMessage().contains("Russian review"));
  }

  @Test
  void rejectsChangedEnglishAfterFreshnessValidation() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.page().resolve("en.md"), "changed\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertTrue(error.getMessage().contains("English review changed"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ru", "en"})
  void rejectsMissingProposedArtifact(String language) throws Exception {
    Fixture fixture = fixture();
    Files.delete(fixture.page().resolve(language + ".md"));

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertEquals("translation", error.field());
  }

  @Test
  void rejectsSymbolicPublishedArtifact() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Path outside = temp.resolve("outside.md");
    Files.writeString(outside, "outside\n");
    Files.createSymbolicLink(published.resolve("ru.md"), outside);
    Files.writeString(published.resolve("en.md"), "approved\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
  }

  @Test
  void rejectsSymbolicPublishedDirectory() throws Exception {
    Fixture fixture = fixture();
    Path outside = temp.resolve("outside-published");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("ru.md"), "approved\n");
    Files.writeString(outside.resolve("en.md"), "approved\n");
    Files.createSymbolicLink(fixture.page().resolve("published"), outside);

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
  }

  @Test
  void rejectsHardLinkedPublishedArtifact() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Path original = temp.resolve("approved.md");
    Files.writeString(original, "approved\n");
    Files.createLink(published.resolve("ru.md"), original);
    Files.writeString(published.resolve("en.md"), "approved\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
  }

  @Test
  void rejectsDirectoryInPublishedPair() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published.resolve("ru.md"));
    Files.writeString(published.resolve("en.md"), "approved\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertTrue(error.getMessage().contains("regular file"));
  }

  @Test
  void rejectsInvalidUtf8PublishedArtifact() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.write(published.resolve("ru.md"), new byte[] {(byte) 0xc3, (byte) 0x28});
    Files.writeString(published.resolve("en.md"), "approved\n");

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertTrue(error.getMessage().contains("valid UTF-8"));
  }

  @Test
  void mapsPublishedReadFailureToPublishedSnapshotDiagnostic() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    Files.writeString(published.resolve("ru.md"), "approved\n");
    Files.writeString(published.resolve("en.md"), "approved\n");
    ReviewLaunchPlanner planner = new ReviewLaunchPlanner((path, label) -> {
      if (path.endsWith(Path.of("published", "en.md"))) {
        throw new IOException("permission denied");
      }
      return Files.readAllBytes(path);
    });

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> planner.plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertTrue(error.getMessage().contains("permission denied"));
  }

  @Test
  void blocksWhenPublishedMemberAbsenceCannotBeConfirmedAfterIoFailure() throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    ReviewLaunchPlanner planner = new ReviewLaunchPlanner(
        (path, label) -> Files.readAllBytes(path),
        path -> {
          if (path.endsWith(Path.of("published", "ru.md"))) {
            throw new AccessDeniedException(path.toString());
          }
          return Files.readAttributes(
              path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        });

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> planner.plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertEquals("published-snapshot", error.field());
    assertTrue(error.getMessage().contains("cannot be determined"));
  }

  @Test
  void blocksWhenPublishedMemberAbsenceCannotBeConfirmedAfterSecurityFailure()
      throws Exception {
    Fixture fixture = fixture();
    Path published = fixture.page().resolve("published");
    Files.createDirectories(published);
    ReviewLaunchPlanner planner = new ReviewLaunchPlanner(
        (path, label) -> Files.readAllBytes(path),
        path -> {
          if (path.endsWith(Path.of("published", "en.md"))) {
            throw new SecurityException("security policy denied the probe");
          }
          return Files.readAttributes(
              path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        });

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> planner.plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("published_snapshot_inconsistent", error.status());
    assertEquals("published-snapshot", error.field());
    assertTrue(error.getMessage().contains("cannot be determined"));
  }

  @Test
  void mapsProposedReadFailureToStaleTranslationDiagnostic() throws Exception {
    Fixture fixture = fixture();
    ReviewLaunchPlanner planner = new ReviewLaunchPlanner((path, label) -> {
      if (path.endsWith("ru.md")) {
        throw new IOException("permission denied");
      }
      return Files.readAllBytes(path);
    });

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> planner.plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertEquals("translation", error.field());
    assertTrue(error.getMessage().contains("permission denied"));
  }

  @Test
  void rejectsReviewDirectoryOutsideConfirmedRoot() throws Exception {
    Fixture fixture = fixture();
    Path outside = temp.resolve("outside/blog/essay");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("ru.md"), ReviewWorkspace.renderRuReview(fixture.entry()));
    Files.write(outside.resolve("en.md"), fixture.english());

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), outside, fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertTrue(error.getMessage().contains("escapes the review root"));
  }

  @Test
  void rejectsSymbolicProposedArtifact() throws Exception {
    Fixture fixture = fixture();
    Path proposed = fixture.page().resolve("ru.md");
    Path outside = temp.resolve("outside-ru.md");
    Files.writeString(outside, Files.readString(proposed));
    Files.delete(proposed);
    Files.createSymbolicLink(proposed, outside);

    ReviewLaunchPlanner.ReviewLaunchException error = assertThrows(
        ReviewLaunchPlanner.ReviewLaunchException.class,
        () -> new ReviewLaunchPlanner().plan(
            fixture.reviewRoot(), fixture.page(), fixture.entry(), fixture.english()));

    assertEquals("stale", error.status());
    assertEquals("translation", error.field());
  }

  private Fixture fixture() throws Exception {
    Path reviewRoot = temp.resolve("review");
    Path page = reviewRoot.resolve("blog/essay");
    Files.createDirectories(page);
    ManifestEntry entry = entry();
    byte[] english = """
        ---
        sourceHash: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        translationStatus: generated
        ---
        English.
        """.getBytes(StandardCharsets.UTF_8);
    Files.writeString(page.resolve("ru.md"), ReviewWorkspace.renderRuReview(entry));
    Files.write(page.resolve("en.md"), english);
    return new Fixture(reviewRoot, page, entry, english);
  }

  private static ManifestEntry entry() {
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        new LinkedHashMap<>(Map.of(
            "id", "essay",
            "title", "Русский заголовок",
            "language", "ru",
            "sourceLanguage", "ru",
            "sourceHash", "a".repeat(64))),
        "Русский текст.\n");
  }

  private static void writeSemanticMarker(Path reviewRoot) throws Exception {
    Path marker = reviewRoot.resolve(".semantic-links/schema-v1.active.json");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, """
        {
          "schemaVersion": 1,
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "activatedAt": "2026-07-30T00:00:00Z"
        }
        """.formatted("a".repeat(64), "b".repeat(64)));
  }

  private static byte[] referencesFor(byte[] ru, byte[] en) {
    return PageReferenceMapCodec.write(new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "vault-ref-page",
        "blog/Essay.md",
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en),
        List.of(),
        Map.of()));
  }

  private record Fixture(
      Path reviewRoot,
      Path page,
      ManifestEntry entry,
      byte[] english) {
    private Fixture {
      english = english.clone();
    }

    @Override
    public byte[] english() {
      return english.clone();
    }
  }
}
