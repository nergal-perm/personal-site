# Zed Translation Review Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both Obsidian review entry points open exporter-approved RU and EN review targets in two new Zed workspace windows, using plain files before the first approval and published-to-proposed diffs afterward.

**Architecture:** Add an editor-neutral `ReviewLaunchPlanner` to the Java exporter and expose its two ordered targets through bridge schema version 2. Keep cache knowledge, path safety, and freshness validation in the exporter; keep Zed CLI configuration, process invocation, and user feedback in the Obsidian plugin.

**Tech Stack:** Java 21, Maven, JUnit 6, JNA file-descriptor safety, Jackson, picocli, Node.js CommonJS, Node built-in test runner, Obsidian desktop plugin API, Zed 1.12 CLI on macOS.

## Global Constraints

- The exporter owns review-plan construction and all knowledge of `review/<collection>/<publicId>`.
- Proposed `ru.md` and `en.md` must both be fresh, safe, readable UTF-8 regular files.
- Two missing published files mean `baselineState: absent`.
- Two safe published files mean `baselineState: complete`.
- A partial, unsafe, or unreadable published pair returns `status: published_snapshot_inconsistent`, a blocking `published-snapshot` diagnostic, and no plan.
- The bridge schema is exactly version 2 and adds nullable top-level `reviewPlan`.
- A successful plan contains exactly two targets ordered `ru`, then `en`.
- The plugin never derives `ru.md`, `en.md`, or `published/` paths.
- The default Zed CLI is `/Applications/Zed.app/Contents/MacOS/cli`.
- Every review target opens with `-n` in its own new Zed workspace window.
- Complete-baseline diffs pass published as the old operand and proposed as the new operand.
- Both language launches are attempted after CLI preflight; success is reported only when both exit with code 0.
- Invoke Zed with an argument array and `shell: false`.
- Perform no window tiling, positioning, focus control, AppleScript, or Accessibility automation.
- Do not fall back to `$PATH`, `open -a Zed`, `shell.openPath`, another editor, or schema version 1.
- Both the explicit command and post-prepare button call one shared inspect-and-launch helper.
- The post-prepare button retains the path of the prepared note even if the active note changes.
- `mark-reviewed` remains the approval and exact-byte revalidation boundary.
- Inspection is read-only and does not advance or repair the published baseline.
- Add no third-party dependency.

---

## File map

### Create

- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java`
  - Validates proposed artifacts, classifies the published pair, and returns two editor-neutral comparison targets.
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`
  - Covers absent, complete, partial, unsafe, unreadable, stale-RU, UTF-8, path, and ordering behavior.

### Modify

- `exporter-java/src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java`
  - Advances the exact response schema to version 2 and serializes nullable `reviewPlan`.
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
  - Invokes the planner only after fresh English validation and maps typed planning failures to bridge diagnostics.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Covers schema version 2, plan payloads, inconsistent-baseline blocking, and read-only inspection.
- `obsidian-plugin/bridge-client.js`
  - Requires bridge schema version 2 and reports explicit version mismatch diagnostics.
- `obsidian-plugin/main.js`
  - Mirrors version-2 parsing, adds Zed settings and process handling, and unifies both review entry points.
- `obsidian-plugin/tests/bridge-client.test.cjs`
  - Covers protocol mismatch, plan validation, Zed arguments and failures, settings, captured-note behavior, and removal of folder opening.
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
  - Updates the generated author-action contract from folder opening to the two-window Zed workflow.
- `README.md`
  - Describes the user-visible two-window Zed review flow.
- `exporter-java/README.md`
  - Documents schema-version-2 review plans and published-pair blocking.
- `obsidian-plugin/DEPLOY.md`
  - Documents Zed CLI configuration and coordinated native/plugin deployment.

---

### Task 1: Add the exporter-owned review launch planner

**Files:**
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java`
- Create: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`

**Interfaces:**
- Consumes:
  - `ReviewWorkspace.renderRuReview(ManifestEntry entry) -> String`
  - a bounded review root and page directory
  - the exact English bytes returned by the existing fresh-pair validation
- Produces:
  - `ReviewLaunchPlanner.ReviewPlan plan(Path reviewRoot, Path reviewDirectory, ManifestEntry entry, byte[] validatedEnglish)`
  - `ReviewLaunchPlanner.ReviewPlan(String baselineState, List<ReviewLaunchPlanner.ReviewTarget> targets)`
  - `ReviewLaunchPlanner.ReviewTarget(String language, Path proposedPath, Path publishedPath)`
  - `ReviewLaunchPlanner.ReviewLaunchException.status() -> String`
  - `ReviewLaunchPlanner.ReviewLaunchException.field() -> String`

- [ ] **Step 1: Write failing absent, complete, and partial-pair tests**

Create `ReviewLaunchPlannerTest` in package
`dev.eugene.astroexport.review` with these core tests and helpers:

```java
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
```

Use these imports:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
```

- [ ] **Step 2: Run the planner test and verify RED**

Run:

```bash
cd exporter-java
mvn test -Dtest=ReviewLaunchPlannerTest
```

Expected: compilation fails because `ReviewLaunchPlanner` does not exist.

- [ ] **Step 3: Implement the minimal plan model and baseline classification**

Create `ReviewLaunchPlanner.java` with these types and core flow:

```java
package dev.eugene.astroexport.review;

import dev.eugene.astroexport.fs.JnaFileDescriptor;
import dev.eugene.astroexport.model.ManifestEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Builds safe, editor-neutral file targets for one translation review. */
public final class ReviewLaunchPlanner {
  private final SafeReader safeReader;

  public ReviewLaunchPlanner() {
    this(ReviewLaunchPlanner::readSafeUtf8);
  }

  ReviewLaunchPlanner(SafeReader safeReader) {
    this.safeReader = Objects.requireNonNull(safeReader, "safeReader");
  }

  public ReviewPlan plan(
      Path reviewRoot,
      Path reviewDirectory,
      ManifestEntry entry,
      byte[] validatedEnglish) {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(validatedEnglish, "validatedEnglish");
    Path root = realDirectory(reviewRoot, "review root");
    Path page = realDirectory(reviewDirectory, "review directory");
    if (!page.startsWith(root)) {
      throw proposalFailure("Review directory escapes the review root.");
    }

    Path proposedRu = page.resolve("ru.md");
    Path proposedEn = page.resolve("en.md");
    byte[] russian = readProposal(proposedRu, "Russian proposal");
    byte[] english = readProposal(proposedEn, "English proposal");
    byte[] expectedRussian =
        ReviewWorkspace.renderRuReview(entry).getBytes(StandardCharsets.UTF_8);
    if (!Arrays.equals(expectedRussian, russian)) {
      throw proposalFailure(
          "Russian review does not match the current normalized source; run prepare again.");
    }
    if (!Arrays.equals(validatedEnglish, english)) {
      throw proposalFailure(
          "English review changed after freshness validation; inspect it again.");
    }

    Path publishedDirectory = page.resolve("published");
    if (!Files.exists(publishedDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return new ReviewPlan("absent", List.of(
          new ReviewTarget("ru", proposedRu, null),
          new ReviewTarget("en", proposedEn, null)));
    }
    if (Files.isSymbolicLink(publishedDirectory)
        || !Files.isDirectory(publishedDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw publishedFailure("Published snapshot path must be a non-symbolic directory.");
    }
    Path realPublished;
    try {
      realPublished = publishedDirectory.toRealPath();
    } catch (IOException error) {
      throw publishedFailure("Published snapshot directory is unreadable.", error);
    }
    if (!realPublished.startsWith(page)) {
      throw publishedFailure("Published snapshot directory escapes the review page.");
    }
    Path publishedRu = realPublished.resolve("ru.md");
    Path publishedEn = realPublished.resolve("en.md");
    boolean hasRu = Files.exists(publishedRu, LinkOption.NOFOLLOW_LINKS);
    boolean hasEn = Files.exists(publishedEn, LinkOption.NOFOLLOW_LINKS);
    if (!hasRu && !hasEn) {
      return new ReviewPlan("absent", List.of(
          new ReviewTarget("ru", proposedRu, null),
          new ReviewTarget("en", proposedEn, null)));
    }
    if (hasRu != hasEn) {
      throw publishedFailure(
          "Published snapshot is incomplete: ru.md and en.md must exist as one pair.");
    }
    readPublished(publishedRu, "Published Russian snapshot");
    readPublished(publishedEn, "Published English snapshot");
    return new ReviewPlan("complete", List.of(
        new ReviewTarget("ru", proposedRu, publishedRu),
        new ReviewTarget("en", proposedEn, publishedEn)));
  }

  private byte[] readProposal(Path path, String label) {
    try {
      return safeReader.read(path, label);
    } catch (IOException | IllegalArgumentException error) {
      throw proposalFailure(label + " is unavailable: " + error.getMessage(), error);
    }
  }

  private void readPublished(Path path, String label) {
    try {
      safeReader.read(path, label);
    } catch (IOException | IllegalArgumentException error) {
      throw publishedFailure(label + " is unsafe or unreadable: " + error.getMessage(), error);
    }
  }

  private static Path realDirectory(Path path, String label) {
    try {
      Path absolute = Objects.requireNonNull(path, label).toAbsolutePath().normalize();
      if (Files.isSymbolicLink(absolute)
          || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(label + " must be a non-symbolic directory.");
      }
      return absolute.toRealPath();
    } catch (IOException | IllegalArgumentException error) {
      throw proposalFailure(label + " is unavailable: " + error.getMessage(), error);
    }
  }

  private static byte[] readSafeUtf8(Path path, String label) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(label + " is missing.");
    }
    try (JnaFileDescriptor descriptor = JnaFileDescriptor.openReadNoFollow(path)) {
      JnaFileDescriptor.Snapshot snapshot = descriptor.snapshot();
      if (!snapshot.attributes().isRegularFile()) {
        throw new IllegalArgumentException(label + " must be a regular file.");
      }
      if (snapshot.linkCount() != 1) {
        throw new IllegalArgumentException(label + " must have exactly one hard link.");
      }
      byte[] bytes = descriptor.readAllBytes();
      decodeUtf8(bytes, label);
      return bytes;
    }
  }

  private static void decodeUtf8(byte[] bytes, String label) {
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(label + " must be valid UTF-8.", error);
    }
  }

  private static ReviewLaunchException proposalFailure(String message) {
    return proposalFailure(message, null);
  }

  private static ReviewLaunchException proposalFailure(String message, Throwable cause) {
    return new ReviewLaunchException("stale", "translation", message, cause);
  }

  private static ReviewLaunchException publishedFailure(String message) {
    return publishedFailure(message, null);
  }

  private static ReviewLaunchException publishedFailure(String message, Throwable cause) {
    return new ReviewLaunchException(
        "published_snapshot_inconsistent", "published-snapshot", message, cause);
  }

  @FunctionalInterface
  interface SafeReader {
    byte[] read(Path path, String label) throws IOException;
  }

  public record ReviewPlan(String baselineState, List<ReviewTarget> targets) {
    public ReviewPlan {
      baselineState = Objects.requireNonNull(baselineState, "baselineState");
      targets = List.copyOf(targets);
    }
  }

  public record ReviewTarget(
      String language,
      Path proposedPath,
      Path publishedPath) {
    public ReviewTarget {
      language = Objects.requireNonNull(language, "language");
      proposedPath = Objects.requireNonNull(proposedPath, "proposedPath");
    }
  }

  public static final class ReviewLaunchException extends IllegalArgumentException {
    private final String status;
    private final String field;

    ReviewLaunchException(
        String status,
        String field,
        String message,
        Throwable cause) {
      super(message, cause);
      this.status = status;
      this.field = field;
    }

    public String status() {
      return status;
    }

    public String field() {
      return field;
    }
  }
}
```

- [ ] **Step 4: Run the core planner tests**

Run:

```bash
cd exporter-java
mvn test -Dtest=ReviewLaunchPlannerTest
```

Expected: PASS for absent, complete, and both partial-pair cases.

- [ ] **Step 5: Add unsafe and stale-artifact regression tests**

Add these edge-case regression tests to `ReviewLaunchPlannerTest`:

```java
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
```

Add `import java.io.IOException;`.

Expected: PASS if Step 3 implemented every fail-closed branch exactly. Any
missed symlink, hard-link, type, UTF-8, read-failure, or containment check
fails here before the planner commit.

- [ ] **Step 6: Tighten the planner until every unsafe case passes**

Keep `SafeReader` package-private for injected read-failure coverage. Ensure
the production reader:

- opens leaves through `JnaFileDescriptor.openReadNoFollow`;
- accepts only `snapshot.attributes().isRegularFile()`;
- requires `snapshot.linkCount() == 1`;
- validates UTF-8 with `CodingErrorAction.REPORT`;
- maps proposal failures to `stale/translation`;
- maps published failures to
  `published_snapshot_inconsistent/published-snapshot`.

Do not add automatic cache repair or deletion.

- [ ] **Step 7: Run focused and package-level tests**

Run:

```bash
cd exporter-java
mvn test -Dtest=ReviewLaunchPlannerTest,ReviewWorkspaceTest,PublishedSnapshotStoreTest
```

Expected: PASS.

- [ ] **Step 8: Commit the planner**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java
git add exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java
git commit -m "feat(exporter): plan translation review targets"
```

---

### Task 2: Expose review plans through bridge schema version 2

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`

**Interfaces:**
- Consumes:
  - `ReviewLaunchPlanner.plan(...)`
  - `ReviewPairState.content()` from fresh English validation
- Produces:
  - `BridgeResponse.SCHEMA_VERSION == 2`
  - `BridgeResponse.Builder.reviewPlan(ReviewLaunchPlanner.ReviewPlan plan)`
  - nullable top-level `reviewPlan`

- [ ] **Step 1: Change command tests to require schema version 2 and an absent plan**

In `AstroExportCommandTest`:

1. Insert `"reviewPlan"` immediately after `"translationStatus"` in
   `BRIDGE_KEYS`.
2. Change exact schema assertions from `1` to `2`.
3. In `inspectBridgeHasExactSchemaAndIsReadOnlyWithWorkspaceHealth`, write
   the proposed Russian review before taking the tree snapshot:

```java
ReviewWorkspace.writeRuReviewFile(review, entry);
writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
```

4. Add these plan assertions:

```java
Map<?, ?> reviewPlan = (Map<?, ?>) payload.get("reviewPlan");
assertEquals("absent", reviewPlan.get("baselineState"));
List<?> targets = (List<?>) reviewPlan.get("targets");
assertEquals(2, targets.size());
```

Implement the assertion helpers with nullable `publishedPath` support:

```java
private static Map<String, Object> nullableMap(Map<?, ?> source) {
  LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
  source.forEach((key, value) -> copy.put(String.valueOf(key), value));
  return copy;
}
```

```java
private static Map<String, Object> target(
    String language,
    Path proposed,
    Path published) {
  LinkedHashMap<String, Object> target = new LinkedHashMap<>();
  target.put("language", language);
  target.put("proposedPath", proposed.toAbsolutePath().normalize().toString());
  target.put("publishedPath",
      published == null ? null : published.toAbsolutePath().normalize().toString());
  return target;
}
```

Then assert:

```java
assertEquals(target("ru", review.resolve("blog/essay/ru.md"), null),
    nullableMap((Map<?, ?>) targets.get(0)));
assertEquals(target("en", review.resolve("blog/essay/en.md"), null),
    nullableMap((Map<?, ?>) targets.get(1)));
```

Add `assertEquals(null, payload.get("reviewPlan"));` to the prepare response
test and to `assertNonRefreshBridgeIoFailure`.

- [ ] **Step 2: Add failing complete and inconsistent plan command tests**

Add:

```java
@Test
void inspectBridgeReturnsCompletePublishedComparisonPlan() throws Exception {
  Path vault = temp.resolve("vault");
  writeBlogNote(vault);
  Path review = temp.resolve("review");
  ManifestEntry entry = currentBlogEntry(vault);
  ReviewWorkspace.writeRuReviewFile(review, entry);
  writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
  ReviewWorkspace.writePublishedSnapshot(
      review, "blog", "essay", "approved Russian\n", "approved English\n");

  CommandFixture.Result result = run(command(),
      "inspect-publication",
      "--vault", vault.toString(),
      "--note", "anywhere/Essay.md",
      "--review", review.toString(),
      "--json");

  assertEquals(0, result.exitCode(), result.stderr());
  Map<String, Object> payload = json(result.stdout());
  Map<?, ?> plan = (Map<?, ?>) payload.get("reviewPlan");
  assertEquals("complete", plan.get("baselineState"));
  List<?> targets = (List<?>) plan.get("targets");
  assertEquals(target(
      "ru",
      review.resolve("blog/essay/ru.md"),
      review.resolve("blog/essay/published/ru.md")),
      nullableMap((Map<?, ?>) targets.get(0)));
  assertEquals(target(
      "en",
      review.resolve("blog/essay/en.md"),
      review.resolve("blog/essay/published/en.md")),
      nullableMap((Map<?, ?>) targets.get(1)));
}

@Test
void inspectBridgeBlocksPartialPublishedSnapshotWithoutChangingFiles() throws Exception {
  Path vault = temp.resolve("vault");
  writeBlogNote(vault);
  Path review = temp.resolve("review");
  ManifestEntry entry = currentBlogEntry(vault);
  ReviewWorkspace.writeRuReviewFile(review, entry);
  writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
  Path published = review.resolve("blog/essay/published");
  Files.createDirectories(published);
  Files.writeString(published.resolve("ru.md"), "approved Russian\n");
  Map<String, ByteBuffer> beforeVault = treeSnapshot(vault);
  Map<String, ByteBuffer> beforeReview = treeSnapshot(review);

  CommandFixture.Result result = run(command(),
      "inspect-publication",
      "--vault", vault.toString(),
      "--note", "anywhere/Essay.md",
      "--review", review.toString(),
      "--json");

  assertEquals(1, result.exitCode());
  Map<String, Object> payload = json(result.stdout());
  assertEquals(false, payload.get("ok"));
  assertEquals("published_snapshot_inconsistent", payload.get("status"));
  assertEquals(null, payload.get("reviewPlan"));
  assertEquals("published-snapshot", firstDiagnostic(payload).get("field"));
  assertEquals(true, firstDiagnostic(payload).get("blocking"));
  assertEquals(beforeVault, treeSnapshot(vault));
  assertEquals(beforeReview, treeSnapshot(review));
}
```

- [ ] **Step 3: Run command tests and verify RED**

Run:

```bash
cd exporter-java
mvn test -Dtest=AstroExportCommandTest
```

Expected: failures for schema version, missing `reviewPlan`, and missing
planner integration.

- [ ] **Step 4: Add version-2 plan serialization to `BridgeResponse`**

Change:

```java
public static final int SCHEMA_VERSION = 2;
```

Add:

```java
import dev.eugene.astroexport.review.ReviewLaunchPlanner;
```

Add a builder field and method:

```java
private ReviewLaunchPlanner.ReviewPlan reviewPlan;

public Builder reviewPlan(ReviewLaunchPlanner.ReviewPlan reviewPlan) {
  this.reviewPlan = reviewPlan;
  return this;
}
```

Insert after `translationStatus` in `build()`:

```java
values.put("reviewPlan", reviewPlanPayload(reviewPlan));
```

Serialize with stable insertion order:

```java
private static Map<String, Object> reviewPlanPayload(
    ReviewLaunchPlanner.ReviewPlan plan) {
  if (plan == null) {
    return null;
  }
  LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
  payload.put("baselineState", plan.baselineState());
  payload.put("targets",
      plan.targets().stream().map(BridgeResponse::reviewTargetPayload).toList());
  return payload;
}

private static Map<String, Object> reviewTargetPayload(
    ReviewLaunchPlanner.ReviewTarget target) {
  LinkedHashMap<String, Object> item = new LinkedHashMap<>();
  item.put("language", target.language());
  item.put("proposedPath", target.proposedPath().toString());
  item.put("publishedPath",
      target.publishedPath() == null ? null : target.publishedPath().toString());
  return item;
}
```

- [ ] **Step 5: Integrate the planner into successful inspection**

Import `ReviewLaunchPlanner` in `AstroExportCommand`. After `fresh` is known
true, construct the plan from the exact validated English bytes:

```java
ReviewLaunchPlanner.ReviewPlan reviewPlan = null;
if (fresh) {
  try {
    reviewPlan = new ReviewLaunchPlanner().plan(
        reviewRoot,
        identity.reviewDirectory(),
        preflight.entry(),
        pair.content());
  } catch (ReviewLaunchPlanner.ReviewLaunchException error) {
    emitJson(bridge("inspect-publication", false, error.status())
        .note(note)
        .identity(identity)
        .diagnostics(List.of(new PublicationDiagnostic(
            error.field(), error.getMessage())))
        .workspaceHealth(preflight.workspaceHealth())
        .pairFreshness(pair.freshness())
        .translationStatus(pair.translationStatus())
        .build());
    return 1;
  }
}
```

Add `.reviewPlan(reviewPlan)` to the existing success response. Leave stale,
invalid, metadata, I/O, prepare, mark-reviewed, and refresh responses with
the builder default `null`.

Do not run the planner before English freshness succeeds.

- [ ] **Step 6: Run focused Java tests**

Run:

```bash
cd exporter-java
mvn test -Dtest=ReviewLaunchPlannerTest,AstroExportCommandTest
```

Expected: PASS.

- [ ] **Step 7: Run the full JVM suite**

Run:

```bash
cd exporter-java
mvn test
```

Expected: all Java tests pass with every bridge response using schema version
2 and an explicit nullable `reviewPlan`.

- [ ] **Step 8: Commit bridge version 2**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java
git add exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java
git add exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java
git commit -m "feat(exporter): expose review plans in bridge v2"
```

---

### Task 3: Make both JavaScript bridge clients require schema version 2

**Files:**
- Modify: `obsidian-plugin/bridge-client.js`
- Modify: `obsidian-plugin/main.js:6-230`
- Modify: `obsidian-plugin/tests/bridge-client.test.cjs:24-305`

**Interfaces:**
- Consumes: exporter bridge JSON
- Produces:
  - `parseResponse(...)` accepts only `schemaVersion: 2`
  - `BridgeClientError.code == "schema_mismatch"` for any other numeric version
  - response fixtures always include nullable `reviewPlan`

- [ ] **Step 1: Update fixtures and write a failing schema-mismatch test**

Change the test response helper to:

```js
function response(command, overrides = {}) {
  return {
    schemaVersion: 2,
    command,
    ok: true,
    status: "ready_for_review",
    note: "concepts/Boundary; note.md",
    collection: "concepts",
    publicId: "boundary-note",
    reviewDirectory: "/tmp/review/concepts/boundary-note",
    pairFreshness: "fresh",
    translationStatus: "generated",
    reviewPlan: null,
    diagnostics: [],
    workspaceHealth: [],
    jobId: "job-1",
    ...overrides,
  };
}
```

Add:

```js
test("schema version mismatch names both observed and expected versions", async () => {
  const { BridgeClientError } = bridgeExports();
  const payload = response("inspect-publication", { schemaVersion: 1 });
  const fake = fakeSpawnResult({ stdout: JSON.stringify(payload) });
  const client = clientWith(fake);

  await assert.rejects(
    client.run("inspect-publication", "concepts/Current.md"),
    (error) => {
      assert.ok(error instanceof BridgeClientError);
      assert.equal(error.code, "schema_mismatch");
      assert.match(error.diagnostic.message, /версию схемы 1/);
      assert.match(error.diagnostic.message, /ожидается версия 2/);
      assert.match(error.diagnostic.message, /пересоберите exporter/i);
      return true;
    },
  );
});
```

- [ ] **Step 2: Run the plugin test and verify RED**

Run the repository-local plugin tests while skipping the unrelated external
community-plugin fixture:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: schema fixture tests fail because both parsers still require
version 1; the new mismatch test receives `invalid_json`.

- [ ] **Step 3: Implement explicit schema validation in `bridge-client.js`**

Immediately after JSON parsing, before `validObject`, add:

```js
if (
  payload !== null &&
  typeof payload === "object" &&
  !Array.isArray(payload) &&
  Number.isInteger(payload.schemaVersion) &&
  payload.schemaVersion !== 2
) {
  throw new BridgeClientError(
    "schema_mismatch",
    `Exporter вернул версию схемы ${payload.schemaVersion}; ожидается версия 2. ` +
      "Пересоберите exporter и перезагрузите Obsidian plugin.",
  );
}
```

Change the normal predicate to:

```js
payload.schemaVersion === 2
```

- [ ] **Step 4: Mirror the exact parser change in `main.js`**

Apply the identical version check and Russian diagnostic inside the inlined
`createBridgeClient` block in `main.js`. Do not add a relative runtime
`require`.

- [ ] **Step 5: Run the bridge and host-loading tests**

Run:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: PASS, including shipped-main loading and the explicit version
mismatch.

- [ ] **Step 6: Confirm the readable and inlined clients stay synchronized**

Run:

```bash
rg -n "schemaVersion.*2|schema_mismatch|ожидается версия 2" obsidian-plugin/bridge-client.js obsidian-plugin/main.js
```

Expected: both files contain the same schema predicate, error code, and user
message.

- [ ] **Step 7: Commit the protocol update**

```bash
git add obsidian-plugin/bridge-client.js
git add obsidian-plugin/main.js
git add obsidian-plugin/tests/bridge-client.test.cjs
git commit -m "feat(obsidian-plugin): require bridge schema v2"
```

---

### Task 4: Add the testable Zed launcher and setting

**Files:**
- Modify: `obsidian-plugin/main.js:1-2,265-390`
- Modify: `obsidian-plugin/tests/bridge-client.test.cjs:322-623`

**Interfaces:**
- Consumes: version-2 `reviewPlan`
- Produces:
  - setting `settings.zedCli`
  - `validateReviewPlan(plan) -> ReviewTarget[]` or throws
  - `AstroPublicationWorkflowPlugin.launchReviewPlan(plan) -> Promise<{ok, diagnostics}>`

- [ ] **Step 1: Extend the test harness with fake filesystem and process dependencies**

Change `loadPluginHarness` parameters to accept:

```js
function loadPluginHarness({
  savedData = null,
  existsSync = fs.existsSync,
  lstatSync = () => ({ isFile: () => true }),
  accessSync = () => {},
  spawn = fakeSpawnResult().spawn,
  homeDirectory = os.homedir(),
} = {}) {
```

Return the full `node:fs` surface required by the shipped plugin:

```js
if (request === "node:fs") {
  return {
    existsSync,
    lstatSync,
    accessSync,
    constants: fs.constants,
  };
}
if (request === "node:child_process") return { spawn };
```

Update `loadShippedMainWithHostRequire` so its
`"node:child_process"` entry supplies `spawn() {}` and its `"node:fs"`
entry supplies real `fs`.

Add:

```js
function reviewPlan(baselineState = "absent") {
  const complete = baselineState === "complete";
  return {
    baselineState,
    targets: [
      {
        language: "ru",
        proposedPath: "/review/blog/essay/ru.md",
        publishedPath: complete ? "/review/blog/essay/published/ru.md" : null,
      },
      {
        language: "en",
        proposedPath: "/review/blog/essay/en.md",
        publishedPath: complete ? "/review/blog/essay/published/en.md" : null,
      },
    ],
  };
}

function sequenceSpawn(results) {
  const calls = [];
  const spawn = (executable, args, options) => {
    const result = results[calls.length] || { exitCode: 0, stderr: "" };
    calls.push({ executable, args, options });
    const child = new EventEmitter();
    child.stderr = new EventEmitter();
    process.nextTick(() => {
      if (result.stderr) child.stderr.emit("data", Buffer.from(result.stderr));
      if (result.error) child.emit("error", result.error);
      child.emit("close", result.exitCode, null);
    });
    return child;
  };
  return { spawn, calls };
}
```

- [ ] **Step 2: Write failing settings and exact-argument tests**

Add:

```js
test("Zed CLI setting has a macOS app default and preserves an explicit value", async () => {
  const defaultHarness = loadPluginHarness();
  const defaultPlugin = new defaultHarness.PluginClass(defaultHarness.app);
  await defaultPlugin.onload();
  assert.equal(
    defaultPlugin.settings.zedCli,
    "/Applications/Zed.app/Contents/MacOS/cli",
  );

  const explicitHarness = loadPluginHarness({
    savedData: { zedCli: "/custom/Zed.app/Contents/MacOS/cli" },
  });
  const explicitPlugin = new explicitHarness.PluginClass(explicitHarness.app);
  await explicitPlugin.onload();
  assert.equal(
    explicitPlugin.settings.zedCli,
    "/custom/Zed.app/Contents/MacOS/cli",
  );
  explicitPlugin.settings.zedCli = "/saved/Zed.app/Contents/MacOS/cli";
  await explicitPlugin.saveSettings();
  assert.equal(
    explicitPlugin.savedData.zedCli,
    "/saved/Zed.app/Contents/MacOS/cli",
  );
});

test("absent baseline launches proposed RU and EN in separate new workspaces", async () => {
  const process = sequenceSpawn([{ exitCode: 0 }, { exitCode: 0 }]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();

  const result = await plugin.launchReviewPlan(reviewPlan("absent"));

  assert.deepEqual(result, { ok: true, diagnostics: [] });
  assert.deepEqual(process.calls.map(({ executable, args, options }) => ({
    executable, args, options,
  })), [
    {
      executable: "/Applications/Zed.app/Contents/MacOS/cli",
      args: ["-n", "/review/blog/essay/ru.md"],
      options: {
        shell: false,
        windowsHide: true,
        stdio: ["ignore", "ignore", "pipe"],
      },
    },
    {
      executable: "/Applications/Zed.app/Contents/MacOS/cli",
      args: ["-n", "/review/blog/essay/en.md"],
      options: {
        shell: false,
        windowsHide: true,
        stdio: ["ignore", "ignore", "pipe"],
      },
    },
  ]);
});

test("complete baseline launches published-to-proposed RU and EN diffs", async () => {
  const process = sequenceSpawn([{ exitCode: 0 }, { exitCode: 0 }]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();

  const result = await plugin.launchReviewPlan(reviewPlan("complete"));

  assert.equal(result.ok, true);
  assert.deepEqual(process.calls.map(({ args }) => args), [
    [
      "-n",
      "--diff",
      "/review/blog/essay/published/ru.md",
      "/review/blog/essay/ru.md",
    ],
    [
      "-n",
      "--diff",
      "/review/blog/essay/published/en.md",
      "/review/blog/essay/en.md",
    ],
  ]);
});
```

- [ ] **Step 3: Write failing preflight, malformed-plan, and partial-launch tests**

Add:

```js
test("missing or non-executable Zed CLI blocks both launches", async () => {
  for (const harnessOptions of [
    {
      savedData: { zedCli: "relative/Zed/cli" },
    },
    {
      lstatSync() {
        throw Object.assign(new Error("missing"), { code: "ENOENT" });
      },
    },
    {
      lstatSync() {
        return { isFile: () => false };
      },
    },
    {
      accessSync() {
        throw Object.assign(new Error("denied"), { code: "EACCES" });
      },
    },
  ]) {
    const process = sequenceSpawn([]);
    const harness = loadPluginHarness({
      ...harnessOptions,
      spawn: process.spawn,
    });
    const plugin = new harness.PluginClass(harness.app);
    await plugin.onload();

    const result = await plugin.launchReviewPlan(reviewPlan("absent"));

    assert.equal(result.ok, false);
    assert.equal(process.calls.length, 0);
    assert.equal(result.diagnostics[0].field, "zed");
  }
});

test("malformed review plan blocks before Zed preflight", async () => {
  const process = sequenceSpawn([]);
  const harness = loadPluginHarness({
    spawn: process.spawn,
    lstatSync() {
      throw new Error("CLI preflight must not run");
    },
  });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();

  const malformed = reviewPlan("complete");
  malformed.targets.reverse();
  const result = await plugin.launchReviewPlan(malformed);

  assert.equal(result.ok, false);
  assert.equal(result.diagnostics[0].field, "review-plan");
  assert.equal(process.calls.length, 0);
});

test("one failed language still attempts the other and returns no success", async () => {
  const process = sequenceSpawn([
    { exitCode: 1, stderr: "RU failed" },
    { exitCode: 0 },
  ]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();

  const result = await plugin.launchReviewPlan(reviewPlan("complete"));

  assert.equal(result.ok, false);
  assert.equal(process.calls.length, 2);
  assert.deepEqual(result.diagnostics.map(({ field }) => field), ["zed-ru"]);
  assert.match(result.diagnostics[0].message, /RU failed/);
});
```

Also add this concrete malformed-plan table:

```js
test("every malformed plan is rejected before process launch", async () => {
  const mutations = [
    (plan) => { plan.baselineState = "unknown"; },
    (plan) => { plan.targets.pop(); },
    (plan) => { plan.targets[0].proposedPath = "relative/ru.md"; },
    (plan) => { plan.targets[0].publishedPath = "/unexpected/ru.md"; },
    (plan) => {
      plan.baselineState = "complete";
      plan.targets[0].publishedPath = null;
      plan.targets[1].publishedPath = "/published/en.md";
    },
    (plan) => { plan.targets[1].language = "de"; },
  ];

  for (const mutate of mutations) {
    const process = sequenceSpawn([]);
    const harness = loadPluginHarness({ spawn: process.spawn });
    const plugin = new harness.PluginClass(harness.app);
    await plugin.onload();
    const plan = reviewPlan("absent");
    mutate(plan);

    const result = await plugin.launchReviewPlan(plan);

    assert.equal(result.ok, false);
    assert.equal(result.diagnostics[0].field, "review-plan");
    assert.equal(process.calls.length, 0);
  }
});
```

- [ ] **Step 4: Run the plugin test and verify RED**

Run:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: failures because there is no Zed setting or launcher.

- [ ] **Step 5: Implement strict plan and CLI validation**

At the top level of `main.js`, require:

```js
const fs = require("node:fs");
const { spawn: spawnProcess } = require("node:child_process");
```

Add:

```js
const DEFAULT_ZED_CLI = "/Applications/Zed.app/Contents/MacOS/cli";

function validateReviewPlan(plan) {
  if (!plan || !["absent", "complete"].includes(plan.baselineState)) {
    throw new Error("Exporter вернул неизвестное состояние published baseline.");
  }
  if (!Array.isArray(plan.targets) || plan.targets.length !== 2) {
    throw new Error("Exporter должен вернуть ровно две цели проверки.");
  }
  const expectedLanguages = ["ru", "en"];
  return plan.targets.map((target, index) => {
    if (!target || target.language !== expectedLanguages[index]) {
      throw new Error("Цели проверки должны быть упорядочены как ru, затем en.");
    }
    if (
      typeof target.proposedPath !== "string" ||
      !path.isAbsolute(target.proposedPath)
    ) {
      throw new Error(`Exporter вернул некорректный proposed path для ${target.language}.`);
    }
    if (plan.baselineState === "absent" && target.publishedPath !== null) {
      throw new Error(`Absent baseline не должен содержать published path для ${target.language}.`);
    }
    if (
      plan.baselineState === "complete" &&
      (typeof target.publishedPath !== "string" ||
        !path.isAbsolute(target.publishedPath))
    ) {
      throw new Error(`Complete baseline требует published path для ${target.language}.`);
    }
    return target;
  });
}

function zedCliDiagnostic(zedCli) {
  if (typeof zedCli !== "string" || !path.isAbsolute(zedCli)) {
    return localDiagnostic("Укажите абсолютный путь к Zed CLI.", "zed");
  }
  try {
    const stats = fs.lstatSync(zedCli);
    if (!stats.isFile()) {
      return localDiagnostic("Zed CLI должен быть обычным исполняемым файлом.", "zed");
    }
    fs.accessSync(zedCli, fs.constants.X_OK);
    return null;
  } catch (_error) {
    return localDiagnostic(
      `Zed CLI недоступен или не исполняется: ${zedCli}.`,
      "zed",
    );
  }
}
```

Change `localDiagnostic` to accept a field:

```js
function localDiagnostic(message, field = "bridge") {
  return { field, message, blocking: true };
}
```

- [ ] **Step 6: Implement deterministic two-target process handling**

Add:

```js
function zedArgs(baselineState, target) {
  return baselineState === "complete"
    ? ["-n", "--diff", target.publishedPath, target.proposedPath]
    : ["-n", target.proposedPath];
}

function runZedTarget(zedCli, baselineState, target) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawnProcess(zedCli, zedArgs(baselineState, target), {
        shell: false,
        windowsHide: true,
        stdio: ["ignore", "ignore", "pipe"],
      });
    } catch (_error) {
      resolve(localDiagnostic(
        `Не удалось запустить окно Zed для ${target.language.toUpperCase()}.`,
        `zed-${target.language}`,
      ));
      return;
    }
    let stderr = "";
    let settled = false;
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", () => {
      if (settled) return;
      settled = true;
      resolve(localDiagnostic(
        `Не удалось запустить окно Zed для ${target.language.toUpperCase()}.`,
        `zed-${target.language}`,
      ));
    });
    child.on("close", (exitCode, signal) => {
      if (settled) return;
      settled = true;
      if (exitCode === 0) {
        resolve(null);
        return;
      }
      const detail = stderr.trim();
      resolve(localDiagnostic(
        `Zed не принял ${target.language.toUpperCase()} review` +
          `${signal ? `; signal ${signal}` : `; exit ${exitCode}`}` +
          `${detail ? `: ${detail}` : "."}`,
        `zed-${target.language}`,
      ));
    });
  });
}
```

Add this plugin method:

```js
async launchReviewPlan(plan) {
  let targets;
  try {
    targets = validateReviewPlan(plan);
  } catch (error) {
    return {
      ok: false,
      diagnostics: [localDiagnostic(error.message, "review-plan")],
    };
  }
  const cliFailure = zedCliDiagnostic(this.settings.zedCli);
  if (cliFailure) {
    return { ok: false, diagnostics: [cliFailure] };
  }
  const diagnostics = [];
  for (const target of targets) {
    const diagnostic = await runZedTarget(
      this.settings.zedCli,
      plan.baselineState,
      target,
    );
    if (diagnostic) diagnostics.push(diagnostic);
  }
  return { ok: diagnostics.length === 0, diagnostics };
}
```

- [ ] **Step 7: Add and persist the Zed CLI setting**

Add to `onload()`:

```js
zedCli: saved.zedCli || DEFAULT_ZED_CLI,
```

Add to `saveSettings()`:

```js
zedCli: this.settings.zedCli,
```

Add to `PublicationWorkflowSettingTab.display()`:

```js
new Setting(containerEl)
  .setName("Zed CLI")
  .setDesc("Абсолютный путь к CLI внутри Zed.app; каждая языковая версия открывается в новом окне.")
  .addText((text) => text
    .setPlaceholder(DEFAULT_ZED_CLI)
    .setValue(this.plugin.settings.zedCli)
    .onChange(async (value) => {
      this.plugin.settings.zedCli = value.trim();
      await this.plugin.saveSettings();
    }));
```

Update existing exact settings assertions to include:

```js
zedCli: "/Applications/Zed.app/Contents/MacOS/cli",
```

- [ ] **Step 8: Run all plugin tests**

Run:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: PASS with exact plain-file and diff argument order, strict
preflight, no shell, and partial-launch diagnostics.

- [ ] **Step 9: Commit the launcher**

```bash
git add obsidian-plugin/main.js
git add obsidian-plugin/tests/bridge-client.test.cjs
git commit -m "feat(obsidian-plugin): launch Zed review targets"
```

---

### Task 5: Route both review entry points through inspect-and-launch

**Files:**
- Modify: `obsidian-plugin/main.js:232-484`
- Modify: `obsidian-plugin/tests/bridge-client.test.cjs:625-725`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:537-543`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java:1317-1345`

**Interfaces:**
- Consumes:
  - `bridgeClient.run("inspect-publication", notePath)`
  - `launchReviewPlan(result.reviewPlan)`
- Produces:
  - `inspectAndOpenReview(notePath) -> Promise<boolean>`
  - command and post-prepare button both call that method

- [ ] **Step 1: Replace the folder-opening test with a failing two-window workflow test**

Replace
`"open review uses inspect-publication and only the bridge-confirmed directory"`
with:

```js
test("open review inspects the active note and launches the exporter plan", async () => {
  const process = sequenceSpawn([{ exitCode: 0 }, { exitCode: 0 }]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(...args) {
      bridgeCalls.push(args);
      return response("inspect-publication", {
        reviewPlan: reviewPlan("complete"),
      });
    },
  };

  await command(plugin, "open-current-translation-review").callback();

  assert.deepEqual(bridgeCalls, [
    ["inspect-publication", "concepts/Current.md"],
  ]);
  assert.equal(process.calls.length, 2);
  assert.ok(harness.notices.some(
    ({ message }) => message === "Проверка перевода открыта в двух окнах Zed.",
  ));
});
```

- [ ] **Step 2: Add a failing captured-note test for the post-prepare button**

Add a recursive helper:

```js
function findElement(root, predicate) {
  if (predicate(root)) return root;
  for (const child of root.children) {
    const found = findElement(child, predicate);
    if (found) return found;
  }
  return null;
}
```

Add:

```js
test("post-prepare review button re-inspects the prepared note after focus changes", async () => {
  const process = sequenceSpawn([{ exitCode: 0 }, { exitCode: 0 }]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile =
    new harness.FakeTFile("concepts/Prepared.md");
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(commandName, notePath) {
      bridgeCalls.push([commandName, notePath]);
      if (commandName === "prepare") {
        return response("prepare", { note: notePath, reviewPlan: null });
      }
      return response("inspect-publication", {
        note: notePath,
        reviewPlan: reviewPlan("absent"),
      });
    },
  };

  await command(plugin, "prepare-current-note-for-public-site").callback();
  const modal = harness.modals.at(-1);
  const button = findElement(
    modal.contentEl,
    (element) => element.ownText === "Открыть проверку",
  );
  assert.ok(button);
  harness.app.workspace.activeFile =
    new harness.FakeTFile("concepts/Other.md");

  await button.listeners.click();

  assert.deepEqual(bridgeCalls, [
    ["prepare", "concepts/Prepared.md"],
    ["inspect-publication", "concepts/Prepared.md"],
  ]);
  assert.deepEqual(process.calls.map(({ args }) => args), [
    ["-n", "/review/blog/essay/ru.md"],
    ["-n", "/review/blog/essay/en.md"],
  ]);
});
```

- [ ] **Step 3: Add failing blocked and partial-Zed UI assertions**

Add:

```js
test("blocked inspection launches no Zed window and shows exporter diagnostics", async () => {
  const process = sequenceSpawn([]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  plugin.bridgeClient = {
    async run() {
      return response("inspect-publication", {
        ok: false,
        status: "published_snapshot_inconsistent",
        reviewPlan: null,
        diagnostics: [{
          field: "published-snapshot",
          message: "Published snapshot is incomplete.",
          blocking: true,
        }],
      });
    },
  };

  await command(plugin, "open-current-translation-review").callback();

  assert.equal(process.calls.length, 0);
  assert.match(harness.modals.at(-1).contentEl.text(), /published-snapshot/);
  assert.equal(
    harness.notices.some(({ message }) => /двух окнах Zed/.test(message)),
    false,
  );
});

test("partial Zed launch shows language diagnostics without success", async () => {
  const process = sequenceSpawn([
    { exitCode: 0 },
    { exitCode: 1, stderr: "EN rejected" },
  ]);
  const harness = loadPluginHarness({ spawn: process.spawn });
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  plugin.bridgeClient = {
    async run() {
      return response("inspect-publication", {
        reviewPlan: reviewPlan("complete"),
      });
    },
  };

  await command(plugin, "open-current-translation-review").callback();

  assert.equal(process.calls.length, 2);
  assert.match(harness.modals.at(-1).contentEl.text(), /zed-en/);
  assert.equal(
    harness.notices.some(({ message }) => /двух окнах Zed/.test(message)),
    false,
  );
});
```

- [ ] **Step 4: Make the generated publication contract test require Zed wording**

In
`migrateOverridesAndPublicationContractCommandsMatchOperatorSurface`, add:

```java
assertTrue(text.contains("Open current translation review"));
assertTrue(text.contains("два новых окна Zed"));
assertFalse(text.contains("открывает внешний каталог"));
```

- [ ] **Step 5: Run UI and contract tests and verify RED**

Run:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: folder-opening behavior and old modal callback make the new tests
fail.

Run:

```bash
cd exporter-java
mvn test -Dtest=AstroExportCommandTest
```

Expected: the generated contract still describes opening an external
directory.

- [ ] **Step 6: Implement the shared inspect-and-launch helper**

Remove:

```js
const { shell } = require("electron");
```

Delete `hasReviewTarget` and `openConfirmedReview`.

Add:

```js
async inspectAndOpenReview(notePath) {
  const running = new Notice("Проверка внешнего перевода…", 0);
  try {
    const result = await this.bridgeClient.run(
      "inspect-publication",
      notePath,
    );
    if (!result.ok) {
      this.showBlocked(result, "Перевод пока нельзя открыть");
      return false;
    }
    const launched = await this.launchReviewPlan(result.reviewPlan);
    if (!launched.ok) {
      this.showBlocked(
        { diagnostics: launched.diagnostics },
        "Проверка в Zed открыта не полностью",
      );
      return false;
    }
    new Notice("Проверка перевода открыта в двух окнах Zed.");
    return true;
  } catch (error) {
    this.showBridgeError(error);
    return false;
  } finally {
    running.hide();
  }
}
```

Reduce `openCurrentReview()` to:

```js
async openCurrentReview() {
  const file = this.activeMarkdownNote();
  if (!file) return;
  await this.inspectAndOpenReview(file.path);
}
```

- [ ] **Step 7: Capture the prepared note in the modal callback**

In `prepareCurrentNote`, remove the `hasReviewTarget` branch. Replace the
modal creation with:

```js
new ReviewReadyModal(
  this.app,
  () => this.inspectAndOpenReview(file.path),
).open();
```

Update modal copy from the folder-oriented sentence to:

```js
this.contentEl.createEl("p", {
  text: "Откройте русскую и английскую версии для проверки в двух окнах Zed.",
});
```

Keep modal closing after the callback finishes. The diagnostics modal remains
visible if launch fails.

- [ ] **Step 8: Remove every folder-opening test dependency**

Remove `openedPaths` from `loadPluginHarness`, remove the fake Electron
`shell.openPath`, and remove `electron` from
`loadShippedMainWithHostRequire` if no other live code uses it.

Add:

```js
test("shipped review flow has no shell.openPath fallback", () => {
  const source = fs.readFileSync(
    path.resolve(__dirname, "../main.js"),
    "utf8",
  );
  assert.doesNotMatch(source, /shell\.openPath/);
});
```

- [ ] **Step 9: Update the generated author-action description**

Replace the `Open current translation review` entry in
`CommandServices.PublicationContractRenderer.AUTHOR_ACTIONS` with:

```java
Map.entry(
    "Open current translation review",
    "проверяет актуальность пары и открывает RU и EN в два новых окна Zed; "
        + "после первого одобрения открываются сравнения published-to-proposed.")
```

- [ ] **Step 10: Run all focused tests**

Run:

```bash
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: every repository-local plugin test passes. Automated tests must
record fake Zed calls and open no real GUI windows.

Run:

```bash
cd exporter-java
mvn test -Dtest=AstroExportCommandTest
```

Expected: PASS with current review-plan bridge tests and updated author-action
wording.

- [ ] **Step 11: Commit the shared workflow**

```bash
git add obsidian-plugin/main.js
git add obsidian-plugin/tests/bridge-client.test.cjs
git add exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java
git add exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java
git commit -m "feat(obsidian-plugin): share Zed review workflow"
```

---

### Task 6: Document, rebuild, and verify the coordinated feature

**Files:**
- Modify: `README.md`
- Modify: `exporter-java/README.md`
- Modify: `obsidian-plugin/DEPLOY.md`

**Interfaces:**
- Consumes: completed exporter and plugin behavior
- Produces: operator documentation and final verification evidence

- [ ] **Step 1: Update repository pipeline documentation**

Add after pipeline step 2 in `README.md`:

```markdown
The review action asks the exporter for an explicit two-target review plan.
Before the first approval it opens proposed `ru.md` and `en.md`; afterward it
opens published-to-proposed RU and EN diffs. Each target opens in a separate
new Zed workspace window.
```

Keep approval ownership in the following steps unchanged.

- [ ] **Step 2: Document the exporter bridge contract**

Add a **Review launch plans** section to `exporter-java/README.md`:

```markdown
## Review launch plans

`inspect-publication --json` uses bridge schema version 2. A successful
response contains `reviewPlan` with ordered `ru` and `en` targets.

- `baselineState: absent` means neither approved snapshot exists; each target
  has only `proposedPath`.
- `baselineState: complete` means both approved snapshots are safe; each
  target has `publishedPath` and `proposedPath`.
- A partial or unsafe approved pair returns
  `published_snapshot_inconsistent`, a blocking `published-snapshot`
  diagnostic, and no plan.

Inspection is read-only. `mark-reviewed` still revalidates exact bytes and is
the only command that advances `published/`.
```

- [ ] **Step 3: Document Zed and coordinated deployment**

Add to `obsidian-plugin/DEPLOY.md`:

```markdown
## Zed review windows

The plugin setting **Zed CLI** defaults to:

`/Applications/Zed.app/Contents/MacOS/cli`

The review action opens two new Zed workspace windows. With no approved
baseline, they contain proposed RU and EN files. With an approved pair, they
contain published-to-proposed RU and EN diffs. The plugin does not tile or
position the windows.

The exporter and plugin now share bridge schema version 2. Rebuild the native
exporter first, then reload the Obsidian plugin. A schema mismatch is
intentionally blocking and reports which component must be refreshed.
```

Keep the shipped file list as `main.js`, `bridge-client.js`, `manifest.json`,
and `styles.css`. Do not add `data.json` to git.

- [ ] **Step 4: Run documentation and full automated checks**

Run:

```bash
git diff --check
node --test --test-skip-pattern='community plugin enablement' \
  obsidian-plugin/tests/bridge-client.test.cjs
```

Then:

```bash
cd exporter-java
mvn test
```

Expected: whitespace clean; all repository-local plugin tests pass; full Java
suite passes.

Run the complete plugin command once to retain evidence for the unrelated
external fixture:

```bash
node --test obsidian-plugin/tests/bridge-client.test.cjs
```

Expected in the current checkout: all feature tests pass and only
`community plugin enablement retains the live list and adds only this plugin`
fails because `/Users/eugene/Dev/community-plugins.json` is absent. Keep that
pre-existing failure separate from this feature.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git add exporter-java/README.md
git add obsidian-plugin/DEPLOY.md
git commit -m "docs: explain Zed translation review workflow"
```

- [ ] **Step 6: Build the native exporter**

Run:

```bash
cd exporter-java
mvn -Pnative native:compile
```

Expected: exit 0 and executable
`exporter-java/target/astro-export`.

- [ ] **Step 7: Exercise complete and absent plans through the native binary**

The following live pair was fresh on 2026-07-29 and supplies a concrete
read-only source fixture. Copy its review page to private temporary storage
so the absent-baseline check never mutates the live cache:

```bash
SMOKE_ROOT="$(mktemp -d /private/tmp/zed-review-plan.XXXXXX)"
mkdir -p "$SMOKE_ROOT/review/blog"
cp -R \
  /Users/eugene/Documents/personal-wiki/tools/astro-export/review/blog/work-without-learning-is-waste \
  "$SMOKE_ROOT/review/blog/"
```

Run the complete-baseline inspection:

```bash
exporter-java/target/astro-export inspect-publication \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --note 'claims/Растрата в инновациях — работа, не производящая знания.md' \
  --review "$SMOKE_ROOT/review" \
  --json
```

Expected: exit 0, `"schemaVersion":2`,
`"baselineState":"complete"`, and two targets.

Move the temporary baseline aside and rerun:

```bash
mv \
  "$SMOKE_ROOT/review/blog/work-without-learning-is-waste/published" \
  "$SMOKE_ROOT/review/blog/work-without-learning-is-waste/published.saved"
exporter-java/target/astro-export inspect-publication \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --note 'claims/Растрата в инновациях — работа, не производящая знания.md' \
  --review "$SMOKE_ROOT/review" \
  --json
```

Expected: exit 0, `"schemaVersion":2`,
`"baselineState":"absent"`, and null published paths. Keep the temporary
directory if evidence needs inspection; it is safe to discard later because
it contains only copies.

- [ ] **Step 8: Verify the installed Zed CLI and reload boundary**

Run:

```bash
/Applications/Zed.app/Contents/MacOS/cli --version
```

Expected: Zed version and `/Applications/Zed.app`.

In Obsidian, reload **Подготовка публикаций для Astro** after the native
binary build. Confirm the plugin setting points to:

```text
/Users/eugene/Dev/personal-site/exporter-java/target/astro-export
```

and Zed CLI points to:

```text
/Applications/Zed.app/Contents/MacOS/cli
```

- [ ] **Step 9: Perform the user-visible acceptance check**

Open:

```text
claims/Растрата в инновациях — работа, не производящая знания.md
```

Invoke **Открыть проверку перевода текущей заметки**.

Expected:

- one new Zed workspace window contains the RU diff;
- a second new Zed workspace window contains the EN diff;
- both use published as old and proposed as new;
- Obsidian reports success only after both CLI requests return successfully;
- neither the review folder nor a `shell.openPath` fallback opens;
- Zed/macOS chooses window placement.

- [ ] **Step 10: Run final repository checks**

Run:

```bash
git status --short
git diff --check 8f82d5e..HEAD
git log --oneline --decorate -8
```

Expected: clean worktree, no whitespace errors across the design-to-feature
range, and one focused commit per task.
