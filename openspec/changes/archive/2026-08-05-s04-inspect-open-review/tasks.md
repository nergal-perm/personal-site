# S04 — Inspect and Open First-Publication Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `inspect-publication`, once a candidate exists for the inspected publication (installed by S03's `prepare`), reports `candidateState: "ready"`, top-level `status: "ready_for_review"`, and a `reviewPlan` (`baselineState: "absent"`, `targets: [ru, en]` as absolute paths) that the obsidian-plugin's already-built `validateReviewPlan`/`launchReviewPlan` accept as-is to open both candidates in separate editor windows. An unprepared essay still returns S02's unchanged `not_prepared`/all-`absent` shape.

**Architecture:** No new production adapter. `CandidateWorkspace` (the existing port from S03) gains one read method, `find(identity): Optional<CandidatePaths>`, implemented first by `NullCandidateWorkspace` (in-memory fake, proven first) and then by `FilesystemCandidateWorkspace` (proven against the same contract) per `design.md` D1/D2/D4. Two new small bridge-response value types, `ReviewTarget` and `ReviewPlan` (D3), get built from a found candidate's paths. `InspectPublicationHandler` gains a `CandidateWorkspace` constructor dependency and branches on `find(...)` (D5). `InspectPublicationCommand`'s already-declared, previously-inert `--review` option finally gets wired to a real `CandidateWorkspace`. `bridge-contract/schema-v2.json` declares the `reviewPlan`/`reviewTarget` shape (D7).

**Tech Stack:** Same as S01-S03 — Java 17, Maven, picocli, Jackson, com.networknt:json-schema-validator, JUnit Jupiter, obsidian-plugin's Node `node --test`. **No `pom.xml` change in this slice** — every new type uses only `java.nio.file`/`java.util`, already available.

## Global Constraints

- Requirements introduced: RVA-01 (real delta, `specs/review-and-approval/spec.md`), RVA-02, BRG-04, BRG-07 (all three scope pins, `scope-pins.md`) — no other requirement is pulled in.
- `publication-exporter/pom.xml` is not modified in this slice.
- Functional collaborative-design decisions (binding, do not re-litigate): (1) RVA-01 gets a new scenario, "First-publication candidate is reviewed", rather than broadening the existing "complete candidate + approved baseline" scenario. (2) `semanticReferenceState` continues to report `"absent"` for a first-publication candidate — SEM-03's empty-map realization is not surfaced through inspection in this slice.
- Technical collaborative-design decisions (binding, do not re-litigate): (3) top-level `status` becomes `"ready_for_review"` when a candidate exists, matching what `prepare` already returns for the same condition (D5). (4) `find()` gets tests added directly to the existing `NullCandidateWorkspaceTest`/`FilesystemCandidateWorkspaceTest` files — no new shared contract-test base class (D6).
- `/nullables`: `CandidateWorkspace.find(...)` is added to both `create()`/`createNull()` sides of the existing port; no mocking library is used anywhere in this plan. `NullCandidateWorkspace`'s `find(...)` derives synthetic-but-deterministic absolute paths from its own in-memory `installed` state — no new I/O, no new stub class.
- `/applying-sbpp`: `CandidatePaths`, `ReviewTarget`, `ReviewPlan` are each built via a named Constructor Method (`of(...)`/`firstPublication(...)`) with a `private` constructor — never bare `new` from outside their own class, matching the `PublicationIdentity`/`Diagnostic`/`ReferenceMap` precedent (do NOT convert any of these to `record`s). `InspectPublicationHandler#inspect` stays a Composed Method table of contents (admit → find candidate → branch to one of two named response builders).
- `/oo-design-guide`: `InspectPublicationHandler` keeps the same one-dominant-public-method heuristic-3.9 departure already established in S01-S03 for this class — noted once, not re-litigated per task. `CandidateWorkspace.find(...)` is a pure query (no mutation) even though the interface now also carries the `install(...)` command — accepted departure from strict CQS-at-the-interface-level per `design.md`'s Risk on D1, not to be "fixed" by adding a second port in this slice.
- Out of scope for S04 — do not implement: `baselineState: "complete"` and the approved-versus-candidate diff (RVA-02's "Existing publication changed" scenario, S08/S09's concern — no approved snapshot can exist until S05), approval, candidate replacement, any editor-launch implementation detail (already built and tested in `obsidian-plugin`, untouched here), reporting `semanticReferenceState` as anything but `"absent"`.
- Governance: implements Haft problem `prob-20260805-d9f3aef2`; do not close it or archive this OpenSpec change until the final task's full verification pass is green and the branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the final GPT-5.6 Sol max-effort review) confirms completeness.

---

### Task 1: `CandidatePaths` — a paths-only Whole Value

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidatePaths.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidatePathsTest.java`

**Interfaces:**
- Produces: `CandidatePaths.of(Path ruPath, Path enPath): CandidatePaths`, `#ruPath(): Path`, `#enPath(): Path` — consumed by Task 2, 3, 5.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.candidate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidatePathsTest {

    @Test
    void accessorsReturnConstructedValues() {
        CandidatePaths paths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));

        assertEquals(Path.of("/review/blog/my-essay/candidate/ru.md"), paths.ruPath());
        assertEquals(Path.of("/review/blog/my-essay/candidate/en.md"), paths.enPath());
    }

    @Test
    void equalPathsBuiltSeparatelyAreEqual() {
        assertEquals(
                CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")),
                CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")));
    }

    @Test
    void ruPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidatePaths.of(null, Path.of("en.md")));
        assertEquals("ruPath", exception.getMessage());
    }

    @Test
    void enPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidatePaths.of(Path.of("ru.md"), null));
        assertEquals("enPath", exception.getMessage());
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CandidatePathsTest`
Expected: FAIL — compile error, `CandidatePaths` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.candidate;

import java.nio.file.Path;
import java.util.Objects;

public final class CandidatePaths {

    private final Path ruPath;
    private final Path enPath;

    private CandidatePaths(Path ruPath, Path enPath) {
        this.ruPath = Objects.requireNonNull(ruPath, "ruPath");
        this.enPath = Objects.requireNonNull(enPath, "enPath");
    }

    public static CandidatePaths of(Path ruPath, Path enPath) {
        return new CandidatePaths(ruPath, enPath);
    }

    public Path ruPath() {
        return ruPath;
    }

    public Path enPath() {
        return enPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidatePaths that)) {
            return false;
        }
        return ruPath.equals(that.ruPath) && enPath.equals(that.enPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruPath, enPath);
    }

    @Override
    public String toString() {
        return "CandidatePaths[ruPath=" + ruPath + ", enPath=" + enPath + "]";
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CandidatePathsTest`
Expected: PASS — 4 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidatePaths.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidatePathsTest.java
git commit -m "feat(publication-exporter): add CandidatePaths value type"
```

---

### Task 2: `CandidateWorkspace#find(...)` — interface method and in-memory fake

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java`

**Interfaces:**
- Consumes: `CandidatePaths.of(Path, Path)` (Task 1).
- Produces: `CandidateWorkspace#find(PublicationIdentity): Optional<CandidatePaths>` — consumed by Task 3 (real adapter, same contract) and Task 7 (`InspectPublicationHandler`).

**Correction found during implementation:** adding `find(...)` to the `CandidateWorkspace` interface is a Java compile-time break for every existing implementor the moment it lands — `FilesystemCandidateWorkspace` (Task 3) does not compile again until it also implements `find(...)`. Tasks 2 and 3 must therefore be implemented and committed together as one unit (one implementer dispatch, one commit) — the task boundary below still documents each adapter's own steps and tests separately for clarity, but do not attempt to compile or commit Task 2 alone before Task 3's steps are also applied.

- [x] **Step 1: Write the failing tests (append to `NullCandidateWorkspaceTest`)**

```java
    @Test
    void findIsAbsentBeforeAnyInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsPathsEndingInRuMdAndEnMdAfterInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void findIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.find(otherIdentity));
    }
```

Add the import (alongside the existing ones at the top of the file):

```java
import java.util.Optional;
```

and add `import static org.junit.jupiter.api.Assertions.assertTrue;` next to the existing `assertEquals` static import.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest`
Expected: FAIL — compile error, `find` is undefined on `NullCandidateWorkspace`

- [x] **Step 3: Write minimal implementation**

In `CandidateWorkspace.java`, add the method to the interface and the import:

```java
import java.util.Optional;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    static CandidateWorkspace create(Path reviewRoot) {
        return new FilesystemCandidateWorkspace(reviewRoot);
    }

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
```

In `NullCandidateWorkspace.java`, add `find(...)` and its two private helpers, plus the `java.nio.file.Path` and `java.util.Optional` imports:

```java
import java.nio.file.Path;
import java.util.Optional;
```

```java
    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity).map(NullCandidateWorkspace::syntheticPaths);
    }

    private Optional<InstalledCandidate> lastInstalledMatching(PublicationIdentity identity) {
        InstalledCandidate match = null;
        for (InstalledCandidate candidate : installed) {
            if (candidate.identity().equals(identity)) {
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    private static CandidatePaths syntheticPaths(InstalledCandidate candidate) {
        Path candidateDirectory = Path.of("/candidate", candidate.identity().publicCollection(),
                candidate.identity().publicId(), "candidate");
        return CandidatePaths.of(candidateDirectory.resolve("ru.md"), candidateDirectory.resolve("en.md"));
    }
```

`lastInstalledMatching` mirrors the real adapter's own semantics (a later `install()` for the same identity replaces the candidate at the same directory) using only the state `NullCandidateWorkspace` already keeps — no new stub class, per `/nullables`.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest`
Expected: PASS — 6 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java
git commit -m "feat(publication-exporter): add CandidateWorkspace#find with in-memory fake"
```

---

### Task 3: `FilesystemCandidateWorkspace#find(...)` — real adapter proven against the same contract

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`

**Interfaces:**
- Consumes: `CandidatePaths.of(Path, Path)` (Task 1); `candidateDirectory(PublicationIdentity)` (existing private helper, reused, not duplicated).
- Produces: real-adapter `find(...)`, proven equivalent to Task 2's fake — consumed by Task 7/8.

**See Task 2's correction note:** these steps must be applied in the same implementer dispatch and commit as Task 2 — `CandidateWorkspace#find(...)` does not compile with only one adapter implementing it.

- [x] **Step 1: Write the failing tests (append to `FilesystemCandidateWorkspaceTest`)**

```java
    @Test
    void findIsAbsentBeforeInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsAbsolutePathsToTheInstalledFiles() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path candidateDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("candidate");
        assertEquals(candidateDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertEquals(candidateDir.resolve("en.md").toRealPath(), found.get().enPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
        assertTrue(found.get().enPath().isAbsolute());
    }

    @Test
    void findIsAbsentForADifferentIdentityAfterInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.find(otherIdentity));
    }
```

Add `import java.util.Optional;` alongside the existing imports at the top of the file.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest`
Expected: FAIL — compile error, `find` is undefined on `FilesystemCandidateWorkspace`

- [x] **Step 3: Write minimal implementation**

Add to `FilesystemCandidateWorkspace.java` (`Optional` is already imported in this file):

```java
    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path candidateDirectory = candidateDirectory(identity);
        Path ruPath = candidateDirectory.resolve("ru.md");
        Path enPath = candidateDirectory.resolve("en.md");
        if (Files.exists(ruPath) && Files.exists(enPath)) {
            return Optional.of(CandidatePaths.of(ruPath, enPath));
        }
        return Optional.empty();
    }
```

Place it directly after `install(...)` — it reuses the same private `candidateDirectory(identity)` helper `install` already calls, per `design.md` D4: completeness is exactly "both `ru.md` and `en.md` exist," since `install`'s stage-then-`ATOMIC_MOVE` write path (S03) can never leave a partial directory visible.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest`
Expected: PASS — 9 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java
git commit -m "feat(publication-exporter): add FilesystemCandidateWorkspace#find"
```

---

### Task 4: `ReviewTarget` — a bridge-response Whole Value

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewTarget.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/ReviewTargetTest.java`

**Interfaces:**
- Produces: `ReviewTarget.of(String language, String proposedPath, String publishedPath): ReviewTarget` — consumed by Task 5.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewTargetTest {

    @Test
    void accessorsReturnConstructedValues() {
        ReviewTarget target = ReviewTarget.of("ru", "/review/blog/my-essay/candidate/ru.md", null);

        assertEquals("ru", target.language());
        assertEquals("/review/blog/my-essay/candidate/ru.md", target.proposedPath());
        assertNull(target.publishedPath());
    }

    @Test
    void equalTargetsBuiltSeparatelyAreEqual() {
        assertEquals(
                ReviewTarget.of("ru", "/ru.md", null),
                ReviewTarget.of("ru", "/ru.md", null));
    }

    @Test
    void languageIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReviewTarget.of(null, "/ru.md", null));
        assertEquals("language", exception.getMessage());
    }

    @Test
    void proposedPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReviewTarget.of("ru", null, null));
        assertEquals("proposedPath", exception.getMessage());
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReviewTargetTest`
Expected: FAIL — compile error, `ReviewTarget` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class ReviewTarget {

    private final String language;
    private final String proposedPath;
    private final String publishedPath;

    private ReviewTarget(String language, String proposedPath, String publishedPath) {
        this.language = Objects.requireNonNull(language, "language");
        this.proposedPath = Objects.requireNonNull(proposedPath, "proposedPath");
        this.publishedPath = publishedPath;
    }

    public static ReviewTarget of(String language, String proposedPath, String publishedPath) {
        return new ReviewTarget(language, proposedPath, publishedPath);
    }

    @JsonProperty("language")
    public String language() {
        return language;
    }

    @JsonProperty("proposedPath")
    public String proposedPath() {
        return proposedPath;
    }

    @JsonProperty("publishedPath")
    public String publishedPath() {
        return publishedPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewTarget that)) {
            return false;
        }
        return language.equals(that.language)
                && proposedPath.equals(that.proposedPath)
                && Objects.equals(publishedPath, that.publishedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(language, proposedPath, publishedPath);
    }

    @Override
    public String toString() {
        return "ReviewTarget[language=" + language + ", proposedPath=" + proposedPath
                + ", publishedPath=" + publishedPath + "]";
    }
}
```

`publishedPath` deliberately has no `@JsonInclude` annotation and no null-rejection: this class is not annotated `@JsonInclude(NON_NULL)`, so Jackson's default (include-always) applies, serializing a null `publishedPath` as JSON `null` — exactly what the plugin's `validateReviewPlan` requires (`target.publishedPath !== null` must always be checkable, never an absent key).

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReviewTargetTest`
Expected: PASS — 4 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewTarget.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/ReviewTargetTest.java
git commit -m "feat(publication-exporter): add ReviewTarget value type"
```

---

### Task 5: `ReviewPlan` — first-publication factory

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/ReviewPlanTest.java`

**Interfaces:**
- Consumes: `ReviewTarget.of(...)` (Task 4), `CandidatePaths#ruPath()`/`#enPath()` (Task 1).
- Produces: `ReviewPlan.firstPublication(CandidatePaths): ReviewPlan`, `#baselineState(): String`, `#targets(): List<ReviewTarget>` — consumed by Task 6/7.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.bridge;

import dev.eugene.publicationexporter.candidate.CandidatePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewPlanTest {

    @Test
    void firstPublicationReportsAbsentBaselineAndOrderedRuThenEnTargets() {
        CandidatePaths paths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));

        ReviewPlan plan = ReviewPlan.firstPublication(paths);

        assertEquals("absent", plan.baselineState());
        assertEquals(List.of(
                ReviewTarget.of("ru", "/review/blog/my-essay/candidate/ru.md", null),
                ReviewTarget.of("en", "/review/blog/my-essay/candidate/en.md", null)),
                plan.targets());
    }

    @Test
    void targetsListIsImmutable() {
        ReviewPlan plan = ReviewPlan.firstPublication(CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")));

        assertThrows(UnsupportedOperationException.class,
                () -> plan.targets().add(ReviewTarget.of("ru", "x", null)));
    }

    @Test
    void candidatePathsIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> ReviewPlan.firstPublication(null));
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReviewPlanTest`
Expected: FAIL — compile error, `ReviewPlan` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.candidate.CandidatePaths;

import java.util.List;
import java.util.Objects;

public final class ReviewPlan {

    private static final String BASELINE_ABSENT = "absent";

    private final String baselineState;
    private final List<ReviewTarget> targets;

    private ReviewPlan(String baselineState, List<ReviewTarget> targets) {
        this.baselineState = Objects.requireNonNull(baselineState, "baselineState");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public static ReviewPlan firstPublication(CandidatePaths candidatePaths) {
        Objects.requireNonNull(candidatePaths, "candidatePaths");
        return new ReviewPlan(BASELINE_ABSENT, List.of(
                ReviewTarget.of("ru", candidatePaths.ruPath().toString(), null),
                ReviewTarget.of("en", candidatePaths.enPath().toString(), null)));
    }

    @JsonProperty("baselineState")
    public String baselineState() {
        return baselineState;
    }

    @JsonProperty("targets")
    public List<ReviewTarget> targets() {
        return targets;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewPlan that)) {
            return false;
        }
        return baselineState.equals(that.baselineState) && targets.equals(that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baselineState, targets);
    }

    @Override
    public String toString() {
        return "ReviewPlan[baselineState=" + baselineState + ", targets=" + targets + "]";
    }
}
```

Only `firstPublication(...)` is built now, per `design.md` D3: a `forExistingPublication(...)`/`"complete"` factory is S08/S09's decision once that slice's real diff data exists, not a shape guessed ahead of it.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReviewPlanTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/ReviewPlanTest.java
git commit -m "feat(publication-exporter): add ReviewPlan.firstPublication factory"
```

---

### Task 6: `BridgeResponse` gains a nullable `reviewPlan` field (inert)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java`

**Interfaces:**
- Consumes: `ReviewPlan` (Task 5).
- Produces: `BridgeResponse.essayInspected(String, String, PublicationIdentity, String, String, String, String, ReviewPlan): BridgeResponse` (signature now takes 8 params), `#reviewPlan(): ReviewPlan` — consumed by Task 7.

This task only adds the *capability*; no response actually carries a non-null `reviewPlan` until Task 7 wires `InspectPublicationHandler`'s logic. The one existing call site is updated to pass `null`, keeping today's response shape byte-for-byte identical.

- [x] **Step 1: Write the failing tests**

Update the existing `essayInspectedResponseSerializesToSchemaV2Shape` call site in `BridgeResponseJsonTest.java` (adds a trailing `null`):

```java
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "not_prepared", identity,
                "absent", "absent", "absent", "absent", null);
```

Append two new tests to the same file:

```java
    @Test
    void essayInspectedResponseOmitsReviewPlanFromJsonWhenNull() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "not_prepared", identity,
                "absent", "absent", "absent", "absent", null);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertFalse(parsed.has("reviewPlan"));
    }

    @Test
    void essayInspectedResponseIncludesReviewPlanWhenPresent() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "absent", "absent", "absent", ReviewPlan.firstPublication(candidatePaths));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals("absent", parsed.get("reviewPlan").get("baselineState").asText());
        assertEquals(2, parsed.get("reviewPlan").get("targets").size());
        assertEquals("ru", parsed.get("reviewPlan").get("targets").get(0).get("language").asText());
        assertTrue(parsed.get("reviewPlan").get("targets").get(0).get("publishedPath").isNull());
    }
```

Add the two new imports at the top of the file:

```java
import dev.eugene.publicationexporter.candidate.CandidatePaths;

import java.nio.file.Path;
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: FAIL — compile error, `essayInspected` does not accept an 8th argument

- [x] **Step 3: Write minimal implementation**

Replace the whole `BridgeResponse.java` file:

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeResponse {

    private final int schemaVersion;
    private final String command;
    private final boolean ok;
    private final String status;
    private final List<Diagnostic> diagnostics;
    private final List<Diagnostic> workspaceHealth;
    private final PublicationIdentity identity;
    private final String candidateState;
    private final String approvedSnapshotState;
    private final String semanticReferenceState;
    private final String releaseState;
    private final ReviewPlan reviewPlan;

    private BridgeResponse(
            int schemaVersion,
            String command,
            boolean ok,
            String status,
            List<Diagnostic> diagnostics,
            List<Diagnostic> workspaceHealth,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState,
            ReviewPlan reviewPlan) {
        this.schemaVersion = schemaVersion;
        this.command = Objects.requireNonNull(command, "command");
        this.ok = ok;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.workspaceHealth = Objects.requireNonNull(workspaceHealth, "workspaceHealth");
        this.identity = identity;
        this.candidateState = candidateState;
        this.approvedSnapshotState = approvedSnapshotState;
        this.semanticReferenceState = semanticReferenceState;
        this.releaseState = releaseState;
        this.reviewPlan = reviewPlan;
    }

    public static BridgeResponse blocked(String command, Diagnostic diagnostic) {
        return blocked(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse blocked(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "metadata_blocked",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }

    public static BridgeResponse prepared(String command, PublicationIdentity identity) {
        return new BridgeResponse(2, command, true, "ready_for_review",
                List.of(), List.of(), Objects.requireNonNull(identity, "identity"),
                null, null, null, null, null);
    }

    public static BridgeResponse translationFailed(String command, Diagnostic diagnostic) {
        return translationFailed(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse translationFailed(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "translation_failed",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }

    public static BridgeResponse essayInspected(
            String command,
            String status,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState,
            ReviewPlan reviewPlan) {
        return new BridgeResponse(2, command, true, status, List.of(), List.of(),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(candidateState, "candidateState"),
                Objects.requireNonNull(approvedSnapshotState, "approvedSnapshotState"),
                Objects.requireNonNull(semanticReferenceState, "semanticReferenceState"),
                Objects.requireNonNull(releaseState, "releaseState"),
                reviewPlan);
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("command")
    public String command() {
        return command;
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("status")
    public String status() {
        return status;
    }

    @JsonProperty("diagnostics")
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @JsonProperty("workspaceHealth")
    public List<Diagnostic> workspaceHealth() {
        return workspaceHealth;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("candidateState")
    public String candidateState() {
        return candidateState;
    }

    @JsonProperty("approvedSnapshotState")
    public String approvedSnapshotState() {
        return approvedSnapshotState;
    }

    @JsonProperty("semanticReferenceState")
    public String semanticReferenceState() {
        return semanticReferenceState;
    }

    @JsonProperty("releaseState")
    public String releaseState() {
        return releaseState;
    }

    @JsonProperty("reviewPlan")
    public ReviewPlan reviewPlan() {
        return reviewPlan;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgeResponse that)) {
            return false;
        }
        return schemaVersion == that.schemaVersion
                && ok == that.ok
                && command.equals(that.command)
                && status.equals(that.status)
                && diagnostics.equals(that.diagnostics)
                && workspaceHealth.equals(that.workspaceHealth)
                && Objects.equals(identity, that.identity)
                && Objects.equals(candidateState, that.candidateState)
                && Objects.equals(approvedSnapshotState, that.approvedSnapshotState)
                && Objects.equals(semanticReferenceState, that.semanticReferenceState)
                && Objects.equals(releaseState, that.releaseState)
                && Objects.equals(reviewPlan, that.reviewPlan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, command, ok, status, diagnostics, workspaceHealth,
                identity, candidateState, approvedSnapshotState, semanticReferenceState, releaseState, reviewPlan);
    }

    @Override
    public String toString() {
        return "BridgeResponse[schemaVersion=" + schemaVersion + ", command=" + command
                + ", ok=" + ok + ", status=" + status + ", diagnostics=" + diagnostics
                + ", workspaceHealth=" + workspaceHealth + ", identity=" + identity
                + ", candidateState=" + candidateState + ", approvedSnapshotState=" + approvedSnapshotState
                + ", semanticReferenceState=" + semanticReferenceState + ", releaseState=" + releaseState
                + ", reviewPlan=" + reviewPlan + "]";
    }
}
```

Update `InspectPublicationHandler.java`'s sole call site (still returns exactly today's shape — trailing `null`):

```java
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, intake.identity(),
                ABSENT, ABSENT, ABSENT, ABSENT, null);
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: PASS — 10 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS — every pre-existing test across the whole module still passes unchanged, since every response shape produced today is unaffected (the new field is always `null` until Task 7)

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java
git commit -m "feat(publication-exporter): add nullable reviewPlan field to BridgeResponse"
```

---

### Task 7: Wire `InspectPublicationHandler` to report a ready candidate and its review plan

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`

**Interfaces:**
- Consumes: `CandidateWorkspace#find(...)` (Task 2/3), `ReviewPlan.firstPublication(...)` (Task 5), `BridgeResponse.essayInspected(..., ReviewPlan)` (Task 6).
- Produces: `InspectPublicationHandler(CandidateWorkspace)` — the constructor is no longer no-arg; every existing call site must be updated in this task.

**Correction found during implementation:** `InspectPublicationCommand.java` (Task 8) also calls `new InspectPublicationHandler()` and does not compile again until it is updated too — the same class of Java compile-time break Tasks 2/3 already hit. Tasks 7 and 8 must therefore be implemented and committed together as one unit (one implementer dispatch, one commit) — the task boundary below still documents each concern's own steps and tests separately for clarity, but do not attempt to compile or commit Task 7 alone before Task 8's steps are also applied.

This is the real behavioural slice: RVA-01's new "First-publication candidate is reviewed" scenario and BRG-04's "Candidate is ready but approved snapshot is absent" scenario both become observable here.

- [x] **Step 1: Write the failing tests**

Update the `handler` field in `InspectPublicationHandlerTest.java` (this keeps every existing test's behaviour unchanged — a nulled workspace with nothing installed still reports everything absent):

```java
    private final InspectPublicationHandler handler =
            new InspectPublicationHandler(CandidateWorkspace.createNull());
```

Add the import:

```java
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
```

Append a new test to the same file:

```java
    @Test
    void essayWithACompleteCandidateReportsReadyWithAFirstPublicationReviewPlan() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        candidateWorkspace.install(identity, "RU body", "EN body",
                ReferenceMap.empty(identity, "ru-hash", "en-hash"));
        InspectPublicationHandler handlerWithCandidate = new InspectPublicationHandler(candidateWorkspace);

        BridgeResponse response = handlerWithCandidate.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("ready", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
        assertEquals("absent", response.reviewPlan().baselineState());
        assertEquals(2, response.reviewPlan().targets().size());
        assertEquals("ru", response.reviewPlan().targets().get(0).language());
        assertEquals("en", response.reviewPlan().targets().get(1).language());
        assertNull(response.reviewPlan().targets().get(0).publishedPath());
    }
```

Add the imports:

```java
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.reference.ReferenceMap;
```

and `import static org.junit.jupiter.api.Assertions.assertNull;` next to the existing static imports.

Also update `SchemaConformanceTest.java`'s two `new InspectPublicationHandler()` call sites (lines constructing the handler for `blockedResponseConformsToSchemaV2` and `validEssayResponseConformsToSchemaV2`) to:

```java
InspectPublicationHandler handler = new InspectPublicationHandler(CandidateWorkspace.createNull());
```

and add `import dev.eugene.publicationexporter.candidate.CandidateWorkspace;` to that file.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: FAIL — compile error, `InspectPublicationHandler()` no-arg constructor no longer exists (expected: the constructor change is intentional and lands in this step)

- [x] **Step 3: Write minimal implementation**

Replace the whole `InspectPublicationHandler.java` file:

```java
package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.bridge.ReviewPlan;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Objects;
import java.util.Optional;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String READY_FOR_REVIEW = "ready_for_review";
    private static final String ABSENT = "absent";
    private static final String READY = "ready";

    private final CandidateWorkspace candidateWorkspace;

    public InspectPublicationHandler(CandidateWorkspace candidateWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidatePaths> candidate = candidateWorkspace.find(intake.identity());
        if (candidate.isPresent()) {
            return readyForReviewResponse(intake.identity(), candidate.get());
        }
        return notPreparedResponse(intake.identity());
    }

    private BridgeResponse readyForReviewResponse(PublicationIdentity identity, CandidatePaths candidatePaths) {
        return BridgeResponse.essayInspected(
                COMMAND, READY_FOR_REVIEW, identity,
                READY, ABSENT, ABSENT, ABSENT, ReviewPlan.firstPublication(candidatePaths));
    }

    private BridgeResponse notPreparedResponse(PublicationIdentity identity) {
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, identity,
                ABSENT, ABSENT, ABSENT, ABSENT, null);
    }
}
```

`inspect(...)` stays a Composed Method table of contents (admit → find → branch to one of two named response builders) per `/applying-sbpp`; `readyForReviewResponse`/`notPreparedResponse` are Intention Revealing Selectors naming the two outcomes, matching the same one-dominant-public-method shape `/oo-design-guide` already accepted for this class in S01-S03.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: PASS — 8 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS — 4 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java
git commit -m "feat(publication-exporter): report ready candidate state and review plan from inspect-publication"
```

---

### Task 8: Wire the `inspect-publication` CLI's `--review` option to a real `CandidateWorkspace`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `CandidateWorkspace.create(Path)` (existing, S03), `InspectPublicationHandler(CandidateWorkspace)` (Task 7).

**See Task 7's correction note:** these steps must be applied in the same implementer dispatch and commit as Task 7 — `InspectPublicationCommand.java` does not compile once `InspectPublicationHandler`'s constructor changes.

- [x] **Step 1: Write the failing test**

Append to `InspectPublicationCliAcceptanceTest.java`:

```java
    @Test
    void essayWithACompleteCandidateProducesReadyForReviewResponseWithReviewPlan() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);
        prepare();

        int exitCode = inspect("blog/my-essay.md");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_for_review", response.get("status").asText());
        assertEquals("ready", response.get("candidateState").asText());
        assertEquals("absent", response.get("approvedSnapshotState").asText());
        JsonNode reviewPlan = response.get("reviewPlan");
        assertEquals("absent", reviewPlan.get("baselineState").asText());
        JsonNode targets = reviewPlan.get("targets");
        assertEquals(2, targets.size());
        assertEquals("ru", targets.get(0).get("language").asText());
        assertTrue(targets.get(0).get("proposedPath").asText().endsWith("ru.md"));
        assertTrue(Path.of(targets.get(0).get("proposedPath").asText()).isAbsolute());
        assertTrue(targets.get(0).get("publishedPath").isNull());
        assertEquals("en", targets.get(1).get("language").asText());
        assertTrue(targets.get(1).get("proposedPath").asText().endsWith("en.md"));
        assertTrue(targets.get(1).get("publishedPath").isNull());
    }

    private void prepare() throws Exception {
        PrepareCommand prepareCommand = new PrepareCommand(TranslationWorker.createNull("# My Essay in English"));
        CommandLine commandLine = new CommandLine(new Main(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == PrepareCommand.class) {
                    return cls.cast(prepareCommand);
                }
                return CommandLine.defaultFactory().create(cls);
            }
        });

        int exitCode = commandLine.execute(
                "prepare",
                "--vault", vaultRoot.toString(),
                "--note", "blog/my-essay.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");

        assertEquals(0, exitCode);
        capturedOut.reset();
    }
```

Add the import: `import dev.eugene.publicationexporter.translation.TranslationWorker;`. (`PrepareCommand` and `Main` are already visible — same `cli` package as this test.)

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: FAIL — `candidateState`/`status` are `"absent"`/`"not_prepared"`, `reviewPlan` is null (the `--review` option is still inert)

- [x] **Step 3: Write minimal implementation**

Replace the whole `InspectPublicationCommand.java` file:

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.inspect.InspectPublicationHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "inspect-publication")
public final class InspectPublicationCommand implements Callable<Integer> {

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--note", required = true)
    String notePath;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        BridgeResponse response = new InspectPublicationHandler(candidateWorkspace)
                .inspect(VaultRelativePath.of(notePath), vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: PASS — 9 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java
git commit -m "feat(publication-exporter): wire inspect-publication's --review option to CandidateWorkspace"
```

---

### Task 9: Declare `reviewPlan`/`reviewTarget` in `bridge-contract/schema-v2.json` and extend conformance tests

**Files:**
- Modify: `bridge-contract/schema-v2.json`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`
- Modify: `obsidian-plugin/tests/schema-conformance.test.cjs`

**Interfaces:**
- Consumes: `ReviewPlan.firstPublication(...)` (Task 5), `CandidatePaths.of(...)` (Task 1).

- [x] **Step 1: Write the failing tests**

Append to `SchemaConformanceTest.java`:

```java
    @Test
    void essayInspectedResponseWithReviewPlanConformsToSchemaV2() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "absent", "absent", "absent", ReviewPlan.firstPublication(candidatePaths));

        assertConformsToSchemaV2(response);
    }
```

Add one import: `import dev.eugene.publicationexporter.candidate.CandidatePaths;` (`java.nio.file.Path` is already imported in this file for `SCHEMA_PATH`).

Append to `obsidian-plugin/tests/schema-conformance.test.cjs`, directly after `essayInspectedFixture()`:

```javascript
function essayInspectedWithReviewPlanFixture() {
  return {
    ...essayInspectedFixture(),
    status: "ready_for_review",
    candidateState: "ready",
    reviewPlan: {
      baselineState: "absent",
      targets: [
        { language: "ru", proposedPath: "/review/blog/my-essay/candidate/ru.md", publishedPath: null },
        { language: "en", proposedPath: "/review/blog/my-essay/candidate/en.md", publishedPath: null },
      ],
    },
  };
}

test("ready-for-review-with-plan fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = essayInspectedWithReviewPlanFixture();
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("validator rejects a reviewPlan with only one target", () => {
  const schema = loadSchema();
  const fixture = essayInspectedWithReviewPlanFixture();
  fixture.reviewPlan.targets = [fixture.reviewPlan.targets[0]];
  const errors = validateAgainstSchema(schema, fixture);
  assert.ok(errors.length > 0);
});

test("validator rejects a reviewPlan with an unrecognised baselineState", () => {
  const schema = loadSchema();
  const fixture = essayInspectedWithReviewPlanFixture();
  fixture.reviewPlan.baselineState = "not-a-real-state";
  const errors = validateAgainstSchema(schema, fixture);
  assert.ok(errors.length > 0);
});

test("plugin's real bridge client accepts a schema-conformant ready-for-review-with-plan response", async () => {
  const fixture = essayInspectedWithReviewPlanFixture();
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 0 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/my-essay.md");
  assert.deepEqual(result.reviewPlan, fixture.reviewPlan);
});
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: FAIL — schema violation, `reviewPlan` is not declared, so `$ref`/`enum` checks the test relies on do not yet exist to fail against (this new test alone would actually pass today since `additionalProperties: true` tolerates the field — the two negative-control tests below are what prove the schema addition is real; run them together)

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: FAIL — the two negative-control tests ("rejects a reviewPlan with only one target", "rejects ... unrecognised baselineState") currently find zero errors, since nothing in today's schema constrains `reviewPlan`'s shape yet

- [x] **Step 3: Write minimal implementation**

Replace the whole `bridge-contract/schema-v2.json` file:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://personal-site.internal/bridge-contract/schema-v2.json",
  "title": "Publication bridge response (schema v2)",
  "type": "object",
  "required": ["schemaVersion", "command", "ok", "status", "diagnostics", "workspaceHealth"],
  "additionalProperties": true,
  "properties": {
    "schemaVersion": { "const": 2 },
    "command": {
      "type": "string",
      "enum": ["prepare", "inspect-publication", "mark-reviewed", "refresh-publication-queue"]
    },
    "ok": { "type": "boolean" },
    "status": { "type": "string" },
    "diagnostics": {
      "type": "array",
      "items": { "$ref": "#/definitions/diagnostic" }
    },
    "workspaceHealth": {
      "type": "array",
      "items": { "$ref": "#/definitions/diagnostic" }
    },
    "identity": {
      "type": "object",
      "required": ["publicCollection", "publicContentType", "publicId"],
      "additionalProperties": true,
      "properties": {
        "publicCollection": { "type": "string" },
        "publicContentType": { "type": "string" },
        "publicId": { "type": "string" }
      }
    },
    "candidateState": { "type": "string" },
    "approvedSnapshotState": { "type": "string" },
    "semanticReferenceState": { "type": "string" },
    "releaseState": { "type": "string" },
    "reviewPlan": { "$ref": "#/definitions/reviewPlan" }
  },
  "definitions": {
    "diagnostic": {
      "type": "object",
      "required": ["field", "message", "blocking"],
      "additionalProperties": true,
      "properties": {
        "field": { "type": "string" },
        "message": { "type": "string" },
        "blocking": { "type": "boolean" }
      }
    },
    "reviewPlan": {
      "type": "object",
      "required": ["baselineState", "targets"],
      "additionalProperties": true,
      "properties": {
        "baselineState": { "type": "string", "enum": ["absent", "complete"] },
        "targets": {
          "type": "array",
          "minItems": 2,
          "maxItems": 2,
          "items": { "$ref": "#/definitions/reviewTarget" }
        }
      }
    },
    "reviewTarget": {
      "type": "object",
      "required": ["language", "proposedPath", "publishedPath"],
      "additionalProperties": true,
      "properties": {
        "language": { "type": "string", "enum": ["ru", "en"] },
        "proposedPath": { "type": "string" },
        "publishedPath": { "type": ["string", "null"] }
      }
    }
  }
}
```

`"complete"` and non-null `publishedPath` are declared now even though no exporter response produces them until S05-S09, matching the same precedent the `command` enum already set by listing `mark-reviewed`/`refresh-publication-queue` ahead of their own slices — per `design.md` D7.

Note (not a code change, informational): the hand-rolled JS validator's `validatePrimitiveType` only compares `schema.type` by strict equality against a single string, so a union `"type": ["string", "null"]` silently matches anything rather than truly enforcing "string or null" on the JS side. This is a pre-existing limitation of that validator, not introduced by this task; the Java-side `com.networknt:json-schema-validator` enforces the union correctly. Extending the JS validator to support union types is out of scope for this slice.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS — 5 tests, 0 failures

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: PASS — all tests including the 4 new ones

- [x] **Step 5: Commit**

```bash
git add bridge-contract/schema-v2.json \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java \
        obsidian-plugin/tests/schema-conformance.test.cjs
git commit -m "test(publication-exporter,obsidian-plugin): declare and conformance-test reviewPlan/reviewTarget in schema-v2"
```

---

### Task 10: Full verification pass

**Files:** none (verification only)

- [x] **Step 1: Run the complete `publication-exporter` suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (baseline 152 + this slice's new tests across Tasks 1-9)

- [x] **Step 2: Run the obsidian-plugin conformance suite**

Run: `cd obsidian-plugin && node --test tests/`
Expected: all tests passing, including the Task 9 additions

- [x] **Step 3: Validate the OpenSpec change**

Run: `openspec validate s04-inspect-open-review --strict`
Expected: `Change 's04-inspect-open-review' is valid`

- [x] **Step 4: Confirm the working tree is clean and every task's commit is present**

Run: `git log --oneline -10` and `git status --porcelain=v1`
Expected: 9 feature/test commits from this plan (Tasks 1-9; Task 10 has no code changes to commit), clean tree

- [x] **Step 5: Report readiness for review**

Do not close Haft problem `prob-20260805-d9f3aef2` or archive this OpenSpec change here — that happens after
the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the
final GPT-5.6 Sol max-effort review) confirms the slice is complete.
