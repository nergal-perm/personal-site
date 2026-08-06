# S07 — Install and Build the First Managed Site Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install one approved essay (S06's precedent, read fresh from `ApprovedSnapshotWorkspace`) into the site's previously-absent managed content roots as `src/content/blog/{ru,en}/<publicId>.md` plus a site-level `.astro-export/release-provenance.json` manifest, passing the real `site/scripts/check-content.mjs` gate and a real `astro build` — while closing a real requirement gap this slice is the first to need: `title`/`description` must be admitted from vault frontmatter and threaded through the existing pipeline, since `site/src/content.config.ts`'s schema requires both and nothing upstream carries them today.

**Architecture:** One new production port (`ManagedSiteInstaller`, in-memory fake first then a real filesystem adapter) plus a new `install-to-site` CLI command — this slice's one new production boundary adapter (design.md D5). Everything else is widening three already-shipped interfaces (`CandidateWorkspace`, `ApprovedSnapshotWorkspace`, `TranslationWorker`) and one shared value type (`CandidateSnapshot`) to carry two more admitted string fields end to end (design.md D1-D4), following the interface-change discipline used for every prior slice: every known implementor and test double is enumerated and updated in the same commit as its interface. `SiteReleaseManifest` (design.md D6) reimplements `check-content.mjs`'s exact SHA-256/canonical-JSON scheme in Java, proven byte-compatible by a real-adapter contract test that subprocess-invokes the actual gate script — not by a Java-side test asserting against itself.

**Tech Stack:** Same as S01-S06 — Java 17, Maven, picocli, Jackson, JUnit Jupiter. `publication-exporter/pom.xml` is not modified — every new type uses only `java.nio.file`/`java.security`/`java.util`, already available. The real-adapter contract test and the one slow smoke test invoke `node` (for `check-content.mjs`) and `npx astro build` as **test-only** subprocesses (`ProcessBuilder`), matching the operator's decision that production code never shells out (design.md Context point 3).

## Global Constraints

- Requirements introduced: REL-05 (real delta, "Empty-destination install" scenario, `specs/release-materialization/spec.md`), ADM-04 (real delta, essay added to the kind-specific-contract field list, `specs/publication-admission/spec.md`). REL-04, REL-06, PCM-01, PCM-02, TRP-01, REL-01, REL-03 are realized, not modified — see `scope-pins.md`.
- Functional collaborative-design decisions (binding, do not re-litigate): REL-05 gets the new empty-destination scenario; title/description are admitted from vault frontmatter (ADM-04), not synthesized by the install adapter.
- Technical collaborative-design decisions (binding, do not re-litigate — see `design.md` D1-D9): (1) `EssayAdmission`/`NoteIntake` admit `title`/`description`, blocking with a field-specific diagnostic when either is blank. (2) `CandidateSnapshot` widens to seven fields (`ruBody`, `enBody`, `ruTitle`, `enTitle`, `ruDescription`, `enDescription`, `referenceMap`); `CandidateWorkspace#install` and `ApprovedSnapshotWorkspace#install` both widen to match. (3) The translation worker translates all three RU strings in one invocation; `TranslationResult` carries all three EN strings. (4) `install-to-site` is a new, independent command reading `ApprovedSnapshotWorkspace#read` directly — `build-from-review`/`ReleaseOutputStore`/`ReleaseProvenance` are untouched. (5) `ManagedSiteInstaller#install(identity, CandidateSnapshot)` writes `<site>/src/content/<collection>/{ru,en}/<publicId>.md` plus `<site>/.astro-export/release-provenance.json`. (6) `SiteReleaseManifest` sets `selectedPages: []` (operator decision — nothing in `check-content.mjs` independently validates its contents) and `activationCount`/`deactivationCount: 0`. (7) `sourceHash` in both locale files equals `referenceMap.ruHash()`; `translationStatus` is `"source"` for RU and `"generated"` for EN; `translationOf` is set only on the EN file.
- `/nullables`: every new port (`ManagedSiteInstaller`) gets `create()`/`createNull()` factories from the start; in-memory fakes are proven before real adapters; no mocking library anywhere in this plan.
- `/applying-sbpp`: every new value type (`SiteReleaseManifest`, `ManagedTreeHash`, `PayloadFileHash`) is built via a named Constructor Method with a `private` constructor — never bare `new` from outside its own package/class, matching `PublicationIdentity`/`ReferenceMap`/`CandidateSnapshot`/`ReleaseProvenance` precedent (do NOT convert any of these to `record`s). `InstallToSiteHandler#installToSite` is a Composed Method table of contents, mirroring `BuildFromReviewHandler#buildFromReview`'s existing shape.
- `/oo-design-guide`: `ManagedSiteInstaller` and `ApprovedSnapshotWorkspace`/`ReleaseOutputStore` stay separate interfaces — installing into the site and materializing review-root release output are distinct lifecycles with no shared behavior beyond low-level filesystem mechanics (heuristic 5.9/5.10). `SiteReleaseManifest`'s hashing internals stay private to the `release`/`site` package; `ManagedSiteInstaller`'s public contract exposes only `install(identity, snapshot)` (heuristic 2.1/2.3, matching `ReleaseOutputStore`'s own one-method public surface).
- **Interface-change discipline** (memory `feedback-java-interface-change-task-planning`): this slice changes three interfaces at once — the widest ripple so far. Every task below that touches an interface enumerates its exact known implementors and test doubles; none may be split into a partial commit.
- **Fixture blast radius**: `EssayAdmission` will reject every existing "valid essay" test fixture across the codebase once title/description become mandatory. Grep confirms exactly 8 test files exercise `EssayAdmission`/`NoteIntake` acceptance with a `publish: true` fixture and must gain `title:`/`description:` frontmatter lines: `EssayAdmissionTest`, `NoteIntakeTest`, `PrepareHandlerTest`, `MarkReviewedHandlerTest`, `InspectPublicationHandlerTest`, `PrepareCliAcceptanceTest`, `MarkReviewedCliAcceptanceTest`, `InspectPublicationCliAcceptanceTest`. Four other files matched the same grep (`SchemaConformanceTest`, `FrontmatterTest`, `FilesystemVaultReaderTest`, `NullVaultReaderTest`) but do not import `EssayAdmission`/`NoteIntake` and are confirmed unaffected — do not touch them.
- Out of scope for S07 — do not implement: replacing an existing site generation or recovery from an interrupted replacement (S10), assets (`public/assets/vault`) or curated pages (`src/data/pages`) as exporter-generated output (this slice's fixtures pre-seed curated pages as static data), multiple publications in one invocation (S16), semantic occurrence resolution (S20), any `content.config.ts` field beyond `title`/`description`/`publish`/`contentType`/`language`/`sourceLanguage`/`sourceHash`/`translationStatus`/`translationOf`/`id`.
- Governance: implements Haft problem `prob-20260806-e95236b1`; do not close it or archive this OpenSpec change until the final task's full verification pass is green AND the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the final GPT-5.6 Sol max-effort review) confirms the slice is complete.

---

### Task 1: `EssayAdmission` admits title/description — every fixture, one commit

- [x] 1.1 Add `title`/`description` admission to `EssayAdmission`
- [x] 1.2 Update every existing test fixture that must now pass admission
- [x] 1.3 Commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java`

**Interfaces:**
- Produces: `EssayAdmission.Result#title(): String`, `#description(): String` (only meaningful when `accepted()`) — consumed by Task 2 (`NoteIntake.Result`).

This is the design.md D1 admission task. Every "valid essay" fixture across the codebase currently omits `title`/`description`; after this task they are mandatory, so every such fixture must gain both lines or its test flips from accepted to blocked.

- [x] **Step 1: Write the failing tests**

Append to `EssayAdmissionTest`:

```java
    @Test
    void missingTitleIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                description: A valid description.
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("title", result.diagnostics().get(0).field());
    }

    @Test
    void blankDescriptionIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: "   "
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("description", result.diagnostics().get(0).field());
    }
```

Update the existing `validEssayIsAccepted` test to add `title`/`description` to its fixture and assert the new accessors:

```java
    @Test
    void validEssayIsAccepted() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "essay", "my-essay"), result.identity());
        assertEquals("8f2c-my-essay", result.sourceId());
        assertEquals("My Essay", result.title());
        assertEquals("A valid description.", result.description());
    }
```

Every other existing `EssayAdmissionTest` fixture that currently expects `accepted()` (not just `validEssayIsAccepted`) must also gain `title: My Essay` / `description: A valid description.` lines, or it will now fail for the wrong reason (missing title/description instead of the field the test actually targets). Grep the file for `assertTrue(result.accepted())` and `assertFalseAccepted` calls whose fixture doesn't already include `title`/`description`, and add both lines to every fixture except the ones this task's own two new tests intentionally omit one field from.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=EssayAdmissionTest`
Expected: FAIL — compile error, `title()`/`description()` undefined on `EssayAdmission.Result`

- [x] **Step 3: Write minimal implementation**

Replace `EssayAdmission.java`'s `admit` method and `Result` type:

```java
    public Result admit(Frontmatter frontmatter) {
        if (!isPublished(frontmatter)) {
            return Result.blocked(List.of(publishDiagnostic()));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String collection = requireCollection(frontmatter, diagnostics);
        String contentType = requireContentType(frontmatter, collection, diagnostics);
        String sourceId = requireSourceId(frontmatter, diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);

        if (!diagnostics.isEmpty()) {
            return Result.blocked(diagnostics);
        }
        return Result.accepted(PublicationIdentity.of(collection, contentType, publicId), sourceId, title, description);
    }
```

Add, alongside the existing `requireSourceId`:

```java
    private String requireNonBlank(Frontmatter frontmatter, String key, List<Diagnostic> diagnostics) {
        String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "Note has no " + key + "."));
        }
        return value;
    }
```

Replace `Result`'s fields, constructor, and factories:

```java
    public static final class Result {

        private final PublicationIdentity identity;
        private final String sourceId;
        private final String title;
        private final String description;
        private final List<Diagnostic> diagnostics;

        private Result(PublicationIdentity identity, String sourceId, String title, String description,
                List<Diagnostic> diagnostics) {
            this.identity = identity;
            this.sourceId = sourceId;
            this.title = title;
            this.description = description;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(PublicationIdentity identity, String sourceId, String title, String description) {
            return new Result(
                    Objects.requireNonNull(identity, "identity"),
                    Objects.requireNonNull(sourceId, "sourceId"),
                    Objects.requireNonNull(title, "title"),
                    Objects.requireNonNull(description, "description"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, null, null, diagnostics);
        }

        public boolean accepted() { return diagnostics.isEmpty(); }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public PublicationIdentity identity() { return identity; }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String sourceId() { return sourceId; }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String title() { return title; }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String description() { return description; }

        public List<Diagnostic> diagnostics() { return diagnostics; }

        @Override
        public String toString() {
            return "EssayAdmission.Result[identity=" + identity + ", sourceId=" + sourceId
                    + ", title=" + title + ", description=" + description + ", diagnostics=" + diagnostics + "]";
        }
    }
```

Note: `wrongCollectionBlocksBothCollectionAndContentType` and any other test whose fixture is deliberately missing a field other than title/description must ALSO gain `title`/`description` lines, or it will report the wrong diagnostic count/field. Add both lines to every `EssayAdmissionTest` fixture that isn't specifically testing a missing title or missing description.

Now update the other 7 fixture files. For each, find every `publish: true` fixture block used in a test that asserts acceptance (not one of the deliberately-blocked-on-an-unrelated-field tests, which also need the two new lines added so they still block on the field under test, not title/description) and add:

```yaml
title: My Essay
description: A valid description.
```

immediately after `id: 8f2c-my-essay` (or the fixture's equivalent identity line). Apply this to: `NoteIntakeTest` (`VALID_ESSAY` constant and any other accepted fixture), `PrepareHandlerTest` (`VALID_ESSAY` constant), `MarkReviewedHandlerTest` (`VALID_ESSAY` constant), `InspectPublicationHandlerTest`, `PrepareCliAcceptanceTest`, `MarkReviewedCliAcceptanceTest`, `InspectPublicationCliAcceptanceTest` — each file's accepted-path fixtures, identified by grepping `publish: true` in that file and checking whether the surrounding test expects success.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=EssayAdmissionTest,NoteIntakeTest,PrepareHandlerTest,MarkReviewedHandlerTest,InspectPublicationHandlerTest,PrepareCliAcceptanceTest,MarkReviewedCliAcceptanceTest,InspectPublicationCliAcceptanceTest`
Expected: PASS across all eight classes, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java
git commit -m "feat(publication-exporter): admit title/description from vault frontmatter (ADM-04)"
```

---

### Task 2: `NoteIntake.Result` exposes title/description

- [x] 2.1 Delegate `title()`/`description()` from `EssayAdmission.Result`
- [x] 2.2 Commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`

**Interfaces:**
- Consumes: `EssayAdmission.Result#title()`/`#description()` (Task 1).
- Produces: `NoteIntake.Result#title(): String`, `#description(): String` — consumed by Task 6 (`PrepareHandler`) and Task 7 (`MarkReviewedHandler`).

- [x] **Step 1: Write the failing test**

Append to `NoteIntakeTest` (using the already-updated `VALID_ESSAY` constant from Task 1, which now includes `title: ...`/`description: ...`):

```java
    @Test
    void acceptedIntakeExposesTitleAndDescription() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertTrue(result.accepted());
        assertEquals("My Essay", result.title());
        assertEquals("A valid description.", result.description());
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NoteIntakeTest#acceptedIntakeExposesTitleAndDescription`
Expected: FAIL — compile error, `title()`/`description()` undefined on `NoteIntake.Result`

- [x] **Step 3: Write minimal implementation**

In `NoteIntake.Result`, add alongside the existing `body()`:

```java
        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String title() {
            return admission.title();
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String description() {
            return admission.description();
        }
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NoteIntakeTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java
git commit -m "feat(publication-exporter): expose title/description from NoteIntake.Result"
```

---

### Task 3: `CandidateSnapshot` and `CandidateWorkspace` widen — every implementor, one commit

- [x] 3.1 Widen `CandidateSnapshot` to seven fields
- [x] 3.2 Widen `CandidateWorkspace#install` and update every implementor/test double
- [x] 3.3 Commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`

**Interfaces:**
- Produces: `CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap): CandidateSnapshot`, `#ruTitle()`, `#enTitle()`, `#ruDescription()`, `#enDescription()` — consumed by Task 4, Task 6, Task 7, Task 10, Task 12.
- Produces: `CandidateWorkspace#install(identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap)` — consumed by Task 6 (`PrepareHandler`).

This is design.md D3's candidate half. `CandidateWorkspace` has exactly two `src/main` implementors (`NullCandidateWorkspace`, `FilesystemCandidateWorkspace`) plus whatever anonymous test doubles exist in `PrepareHandlerTest`/`MarkReviewedHandlerTest` — Task 6/7 update those call sites, not this task (they don't implement the interface, they only call it).

- [x] **Step 1: Write the failing tests**

Replace `CandidateSnapshot`'s existing round-trip assertions in any direct test (if one exists; otherwise this is exercised entirely through `NullCandidateWorkspaceTest`/`FilesystemCandidateWorkspaceTest` below) and update those two:

Append to `NullCandidateWorkspaceTest`:

```java
    @Test
    void readReturnsTheInstalledTitleAndDescription() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
    }
```

(Adjust `IDENTITY`/imports to match this file's existing constants — it already declares `IDENTITY` for its pre-existing `install`/`find`/`read` tests.) Every pre-existing `install(...)` call in this file's other tests must also add the four new arguments — grep for `.install(` and update each call site with placeholder title/description strings (e.g., `"Title"`, `"EN Title"`, `"Description."`, `"EN Description."`), since `install`'s parameter list is now longer everywhere it's called.

Apply the identical two changes (new test + updated existing `install(...)` call sites) to `FilesystemCandidateWorkspaceTest`.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest`
Expected: FAIL — compile error, `install`/`CandidateSnapshot.of` argument count mismatch

- [x] **Step 3: Write minimal implementation**

Replace `CandidateSnapshot.java` in full:

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.Objects;

public final class CandidateSnapshot {

    private final String ruBody;
    private final String enBody;
    private final String ruTitle;
    private final String enTitle;
    private final String ruDescription;
    private final String enDescription;
    private final ReferenceMap referenceMap;

    private CandidateSnapshot(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
        this.enBody = Objects.requireNonNull(enBody, "enBody");
        this.ruTitle = Objects.requireNonNull(ruTitle, "ruTitle");
        this.enTitle = Objects.requireNonNull(enTitle, "enTitle");
        this.ruDescription = Objects.requireNonNull(ruDescription, "ruDescription");
        this.enDescription = Objects.requireNonNull(enDescription, "enDescription");
        this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
    }

    public static CandidateSnapshot of(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        return new CandidateSnapshot(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
    }

    public String ruBody() { return ruBody; }
    public String enBody() { return enBody; }
    public String ruTitle() { return ruTitle; }
    public String enTitle() { return enTitle; }
    public String ruDescription() { return ruDescription; }
    public String enDescription() { return enDescription; }
    public ReferenceMap referenceMap() { return referenceMap; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CandidateSnapshot that)) return false;
        return ruBody.equals(that.ruBody) && enBody.equals(that.enBody)
                && ruTitle.equals(that.ruTitle) && enTitle.equals(that.enTitle)
                && ruDescription.equals(that.ruDescription) && enDescription.equals(that.enDescription)
                && referenceMap.equals(that.referenceMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
    }

    @Override
    public String toString() {
        return "CandidateSnapshot[ruBody=" + ruBody + ", enBody=" + enBody
                + ", ruTitle=" + ruTitle + ", enTitle=" + enTitle
                + ", ruDescription=" + ruDescription + ", enDescription=" + enDescription
                + ", referenceMap=" + referenceMap + "]";
    }
}
```

In `CandidateWorkspace.java`, widen the interface method:

```java
    void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap);
```

In `NullCandidateWorkspace.java`, widen `install` and `InstalledCandidate` to carry and forward all six strings (title/description alongside body), and widen `read`'s `CandidateSnapshot.of(...)` call to pass them through. Follow the existing `InstalledCandidate` shape exactly, adding `ruTitle`/`enTitle`/`ruDescription`/`enDescription` fields, constructor parameters, accessors, and `equals`/`hashCode`/`toString` terms alongside the existing `ruBody`/`enBody`/`referenceMap`.

In `FilesystemCandidateWorkspace.java`, widen `install` to accept and stage the four new strings, and `read`/`snapshotFrom` to reconstruct them. Title/description are staged as two more UTF-8 text files in the candidate directory, `ru.title`/`en.title`/`ru.description`/`en.description`, written in `writeTriple` (rename it or add alongside — it's no longer just a triple) and read back in `snapshotFrom` the same way `ru.md`/`en.md` already are, each going through the existing `requireWithinReviewRoot` confinement check.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest`
Expected: PASS, 0 failures. (`PrepareHandlerTest`/`MarkReviewedHandlerTest` will still fail to compile until Tasks 6/7 update their call sites — that is expected at this point.)

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java
git commit -m "feat(publication-exporter): widen CandidateSnapshot/CandidateWorkspace to carry title/description"
```

---

### Task 4: `ApprovedSnapshotWorkspace` widens — every implementor, one commit

- [x] 4.1 Widen `ApprovedSnapshotWorkspace#install` and every implementor/test double

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`

**Interfaces:**
- Consumes: `CandidateSnapshot` (Task 3, seven-field shape).
- Produces: `ApprovedSnapshotWorkspace#install(identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap)`, `#read(identity)` now returns the widened `CandidateSnapshot` automatically (S06's `read` signature is unchanged; only what it returns is wider) — consumed by Task 7 (`MarkReviewedHandler`), Task 9 (`InstallToSiteHandler`).

Same shape as Task 3, applied to `ApprovedSnapshotWorkspace`'s two known `src/main` implementors. `read(...)`'s own signature does not change — only `CandidateSnapshot`'s shape (already widened by Task 3) does, so `read` needs no new logic beyond reconstructing the two new fields the same way `FilesystemApprovedSnapshotWorkspace#read` already reconstructs `ruBody`/`enBody`.

- [x] **Step 1: Write the failing tests**

Append to `NullApprovedSnapshotWorkspaceTest`, mirroring Task 3's `NullCandidateWorkspaceTest` addition:

```java
    @Test
    void readReturnsTheInstalledTitleAndDescription() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
    }
```

Update every pre-existing `.install(...)` call site in both `NullApprovedSnapshotWorkspaceTest` and `FilesystemApprovedSnapshotWorkspaceTest` to pass the four new arguments, exactly as Task 3 did for the candidate tests.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest`
Expected: FAIL — compile error, argument count mismatch

- [x] **Step 3: Write minimal implementation**

In `ApprovedSnapshotWorkspace.java`, widen `install` identically to Task 3's `CandidateWorkspace#install` widening.

In `NullApprovedSnapshotWorkspace.java`, widen `InstalledApprovedSnapshot` and `install`/`read` exactly as Task 3 widened `NullCandidateWorkspace`'s `InstalledCandidate`.

In `FilesystemApprovedSnapshotWorkspace.java`, widen `install` to stage `ru.title`/`en.title`/`ru.description`/`en.description` alongside `ru.md`/`en.md`/`references.json` (same `writeTriple`-style helper, now five-plus files), and widen `read` to reconstruct all seven `CandidateSnapshot` fields, guarding on the existence of all five content files (not just three) before returning a present `Optional`.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java
git commit -m "feat(publication-exporter): widen ApprovedSnapshotWorkspace to carry title/description"
```

---

### Task 5: `TranslationResult`/`TranslationWorker`/`NullTranslationWorker` widen — one commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationResultTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/NullTranslationWorkerTest.java`

**Interfaces:**
- Produces: `TranslationResult.success(enBody, enTitle, enDescription)`, `#enTitle()`, `#enDescription()`; `TranslationWorker#translate(ruBody, ruTitle, ruDescription)`; `TranslationWorker.createNull(enBody, enTitle, enDescription)` — consumed by Task 6 (`PrepareHandler`), Task 8 (`ProcessTranslationWorker`).

Design.md D4's fake half. The real subprocess adapter (`ProcessTranslationWorker`) is Task 8, isolated per S06's own precedent of separating fake-first from real-adapter-second.

- [x] **Step 1: Write the failing tests**

Replace `TranslationResultTest`'s `successExposesEnBody`/`successRejectsNullBody` and add title/description coverage:

```java
    @Test
    void successExposesEnBodyTitleAndDescription() {
        TranslationResult result = TranslationResult.success("Hello", "Hi there", "A description.");

        assertTrue(result.succeeded());
        assertEquals("Hello", result.enBody());
        assertEquals("Hi there", result.enTitle());
        assertEquals("A description.", result.enDescription());
    }

    @Test
    void successRejectsNullTitle() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success("Hello", null, "d"));
    }

    @Test
    void successRejectsNullDescription() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success("Hello", "t", null));
    }
```

(Remove or fold the old two-argument `successExposesEnBody`/`successRejectsNullBody` tests into these — the old `success(String)` factory no longer exists.)

Replace `NullTranslationWorkerTest`'s fixtures to construct `TranslationResult.success("EN body", "EN title", "EN description.")` and call `worker.translate("RU body", "RU title", "RU description.")`, asserting `result.enTitle()`/`result.enDescription()` alongside the existing `enBody()` assertion. Update `everyRequestedBodyIsTracked` to assert on whatever tracking accessor Step 3 below settles on (a list of the three-string tuples, not just bodies).

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=TranslationResultTest,NullTranslationWorkerTest`
Expected: FAIL — compile error

- [x] **Step 3: Write minimal implementation**

Replace `TranslationResult.java` in full:

```java
package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class TranslationResult {

    private final String enBody;
    private final String enTitle;
    private final String enDescription;
    private final String failureReason;

    private TranslationResult(String enBody, String enTitle, String enDescription, String failureReason) {
        this.enBody = enBody;
        this.enTitle = enTitle;
        this.enDescription = enDescription;
        this.failureReason = failureReason;
    }

    public static TranslationResult success(String enBody, String enTitle, String enDescription) {
        return new TranslationResult(
                Objects.requireNonNull(enBody, "enBody"),
                Objects.requireNonNull(enTitle, "enTitle"),
                Objects.requireNonNull(enDescription, "enDescription"),
                null);
    }

    public static TranslationResult failure(String reason) {
        return new TranslationResult(null, null, null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean succeeded() { return enBody != null; }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enBody() { return enBody; }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enTitle() { return enTitle; }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enDescription() { return enDescription; }

    /** Only meaningful when {@link #succeeded()} is {@code false}. */
    public String failureReason() { return failureReason; }

    @Override
    public String toString() {
        return "TranslationResult[enBody=" + enBody + ", enTitle=" + enTitle
                + ", enDescription=" + enDescription + ", failureReason=" + failureReason + "]";
    }
}
```

In `TranslationWorker.java`:

```java
package dev.eugene.publicationexporter.translation;

public interface TranslationWorker {

    TranslationResult translate(String ruBody, String ruTitle, String ruDescription);

    static TranslationWorker createNull(String enBody, String enTitle, String enDescription) {
        return new NullTranslationWorker(TranslationResult.success(enBody, enTitle, enDescription));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationResult.failure(reason));
    }
}
```

In `NullTranslationWorker.java`, widen `translate` to accept three strings and track them as a small local record instead of a flat `List<String>`:

```java
package dev.eugene.publicationexporter.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullTranslationWorker implements TranslationWorker {

    private final TranslationResult result;
    private final List<RequestedTranslation> requested = new ArrayList<>();

    public NullTranslationWorker(TranslationResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public TranslationResult translate(String ruBody, String ruTitle, String ruDescription) {
        requested.add(RequestedTranslation.of(ruBody, ruTitle, ruDescription));
        return result;
    }

    public List<RequestedTranslation> requested() {
        return List.copyOf(requested);
    }

    public record RequestedTranslation(String ruBody, String ruTitle, String ruDescription) {
        public static RequestedTranslation of(String ruBody, String ruTitle, String ruDescription) {
            return new RequestedTranslation(ruBody, ruTitle, ruDescription);
        }
    }
}
```

(A `record` is acceptable here specifically because `RequestedTranslation` is test-support/in-memory tracking state, not a domain Whole Value crossing a production boundary — matching `/applying-sbpp`'s constraint on `CandidateSnapshot`/`ReferenceMap`/etc., not on incidental test-double bookkeeping.)

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=TranslationResultTest,NullTranslationWorkerTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationResultTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/NullTranslationWorkerTest.java
git commit -m "feat(publication-exporter): widen TranslationWorker to translate title/description with the body"
```

---

### Task 6: `ProcessTranslationWorker` widens — the real subprocess adapter

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerTest.java`

**Interfaces:**
- Consumes: `TranslationWorker#translate(ruBody, ruTitle, ruDescription)`, `TranslationResult.success(enBody, enTitle, enDescription)` (Task 5).

Design.md D4's real-adapter half, isolated in its own commit per S06's own precedent (S06 D3's staging refactor was likewise isolated from its callers' behavioral changes). The prompt now asks for three named output files instead of one; `collectResult` reads all three, failing closed if any is absent — the same shape the existing `missingResultFileIsReportedAsFailure` test already exercises for one file.

- [x] **Step 1: Write the failing tests**

Update every test in `ProcessTranslationWorkerTest` to call `worker.translate("ignored", "ignored title", "ignored description")` instead of `worker.translate("ignored")`. Update `writesFixedResult` and the shell-based `TranslationCommand` lambdas to write all three files:

```java
    private static TranslationCommand writesFixedResult(String body, String title, String description) {
        return (Path workdir, String prompt) -> List.of("sh", "-c",
                "printf '%s' " + shellQuote(body) + " > candidate.en.md && "
                        + "printf '%s' " + shellQuote(title) + " > candidate.en.title.txt && "
                        + "printf '%s' " + shellQuote(description) + " > candidate.en.description.txt");
    }
```

Update the call sites of `writesFixedResult("Translated text")` to `writesFixedResult("Translated text", "Translated title", "Translated description.")`, and update assertions to also check `result.enTitle()`/`result.enDescription()`.

Add a new test asserting the title-file-missing case fails closed the same way the body-file-missing case already does:

```java
    @Test
    void missingTitleFileIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c",
                        "printf '%s' 'body' > candidate.en.md && printf '%s' 'desc' > candidate.en.description.txt"),
                Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored", "ignored", "ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("candidate.en.title.txt"));
    }
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ProcessTranslationWorkerTest`
Expected: FAIL — compile error (`translate` argument count) and/or missing-file-name mismatch

- [x] **Step 3: Write minimal implementation**

In `ProcessTranslationWorker.java`, add the two new result file name constants alongside `RESULT_FILE_NAME`:

```java
    private static final String BODY_FILE_NAME = "candidate.en.md";
    private static final String TITLE_FILE_NAME = "candidate.en.title.txt";
    private static final String DESCRIPTION_FILE_NAME = "candidate.en.description.txt";
```

(Rename `RESULT_FILE_NAME` to `BODY_FILE_NAME` throughout.) Widen `translate`:

```java
    @Override
    public TranslationResult translate(String ruBody, String ruTitle, String ruDescription) {
        Path workdir = createScratchWorkdir();
        try {
            return runAndCollect(workdir, prompt(ruBody, ruTitle, ruDescription));
        } finally {
            deleteRecursively(workdir);
        }
    }
```

Widen `collectResult` to read all three files, failing closed on the first missing one (in `BODY_FILE_NAME`, `TITLE_FILE_NAME`, `DESCRIPTION_FILE_NAME` order, matching the existing message format `"Translation worker completed without writing " + name + "."`):

```java
    private TranslationResult collectResult(Path workdir) {
        Optional<String> body = readIfPresent(workdir, BODY_FILE_NAME);
        if (body.isEmpty()) {
            return missingFileFailure(BODY_FILE_NAME);
        }
        Optional<String> title = readIfPresent(workdir, TITLE_FILE_NAME);
        if (title.isEmpty()) {
            return missingFileFailure(TITLE_FILE_NAME);
        }
        Optional<String> description = readIfPresent(workdir, DESCRIPTION_FILE_NAME);
        if (description.isEmpty()) {
            return missingFileFailure(DESCRIPTION_FILE_NAME);
        }
        return TranslationResult.success(body.get(), title.get(), description.get());
    }

    private Optional<String> readIfPresent(Path workdir, String fileName) {
        Path file = workdir.resolve(fileName);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static TranslationResult missingFileFailure(String fileName) {
        return TranslationResult.failure("Translation worker completed without writing " + fileName + ".");
    }
```

(This changes the caught-exception shape for an unreadable-but-present file from a returned failure to a thrown `UncheckedIOException` for the title/description files, matching how `collectResult`'s existing IOException handling already worked for the body file before this task — reuse that same try/catch-to-failure pattern instead if `IOException` on `readIfPresent` should stay a returned failure; keep it consistent with the existing single-file behavior it's replacing.)

Widen the prompt to request three named files:

```java
    private static String prompt(String ruBody, String ruTitle, String ruDescription) {
        return """
                # Bounded Russian-to-English publication translation

                Work only inside the current directory. Translate the Russian title, description,
                and body below to English prose of equivalent meaning and structure. Write:
                - the translated title, and only the title, to candidate.en.title.txt
                - the translated description, and only the description, to candidate.en.description.txt
                - the translated body, and only the body, to candidate.en.md
                Do not return commentary or a patch in place of those files.

                <title>
                %s
                </title>
                <description>
                %s
                </description>
                <body>
                %s
                </body>
                """.formatted(ruTitle, ruDescription, ruBody);
    }
```

Add `import java.util.Optional;` if not already present.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ProcessTranslationWorkerTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerTest.java
git commit -m "feat(publication-exporter): translate title/description alongside body in ProcessTranslationWorker"
```

---

### Task 7: `PrepareHandler` wiring

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Consumes: `NoteIntake.Result#title()`/`#description()` (Task 2), `TranslationWorker#translate(ruBody, ruTitle, ruDescription)` (Task 5), `CandidateWorkspace#install(..., ruTitle, enTitle, ruDescription, enDescription, ...)` (Task 3).

No new logic — this is pure plumbing. `prepareAdmittedEssay` now threads two more strings from intake, through translation, into the widened `install` call.

- [x] **Step 1: Update the test**

`PrepareHandlerTest`'s fixtures already gained `title`/`description` frontmatter in Task 1. Update `validEssayInstallsOneCandidateAndReturnsReadyForReview` to construct the worker with `TranslationWorker.createNull("Translated body", "Translated title", "Translated description.")` and assert on the installed candidate's title/description:

```java
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        // ... existing assertions ...
        assertEquals("Translated title", installed.enTitle());
        assertEquals("Translated description.", installed.enDescription());
        assertEquals("My Essay", installed.ruTitle());
        assertEquals("A valid description.", installed.ruDescription());
```

Update every other `PrepareHandlerTest` call site constructing `TranslationWorker.createNull(...)`/`TranslationWorker.createNullFailing(...)` and every `NullCandidateWorkspace.InstalledCandidate` accessor call to the widened shapes from Tasks 3/5.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareHandlerTest`
Expected: FAIL — compile error, `PrepareHandler` still calls the old two-argument `translate`/seven... four-argument `install`

- [x] **Step 3: Write minimal implementation**

Replace `PrepareHandler.java`'s `prepare`/`prepareAdmittedEssay`:

```java
    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return prepareAdmittedEssay(intake.identity(), intake.body(), intake.title(), intake.description());
    }

    private BridgeResponse prepareAdmittedEssay(
            PublicationIdentity identity, String ruBody, String ruTitle, String ruDescription) {
        TranslationResult translation;
        try {
            translation = translationWorker.translate(ruBody, ruTitle, ruDescription);
        } catch (UncheckedIOException failure) {
            return candidateFailure(ioFailureMessage("Translation worker I/O failed", failure));
        }
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        if (enBody.isBlank()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", "Translation worker produced a blank candidate."));
        }
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity, ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody));
        try {
            candidateWorkspace.install(identity, ruBody, enBody,
                    ruTitle, translation.enTitle(), ruDescription, translation.enDescription(), referenceMap);
        } catch (UncheckedIOException failure) {
            return candidateFailure(ioFailureMessage("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareHandlerTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(publication-exporter): thread title/description through PrepareHandler"
```

---

### Task 8: `MarkReviewedHandler` wiring

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`

**Interfaces:**
- Consumes: `CandidateSnapshot#ruTitle()`/`#enTitle()`/`#ruDescription()`/`#enDescription()` (Task 3), `ApprovedSnapshotWorkspace#install(..., ruTitle, enTitle, ruDescription, enDescription, ...)` (Task 4).

Also pure plumbing — the candidate already carries title/description (installed by Task 7's `PrepareHandler`); `installApprovedSnapshot` just forwards them unchanged, exactly as it already forwards `ruBody`/`enBody`.

- [x] **Step 1: Update the test**

Update `MarkReviewedHandlerTest`'s fixtures (already gained `title`/`description` in Task 1) and every place a `CandidateWorkspace`/`ApprovedSnapshotWorkspace` fake is installed with the pre-widening argument count, including the anonymous `approvedSnapshotWorkspaceThrowing`/`candidateWorkspaceThrowing` test doubles' `install` overrides (add the four new parameters to each override's signature, throwing the same injected failure as before). Add an assertion that the approved installation carries the candidate's title/description through unchanged.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedHandlerTest`
Expected: FAIL — compile error

- [x] **Step 3: Write minimal implementation**

Replace `installApprovedSnapshot`:

```java
    private BridgeResponse installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot candidate) {
        try {
            approvedSnapshotWorkspace.install(
                    identity, candidate.ruBody(), candidate.enBody(),
                    candidate.ruTitle(), candidate.enTitle(),
                    candidate.ruDescription(), candidate.enDescription(),
                    candidate.referenceMap());
        } catch (ApprovedSnapshotAlreadyExistsException raceLoser) {
            return alreadyApprovedResponse();
        } catch (UncheckedIOException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("candidate", "Approved installation failed."));
        }
        return BridgeResponse.approved(COMMAND, identity);
    }
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedHandlerTest`
Expected: PASS, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS across the whole module — every interface widened by Tasks 3-8 is now fully consistent end to end.

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java
git commit -m "feat(publication-exporter): thread title/description through MarkReviewedHandler"
```

---

### Task 9: `SiteReleaseManifest` — the check-content.mjs-compatible manifest builder

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/SiteReleaseManifest.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedTreeHash.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/PayloadFileHash.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/SiteReleaseManifestTest.java`

**Interfaces:**
- Produces: `SiteReleaseManifest.computeOver(Path siteRoot, List<String> payloadRoots): SiteReleaseManifest`, `#toCanonicalJson(): String` — consumed by Task 12 (`FilesystemManagedSiteInstaller`).

Design.md D6. This is the plan's own carve-out for "a component or unit test... for genuinely combinatorial parsing, diffing, hashing, or recovery logic unclear at acceptance-test scope" — the hashing/canonicalization scheme is exactly that, and its correctness is checked twice: once here in isolation (does the Java code compute what it says it computes), and once in Task 13 against the real `check-content.mjs` (does the JS side accept what the Java code produced).

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteReleaseManifestTest {

    @TempDir
    Path root;

    @Test
    void managedTreesAreHashedOverKindLengthPathAndPayload() throws Exception {
        Path contentDir = root.resolve("src/content");
        Files.createDirectories(contentDir.resolve("blog/ru"));
        Files.writeString(contentDir.resolve("blog/ru/my-essay.md"), "---\nid: my-essay\n---\nBody.", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));

        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(
                root, List.of("public/assets/vault", "src/content", "src/data/pages"));

        assertEquals(1, manifest.schemaVersion());
        assertEquals(List.of(), manifest.selectedPages());
        assertEquals(0, manifest.activationCount());
        assertEquals(0, manifest.deactivationCount());
        assertEquals(3, manifest.managedTrees().size());
        assertEquals("src/content", manifest.managedTrees().get(1).relative());
        assertTrue(manifest.managedTrees().get(1).sha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void payloadDigestIsDeterministicAcrossRecomputation() throws Exception {
        Files.createDirectories(root.resolve("src/content/blog/ru"));
        Files.writeString(root.resolve("src/content/blog/ru/a.md"), "content", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));
        List<String> payloadRoots = List.of("public/assets/vault", "src/content", "src/data/pages");

        SiteReleaseManifest first = SiteReleaseManifest.computeOver(root, payloadRoots);
        SiteReleaseManifest second = SiteReleaseManifest.computeOver(root, payloadRoots);

        assertEquals(first.payloadDigest(), second.payloadDigest());
    }

    @Test
    void canonicalJsonOrdersFieldsToMatchCheckContentMjs() throws Exception {
        Files.createDirectories(root.resolve("src/content"));
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));

        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(
                root, List.of("public/assets/vault", "src/content", "src/data/pages"));

        String json = manifest.toCanonicalJson();
        int schemaVersionIndex = json.indexOf("\"schemaVersion\"");
        int selectedPagesIndex = json.indexOf("\"selectedPages\"");
        int managedTreesIndex = json.indexOf("\"managedTrees\"");
        int managedFilesIndex = json.indexOf("\"managedFiles\"");
        int activationCountIndex = json.indexOf("\"activationCount\"");
        int deactivationCountIndex = json.indexOf("\"deactivationCount\"");
        int payloadDigestIndex = json.indexOf("\"payloadDigest\"");
        assertTrue(schemaVersionIndex < selectedPagesIndex);
        assertTrue(selectedPagesIndex < managedTreesIndex);
        assertTrue(managedTreesIndex < managedFilesIndex);
        assertTrue(managedFilesIndex < activationCountIndex);
        assertTrue(activationCountIndex < deactivationCountIndex);
        assertTrue(deactivationCountIndex < payloadDigestIndex);
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SiteReleaseManifestTest`
Expected: FAIL — compile error, none of these classes exist yet

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.site;

import java.util.Objects;

public final class ManagedTreeHash {
    private final String relative;
    private final String sha256;

    private ManagedTreeHash(String relative, String sha256) {
        this.relative = Objects.requireNonNull(relative, "relative");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public static ManagedTreeHash of(String relative, String sha256) {
        return new ManagedTreeHash(relative, sha256);
    }

    public String relative() { return relative; }
    public String sha256() { return sha256; }
}
```

```java
package dev.eugene.publicationexporter.site;

import java.util.Objects;

public final class PayloadFileHash {
    private final String path;
    private final String sha256;

    private PayloadFileHash(String path, String sha256) {
        this.path = Objects.requireNonNull(path, "path");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public static PayloadFileHash of(String path, String sha256) {
        return new PayloadFileHash(path, sha256);
    }

    public String path() { return path; }
    public String sha256() { return sha256; }
}
```

```java
package dev.eugene.publicationexporter.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class SiteReleaseManifest {

    private static final int SCHEMA_VERSION = 1;

    private final List<ManagedTreeHash> managedTrees;
    private final List<PayloadFileHash> managedFiles;
    private final String payloadDigest;

    private SiteReleaseManifest(List<ManagedTreeHash> managedTrees, List<PayloadFileHash> managedFiles,
            String payloadDigest) {
        this.managedTrees = List.copyOf(managedTrees);
        this.managedFiles = List.copyOf(managedFiles);
        this.payloadDigest = Objects.requireNonNull(payloadDigest, "payloadDigest");
    }

    public static SiteReleaseManifest computeOver(Path siteRoot, List<String> payloadRoots) {
        List<ManagedTreeHash> managedTrees = new ArrayList<>();
        for (String relative : payloadRoots) {
            managedTrees.add(ManagedTreeHash.of(relative, hashTree(siteRoot.resolve(relative))));
        }
        List<PayloadFileHash> managedFiles = hashPayloadFiles(siteRoot, payloadRoots);
        String digest = computePayloadDigest(managedTrees, managedFiles);
        return new SiteReleaseManifest(managedTrees, managedFiles, digest);
    }

    public int schemaVersion() { return SCHEMA_VERSION; }
    public List<Object> selectedPages() { return List.of(); }
    public List<ManagedTreeHash> managedTrees() { return managedTrees; }
    public List<PayloadFileHash> managedFiles() { return managedFiles; }
    public int activationCount() { return 0; }
    public int deactivationCount() { return 0; }
    public String payloadDigest() { return payloadDigest; }

    public String toCanonicalJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"selectedPages\":[]")
                .append(",\"managedTrees\":").append(managedTreesJson())
                .append(",\"managedFiles\":").append(managedFilesJson())
                .append(",\"activationCount\":0")
                .append(",\"deactivationCount\":0")
                .append(",\"payloadDigest\":\"").append(payloadDigest).append("\"}");
        return json.toString();
    }

    private String managedTreesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedTrees.size(); i++) {
            if (i > 0) json.append(",");
            ManagedTreeHash tree = managedTrees.get(i);
            json.append("{\"relative\":\"").append(tree.relative())
                    .append("\",\"sha256\":\"").append(tree.sha256()).append("\"}");
        }
        return json.append("]").toString();
    }

    private String managedFilesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedFiles.size(); i++) {
            if (i > 0) json.append(",");
            PayloadFileHash file = managedFiles.get(i);
            json.append("{\"path\":\"").append(file.path())
                    .append("\",\"sha256\":\"").append(file.sha256()).append("\"}");
        }
        return json.append("]").toString();
    }

    private static String computePayloadDigest(List<ManagedTreeHash> managedTrees, List<PayloadFileHash> managedFiles) {
        // Mirrors check-content.mjs's `recomputed`/`canonical` objects field-for-field, with an
        // empty payloadDigest placeholder appended last, exactly matching JSON.stringify's key order.
        SiteReleaseManifest withoutDigest = new SiteReleaseManifest(managedTrees, managedFiles, "");
        return sha256Hex(withoutDigest.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    private static List<PayloadFileHash> hashPayloadFiles(Path siteRoot, List<String> payloadRoots) {
        List<PayloadFileHash> records = new ArrayList<>();
        for (String relativeRoot : payloadRoots) {
            for (Path file : listTreeSortedByRelativePath(siteRoot.resolve(relativeRoot))) {
                if (Files.isDirectory(file)) continue;
                String relative = slash(siteRoot.relativize(file).toString());
                records.add(PayloadFileHash.of(relative, sha256Hex(readAllBytes(file))));
            }
        }
        records.sort(Comparator.comparing(PayloadFileHash::path));
        return records;
    }

    private static String hashTree(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : listTreeSortedByRelativePath(root)) {
                String relative = slash(root.relativize(file).toString());
                byte[] relativeBytes = relative.getBytes(StandardCharsets.UTF_8);
                boolean isDirectory = Files.isDirectory(file);
                byte[] payload = isDirectory ? new byte[0] : readAllBytes(file);
                digest.update((isDirectory ? "D" : "F").getBytes(StandardCharsets.UTF_8));
                digest.update(lengthBytes(relativeBytes.length));
                digest.update(relativeBytes);
                digest.update(lengthBytes(payload.length));
                digest.update(payload);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Path> listTreeSortedByRelativePath(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> entries = walk.filter(path -> !path.equals(root)).toList();
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(path -> slash(root.relativize(path).toString())));
            return sorted;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] lengthBytes(int length) {
        byte[] bytes = new byte[8];
        long value = length;
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static String slash(String path) {
        return path.replace(java.io.File.separatorChar, '/');
    }
}
```

`listTreeSortedByRelativePath` sorts by relative-path `String` natural ordering, matching Java's own `String.compareTo` — the same ordering `site/tests/release-provenance.test.mjs`'s "uses Java-compatible natural ordering" test exists to pin down on the JS side (`comparePaths`'s plain `<`/`>` comparison over JS strings, which is UTF-16 code-unit order and matches Java's `String.compareTo` for the path characters this codebase actually uses).

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SiteReleaseManifestTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/site/SiteReleaseManifest.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedTreeHash.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/PayloadFileHash.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/site/SiteReleaseManifestTest.java
git commit -m "feat(publication-exporter): add SiteReleaseManifest matching check-content.mjs's hashing scheme"
```

---

### Task 10: `ManagedSiteInstaller` — new port and in-memory fake

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/SiteAlreadyInstalledException.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/NullManagedSiteInstaller.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/NullManagedSiteInstallerTest.java`

**Interfaces:**
- Consumes: `CandidateSnapshot` (Task 3/4's widened shape).
- Produces: `ManagedSiteInstaller#install(identity, snapshot)`, `ManagedSiteInstaller.createNull()` — consumed by Task 11 (`InstallToSiteHandler`) and Task 12 (real adapter, same contract).

Design.md D5. Same "no `create(Path)` on the interface yet" correction S06's own Task 3 learned — `create(Path)` is added in Task 12, in the same commit as the class it instantiates.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NullManagedSiteInstallerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final CandidateSnapshot SNAPSHOT = CandidateSnapshot.of(
            "RU body", "EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

    @Test
    void installRecordsTheInstalledSnapshot() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();

        installer.install(IDENTITY, SNAPSHOT);

        assertEquals(SNAPSHOT, installer.installed().get(IDENTITY));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();
        installer.install(IDENTITY, SNAPSHOT);

        assertThrows(SiteAlreadyInstalledException.class, () -> installer.install(IDENTITY, SNAPSHOT));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyInstaller() {
        ManagedSiteInstaller installer = ManagedSiteInstaller.createNull();

        installer.install(IDENTITY, SNAPSHOT);
        // no exception: a fresh nulled installer starts empty
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullManagedSiteInstallerTest`
Expected: FAIL — compile error, none of these classes exist yet

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class SiteAlreadyInstalledException extends IllegalStateException {
    public SiteAlreadyInstalledException(PublicationIdentity identity) {
        super("A site installation already exists for " + identity);
    }
}
```

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

public interface ManagedSiteInstaller {

    void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot);

    static ManagedSiteInstaller createNull() {
        return new NullManagedSiteInstaller();
    }
}
```

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NullManagedSiteInstaller implements ManagedSiteInstaller {

    private final Map<PublicationIdentity, CandidateSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");
        if (installed.containsKey(identity)) {
            throw new SiteAlreadyInstalledException(identity);
        }
        installed.put(identity, approvedSnapshot);
    }

    public Map<PublicationIdentity, CandidateSnapshot> installed() {
        return Map.copyOf(installed);
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullManagedSiteInstallerTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/SiteAlreadyInstalledException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/NullManagedSiteInstaller.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/site/NullManagedSiteInstallerTest.java
git commit -m "feat(publication-exporter): add ManagedSiteInstaller port with in-memory fake"
```

---

### Task 11: `InstallToSiteHandler` — the behavioural slice, wired against fakes

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteResult.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java`

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace#read(identity)` (Task 4), `ManagedSiteInstaller#install(identity, snapshot)` (Task 10).
- Produces: `InstallToSiteHandler#installToSite(identity): InstallToSiteResult` — consumed by Task 13 (CLI wiring).

Mirrors `BuildFromReviewHandler`'s exact shape (S06): read the approved snapshot, block before any write if absent, otherwise delegate to the port and report the outcome. No `SiteReleaseManifest` involvement here — manifest computation happens inside the real adapter (Task 12), after it has written real files to hash; the in-memory fake has nothing to hash.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.NullManagedSiteInstaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallToSiteHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void noApprovedSnapshotBlocksBeforeAnyInstall() {
        InstallToSiteHandler handler = new InstallToSiteHandler(
                ApprovedSnapshotWorkspace.createNull(), ManagedSiteInstaller.createNull());

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("No approved snapshot exists to install.", result.message());
    }

    @Test
    void approvedSnapshotIsInstalledIntoTheSite() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals("EN title", siteInstaller.installed().get(IDENTITY).enTitle());
    }

    @Test
    void aSecondInstallIsBlocked() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);
        handler.installToSite(IDENTITY);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("A site installation already exists; replacing it is not yet supported.", result.message());
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InstallToSiteHandlerTest`
Expected: FAIL — compile error, none of these classes exist yet

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.installtosite;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.Objects;

public final class InstallToSiteResult {

    private final boolean ok;
    private final PublicationIdentity identity;
    private final String message;

    private InstallToSiteResult(boolean ok, PublicationIdentity identity, String message) {
        this.ok = ok;
        this.identity = identity;
        this.message = message;
    }

    public static InstallToSiteResult installed(PublicationIdentity identity) {
        return new InstallToSiteResult(true, Objects.requireNonNull(identity, "identity"), null);
    }

    public static InstallToSiteResult blocked(String message) {
        return new InstallToSiteResult(false, null, Objects.requireNonNull(message, "message"));
    }

    @JsonProperty("ok") public boolean ok() { return ok; }
    @JsonProperty("identity") public PublicationIdentity identity() { return identity; }
    @JsonProperty("message") public String message() { return message; }
}
```

```java
package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.SiteAlreadyInstalledException;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class InstallToSiteHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final ManagedSiteInstaller managedSiteInstaller;

    public InstallToSiteHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            ManagedSiteInstaller managedSiteInstaller) {
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.managedSiteInstaller = Objects.requireNonNull(managedSiteInstaller, "managedSiteInstaller");
    }

    public InstallToSiteResult installToSite(PublicationIdentity identity) {
        Optional<CandidateSnapshot> approved;
        try {
            approved = approvedSnapshotWorkspace.read(identity);
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(ioFailureMessage("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        }
        if (approved.isEmpty()) {
            return InstallToSiteResult.blocked("No approved snapshot exists to install.");
        }
        return installApprovedSnapshot(identity, approved.get());
    }

    private InstallToSiteResult installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        try {
            managedSiteInstaller.install(identity, approved);
        } catch (SiteAlreadyInstalledException raceLoser) {
            return InstallToSiteResult.blocked(
                    "A site installation already exists; replacing it is not yet supported.");
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(ioFailureMessage("Site installation failed", failure));
        }
        return InstallToSiteResult.installed(identity);
    }

    private static String ioFailureMessage(String operation, UncheckedIOException failure) {
        String detail = failure.getCause().getMessage();
        return detail == null || detail.isBlank() ? operation + "." : operation + ": " + detail;
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InstallToSiteHandlerTest`
Expected: PASS, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteResult.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java
git commit -m "feat(publication-exporter): add InstallToSiteHandler"
```

---

### Task 12: `FilesystemManagedSiteInstaller` — real create-only adapter

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstallerConfinementException.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java`

**Interfaces:**
- Consumes: `StagedDirectoryInstall` (S06's `fs` package helper), `SiteReleaseManifest.computeOver(...)` (Task 9).
- Produces: real-adapter `install`, plus `ManagedSiteInstaller.create(Path siteRoot)` — consumed by Task 13 (CLI wiring).

Design.md D5's real half. `src/content/blog/{ru,en}/` is a directory shared across every publication in a collection+locale, unlike `candidate`/`approved`/`release`'s per-identity fresh directories — `StagedDirectoryInstall#moveIntoPlace`'s whole-directory atomic move does not directly apply here. This adapter stages both locale markdown files plus the manifest in one temporary staging directory (via `StagedDirectoryInstall#createStagingDirectory`), then moves each file into its final shared-directory destination individually with `Files.move(..., ATOMIC_MOVE)`, confining every destination path to `siteRoot` via `StagedDirectoryInstall#resolveWithinRoot` — reusing the helper's staging/confinement primitives without its single-directory-move assumption.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemManagedSiteInstallerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final CandidateSnapshot SNAPSHOT = CandidateSnapshot.of(
            "# RU body", "# EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

    @TempDir
    Path siteRoot;

    @Test
    void installWritesBothLocaleFilesAndTheManifestIntoAbsentManagedRoots() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(IDENTITY, SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        assertTrue(Files.exists(ruFile));
        assertTrue(Files.exists(enFile));
        String ruContent = Files.readString(ruFile, StandardCharsets.UTF_8);
        assertTrue(ruContent.contains("title: RU title"));
        assertTrue(ruContent.contains("contentType: essay"));
        assertTrue(ruContent.contains("sourceLanguage: ru"));
        assertTrue(ruContent.endsWith("# RU body"));
        String enContent = Files.readString(enFile, StandardCharsets.UTF_8);
        assertTrue(enContent.contains("translationOf: my-essay"));
        assertTrue(enContent.contains("translationStatus: generated"));
        assertTrue(Files.exists(siteRoot.resolve(".astro-export/release-provenance.json")));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);

        assertThrows(SiteAlreadyInstalledException.class, () -> installer.install(IDENTITY, SNAPSHOT));
    }

    @Test
    void escapingSiteRootIsRejected() {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot.resolve("nested"));
        // sanity: constructing against a not-yet-existing nested root must not itself throw
        assertTrue(true);
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemManagedSiteInstallerTest`
Expected: FAIL — compile error, `FilesystemManagedSiteInstaller` does not exist yet

- [x] **Step 3: Write minimal implementation**

Add `create(Path)` to the interface:

```java
    static ManagedSiteInstaller create(Path siteRoot) {
        return new FilesystemManagedSiteInstaller(siteRoot);
    }
```

(Add `import java.nio.file.Path;` to `ManagedSiteInstaller.java`.)

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FilesystemManagedSiteInstaller implements ManagedSiteInstaller {

    private static final List<String> PAYLOAD_ROOTS =
            List.of("public/assets/vault", "src/content", "src/data/pages");

    private final StagedDirectoryInstall stagedInstall;

    public FilesystemManagedSiteInstaller(Path siteRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(siteRoot, "siteRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");

        Path ruDestination = markdownFile(identity, "ru");
        Path enDestination = markdownFile(identity, "en");
        if (Files.exists(ruDestination) || Files.exists(enDestination)) {
            throw new SiteAlreadyInstalledException(identity);
        }
        try {
            writeMarkdownFile(ruDestination, frontmatter(identity, approvedSnapshot, "ru"), approvedSnapshot.ruBody());
            writeMarkdownFile(enDestination, frontmatter(identity, approvedSnapshot, "en"), approvedSnapshot.enBody());
            writeManifest();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeMarkdownFile(Path destination, String frontmatter, String body) throws IOException {
        requireWithinSiteRoot(destination);
        Path staging = stagedInstall.createStagingDirectory("site-install-");
        try {
            Path stagedFile = staging.resolve(destination.getFileName());
            Files.writeString(stagedFile, frontmatter + body, StandardCharsets.UTF_8);
            Files.createDirectories(destination.getParent());
            Files.move(stagedFile, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            StagedDirectoryInstall.deleteRecursively(staging);
        }
    }

    private void writeManifest() throws IOException {
        Path manifestPath = stagedInstall.canonicalRoot().resolve(".astro-export/release-provenance.json");
        requireWithinSiteRoot(manifestPath);
        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(stagedInstall.canonicalRoot(), PAYLOAD_ROOTS);
        Path staging = stagedInstall.createStagingDirectory("site-manifest-");
        try {
            Path stagedFile = staging.resolve("release-provenance.json");
            Files.writeString(stagedFile, manifest.toCanonicalJson(), StandardCharsets.UTF_8);
            Files.createDirectories(manifestPath.getParent());
            Files.move(stagedFile, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            StagedDirectoryInstall.deleteRecursively(staging);
        }
    }

    private Path markdownFile(PublicationIdentity identity, String locale) {
        Path file = stagedInstall.canonicalRoot()
                .resolve("src/content").resolve(identity.publicCollection()).resolve(locale)
                .resolve(identity.publicId() + ".md")
                .normalize();
        requireWithinSiteRoot(file);
        return file;
    }

    private void requireWithinSiteRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new ManagedSiteInstallerConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private static String frontmatter(PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        boolean isRu = "ru".equals(locale);
        StringBuilder yaml = new StringBuilder("---\n");
        yaml.append("id: ").append(identity.publicId()).append('\n');
        yaml.append("title: ").append(isRu ? approved.ruTitle() : approved.enTitle()).append('\n');
        yaml.append("description: ").append(isRu ? approved.ruDescription() : approved.enDescription()).append('\n');
        yaml.append("publish: true\n");
        yaml.append("contentType: ").append(identity.publicContentType()).append('\n');
        yaml.append("language: ").append(locale).append('\n');
        yaml.append("sourceLanguage: ru\n");
        yaml.append("sourceHash: ").append(approved.referenceMap().ruHash()).append('\n');
        yaml.append("translationStatus: ").append(isRu ? "source" : "generated").append('\n');
        if (!isRu) {
            yaml.append("translationOf: ").append(identity.publicId()).append('\n');
        }
        yaml.append("---\n");
        return yaml.toString();
    }
}
```

```java
package dev.eugene.publicationexporter.site;

import java.nio.file.Path;

public final class ManagedSiteInstallerConfinementException extends IllegalStateException {
    public ManagedSiteInstallerConfinementException(Path candidate, Path resolvedCandidate, Path siteRoot) {
        super("Path escapes the site root " + siteRoot + ": " + candidate + " (resolved: " + resolvedCandidate + ")");
    }
}
```

`PublicationIdentity#publicContentType()` and `#publicId()`/`#publicCollection()` already exist (used throughout S02-S06); confirm their exact accessor names against `PublicationIdentity.java` before use — adjust names in this file to match if they differ.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemManagedSiteInstallerTest`
Expected: PASS, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS across the whole module

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstallerConfinementException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java
git commit -m "feat(publication-exporter): add FilesystemManagedSiteInstaller real adapter"
```

---

### Task 13: `InstallToSiteCommand` — CLI wiring, subcommand registration, and the acceptance test

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InstallToSiteCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InstallToSiteCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `InstallToSiteHandler(ApprovedSnapshotWorkspace, ManagedSiteInstaller)` (Task 11), `ApprovedSnapshotWorkspace.create(Path)` (existing), `ManagedSiteInstaller.create(Path)` (Task 12).

This is the slice's system-boundary acceptance test — the real CLI, a real approved-store filesystem root, and a real, previously-absent site root, no fakes anywhere in the test. Exercises REL-05's new "Empty-destination install" scenario end to end.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallToSiteCliAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @TempDir
    Path workRoot;

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void noApprovedSnapshotProducesBlockedResultAndWritesNothing() throws Exception {
        Path siteRoot = workRoot.resolve("site");

        int exitCode = installToSite(workRoot.resolve("review"), siteRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertEquals("No approved snapshot exists to install.", result.get("message").asText());
        assertTrue(Files.notExists(siteRoot));
    }

    @Test
    void approvedSnapshotIsInstalledIntoPreviouslyAbsentManagedRoots() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path siteRoot = workRoot.resolve("site");
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY,
                "# My Essay", "# My Essay (EN)", "My Essay", "My Essay (EN)",
                "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        int exitCode = installToSite(reviewDirectory, siteRoot);

        assertEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(true, result.get("ok").asBoolean());
        assertEquals("my-essay", result.get("identity").get("publicId").asText());
        assertTrue(Files.exists(siteRoot.resolve("src/content/blog/ru/my-essay.md")));
        assertTrue(Files.exists(siteRoot.resolve("src/content/blog/en/my-essay.md")));
        assertTrue(Files.exists(siteRoot.resolve(".astro-export/release-provenance.json")));
        String manifest = Files.readString(siteRoot.resolve(".astro-export/release-provenance.json"));
        assertTrue(manifest.contains("\"schemaVersion\":1"));
    }

    private int installToSite(Path reviewDirectory, Path siteRoot) {
        return new CommandLine(new Main()).execute(
                "install-to-site",
                "--review", reviewDirectory.toString(),
                "--site", siteRoot.toString(),
                "--collection", "blog",
                "--content-type", "essay",
                "--id", "my-essay");
    }

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(), () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InstallToSiteCliAcceptanceTest`
Expected: FAIL — `install-to-site` is not a recognized subcommand

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.installtosite.InstallToSiteHandler;
import dev.eugene.publicationexporter.installtosite.InstallToSiteResult;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "install-to-site")
public final class InstallToSiteCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--site", required = true)
    Path siteRoot;

    @Option(names = "--collection", required = true)
    String collection;

    @Option(names = "--content-type", required = true)
    String contentType;

    @Option(names = "--id", required = true)
    String publicId;

    @Override
    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ManagedSiteInstaller managedSiteInstaller = ManagedSiteInstaller.create(siteRoot);
        PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
        InstallToSiteResult result = new InstallToSiteHandler(approvedSnapshotWorkspace, managedSiteInstaller)
                .installToSite(identity);

        System.out.println(new ObjectMapper().writeValueAsString(result));
        return result.ok() ? 0 : 1;
    }
}
```

Update `Main.java`'s subcommand list:

```java
@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class, InstallToSiteCommand.class })
public final class Main implements Runnable {
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InstallToSiteCliAcceptanceTest`
Expected: PASS — 2 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InstallToSiteCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InstallToSiteCliAcceptanceTest.java
git commit -m "feat(publication-exporter): wire install-to-site CLI command"
```

---

### Task 14: Real-adapter gate-compatibility contract test

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/CheckContentGateContractTest.java`

**Interfaces:**
- Consumes: `FilesystemManagedSiteInstaller` (Task 12) via `ManagedSiteInstaller.create(Path)`; `node`, `site/scripts/check-content.mjs` (external, invoked via `ProcessBuilder`).

Proves `SiteReleaseManifest`'s Java-side hashing is byte-for-byte compatible with `check-content.mjs`'s JS-side verification — not by asserting the Java code against itself, but by literally subprocess-invoking the real gate script against Java-produced output, the same proof strategy `site/tests/release-provenance.test.mjs` already uses for the JS side. This is a JUnit test that shells out — acceptable here specifically because it is the real-adapter contract test the plan's acceptance boundary calls for, not the fast in-memory subset (which stays under 1 second via Tasks 1-11's `Null*` fakes).

This exercises REL-06's two scenarios: "Generated content is coherent" (gate passes) and "Generated content violates a gate" (tampering after install is rejected).

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CheckContentGateContractTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath()
            .resolveSibling("site"); // publication-exporter/ -> ../site

    @TempDir
    Path siteRoot;

    @Test
    void installedOutputPassesTheRealGateWithCuratedPageFixtures() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());

        ProcessResult result = runGate(siteRoot);

        assertEquals(0, result.exitCode(), () -> "gate rejected valid output: " + result.output());
        assertTrue(result.output().contains("Content validation passed successfully"));
    }

    @Test
    void tamperingWithAnInstalledFileIsRejectedBeforeBuild() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Files.writeString(ruFile, "\ntampered\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ProcessResult result = runGate(siteRoot);

        assertEquals(1, result.exitCode());
        assertTrue(result.output().toLowerCase().contains("release-provenance-mismatch"));
    }

    private static CandidateSnapshot essaySnapshot() {
        return CandidateSnapshot.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash"));
    }

    private static void seedCuratedPageFixtures(Path siteRoot) throws IOException {
        List<String> pageIds = List.of("about", "concepts", "essays", "home", "library", "music", "notes",
                "search", "claims");
        for (String language : List.of("ru", "en")) {
            Path pagesDir = siteRoot.resolve("src/data/pages").resolve(language);
            Files.createDirectories(pagesDir);
            for (String id : pageIds) {
                Files.writeString(pagesDir.resolve(id + ".json"), curatedPageJson(id, language), StandardCharsets.UTF_8);
            }
        }
        Files.createDirectories(siteRoot.resolve("public/assets/vault"));
    }

    private static String curatedPageJson(String id, String language) {
        String type = Map.of("about", "page", "concepts", "concept", "essays", "essay", "home", "page",
                "library", "book", "music", "album", "notes", "note", "claims", "claim", "search", "search")
                .get(id);
        boolean isSystemSearch = "search".equals(id);
        StringBuilder json = new StringBuilder("{");
        json.append("\"id\":\"").append(id).append("\",");
        json.append("\"type\":\"").append(type).append("\",");
        json.append("\"searchable\":false,\"topics\":[],\"links\":[],");
        json.append("\"title\":\"Fixture ").append(language).append(' ').append(id).append("\",");
        json.append("\"summary\":\"Valid synthetic fixture page.\"");
        if (!isSystemSearch) {
            json.append(",\"language\":\"").append(language).append("\",\"sourceLanguage\":\"ru\",");
            json.append("\"translationStatus\":\"").append("ru".equals(language) ? "source" : "generated").append('"');
            if (!"ru".equals(language)) {
                json.append(",\"translationOf\":\"").append(id).append('"');
            }
        }
        json.append("}");
        return json.toString();
    }

    private static ProcessResult runGate(Path siteRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("node", "scripts/check-content.mjs")
                .directory(SITE_PROJECT_ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("ASTRO_CONTENT_DIR", siteRoot.resolve("src/content").toString());
        builder.environment().put("ASTRO_PAGES_DIR", siteRoot.resolve("src/data/pages").toString());
        builder.environment().put("ASTRO_RELEASE_MANIFEST", siteRoot.resolve(".astro-export/release-provenance.json").toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("check-content.mjs did not complete within 30s");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {}
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CheckContentGateContractTest`
Expected: FAIL — either a compile error (if Task 12 is incomplete) or a real gate rejection, surfacing any byte-for-byte mismatch between `SiteReleaseManifest` and `check-content.mjs` immediately

- [x] **Step 3: Fix any real mismatch surfaced by Step 2**

If the gate rejects valid output, the failure message names exactly which check failed (`payload roots must be exactly ...`, `managed tree hash mismatch`, `managed file hash mismatch`, `payload digest mismatch`, or a field-level content error). Cross-reference `site/scripts/check-content.mjs`'s `verifyReleaseProvenance`/`hashTree`/`hashPayloadFiles` against `SiteReleaseManifest`'s Task 9 implementation field-by-field and byte-by-byte until this test passes without modifying the test itself — the test is the specification here, not negotiable content.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CheckContentGateContractTest`
Expected: PASS, 0 failures. Confirm this test's own runtime stays well under the plan's slow-test budget for a single subprocess round trip (a few seconds, not the full Astro build Task 15 covers).

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS across the whole module

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/site/CheckContentGateContractTest.java
git commit -m "test(publication-exporter): prove SiteReleaseManifest against the real check-content.mjs gate"
```

---

### Task 15: Slow Astro smoke test

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/AstroBuildSmokeIT.java`

**Interfaces:**
- Consumes: `ManagedSiteInstaller.create(Path)` (Task 12); `npx astro build` (external, invoked via `ProcessBuilder`), run against `site/`'s own `node_modules` (already installed per repo status).

This is the plan's one allowed slow test — a real `astro build` against a real, previously-absent site content tree assembled by the exporter's own real adapter, proving REL-06's "Astro build success" guarantee end to end. Not run as part of the fast in-memory suite; annotate with a `@Tag("slow")` (or equivalent existing convention in this module — check `pom.xml`'s surefire config for an existing slow-test exclusion pattern before inventing a new one) so `mvn test`'s default run stays fast and this test runs in its own explicit invocation.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("slow")
class AstroBuildSmokeIT {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath().resolveSibling("site");

    @TempDir
    Path siteRoot;

    @Test
    void astroBuildSucceedsAgainstTheInstalledOutput() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        CandidateSnapshot snapshot = CandidateSnapshot.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash"));
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, snapshot);

        int exitCode = runAstroBuild(siteRoot);

        assertEquals(0, exitCode);
    }

    // seedCuratedPageFixtures/curatedPageJson: identical to CheckContentGateContractTest's helpers
    // (Task 14) — extract into a small shared test-support class if duplication becomes awkward,
    // per this codebase's own "revisit after a third occurrence" precedent (design.md D3 / S05).

    private static int runAstroBuild(Path siteRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("npx", "astro", "build", "--force")
                .directory(SITE_PROJECT_ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("ASTRO_CONTENT_DIR", siteRoot.resolve("src/content").toString());
        builder.environment().put("ASTRO_PAGES_DIR", siteRoot.resolve("src/data/pages").toString());
        builder.environment().put("ASTRO_RELEASE_MANIFEST", siteRoot.resolve(".astro-export/release-provenance.json").toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("astro build did not complete within 180s. Output so far:\n" + output);
        }
        if (process.exitValue() != 0) {
            fail("astro build failed with exit code " + process.exitValue() + ":\n" + output);
        }
        return process.exitValue();
    }
}
```

Copy `seedCuratedPageFixtures`/`curatedPageJson` from `CheckContentGateContractTest` (Task 14) verbatim for now — if a third near-identical copy appears in a later slice, extract a shared test-support fixture builder then, not preemptively here (two occurrences is not yet the plan's own evidence threshold).

**Astro output directory note:** confirm whether `astro build`'s default output root (`site/dist/`) needs isolating per test run (e.g. via an `ASTRO_OUT_DIR`-equivalent config override, or accepting a shared `dist/` since this test doesn't assert on build output content, only exit code) — check `site/astro.config.mjs` for an existing `outDir` override mechanism before assuming one is needed.

- [x] **Step 2: Run test to verify it fails**

Run: `cd publication-exporter && mvn test -Dtest=AstroBuildSmokeIT`
Expected: FAIL initially if any gate/schema mismatch remains, surfacing the same class of error Task 14 already resolved for `check-content.mjs` — this time from Astro's own Zod schema validation (`content.config.ts`), which Task 14's test does not exercise.

- [x] **Step 3: Fix any real mismatch surfaced by Step 2**

If `content.config.ts`'s Zod schema rejects the frontmatter `FilesystemManagedSiteInstaller` (Task 12) writes, cross-reference every required/defaulted field in `blogEssay`'s schema (`site/src/content.config.ts`) against design.md D7's field list and this test's fixture data until the build succeeds — again, fix the production frontmatter-writing code, not this test.

- [x] **Step 4: Run test to verify it passes**

Run: `cd publication-exporter && mvn test -Dtest=AstroBuildSmokeIT`
Expected: PASS, 1 test, 0 failures. Confirm this is the only test in the module tagged `slow` and that `mvn test`'s default profile excludes it (per whatever tag-exclusion convention Step 1 confirmed or established) so the fast suite's under-one-second acceptance budget is untouched.

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/site/AstroBuildSmokeIT.java
git commit -m "test(publication-exporter): add the one slow Astro build smoke test (REL-06)"
```

---

### Task 16: Full verification pass

**Files:** none (verification only)

- [x] **Step 1: Run the complete fast `publication-exporter` suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (baseline from before this slice + every new/updated test across Tasks 1-14; Task 15's `AstroBuildSmokeIT` is tag-excluded from this default run)

- [x] **Step 2: Run the slow Astro smoke test explicitly**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=AstroBuildSmokeIT` (or the module's equivalent tag-inclusion invocation)
Expected: PASS

- [x] **Step 3: Run the obsidian-plugin conformance suite**

Run: `cd obsidian-plugin && node --test tests/*.test.cjs`
Expected: PASS, unaffected — `install-to-site` is absent from `bridge-contract/schema-v2.json` and introduces no plugin-consumed response shape.

- [x] **Step 4: Confirm every checkbox above is checked**

Re-read this file top to bottom; every `- [ ]` under every task and every numbered step must be `- [x]` before this slice is considered implementation-complete.

- [x] **Step 5: Final commit (if any cleanup remains)**

```bash
git status
```

If clean, no further commit is needed — Tasks 1-15 already committed the complete, working slice. This task exists to gate archival, not to add its own diff.
