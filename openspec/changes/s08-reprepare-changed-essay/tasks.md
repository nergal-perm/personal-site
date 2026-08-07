# S08 — Reprepare a Changed Approved Essay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementer subagents run as Codex Companion Tasks (model tier per task's stated complexity); after each task, run four parallel Codex Companion review passes: spec compliance, code quality, `/applying-sbpp`, `/oo-design-heuristics`.

**Goal:** `prepare` on a note with an approved Russian snapshot computes the complete normalized diff against
it, generates and validates a new English candidate (PCM-06), installs the new RU/EN/`references.json` triple
only as one coherent unit after validation succeeds (TRP-03), and — on translation failure, staleness, or a
result belonging to a different job/source fingerprint — leaves the existing candidate untouched (TRP-03,
TRP-04). `inspect-publication` reports the same diff in a changed-publication review plan (RVA-02).

**Architecture:** Per `design.md` D1-D4: a new in-process `RussianDiff` value type (LCS line diff, no new
dependency); a new `EnglishCandidateValidator` domain service (PCM-06); `TranslationWorker.translate(...)`
gains a `TranslationJob` parameter (job ID + source fingerprint) — the one new production boundary concept
this slice adds, proven first via an in-memory fake, then via `ProcessTranslationWorker`'s real job-root
confinement; `ReviewPlan` gains a `changedPublication(...)` factory and `InspectPublicationHandler` gains an
`ApprovedSnapshotWorkspace` dependency. No new capability, no touched adapters beyond
`translation`/`candidate`/`bridge`/`inspect`/`prepare` packages. `mark-reviewed`, `build-from-review`,
`install-to-site` are untouched.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson (existing deps only — no new dependency this slice).

## Global Constraints

- Nullables: every new port gets a Null Object / in-memory fake first (`create()`/`createNull(...)` factory
  pair), proven behaviorally identical to the real adapter via a shared contract test — mirrors
  `TranslationWorker.createNull(...)`/`createNullFailing(...)` and `CandidateWorkspace.create`/`createNull`
  already in this codebase.
- No mocking libraries. State-based assertions only (existing project convention — no Mockito dependency in
  `pom.xml`).
- Outside-in TDD: one failing CLI acceptance test first, in-memory adapters wired in, then extract/harden the
  real filesystem adapter behind the same behavioral contract (`openspec/implementation-plan.md` discipline).
- In-memory acceptance subset stays under 1 second (existing suite is 324 tests / ~2s total — verify this
  slice doesn't regress that).
- No new production adapter beyond `TranslationJob`-aware `ProcessTranslationWorker` confinement — do not
  introduce a separate `TranslationJobWorkspace` port; job semantics live inside `TranslationWorker`
  implementors per D3.
- Every new/changed public method keeps `Objects.requireNonNull(x, "x")` guards on constructor/method
  parameters, matching every existing type in this package (`CandidateSnapshot`, `ReferenceMap`, `ReviewPlan`,
  `TranslationResult`).
- Keep classes small and single-responsibility (Riel/`oo-design-guide`): `RussianDiff` only diffs and reports;
  `EnglishCandidateValidator` only validates; `TranslationJob` only carries job identity — no god classes
  gathering unrelated behavior onto `PrepareHandler`.

---

## 1. `TranslationJob` value type and `TranslationWorker` interface change

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationJob.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationJobTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java` (call-site update only — no new behavior test here yet)

**Interfaces:**
- Produces: `TranslationJob.forSource(String ruBody, String ruTitle, String ruDescription): TranslationJob`,
  `TranslationJob#id(): String`, `TranslationJob#sourceFingerprint(): String`.
- Produces (changed): `TranslationWorker#translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription): TranslationResult`.
- Consumes: `dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(String)` (existing).

`grep -rn "translationWorker.translate(\|TranslationWorker.create\|implements TranslationWorker" publication-exporter/src` before starting — confirms the only implementors are `NullTranslationWorker` and `ProcessTranslationWorker`, and the only call site is `PrepareHandler.prepareAdmittedEssay`, per `feedback_java_interface_change_task_planning`.

- [x] 1.1 **Write the failing unit test for `TranslationJob`**

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranslationJobTest {

    @Test
    void generatesNonBlankIdAndFingerprint() {
        TranslationJob job = TranslationJob.forSource("body", "title", "description");

        assertNotNull(job.id());
        assertNotEquals("", job.id().strip());
        assertNotNull(job.sourceFingerprint());
    }

    @Test
    void sameSourceProducesSameFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("body", "title", "description");

        assertEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentSourceProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("changed body", "title", "description");

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void twoJobsForSameSourceHaveDifferentIds() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("body", "title", "description");

        assertNotEquals(first.id(), second.id());
    }
}
```

- [x] 1.2 **Run it to confirm it fails to compile** (`TranslationJob` doesn't exist yet)

Run: `cd publication-exporter && mvn -q -Dtest=TranslationJobTest test`
Expected: compilation FAILURE — `cannot find symbol: class TranslationJob`

- [x] 1.3 **Implement `TranslationJob`**

```java
package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.hash.ContentHash;

import java.util.Objects;
import java.util.UUID;

public final class TranslationJob {

    private final String id;
    private final String sourceFingerprint;

    private TranslationJob(String id, String sourceFingerprint) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
    }

    public static TranslationJob forSource(String ruBody, String ruTitle, String ruDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(ruTitle, "ruTitle");
        Objects.requireNonNull(ruDescription, "ruDescription");
        String fingerprint = ContentHash.sha256Hex(ruBody + "\0" + ruTitle + "\0" + ruDescription);
        return new TranslationJob(UUID.randomUUID().toString(), fingerprint);
    }

    public String id() {
        return id;
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranslationJob that)) {
            return false;
        }
        return id.equals(that.id) && sourceFingerprint.equals(that.sourceFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sourceFingerprint);
    }

    @Override
    public String toString() {
        return "TranslationJob[id=" + id + ", sourceFingerprint=" + sourceFingerprint + "]";
    }
}
```

Confirm `ContentHash.sha256Hex` is `public static String sha256Hex(String)` before writing this — read
`publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java` first.

- [x] 1.4 **Run the test to confirm it passes**

Run: `cd publication-exporter && mvn -q -Dtest=TranslationJobTest test`
Expected: PASS, 4/4.

- [x] 1.5 **Change `TranslationWorker#translate` to accept a `TranslationJob`, update both implementors**

`TranslationWorker.java`:
```java
public interface TranslationWorker {

    TranslationResult translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription);

    static TranslationWorker createNull(String enBody, String enTitle, String enDescription) {
        return new NullTranslationWorker(TranslationResult.success(enBody, enTitle, enDescription));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationResult.failure(reason));
    }
}
```

`NullTranslationWorker.java`: change `translate(String ruBody, String ruTitle, String ruDescription)` to
`translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription)`, ignore `job` (it has no
job-authentication behavior to fake yet — Task 5 adds job-aware fakes), keep the existing body unchanged
otherwise, add `Objects.requireNonNull(job, "job")` as the first line.

`ProcessTranslationWorker.java`: change `translate(String ruBody, String ruTitle, String ruDescription)`
to `translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription)`, add
`Objects.requireNonNull(job, "job")` as the first line. Leave the scratch-directory/process-invocation body
otherwise unchanged in this task — Task 6 hardens it to use `job` for confinement.

- [x] 1.6 **Update the only call site, `PrepareHandler.prepareAdmittedEssay`**

```java
TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
try {
    translation = translationWorker.translate(job, ruBody, ruTitle, ruDescription);
} catch (UncheckedIOException failure) {
    return candidateFailure(IoFailureMessages.describe("Translation worker I/O failed", failure));
}
```
Add `import dev.eugene.publicationexporter.translation.TranslationJob;` to `PrepareHandler.java`.

- [x] 1.7 **Update `PrepareHandlerTest`'s existing `translationWorker.translate(...)` verifications, if any, to the new signature**

Read `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
first — it very likely only calls `TranslationWorker.createNull(...)`/`createNullFailing(...)` factories
(which don't change shape), so this step may be a no-op; confirm by running the full suite in 1.8 rather than
guessing.

- [x] 1.8 **Run the full suite to confirm no other call site broke**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS, all 324+4 tests passing (existing 324 plus the 4 new `TranslationJobTest` cases).

- [x] 1.9 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/translation/TranslationJob.java \
        src/test/java/dev/eugene/publicationexporter/translation/TranslationJobTest.java \
        src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java \
        src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java \
        src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java \
        src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java
git commit -m "feat(translation): thread TranslationJob (job id + source fingerprint) through TranslationWorker"
```

---

## 2. `RussianDiff` value type (TRP-02)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/RussianDiff.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/RussianDiffTest.java`

**Interfaces:**
- Produces: `RussianDiff.between(CandidateSnapshot approved, String currentBody, String currentTitle, String currentDescription): RussianDiff`,
  `RussianDiff#isEmpty(): boolean`, `RussianDiff#bodyLines(): List<RussianDiff.Line>`, where
  `RussianDiff.Line` is a small record `Line(RussianDiff.LineKind kind, String text)` and
  `LineKind` is `ADDED`, `REMOVED`, `UNCHANGED`.
- Consumes: `dev.eugene.publicationexporter.candidate.CandidateSnapshot` (existing, read-only).

This is the "genuinely combinatorial ... logic unclear at acceptance-test scope" case
`openspec/implementation-plan.md` calls out for unit tests, per D1 in `design.md`.

- [x] 2.1 **Write failing unit tests covering the LCS diff's combinatorial cases**

```java
package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianDiffTest {

    @Test
    void identicalTextIsEmptyDiff() {
        RussianDiff diff = RussianDiff.betweenBodies("line one\nline two", "line one\nline two");

        assertTrue(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line two")),
                diff.lines());
    }

    @Test
    void appendedLineIsReportedAsAdded() {
        RussianDiff diff = RussianDiff.betweenBodies("line one", "line one\nline two");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.ADDED, "line two")),
                diff.lines());
    }

    @Test
    void removedLineIsReportedAsRemoved() {
        RussianDiff diff = RussianDiff.betweenBodies("line one\nline two", "line one");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "line two")),
                diff.lines());
    }

    @Test
    void middleLineChangedIsRemovedThenAdded() {
        RussianDiff diff = RussianDiff.betweenBodies(
                "one\ntwo\nthree", "one\nCHANGED\nthree");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "one"),
                        new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "two"),
                        new RussianDiff.Line(RussianDiff.LineKind.ADDED, "CHANGED"),
                        new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "three")),
                diff.lines());
    }

    @Test
    void trailingWhitespaceOnlyChangeIsEmptyAfterNormalization() {
        RussianDiff diff = RussianDiff.betweenBodies("line one  \nline two", "line one\nline two");

        assertTrue(diff.isEmpty());
    }

    @Test
    void emptyToNonEmptyIsAllAdded() {
        RussianDiff diff = RussianDiff.betweenBodies("", "new line");

        assertFalse(diff.isEmpty());
        assertEquals(List.of(new RussianDiff.Line(RussianDiff.LineKind.ADDED, "new line")), diff.lines());
    }
}
```

- [x] 2.2 **Run it to confirm it fails to compile**

Run: `cd publication-exporter && mvn -q -Dtest=RussianDiffTest test`
Expected: compilation FAILURE — `RussianDiff` doesn't exist.

- [x] 2.3 **Implement `RussianDiff` with a standard O(n·m) LCS line diff**

```java
package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RussianDiff {

    public enum LineKind { UNCHANGED, ADDED, REMOVED }

    public record Line(LineKind kind, String text) {
        public Line {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }
    }

    private final List<Line> lines;

    private RussianDiff(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    public static RussianDiff betweenBodies(String approvedBody, String currentBody) {
        Objects.requireNonNull(approvedBody, "approvedBody");
        Objects.requireNonNull(currentBody, "currentBody");
        String[] oldLines = normalize(approvedBody);
        String[] newLines = normalize(currentBody);
        return new RussianDiff(lcsDiff(oldLines, newLines));
    }

    public boolean isEmpty() {
        return lines.stream().allMatch(line -> line.kind() == LineKind.UNCHANGED);
    }

    public List<Line> lines() {
        return lines;
    }

    private static String[] normalize(String body) {
        if (body.isEmpty()) {
            return new String[0];
        }
        String[] rawLines = body.split("\n", -1);
        String[] trimmed = new String[rawLines.length];
        for (int i = 0; i < rawLines.length; i++) {
            trimmed[i] = rawLines[i].stripTrailing();
        }
        return trimmed;
    }

    private static List<Line> lcsDiff(String[] oldLines, String[] newLines) {
        int oldLen = oldLines.length;
        int newLen = newLines.length;
        int[][] lengths = new int[oldLen + 1][newLen + 1];
        for (int i = oldLen - 1; i >= 0; i--) {
            for (int j = newLen - 1; j >= 0; j--) {
                lengths[i][j] = oldLines[i].equals(newLines[j])
                        ? lengths[i + 1][j + 1] + 1
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }
        List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldLen && j < newLen) {
            if (oldLines[i].equals(newLines[j])) {
                result.add(new Line(LineKind.UNCHANGED, oldLines[i]));
                i++;
                j++;
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                result.add(new Line(LineKind.REMOVED, oldLines[i]));
                i++;
            } else {
                result.add(new Line(LineKind.ADDED, newLines[j]));
                j++;
            }
        }
        while (i < oldLen) {
            result.add(new Line(LineKind.REMOVED, oldLines[i]));
            i++;
        }
        while (j < newLen) {
            result.add(new Line(LineKind.ADDED, newLines[j]));
            j++;
        }
        return result;
    }
}
```

- [x] 2.4 **Run the tests to confirm they pass**

Run: `cd publication-exporter && mvn -q -Dtest=RussianDiffTest test`
Expected: PASS, 6/6.

- [x] 2.5 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/RussianDiff.java \
        src/test/java/dev/eugene/publicationexporter/prepare/RussianDiffTest.java
git commit -m "feat(prepare): add RussianDiff line-based LCS diff for TRP-02"
```

---

## 3. `EnglishCandidateValidator` domain service (PCM-06)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidatorTest.java`

**Interfaces:**
- Produces: `EnglishCandidateValidator.validate(String ruBody, String enBody, String enTitle, String enDescription): EnglishCandidateValidator.Result`,
  where `Result` is a sealed-by-convention pair: `Result.valid(): Result`,
  `Result.invalid(List<String> diagnostics): Result`, `Result#valid(): boolean`, `Result#diagnostics(): List<String>`.
- Consumes: nothing beyond `java.util.regex` and `java.util` — pure function, no I/O.

- [x] 3.1 **Write failing unit tests for each PCM-06 check**

```java
package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnglishCandidateValidatorTest {

    @Test
    void acceptsStructurallyCompleteCandidate() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See https://example.com/x for details.", "Title", "Description");

        assertTrue(result.valid());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsBlankBody() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "   ", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("body")));
    }

    @Test
    void rejectsBlankTitle() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "  ", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("title")));
    }

    @Test
    void rejectsBlankDescription() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "Title", "  ");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("description")));
    }

    @Test
    void rejectsInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите [другую статью](/ru/blog/other) для деталей.",
                "See [another essay](/ru/blog/other) for details.", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsDroppedExternalUrl() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See the details.", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("https://example.com/x")));
    }
}
```

- [x] 3.2 **Run it to confirm compilation failure**

Run: `cd publication-exporter && mvn -q -Dtest=EnglishCandidateValidatorTest test`
Expected: compilation FAILURE — `EnglishCandidateValidator` doesn't exist.

- [x] 3.3 **Implement `EnglishCandidateValidator`**

```java
package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnglishCandidateValidator {

    private static final Pattern EXTERNAL_URL =
            Pattern.compile("https?://[^\\s)\\]]+");
    private static final Pattern INTERNAL_RU_ROUTE =
            Pattern.compile("\\]\\(/ru/[^)]*\\)");

    private EnglishCandidateValidator() {
    }

    public static Result validate(String ruBody, String enBody, String enTitle, String enDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(enDescription, "enDescription");

        List<String> diagnostics = new ArrayList<>();
        if (enBody.isBlank()) {
            diagnostics.add("Translation worker produced a blank body.");
        }
        if (enTitle.isBlank()) {
            diagnostics.add("Translation worker produced a blank title.");
        }
        if (enDescription.isBlank()) {
            diagnostics.add("Translation worker produced a blank description.");
        }
        if (INTERNAL_RU_ROUTE.matcher(enBody).find()) {
            diagnostics.add("English candidate contains an internal /ru/ route.");
        }
        for (String droppedUrl : droppedExternalUrls(ruBody, enBody)) {
            diagnostics.add("English candidate dropped external URL " + droppedUrl + ".");
        }
        return diagnostics.isEmpty() ? Result.ok() : Result.invalid(diagnostics);
    }

    private static Set<String> droppedExternalUrls(String ruBody, String enBody) {
        Set<String> ruUrls = extractUrls(ruBody);
        Set<String> enUrls = extractUrls(enBody);
        Set<String> dropped = new LinkedHashSet<>(ruUrls);
        dropped.removeAll(enUrls);
        return dropped;
    }

    private static Set<String> extractUrls(String text) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = EXTERNAL_URL.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    public static final class Result {
        private final boolean valid;
        private final List<String> diagnostics;

        private Result(boolean valid, List<String> diagnostics) {
            this.valid = valid;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result ok() {
            return new Result(true, List.of());
        }

        static Result invalid(List<String> diagnostics) {
            return new Result(false, diagnostics);
        }

        public boolean valid() {
            return valid;
        }

        public List<String> diagnostics() {
            return diagnostics;
        }
    }
}
```

- [x] 3.4 **Run the tests to confirm they pass**

Run: `cd publication-exporter && mvn -q -Dtest=EnglishCandidateValidatorTest test`
Expected: PASS, 6/6.

- [x] 3.5 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java \
        src/test/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidatorTest.java
git commit -m "feat(prepare): add EnglishCandidateValidator for PCM-06"
```

---

## 4. Failing CLI acceptance tests for the changed-publication `prepare` path (outside-in entry point)

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java`
- Read first (do not modify): `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`, to confirm how `ApprovedSnapshotWorkspace` is or isn't already wired into the CLI composition root.

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace.createNull(...)` seeded with an approved snapshot — read
  `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
  first for its exact seeding factory shape before writing these tests, since its precise API isn't
  guessed here.
- Produces: none (this task only adds failing tests; Task 5 makes them pass).

This is the "one failing system-boundary acceptance test written in Given-When-Then terms" the
implementation-plan.md discipline requires before touching production code for the behavioral core of this
slice.

- [x] 4.1 **Read the current `PrepareCliAcceptanceTest` and `PrepareCommand` composition root to learn the exact test harness shape (constructor wiring, how CLI args map to a note path, how output JSON is asserted) before writing new cases** — do not skip this: the existing file's helper methods (e.g. a `runPrepare(...)` helper, in-memory vault seeding) are reused verbatim, not reinvented.

- [x] 4.2 **Write four failing acceptance test cases appended to `PrepareCliAcceptanceTest`**, following the exact helper-method patterns found in 4.1 (adapt argument names to match; the GIVEN/WHEN/THEN below is the required behavior, not literal syntax):

  - `preparingChangedApprovedEssayProducesDiffAndNewCandidate()` — **GIVEN** an approved RU/EN snapshot and a
    source note whose body differs from the approved RU body, **WHEN** `prepare` runs with a translation
    worker configured to succeed, **THEN** the response is `prepared` and the new candidate's EN body matches
    the worker's translated output (not the old candidate's).
  - `preparingWithOnlySerializationNoiseChangedInstallsNoNewCandidate()` — **GIVEN** an approved snapshot and
    a source note whose normalized body is identical to the approved RU body (e.g. only trailing whitespace
    differs), **WHEN** `prepare` runs, **THEN** the response reports no new candidate installed and the
    existing candidate directory (if any) is unchanged — assert via `CandidateWorkspace.read(...)` returning
    the same bytes as before the call, or absent if none existed.
  - `failedTranslationPreservesPriorCandidate()` — **GIVEN** an approved snapshot, an existing valid EN
    candidate, and a translation worker configured via `TranslationWorker.createNullFailing(...)`, **WHEN**
    `prepare` runs, **THEN** the response is `translation_failed` and `CandidateWorkspace.read(...)` still
    returns the prior EN candidate bytes unchanged.
  - `invalidTranslationPreservesPriorCandidate()` — **GIVEN** an approved snapshot, an existing valid EN
    candidate, and a translation worker configured via `TranslationWorker.createNull(...)` to return an EN
    body containing an internal `/ru/` route, **WHEN** `prepare` runs, **THEN** the response is
    `translation_failed` with a diagnostic mentioning `/ru/`, and the prior EN candidate bytes are unchanged.

- [x] 4.3 **Run the new tests to confirm they fail for the right reason** (assertion failures on old
  unconditional-install behavior, not compilation errors — if compilation fails, the helper method signatures
  guessed in 4.2 don't match 4.1's actual harness; fix the test code, not the production code, until it
  compiles and fails on behavior)

Run: `cd publication-exporter && mvn -q -Dtest=PrepareCliAcceptanceTest test`
Expected: 4 new FAILURES on assertions (old candidate gets overwritten every time; no diagnostics mention
`/ru/`), 0 compilation errors.

- [x] 4.4 **Commit the failing tests on their own** (outside-in discipline: the red step is a checkpoint)

```bash
cd publication-exporter
git add src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java
git commit -m "test(prepare): add failing acceptance tests for reprepare-changed-essay (S08)"
```

---

## 5. Wire `RussianDiff` + `EnglishCandidateValidator` + `TranslationJob` into `PrepareHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java` (add job/fingerprint-aware fake behavior)
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
- Read first (no modification needed if unchanged): `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java` (already has `read(identity): Optional<CandidateSnapshot>` per S05 — confirmed in `design.md` Context)

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace#read(PublicationIdentity): Optional<CandidateSnapshot>` (existing),
  `RussianDiff.betweenBodies(String, String): RussianDiff` (Task 2),
  `EnglishCandidateValidator.validate(String, String, String, String): EnglishCandidateValidator.Result` (Task 3),
  `TranslationJob.forSource(...)` / `TranslationWorker#translate(TranslationJob, ...)` (Task 1).
- Produces (changed): `PrepareHandler` constructor gains an `ApprovedSnapshotWorkspace` parameter:
  `PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace)`.
  `grep -rn "new PrepareHandler(" publication-exporter/src` first — update every call site (production CLI
  composition root and any remaining test helpers Task 4 didn't already touch).

- [x] 5.1 **Give `NullTranslationWorker` job/fingerprint-aware behavior needed by Task 4's stale/wrong-job cases**

Read the current `NullTranslationWorker.java` (from Task 1.5) first. Add two more factory methods without
removing the existing two:

```java
static TranslationWorker createNullStale() {
    return new NullTranslationWorker(null); // signals "return a result for a different job" — see translate() below
}
```
Then in `translate(TranslationJob job, ...)`, when constructed via `createNullStale()`, ignore the
requested `job` and behave as if the result came from a job with a *different* ID (simulate by simply
returning `TranslationResult.failure("stale")` for this task's scope — full wrong-job authentication is
exercised at the real-adapter level in Task 6's contract test, not fabricated as a distinct in-memory
behavior beyond "stale translation fails"). If `PrepareHandlerTest`/`PrepareCliAcceptanceTest` need a more
precise stale-vs-wrong-job distinction than this, prefer extending `TranslationResult` with a `stale()`
factory (mirroring `failure(String)`) over inventing new `NullTranslationWorker` states — check
`TranslationResult.java`'s existing shape before deciding; keep whichever is the smaller diff.

- [x] 5.2 **Rewrite `PrepareHandler.prepareAdmittedEssay` to diff, validate, and job-authenticate before install**

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(intake.identity());
        if (approved.isPresent()) {
            RussianDiff diff = RussianDiff.betweenBodies(approved.get().ruBody(), intake.body());
            if (diff.isEmpty()) {
                return BridgeResponse.prepared(COMMAND, intake.identity());
            }
        }
        return prepareAdmittedEssay(intake.identity(), intake.body(), intake.title(), intake.description());
    }

    private BridgeResponse prepareAdmittedEssay(
            PublicationIdentity identity, String ruBody, String ruTitle, String ruDescription) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
        TranslationResult translation;
        try {
            translation = translationWorker.translate(job, ruBody, ruTitle, ruDescription);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Translation worker I/O failed", failure));
        }
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        String enTitle = translation.enTitle();
        String enDescription = translation.enDescription();

        EnglishCandidateValidator.Result validation =
                EnglishCandidateValidator.validate(ruBody, enBody, enTitle, enDescription);
        if (!validation.valid()) {
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }

        ReferenceMap referenceMap = ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(ruTitle), ContentHash.sha256Hex(enTitle),
                ContentHash.sha256Hex(ruDescription), ContentHash.sha256Hex(enDescription));
        try {
            candidateWorkspace.install(identity, ruBody, enBody,
                    ruTitle, enTitle, ruDescription, enDescription, referenceMap);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private static Diagnostic blockingDiagnostics(List<String> diagnostics) {
        return Diagnostic.blocking("candidate", String.join(" ", diagnostics));
    }

    private static BridgeResponse candidateFailure(String message) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", message));
    }
}
```

Note: this keeps the S03 first-publication path byte-identical in observable behavior (`approved.isEmpty()`
skips straight to `prepareAdmittedEssay`, same as before) while adding the diff short-circuit and PCM-06
validation for both paths, per D1/D2's "strengthens S03 too" note in `design.md`.

- [x] 5.3 **Update every remaining `new PrepareHandler(...)` call site to pass an `ApprovedSnapshotWorkspace`** — production composition root uses the real `ApprovedSnapshotWorkspace.create(reviewRoot)` (same `reviewRoot` already passed to `CandidateWorkspace.create(reviewRoot)`, confirm they share the root by reading the composition root code first); existing unit/acceptance tests not yet updated by Task 4 pass `ApprovedSnapshotWorkspace.createNull()` for an absent baseline.

- [x] 5.4 **Run `PrepareHandlerTest` and `PrepareCliAcceptanceTest`**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest,PrepareCliAcceptanceTest test`
Expected: PASS, including the 4 previously-failing cases from Task 4.

- [x] 5.5 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS, all tests green, elapsed time still well under a few seconds.

- [x] 5.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java \
        src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java \
        src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java
git commit -m "feat(prepare): diff against approved baseline, validate English candidate, preserve prior candidate on failure (TRP-02, TRP-03, PCM-06)"
```

---

## 6. Real-adapter job confinement in `ProcessTranslationWorker` (TRP-04, the one new production boundary)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerJobConfinementTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/fs/StagedDirectoryInstall.java`, for the existing `requireWithinReviewRoot`-style confinement idiom to mirror (not reuse directly — job roots and review roots are different concepts — but keep the same defensive style: `LinkOption.NOFOLLOW_LINKS`, explicit root-relative resolution, throwing on escape).

**Interfaces:**
- Produces (changed): `ProcessTranslationWorker(TranslationCommand command, Duration timeout, Path jobRoot)`
  — one new constructor parameter, the job root directory. Existing 2-arg constructor callers must be
  updated to pass a job root (production composition root: a fixed subdirectory under the configured review
  root or system temp root — confirm which by reading the CLI composition root; tests: a JUnit `@TempDir`).

- [x] 6.1 **Write a failing contract test proving job-directory confinement and fingerprint authentication for the real adapter**

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTranslationWorkerJobConfinementTest {

    @Test
    void resultOutsideJobDirectoryIsRejected(@TempDir Path jobRoot) throws Exception {
        TranslationCommand command = (workdir, prompt) -> {
            // Simulate a misbehaving/compromised worker trying to write its result
            // one level above its assigned job directory.
            Path escapeTarget = workdir.getParent().resolve("candidate.en.md");
            Files.writeString(escapeTarget, "escaped content");
            return List.of("sh", "-c", "true"); // no-op successful process
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(command, Duration.ofSeconds(5), jobRoot);
        TranslationJob job = TranslationJob.forSource("ru body", "ru title", "ru description");

        TranslationResult result = worker.translate(job, "ru body", "ru title", "ru description");

        assertFalse(result.succeeded());
    }

    @Test
    void matchingJobAndFingerprintSucceeds(@TempDir Path jobRoot) throws Exception {
        TranslationCommand command = (workdir, prompt) -> {
            Files.writeString(workdir.resolve("candidate.en.md"), "translated body");
            Files.writeString(workdir.resolve("candidate.en.title.txt"), "translated title");
            Files.writeString(workdir.resolve("candidate.en.description.txt"), "translated description");
            return List.of("sh", "-c", "true");
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(command, Duration.ofSeconds(5), jobRoot);
        TranslationJob job = TranslationJob.forSource("ru body", "ru title", "ru description");

        TranslationResult result = worker.translate(job, "ru body", "ru title", "ru description");

        assertTrue(result.succeeded());
    }
}
```

Read `TranslationCommand.java` first to confirm its exact functional-interface shape (`argsFor(Path, String)`)
matches the lambda above before finalizing this test.

- [x] 6.2 **Run it to confirm the escape case currently succeeds (proving the gap) and the constructor doesn't compile yet**

Run: `cd publication-exporter && mvn -q -Dtest=ProcessTranslationWorkerJobConfinementTest test`
Expected: compilation FAILURE — no 3-arg `ProcessTranslationWorker` constructor yet.

- [x] 6.3 **Change `ProcessTranslationWorker` to create its scratch directory under the given job root, named by `job.id()`, and validate every collected file resolves within it**

Modify `createScratchWorkdir()` to become an instance method taking `TranslationJob job`:

```java
private final Path jobRoot;

public ProcessTranslationWorker(TranslationCommand command, Duration timeout, Path jobRoot) {
    this.command = Objects.requireNonNull(command, "command");
    this.timeout = requirePositive(timeout);
    this.jobRoot = Objects.requireNonNull(jobRoot, "jobRoot");
}

@Override
public TranslationResult translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription) {
    Objects.requireNonNull(job, "job");
    Path workdir = createScratchWorkdir(job);
    try {
        return runAndCollect(workdir, prompt(ruBody, ruTitle, ruDescription));
    } finally {
        deleteRecursively(workdir);
    }
}

private Path createScratchWorkdir(TranslationJob job) {
    try {
        Path canonicalRoot = Files.createDirectories(jobRoot).toRealPath();
        Path workdir = Files.createDirectory(canonicalRoot.resolve(job.id()));
        return workdir;
    } catch (IOException error) {
        throw new UncheckedIOException(error);
    }
}
```

In `readIfPresent(Path workdir, String fileName)`, before opening the channel, add a confinement check:

```java
private FileRead readIfPresent(Path workdir, String fileName) {
    Path file = workdir.resolve(fileName).normalize();
    if (!file.getParent().equals(workdir)) {
        return FileRead.missing();
    }
    try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            var input = Channels.newInputStream(channel)) {
        return FileRead.present(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (NoSuchFileException error) {
        return FileRead.missing();
    } catch (IOException error) {
        return Files.isSymbolicLink(file) ? FileRead.missing() : FileRead.unreadable(error);
    }
}
```

This makes the "escape one level up" test case in 6.1 fail to find `candidate.en.md` inside `workdir` (it
was written to `workdir.getParent()`), which surfaces as `missingFileFailure(...)` — a `translation_failed`
result, matching TRP-04's "rejected before candidate installation" requirement.

- [x] 6.4 **Update the production CLI composition root's `ProcessTranslationWorker` construction to pass a job root** — read the composition root file first (found via `grep -rn "new ProcessTranslationWorker(" publication-exporter/src/main`) and pass a dedicated subdirectory (e.g. `<reviewRoot>/.jobs`), consistent with how `CandidateWorkspace.create(reviewRoot)` is already rooted.

- [x] 6.5 **Run the new contract test and the full suite**

Run: `cd publication-exporter && mvn -q -Dtest=ProcessTranslationWorkerJobConfinementTest test`
Expected: PASS, 2/2.

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [x] 6.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java \
        src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerJobConfinementTest.java
git commit -m "feat(translation): confine ProcessTranslationWorker results to their job directory (TRP-04)"
```

---

## 7. Changed-publication review plan (RVA-02) and `inspect-publication` wiring

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java`
- Read first: `publication-exporter/bridge-contract/schema-v2.json`'s `reviewPlan` definition, to confirm
  `additionalProperties: true` still holds before assuming schema-safety (per `design.md` D4).

**Interfaces:**
- Produces: `ReviewPlan.changedPublication(CandidatePaths candidatePaths, String ruTitle, String enTitle, String ruDescription, String enDescription, RussianDiff diff): ReviewPlan`
  with `baselineState = "changed"` and a new `@JsonProperty("diff") List<RussianDiff.Line>` field
  (Jackson already serializes records with public accessors — confirm `RussianDiff.Line` needs no extra
  annotation by checking how `CandidatePaths`/other simple types already serialize in `ReviewPlan`).
- Produces (changed): `InspectPublicationHandler(CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace)`.
  `grep -rn "new InspectPublicationHandler(" publication-exporter/src` first.

- [x] 7.1 **Write a failing acceptance test**: `inspectingChangedApprovedEssayReportsDiff()` — **GIVEN** an
  approved RU/EN snapshot, a ready candidate, and a source note whose body differs from the approved RU body,
  **WHEN** `inspect-publication` runs, **THEN** the response's `reviewPlan.baselineState` is `"changed"` and
  `reviewPlan.diff` is non-empty and matches the expected added/removed lines. Follow the exact test-harness
  helper patterns already present in `InspectPublicationCliAcceptanceTest` (read it first — do not guess
  field/method names).

- [x] 7.2 **Run it to confirm it fails**

Run: `cd publication-exporter && mvn -q -Dtest=InspectPublicationCliAcceptanceTest test`
Expected: FAILURE — `baselineState` is currently always `"absent"`... wait, currently it's hardcoded to the
`firstPublication` factory only when a candidate exists; confirm the actual current failure mode by running
this before assuming, then proceed.

- [x] 7.3 **Add `ReviewPlan.changedPublication(...)`**

```java
public static ReviewPlan changedPublication(
        CandidatePaths candidatePaths,
        String ruTitle,
        String enTitle,
        String ruDescription,
        String enDescription,
        RussianDiff diff) {
    Objects.requireNonNull(candidatePaths, "candidatePaths");
    Objects.requireNonNull(diff, "diff");
    return new ReviewPlan("changed", List.of(
            ReviewTarget.of("ru", candidatePaths.ruPath().toString(), null),
            ReviewTarget.of("en", candidatePaths.enPath().toString(), null)),
            ruTitle, enTitle, ruDescription, enDescription, diff.lines());
}
```
This requires widening the private constructor and adding a `diff` field with a `@JsonProperty("diff")`
getter defaulting to `List.of()` for `firstPublication(...)` (so existing S04 tests asserting the JSON shape
of a first-publication plan still see a `diff` key present but empty, not absent — confirm against
`bridge-contract/schema-v2.json`'s `additionalProperties: true` that adding this key cannot break plugin
contract validation before finalizing). Update `equals`/`hashCode`/`toString` to include `diff`.

- [x] 7.4 **Wire `InspectPublicationHandler` to `ApprovedSnapshotWorkspace` and branch on baseline presence**

```java
public InspectPublicationHandler(
        CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
    this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    this.approvedSnapshotWorkspace =
            Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
}
```
In `readyForReviewResponse(...)`, look up `approvedSnapshotWorkspace.read(identity)`; if present, compute
`RussianDiff.betweenBodies(approved.get().ruBody(), candidateSnapshot.ruBody())` and call
`ReviewPlan.changedPublication(...)`; if absent, keep calling `ReviewPlan.firstPublication(...)` unchanged.
Update the one remaining `essayInspected(...)` call's `approvedSnapshotState` argument from the hardcoded
`ABSENT` to `approved.isPresent() ? READY : ABSENT` (this was already wrong/simplified before S08 — confirm
against `BridgeResponse.essayInspected`'s parameter meaning before changing, since getting this state flag
right is exactly what RVA-01's "independent absent candidate/approved/reference/release states" scenario
from S02 already covers and must keep covering).

- [x] 7.5 **Update the production composition root's `new InspectPublicationHandler(...)` call site** to pass
  the same `ApprovedSnapshotWorkspace` instance the composition root already builds for `PrepareHandler`
  (Task 5.3) and `MarkReviewedHandler` — do not construct a second one against a different root.

- [x] 7.6 **Run the acceptance test and the full suite**

Run: `cd publication-exporter && mvn -q -Dtest=InspectPublicationCliAcceptanceTest test`
Expected: PASS.

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS, all tests green.

- [x] 7.7 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java \
        src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java \
        src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java
git commit -m "feat(inspect): report changed-publication review plan with RussianDiff (RVA-02)"
```

---

## 8. Whole-branch regression pass and requirement traceability check

**Files:** none created/modified — verification only.

- [x] 8.1 **Run the complete Maven test suite**

Run: `cd publication-exporter && mvn -B test`
Expected: `Tests run: 3XX+, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, total elapsed time still
sub-second-to-low-seconds (matches the <1s in-memory acceptance-subset constraint; the two slow items in this
suite, if any, stay in the real-adapter contract tests only, per existing project pattern).

- [x] 8.2 **Manually trace each requirement scenario in `scope-pins.md` to the test(s) that now exercise it**:
  TRP-02 (both scenarios) → Task 4/5 tests; TRP-03 (both scenarios) → Task 4/5 tests; TRP-04 (both scenarios)
  → Task 6 tests; RVA-02 changed-publication scenario → Task 7 test; PCM-06 (both scenarios) → Task 3 unit
  tests plus Task 4/5 acceptance coverage. Note any scenario without a direct test in a short list for the
  final branch review to flag — do not silently mark this task done if one is missing.

- [x] 8.3 **Confirm `git status` is clean except for this slice's new/modified files** (no stray build
  artifacts, no `target/` changes committed).

- [x] 8.4 **This task has no commit of its own** — it is the checkpoint before subagent-driven-development
  hands off to the four parallel review passes and the final whole-branch review.
