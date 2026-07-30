# Canonical Review Frontmatter Ordering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every exporter-generated review document the same recursive frontmatter key order so Zed review diffs do not report key-order-only changes.

**Architecture:** Add one pure `FrontmatterCanonicalizer` that returns fresh recursively ordered maps while preserving list order and scalar values. Use it at the final site, review workspace, and translation-template serialization boundaries; keep manifest/hash semantics and byte-preserving English approval unchanged.

**Tech Stack:** Java 21, Maven, JUnit Jupiter 6, SnakeYAML Engine, Jackson

## Global Constraints

- Sort mapping keys with Java's natural `String` order, matching current `SiteWriter` output.
- Sort every nested mapping, including mappings inside lists.
- Preserve list-item order, scalar values, scalar types, and Markdown bodies.
- Treat canonicalization as output formatting; do not change `ManifestBuilder`, `TranslationProjection`, or either hash algorithm.
- Do not rewrite source vault notes or copied legacy Markdown overrides.
- Do not parse and reserialize reviewed English content during `mark-reviewed`.
- Keep final Astro YAML and JSON output byte-identical to the existing `SiteWriter` sorter.
- Write each production change only after its relevant regression test has failed for the expected reason.

---

## File Map

- Create `exporter-java/src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizer.java`
  - Owns recursive map ordering and list traversal.
- Create `exporter-java/src/test/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizerTest.java`
  - Defines the shared ordering, immutability, list-order, and scalar-preservation contract.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java`
  - Replaces its private recursive sorter with the shared canonicalizer.
- Modify `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
  - Proves canonical RU rendering and canonical generated-status EN rendering.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
  - Canonicalizes every exporter-owned Markdown serialization.
- Modify `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java`
  - Proves that the translation candidate template is canonical before the agent sees it.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java`
  - Canonicalizes candidate-template metadata before SnakeYAML renders it.

### Task 1: Shared canonicalizer and unchanged site output

**Files:**

- Create: `exporter-java/src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizer.java`
- Create: `exporter-java/src/test/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizerTest.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:3-39`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:1074-1126`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:1141-1181`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:1282-1324`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/fs/SiteWriterTest.java`

**Interfaces:**

- Consumes: Java `Map<?, ?>`, `List<?>`, and scalar metadata values.
- Produces: `FrontmatterCanonicalizer.canonicalize(Map<?, ?> source) -> Map<String, Object>`.
- Guarantees: the returned root and nested maps are fresh `LinkedHashMap` instances; map keys are compared as `String.valueOf(key)`; lists retain encounter order; scalars are returned unchanged.

- [ ] **Step 1: Write the failing canonicalizer contract test**

Create
`exporter-java/src/test/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizerTest.java`:

```java
package dev.eugene.astroexport.frontmatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FrontmatterCanonicalizerTest {
  @Test
  void canonicalizesEveryMappingWithoutChangingListOrderOrScalars() {
    LinkedHashMap<String, Object> first = new LinkedHashMap<>();
    first.put("z", 2);
    first.put("a", 1);
    LinkedHashMap<String, Object> second = new LinkedHashMap<>();
    second.put("z", 4);
    second.put("a", 3);
    LocalDate date = LocalDate.of(2026, 7, 30);

    LinkedHashMap<String, Object> source = new LinkedHashMap<>();
    source.put("zeta", "last");
    source.put("items", List.of(first, second));
    source.put("date", date);
    source.put("alpha", Map.of("z", "end", "a", "start"));

    Map<String, Object> canonical = FrontmatterCanonicalizer.canonicalize(source);

    assertEquals(List.of("alpha", "date", "items", "zeta"), List.copyOf(canonical.keySet()));
    assertEquals(
        List.of("a", "z"),
        List.copyOf(map(canonical.get("alpha")).keySet()));
    List<?> items = (List<?>) canonical.get("items");
    assertEquals(List.of(1, 3), items.stream().map(item -> map(item).get("a")).toList());
    assertEquals(
        List.of("a", "z"),
        List.copyOf(map(items.getFirst()).keySet()));
    assertEquals(
        List.of("a", "z"),
        List.copyOf(map(items.getLast()).keySet()));
    assertSame(date, canonical.get("date"));
    assertNotSame(source, canonical);
    assertNotSame(first, items.getFirst());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
```

- [ ] **Step 2: Run the test and verify the RED state**

Run:

```bash
cd exporter-java
mvn -Dtest=FrontmatterCanonicalizerTest test
```

Expected: compilation fails because `FrontmatterCanonicalizer` does not exist.

- [ ] **Step 3: Implement the minimal shared canonicalizer**

Create
`exporter-java/src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizer.java`:

```java
package dev.eugene.astroexport.frontmatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces fresh recursively ordered metadata structures for serialization. */
public final class FrontmatterCanonicalizer {
  private FrontmatterCanonicalizer() { }

  public static Map<String, Object> canonicalize(Map<?, ?> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.entrySet().stream()
        .sorted((left, right) -> String.valueOf(left.getKey())
            .compareTo(String.valueOf(right.getKey())))
        .forEachOrdered(entry -> result.put(
            String.valueOf(entry.getKey()),
            canonicalizeValue(entry.getValue())));
    return result;
  }

  private static Object canonicalizeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return canonicalize(map);
    }
    if (value instanceof List<?> list) {
      return list.stream()
          .map(FrontmatterCanonicalizer::canonicalizeValue)
          .toList();
    }
    return value;
  }
}
```

- [ ] **Step 4: Run the canonicalizer test and verify the GREEN state**

Run:

```bash
cd exporter-java
mvn -Dtest=FrontmatterCanonicalizerTest test
```

Expected: PASS.

- [ ] **Step 5: Capture the existing site-writer behavior before refactoring it**

Run:

```bash
cd exporter-java
mvn -Dtest=SiteWriterTest test
```

Expected: PASS. In particular,
`stageSiteSerializesFrontmatterWithPyYamlCompatibleScalars` and the sample
editorial JSON assertion prove recursive alphabetical output before the
implementation is replaced.

- [ ] **Step 6: Replace `SiteWriter`'s private sorter with the shared utility**

Add this import with the other `dev.eugene.astroexport` imports:

```java
import dev.eugene.astroexport.frontmatter.FrontmatterCanonicalizer;
```

Replace the two top-level serialization methods with:

```java
private static byte[] serializeMarkdown(ManifestEntry entry, String body) {
  String metadata =
      yaml(FrontmatterCanonicalizer.canonicalize(entry.metadata())).stripTrailing();
  String canonicalBody = canonicalBody(body);
  String result = canonicalBody.isBlank()
      ? "---\n" + metadata + "\n---\n"
      : "---\n" + metadata + "\n---\n\n" + canonicalBody + "\n";
  return result.getBytes(StandardCharsets.UTF_8);
}

private static byte[] serializeEditorial(ManifestEntry entry) {
  return (json(FrontmatterCanonicalizer.canonicalize(entry.metadata()), 0) + "\n")
      .getBytes(StandardCharsets.UTF_8);
}
```

Delete both private methods:

```java
private static Map<String, Object> sorted(Map<String, Object> source)
private static Object sortedValue(Object value)
```

In `yamlList`, replace the nested-map preparation with:

```java
Map<String, Object> map = FrontmatterCanonicalizer.canonicalize(nested);
```

In `yamlEntry`, replace the nested-map recursive call with:

```java
yamlMap(FrontmatterCanonicalizer.canonicalize(nested), keyIndent + 2, builder);
```

In the map branch of `json`, replace the private-sorter call and use the
canonical map throughout that branch:

```java
if (value instanceof Map<?, ?> map) {
  Map<String, Object> canonical = FrontmatterCanonicalizer.canonicalize(map);
  if (canonical.isEmpty()) {
    return "{}";
  }
  StringBuilder builder = new StringBuilder("{\n");
  int index = 0;
  for (Map.Entry<String, Object> entry : canonical.entrySet()) {
    indent(builder, indent + 2)
        .append(json(entry.getKey(), indent + 2))
        .append(": ")
        .append(json(entry.getValue(), indent + 2));
    if (++index < canonical.size()) {
      builder.append(',');
    }
    builder.append('\n');
  }
  return indent(builder, indent).append('}').toString();
}
```

Delete the now-unused private method:

```java
private static Map<String, Object> castMap(Map<?, ?> map)
```

- [ ] **Step 7: Verify shared behavior and byte-identical site output**

Run:

```bash
cd exporter-java
mvn -Dtest=FrontmatterCanonicalizerTest,SiteWriterTest test
```

Expected: PASS with all existing exact YAML, JSON, scalar, multiline, and
repeated-staging assertions unchanged.

- [ ] **Step 8: Commit the shared canonicalizer**

Run:

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizer.java \
  exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java \
  exporter-java/src/test/java/dev/eugene/astroexport/frontmatter/FrontmatterCanonicalizerTest.java
git commit -m "refactor(exporter): share frontmatter canonicalization"
```

### Task 2: Canonical review Markdown serialization

**Files:**

- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java:3-24`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java:30-53`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java:178-215`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:1-41`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:589-595`

**Interfaces:**

- Consumes: `FrontmatterCanonicalizer.canonicalize(Map<?, ?>)`.
- Produces: canonical YAML from `ReviewWorkspace.renderRuReview`,
  `writeRuReviewFile`, `setGeneratedReviewStatus`, and editorial JSON migration.
- Preserves: `setReviewedStatusPreservingContent` remains line-preserving and
  `stageApprovedSnapshot` stores its supplied English bytes unchanged.

- [ ] **Step 1: Add failing RU and generated-EN serialization tests**

Add this import to `ReviewWorkspaceTest`:

```java
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
```

Add these tests after `renderedRussianReviewExactlyMatchesTheFileWriter`:

```java
@Test
void russianReviewSerializationCanonicalizesEveryMappingLevel() {
  LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
  nested.put("z", 2);
  nested.put("a", 1);
  LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
  metadata.put("zeta", "last");
  metadata.put("nested", nested);
  metadata.put("alpha", "first");
  ManifestEntry entry = new ManifestEntry(
      "blog/Canonical.md",
      "src/content/blog/ru/canonical.md",
      "/ru/essays/canonical/",
      metadata,
      "Body.");

  String rendered = ReviewWorkspace.renderRuReview(entry);
  FrontmatterDocument parsed = FrontmatterDocument.parse(
      Path.of("ru.md"), "ru.md", rendered);

  assertEquals(
      List.of("alpha", "nested", "route", "targetPath", "zeta"),
      List.copyOf(parsed.metadata().keySet()));
  @SuppressWarnings("unchecked")
  Map<String, Object> parsedNested =
      (Map<String, Object>) parsed.metadata().get("nested");
  assertEquals(List.of("a", "z"), List.copyOf(parsedNested.keySet()));
}

@Test
void generatedStatusSerializationCanonicalizesEveryMappingLevel() {
  String generated = ReviewWorkspace.setGeneratedReviewStatus("""
      ---
      zeta: last
      nested:
        z: 2
        a: 1
      translationStatus: reviewed
      alpha: first
      ---
      English body.
      """);

  FrontmatterDocument parsed = FrontmatterDocument.parse(
      Path.of("en.md"), "en.md", generated);

  assertEquals(
      List.of("alpha", "nested", "translationStatus", "zeta"),
      List.copyOf(parsed.metadata().keySet()));
  @SuppressWarnings("unchecked")
  Map<String, Object> parsedNested =
      (Map<String, Object>) parsed.metadata().get("nested");
  assertEquals(List.of("a", "z"), List.copyOf(parsedNested.keySet()));
  assertEquals("generated", parsed.metadata().get("translationStatus"));
  assertTrue(generated.endsWith("English body.\n"));
}
```

- [ ] **Step 2: Run `ReviewWorkspaceTest` and verify the RED state**

Run:

```bash
cd exporter-java
mvn -Dtest=ReviewWorkspaceTest test
```

Expected: both new tests fail because SnakeYAML receives insertion-ordered maps
whose top-level and nested key orders are not canonical.

- [ ] **Step 3: Canonicalize metadata in the shared review serializer**

Add this import to `ReviewWorkspace`:

```java
import dev.eugene.astroexport.frontmatter.FrontmatterCanonicalizer;
```

Replace `serializeMarkdown` with:

```java
private static String serializeMarkdown(Map<String, Object> metadata, String body) {
  String yaml = new Dump(DumpSettings.builder()
      .setDefaultFlowStyle(FlowStyle.BLOCK)
      .build()).dumpToString(FrontmatterCanonicalizer.canonicalize(metadata));
  String content = body == null ? "" : body.strip();
  return "---\n" + yaml + "---\n" + (content.isEmpty() ? "" : content + "\n");
}
```

Do not change `setReviewedStatusPreservingContent`,
`replaceEnglishReviewFile`, or `stageApprovedSnapshot`.

- [ ] **Step 4: Run `ReviewWorkspaceTest` and verify the GREEN state**

Run:

```bash
cd exporter-java
mvn -Dtest=ReviewWorkspaceTest test
```

Expected: PASS. The new ordering tests pass, and the existing
`rewritesGeneratedAndReviewedStatuses` and
`approvedSnapshotUsesManifestEntryInsteadOfMutableOrdinaryRuFile` tests confirm
that reviewed English bytes remain preserved.

- [ ] **Step 5: Commit canonical review serialization**

Run:

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java \
  exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java
git commit -m "feat(exporter): canonicalize review frontmatter"
```

### Task 3: Canonical translation candidate templates

**Files:**

- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java:535-591`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java:3-19`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java:1027-1077`

**Interfaces:**

- Consumes: `FrontmatterCanonicalizer.canonicalize(Map<?, ?>)`.
- Produces: a candidate template whose complete top-level and nested metadata
  ordering is canonical before it enters the translation prompt.
- Relies on: Task 2 reserializes the returned agent candidate canonically before
  installing proposed `en.md`.

- [ ] **Step 1: Add a failing candidate-template ordering test**

Add this test after `promptOmitsDiffSectionWhenNoPublishedSnapshotExists`:

```java
@Test
void candidateTemplateSerializesFrontmatterInCanonicalOrder() throws Exception {
  Fixture fixture = fixture();
  RecordingRunner runner = new RecordingRunner(job -> {
    writeCandidate(job, null);
    return new CodexRunner.Run(0, "", "", false);
  });

  PrepareWorkflow.PrepareResult result = workflow(runner)
      .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

  assertEquals("ready_for_review", result.status());
  String template = runner.prompt
      .substring(
          runner.prompt.indexOf("<candidate-template>")
              + "<candidate-template>".length(),
          runner.prompt.indexOf("</candidate-template>"))
      .strip();
  FrontmatterDocument parsed = FrontmatterDocument.parse(
      Path.of("candidate.en.md"), "candidate.en.md", template);
  List<String> keyOrder = List.copyOf(parsed.metadata().keySet());
  assertEquals(keyOrder.stream().sorted().toList(), keyOrder);
}
```

- [ ] **Step 2: Run `PrepareWorkflowTest` and verify the RED state**

Run:

```bash
cd exporter-java
mvn -Dtest=PrepareWorkflowTest test
```

Expected: the new test fails because candidate controls are inserted before
translated metadata instead of following natural key order.

- [ ] **Step 3: Canonicalize metadata before rendering the candidate template**

Add this import to `PrepareWorkflow`:

```java
import dev.eugene.astroexport.frontmatter.FrontmatterCanonicalizer;
```

Change the final serialization in `candidateTemplate` from:

```java
return "---\n" + YAML.dumpToString(metadata) + "---\n" + suffix;
```

to:

```java
return "---\n"
    + YAML.dumpToString(FrontmatterCanonicalizer.canonicalize(metadata))
    + "---\n"
    + suffix;
```

- [ ] **Step 4: Run `PrepareWorkflowTest` and verify the GREEN state**

Run:

```bash
cd exporter-java
mvn -Dtest=PrepareWorkflowTest test
```

Expected: PASS, including candidate validation, durable English installation,
source-diff scoping, confinement, and recovery tests.

- [ ] **Step 5: Run all focused canonicalization and boundary tests**

Run:

```bash
cd exporter-java
mvn -Dtest=FrontmatterCanonicalizerTest,SiteWriterTest,ReviewWorkspaceTest,PrepareWorkflowTest test
```

Expected: PASS.

- [ ] **Step 6: Commit canonical candidate templates**

Run:

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java \
  exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java
git commit -m "feat(exporter): canonicalize translation templates"
```

## Final Verification

- [ ] **Step 1: Run the complete exporter test suite**

Run:

```bash
cd exporter-java
mvn test
```

Expected: BUILD SUCCESS with zero test failures and zero test errors.

- [ ] **Step 2: Check all implementation commits for whitespace errors**

Run from the repository root:

```bash
git diff --check eb3a0c6..HEAD
```

Expected: no output and exit code 0.

- [ ] **Step 3: Confirm the implementation worktree contains no uncommitted changes**

Run:

```bash
git status --short
```

Expected: no output.

- [ ] **Step 4: Review the implementation commit sequence**

Run:

```bash
git log -5 --oneline
```

Expected: the three implementation commits appear above this plan commit and
design commit `eb3a0c6`, in Task 1 through Task 3 order.
