# S06 — Materialize One Approved Essay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `build-from-review`, given a publication identity with a durable approved snapshot (S05), reads the approved RU/EN bodies and reference map and writes one RU essay file, one EN essay file, and a deterministic minimum release-provenance record into a brand-new, previously empty output root — ignoring any existing candidate, and blocking before any write if no approved snapshot exists.

**Architecture:** One new production adapter pair (`ReleaseOutputStore`, an in-memory fake proven first then a real create-only filesystem adapter), following S05's exact `ApprovedSnapshotWorkspace` shape. `ApprovedSnapshotWorkspace` gains a second read method, `read(identity): Optional<CandidateSnapshot>` (content, not just paths) — its second interface change, so every existing implementor/test-double is updated in one task/commit. A small `ReleaseProvenance` Whole Value binds contract edition, approved-snapshot hashes, and freshly-computed output-file hashes, with activation/deactivation counts hard-coded to zero (no semantic occurrences exist until S19/S20). `BuildFromReviewHandler` produces its own `ReleaseResult` — not a `BridgeResponse` — because `build-from-review` is absent from `bridge-contract/schema-v2.json`'s command enum and is never consumed by the Obsidian plugin. A small internal `StagedDirectoryInstall` helper is extracted from `FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace` and reused by the new `FilesystemReleaseOutputStore` — the third occurrence of the identical stage-then-atomic-move-and-confine shape, the evidence threshold S05's own design doc flagged for finally sharing it.

**Tech Stack:** Same as S01-S05 — Java 17, Maven, picocli, Jackson, JUnit Jupiter. **No `pom.xml` change in this slice** — every new type uses only `java.nio.file`/`java.security`/`java.util`, already available.

## Global Constraints

- Requirements introduced: REL-02 (real delta, `specs/release-materialization/spec.md` — the new "Approved snapshot has no semantic occurrences" scenario), REL-01, REL-03 (determinism half), PCM-01, PCM-02 (all scope pins, `scope-pins.md`) — no other requirement is pulled in.
- `publication-exporter/pom.xml` is not modified in this slice.
- Functional collaborative-design decisions (binding, do not re-litigate): REL-02 gets a new scenario for the zero-semantic-occurrence case; REL-01 and REL-03's determinism scenario are pure scope pins; REL-03's tamper-detection scenario and PCM-01/PCM-02 are scope pins satisfied by construction (see `scope-pins.md`).
- Technical collaborative-design decisions (binding, do not re-litigate — see `design.md` D1-D6): (1) `ApprovedSnapshotWorkspace` gains `read(identity): Optional<CandidateSnapshot>`, reusing S05's `CandidateSnapshot` type — no new `ApprovedSnapshot` type. (2) `ReleaseOutputStore` is this slice's one new port, laid out as `<outputRoot>/<collection>/<id>/release/{ru.md,en.md,release-provenance.json}` — the same identity-scoped-fresh-directory convention as `candidate`/`approved`, deliberately NOT the site's `<collection>/<locale>/<id>.md` shape (that re-layout is S07's job). (3) `FilesystemCandidateWorkspace`, `FilesystemApprovedSnapshotWorkspace`, and the new `FilesystemReleaseOutputStore` share one internal `StagedDirectoryInstall` helper — a pure, behavior-preserving refactor proven by both existing test suites passing unchanged. (4) `ReleaseProvenance` records `approvedRuHash`/`approvedEnHash` (from the reference map) and `outputRuHash`/`outputEnHash` (freshly computed at write time) as five separate stored/derived facts, plus hard-coded `activationCount`/`deactivationCount` of `0`. (5) `build-from-review` produces its own `ReleaseResult`, never a `BridgeResponse`. (6) The CLI supplies identity via `--collection`/`--content-type`/`--id`, not a vault note — there is nothing to admit.
- `/nullables`: `ReleaseOutputStore` gets `create()`/`createNull()` factories from the start, in-memory fake proven before the real adapter; no mocking library anywhere in this plan.
- `/applying-sbpp`: every new value type (`ReleaseProvenance`, `ReleaseResult`) is built via a named Constructor Method with a `private` constructor — never bare `new` from outside its own package/class, matching `PublicationIdentity`/`ReferenceMap`/`CandidateSnapshot` precedent (do NOT convert any of these to `record`s). `BuildFromReviewHandler#buildFromReview` is a Composed Method table of contents, mirroring `PrepareHandler#prepare`'s, `InspectPublicationHandler#inspect`'s, and `MarkReviewedHandler#markReviewed`'s existing shape.
- `/oo-design-guide`: `ReleaseOutputStore` and `ApprovedSnapshotWorkspace` stay two separate interfaces — release and approved-snapshot lifecycles are distinct abstractions (heuristic 5.9/5.10: no common behavior beyond the shared filesystem mechanics, which live in `StagedDirectoryInstall`, not in either public interface). `BuildFromReviewHandler` keeps the same one-dominant-public-method heuristic-3.9 departure already established for `PrepareHandler`/`InspectPublicationHandler`/`MarkReviewedHandler`. `StagedDirectoryInstall` hides its confinement/staging mechanics behind a small protocol (heuristic 2.1/2.3) and knows nothing about `ru.md`/`en.md`/file-naming — that stays in each owning adapter (heuristic 2.9/2.10: don't let the shared helper absorb per-adapter knowledge it doesn't need).
- **Interface-change discipline** (memory `feedback-java-interface-change-task-planning`): `ApprovedSnapshotWorkspace` has exactly three known implementors/test-doubles today — `NullApprovedSnapshotWorkspace`, `FilesystemApprovedSnapshotWorkspace` (both `src/main`), and one anonymous-class test double, `MarkReviewedHandlerTest#approvedSnapshotWorkspaceThrowing` (line ~247-263, `src/test`). Task 1 below updates all three in one commit — do not split it.
- Out of scope for S06 — do not implement: replacing an existing release generation or recovery from an interrupted replacement (S10), installing into `site/src/content/` or any live-site tree and re-laying the release tree into the site's locale-keyed shape (S07), assets, links, and multiple publications in one invocation (S13/S14/S16), resolving semantic occurrences into public routes (S20), reconstructing Astro front matter (title/date/tags/description — no requirement introduces this yet).
- Governance: implements Haft problem `prob-20260806-e107746a`; do not close it or archive this OpenSpec change until the final task's full verification pass is green AND the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the final GPT-5.6 Sol max-effort review) confirms the slice is complete.

---

### Task 1: `ApprovedSnapshotWorkspace#read(...)` — every implementor and test double, one commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`

**Interfaces:**
- Consumes: `CandidateSnapshot.of(...)` (existing, S05), `ReferenceMapCodec.read(...)` (existing, S05).
- Produces: `ApprovedSnapshotWorkspace#read(PublicationIdentity): Optional<CandidateSnapshot>` — consumed by Task 7 (`BuildFromReviewHandler`).

**This is the interface-change task.** All three known implementors/test-doubles are updated here in one commit — see this plan's Global Constraints. Do not commit a partial subset.

- [ ] **Step 1: Write the failing tests**

Append to `NullApprovedSnapshotWorkspaceTest`:

```java
    @Test
    void readIsAbsentBeforeAnyInstall() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentity() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        assertEquals(Optional.empty(), workspace.read(DIFFERENT_IDENTITY));
    }
```

Append to `FilesystemApprovedSnapshotWorkspaceTest`:

```java
    @Test
    void readIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals(referenceMap, read.get().referenceMap());
    }
```

Add `import java.util.Optional;` to `NullApprovedSnapshotWorkspaceTest` if not already present (it is, from S05's `find()` tests); confirm `FilesystemApprovedSnapshotWorkspaceTest` already imports `Optional` (it does).

In `MarkReviewedHandlerTest.java`, add a `read` override to `approvedSnapshotWorkspaceThrowing(...)` (the anonymous class at line ~247-263) — this test double exercises only `find`'s failure path today, so `read` mirrors the same thrown failure for compile-time completeness:

```java
            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
```

Add `import dev.eugene.publicationexporter.candidate.CandidateSnapshot;` to `MarkReviewedHandlerTest.java` if not already present (it is — `CandidateSnapshot` is already imported there from S05's own `candidateWorkspaceThrowing` double).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest,MarkReviewedHandlerTest`
Expected: FAIL — compile error, `read` is undefined on `ApprovedSnapshotWorkspace`

- [ ] **Step 3: Write minimal implementation**

In `ApprovedSnapshotWorkspace.java`, add the method to the interface, directly after `find(...)`:

```java
    Optional<CandidateSnapshot> read(PublicationIdentity identity);
```

Add `import dev.eugene.publicationexporter.candidate.CandidateSnapshot;`.

Replace the whole `NullApprovedSnapshotWorkspace.java` file:

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NullApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Map<PublicationIdentity, InstalledApprovedSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        validateInstallArguments(identity, ruBody, enBody, referenceMap);
        ensureNotAlreadyInstalled(identity);
        installed.put(identity, InstalledApprovedSnapshot.of(ruBody, enBody, referenceMap));
    }

    private void validateInstallArguments(
            PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");
    }

    private void ensureNotAlreadyInstalled(PublicationIdentity identity) {
        if (installed.containsKey(identity)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        validateIdentity(identity);
        if (!hasInstallation(identity)) {
            return Optional.empty();
        }
        return Optional.of(pathsFor(identity));
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        validateIdentity(identity);
        return Optional.ofNullable(installed.get(identity))
                .map(snapshot -> CandidateSnapshot.of(snapshot.ruBody(), snapshot.enBody(), snapshot.referenceMap()));
    }

    private void validateIdentity(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
    }

    private boolean hasInstallation(PublicationIdentity identity) {
        return installed.containsKey(identity);
    }

    private CandidatePaths pathsFor(PublicationIdentity identity) {
        Path approvedDirectory = Path.of("/approved", identity.publicCollection(), identity.publicId(), "approved");
        return CandidatePaths.of(approvedDirectory.resolve("ru.md"), approvedDirectory.resolve("en.md"));
    }

    private static final class InstalledApprovedSnapshot {

        private final String ruBody;
        private final String enBody;
        private final ReferenceMap referenceMap;

        private InstalledApprovedSnapshot(String ruBody, String enBody, ReferenceMap referenceMap) {
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
        }

        static InstalledApprovedSnapshot of(String ruBody, String enBody, ReferenceMap referenceMap) {
            return new InstalledApprovedSnapshot(ruBody, enBody, referenceMap);
        }

        String ruBody() {
            return ruBody;
        }

        String enBody() {
            return enBody;
        }

        ReferenceMap referenceMap() {
            return referenceMap;
        }
    }
}
```

This deliberately changes `NullApprovedSnapshotWorkspace`'s internal storage from `Map<PublicationIdentity, ReferenceMap>` to a body-carrying record — S05's own note on this class ("nothing in this slice's acceptance tests reads approved body content back out") is no longer true. No existing test inspects the map's internal shape, only `find(...)`'s paths-and-existence contract, which is unchanged.

In `FilesystemApprovedSnapshotWorkspace.java`, add after `find(...)`:

```java
    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path approvedDirectory = approvedDirectory(identity);
        Path ruPath = approvedDirectory.resolve("ru.md");
        Path enPath = approvedDirectory.resolve("en.md");
        Path referencesPath = approvedDirectory.resolve("references.json");
        if (!Files.exists(ruPath) || !Files.exists(enPath) || !Files.exists(referencesPath)) {
            return Optional.empty();
        }
        try {
            String ruBody = Files.readString(ruPath, StandardCharsets.UTF_8);
            String enBody = Files.readString(enPath, StandardCharsets.UTF_8);
            ReferenceMap referenceMap = ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
            return Optional.of(CandidateSnapshot.of(ruBody, enBody, referenceMap));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
```

Add `import dev.eugene.publicationexporter.candidate.CandidateSnapshot;`. `ReferenceMap`, `ReferenceMapCodec`, `StandardCharsets`, and `IOException` are already imported in this file.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest,MarkReviewedHandlerTest`
Expected: PASS, 0 failures across all three classes

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS — every pre-existing test across the whole module still passes unchanged

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java
git commit -m "feat(publication-exporter): add ApprovedSnapshotWorkspace#read with both adapters and test doubles"
```

---

### Task 2: `ReleaseProvenance` — a content-bearing Whole Value

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseProvenance.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/ReleaseProvenanceTest.java`

**Interfaces:**
- Produces: `ReleaseProvenance.of(PublicationIdentity, String approvedRuHash, String approvedEnHash, String outputRuHash, String outputEnHash): ReleaseProvenance`, `#contractEdition()`, `#identity()`, `#approvedRuHash()`, `#approvedEnHash()`, `#outputRuHash()`, `#outputEnHash()`, `#activationCount()`, `#deactivationCount()` — consumed by Task 3 (`ReleaseOutputStore#install`) and Task 7 (`BuildFromReviewHandler`).

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseProvenanceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void accessorsReturnConstructedValuesWithZeroActivationCounts() {
        ReleaseProvenance provenance = ReleaseProvenance.of(IDENTITY, "approved-ru", "approved-en", "output-ru", "output-en");

        assertEquals(1, provenance.contractEdition());
        assertEquals(IDENTITY, provenance.identity());
        assertEquals("approved-ru", provenance.approvedRuHash());
        assertEquals("approved-en", provenance.approvedEnHash());
        assertEquals("output-ru", provenance.outputRuHash());
        assertEquals("output-en", provenance.outputEnHash());
        assertEquals(0, provenance.activationCount());
        assertEquals(0, provenance.deactivationCount());
    }

    @Test
    void equalProvenanceBuiltSeparatelyAreEqual() {
        assertEquals(
                ReleaseProvenance.of(IDENTITY, "ru", "en", "ru", "en"),
                ReleaseProvenance.of(IDENTITY, "ru", "en", "ru", "en"));
    }

    @Test
    void identityIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReleaseProvenance.of(null, "ru", "en", "ru", "en"));
        assertEquals("identity", exception.getMessage());
    }

    @Test
    void serializesEveryFieldAsJson() throws Exception {
        ReleaseProvenance provenance = ReleaseProvenance.of(IDENTITY, "approved-ru", "approved-en", "output-ru", "output-en");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(provenance));

        assertEquals(1, json.get("contractEdition").asInt());
        assertEquals("my-essay", json.get("publicationIdentity").get("publicId").asText());
        assertEquals("approved-ru", json.get("approvedRuHash").asText());
        assertEquals("approved-en", json.get("approvedEnHash").asText());
        assertEquals("output-ru", json.get("outputRuHash").asText());
        assertEquals("output-en", json.get("outputEnHash").asText());
        assertEquals(0, json.get("activationCount").asInt());
        assertEquals(0, json.get("deactivationCount").asInt());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReleaseProvenanceTest`
Expected: FAIL — compile error, `ReleaseProvenance` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.Objects;

public final class ReleaseProvenance {

    private static final int CONTRACT_EDITION = 1;

    private final PublicationIdentity identity;
    private final String approvedRuHash;
    private final String approvedEnHash;
    private final String outputRuHash;
    private final String outputEnHash;

    private ReleaseProvenance(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.approvedRuHash = Objects.requireNonNull(approvedRuHash, "approvedRuHash");
        this.approvedEnHash = Objects.requireNonNull(approvedEnHash, "approvedEnHash");
        this.outputRuHash = Objects.requireNonNull(outputRuHash, "outputRuHash");
        this.outputEnHash = Objects.requireNonNull(outputEnHash, "outputEnHash");
    }

    public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash) {
        return new ReleaseProvenance(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash);
    }

    @JsonProperty("contractEdition")
    public int contractEdition() {
        return CONTRACT_EDITION;
    }

    @JsonProperty("publicationIdentity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("approvedRuHash")
    public String approvedRuHash() {
        return approvedRuHash;
    }

    @JsonProperty("approvedEnHash")
    public String approvedEnHash() {
        return approvedEnHash;
    }

    @JsonProperty("outputRuHash")
    public String outputRuHash() {
        return outputRuHash;
    }

    @JsonProperty("outputEnHash")
    public String outputEnHash() {
        return outputEnHash;
    }

    @JsonProperty("activationCount")
    public int activationCount() {
        return 0;
    }

    @JsonProperty("deactivationCount")
    public int deactivationCount() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseProvenance that)) {
            return false;
        }
        return identity.equals(that.identity)
                && approvedRuHash.equals(that.approvedRuHash)
                && approvedEnHash.equals(that.approvedEnHash)
                && outputRuHash.equals(that.outputRuHash)
                && outputEnHash.equals(that.outputEnHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash);
    }

    @Override
    public String toString() {
        return "ReleaseProvenance[identity=" + identity
                + ", approvedRuHash=" + approvedRuHash + ", approvedEnHash=" + approvedEnHash
                + ", outputRuHash=" + outputRuHash + ", outputEnHash=" + outputEnHash + "]";
    }
}
```

`activationCount()`/`deactivationCount()` are hard-coded `0`, not stored fields — there is no semantic-occurrence machinery to produce a non-zero value until S19/S20 (design.md D4).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReleaseProvenanceTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseProvenance.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/release/ReleaseProvenanceTest.java
git commit -m "feat(publication-exporter): add ReleaseProvenance value type"
```

---

### Task 3: `ReleaseOutputStore` — new port and in-memory fake

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseAlreadyExistsException.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/NullReleaseOutputStore.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/NullReleaseOutputStoreTest.java`

**Interfaces:**
- Consumes: `ReleaseProvenance` (Task 2), `PublicationIdentity`.
- Produces: `ReleaseOutputStore.install(...)`, `ReleaseOutputStore.createNull()` — consumed by Task 5 (real adapter, same contract) and Task 7 (`BuildFromReviewHandler`).

**Correction learned from S05's Task 5:** the interface must NOT declare `static ReleaseOutputStore create(Path outputRoot)` in this task — that factory's body would reference `FilesystemReleaseOutputStore`, which does not exist until Task 5, so the module would not compile. `create(Path)` is added in Task 5, in the same commit as the class it instantiates. This task's interface declares `install(...)` and `createNull()` only.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullReleaseOutputStoreTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void installRecordsTheInstalledBodiesAndProvenance() {
        NullReleaseOutputStore store = new NullReleaseOutputStore();

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertTrue(store.installed().containsKey(IDENTITY));
        assertEquals("RU body", store.installed().get(IDENTITY).ruBody());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullReleaseOutputStore store = new NullReleaseOutputStore();
        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertThrows(ReleaseAlreadyExistsException.class,
                () -> store.install(IDENTITY, "RU body 2", "EN body 2", PROVENANCE));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyStore() {
        ReleaseOutputStore store = ReleaseOutputStore.createNull();

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);
        // no exception: a fresh nulled store starts empty, mirroring ApprovedSnapshotWorkspace.createNull()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullReleaseOutputStoreTest`
Expected: FAIL — compile error, none of these classes exist yet

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class ReleaseAlreadyExistsException extends IllegalStateException {

    public ReleaseAlreadyExistsException(PublicationIdentity identity) {
        super("A release already exists for " + identity);
    }
}
```

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public interface ReleaseOutputStore {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance);

    static ReleaseOutputStore createNull() {
        return new NullReleaseOutputStore();
    }
}
```

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NullReleaseOutputStore implements ReleaseOutputStore {

    private final Map<PublicationIdentity, InstalledRelease> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(provenance, "provenance");
        if (installed.containsKey(identity)) {
            throw new ReleaseAlreadyExistsException(identity);
        }
        installed.put(identity, InstalledRelease.of(ruBody, enBody, provenance));
    }

    public Map<PublicationIdentity, InstalledRelease> installed() {
        return Map.copyOf(installed);
    }

    public static final class InstalledRelease {

        private final String ruBody;
        private final String enBody;
        private final ReleaseProvenance provenance;

        private InstalledRelease(String ruBody, String enBody, ReleaseProvenance provenance) {
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        static InstalledRelease of(String ruBody, String enBody, ReleaseProvenance provenance) {
            return new InstalledRelease(ruBody, enBody, provenance);
        }

        public String ruBody() {
            return ruBody;
        }

        public String enBody() {
            return enBody;
        }

        public ReleaseProvenance provenance() {
            return provenance;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullReleaseOutputStoreTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStore.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseAlreadyExistsException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/release/NullReleaseOutputStore.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/release/NullReleaseOutputStoreTest.java
git commit -m "feat(publication-exporter): add ReleaseOutputStore port with in-memory fake"
```

---

### Task 4: `StagedDirectoryInstall` — extract the shared staging/confinement helper

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/fs/StagedDirectoryInstall.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspaceConfinementException.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspaceConfinementException.java`

**Interfaces:**
- Produces: `StagedDirectoryInstall.rootedAt(Path): StagedDirectoryInstall`, `#canonicalRoot()`, `#createStagingDirectory(String prefix)`, `#moveIntoPlace(Path staging, Path destination)`, `#resolveWithinRoot(Path): Optional<Path>`, `StagedDirectoryInstall.deleteRecursively(Path)` — consumed by this task's two callers and by Task 5 (`FilesystemReleaseOutputStore`).

**This is a pure, behavior-preserving refactor.** No test in `FilesystemCandidateWorkspaceTest` or `FilesystemApprovedSnapshotWorkspaceTest` changes in this task — both suites must pass unchanged before and after, proving no observable behavior moved. This is the evidenced third occurrence of the identical stage-then-`ATOMIC_MOVE`-into-a-not-yet-existing-destination shape (design.md D3) — S05's own risk note named this exact trigger condition.

**Visibility widening:** `CandidateWorkspaceConfinementException` and `ApprovedSnapshotWorkspaceConfinementException`'s constructors change from package-private to `public`, so `StagedDirectoryInstall` (a different package, `fs`) can be handed a constructor reference. Nothing else about either exception type changes — same message format, same `IllegalStateException` supertype, same call sites within their own packages.

- [ ] **Step 1: Run the existing tests to confirm the pre-refactor baseline is green**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest`
Expected: PASS — this is the safety net the refactor must not break. No new test is written for this task; `StagedDirectoryInstall`'s behavior is exercised entirely through its two existing callers.

- [ ] **Step 2: Write `StagedDirectoryInstall`**

```java
package dev.eugene.publicationexporter.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.Objects;

public final class StagedDirectoryInstall {

    private final Path canonicalRoot;

    private StagedDirectoryInstall(Path root) {
        this.canonicalRoot = canonicalize(Objects.requireNonNull(root, "root"));
    }

    public static StagedDirectoryInstall rootedAt(Path root) {
        return new StagedDirectoryInstall(root);
    }

    public Path canonicalRoot() {
        return canonicalRoot;
    }

    public Path createStagingDirectory(String prefix) throws IOException {
        Files.createDirectories(canonicalRoot);
        return Files.createTempDirectory(canonicalRoot, prefix);
    }

    public void moveIntoPlace(Path staging, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The resolved real path of {@code candidate} if it lies within this root, empty if it escapes. */
    public Optional<Path> resolveWithinRoot(Path candidate) {
        if (!candidate.startsWith(canonicalRoot)) {
            return Optional.empty();
        }
        if (Files.notExists(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(candidate);
        }
        Path resolvedCandidate = resolveThroughNearestExistingAncestor(candidate);
        Path resolvedRoot = realPathOf(canonicalRoot).orElse(canonicalRoot);
        return resolvedCandidate.startsWith(resolvedRoot) ? Optional.of(resolvedCandidate) : Optional.empty();
    }

    public static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(StagedDirectoryInstall::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort staging cleanup after a failed install
        }
    }

    private static Path resolveThroughNearestExistingAncestor(Path candidate) {
        Path existingAncestor = candidate;
        while (existingAncestor != null
                && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return candidate.toAbsolutePath().normalize();
        }
        try {
            Path realAncestor = existingAncestor.toRealPath();
            return realAncestor.resolve(existingAncestor.relativize(candidate)).normalize();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Path canonicalize(Path root) {
        return realPathOf(root).orElseGet(() -> root.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see deleteRecursively
        }
    }
}
```

`resolveWithinRoot` deliberately returns `Optional<Path>` instead of throwing — exception construction stays inside each owning package (`candidate`/`approved`), which alone knows which confinement-exception type to raise and with what message. This keeps `StagedDirectoryInstall` ignorant of any adapter-specific type, per `/oo-design-guide` heuristic 2.10 (don't let a shared helper absorb knowledge it doesn't need).

- [ ] **Step 3: Widen the two existing confinement exceptions' constructor visibility**

In `CandidateWorkspaceConfinementException.java`, change:

```java
    CandidateWorkspaceConfinementException(Path candidate, Path resolvedCandidate, Path reviewRoot) {
```

to:

```java
    public CandidateWorkspaceConfinementException(Path candidate, Path resolvedCandidate, Path reviewRoot) {
```

In `ApprovedSnapshotWorkspaceConfinementException.java`, apply the identical change (package-private to `public`, same three parameters, same body).

- [ ] **Step 4: Rewrite `FilesystemCandidateWorkspace` to use `StagedDirectoryInstall`**

Replace the whole file:

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final StagedDirectoryInstall stagedInstall;

    FilesystemCandidateWorkspace(Path reviewRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");

        Path destination = candidateDirectory(identity);
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            requireWithinReviewRoot(destination);
            stagedInstall.moveIntoPlace(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

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

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path candidateDirectory = candidateDirectory(identity);
        if (!containsCandidateTriple(candidateDirectory)) {
            return Optional.empty();
        }
        return snapshotFrom(candidateDirectory, identity);
    }

    private static boolean containsCandidateTriple(Path candidateDirectory) {
        return Files.exists(candidateDirectory.resolve("ru.md"))
                && Files.exists(candidateDirectory.resolve("en.md"))
                && Files.exists(candidateDirectory.resolve("references.json"));
    }

    private Optional<CandidateSnapshot> snapshotFrom(
            Path candidateDirectory, PublicationIdentity expectedIdentity) {
        try {
            String ruBody = readCandidateBody(candidateDirectory.resolve("ru.md"));
            String enBody = readCandidateBody(candidateDirectory.resolve("en.md"));
            ReferenceMap referenceMap = readReferenceMap(candidateDirectory.resolve("references.json"));
            return snapshotMatching(expectedIdentity, ruBody, enBody, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readCandidateBody(Path bodyPath) throws IOException {
        requireWithinReviewRoot(bodyPath);
        return Files.readString(bodyPath, StandardCharsets.UTF_8);
    }

    private ReferenceMap readReferenceMap(Path referencesPath) throws IOException {
        requireWithinReviewRoot(referencesPath);
        return ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
    }

    private static Optional<CandidateSnapshot> snapshotMatching(
            PublicationIdentity expectedIdentity, String ruBody, String enBody, ReferenceMap referenceMap) {
        if (!referenceMap.identity().equals(expectedIdentity)) {
            return Optional.empty();
        }
        return Optional.of(CandidateSnapshot.of(ruBody, enBody, referenceMap));
    }

    private Path candidateDirectory(PublicationIdentity identity) {
        Path candidate = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("candidate")
                .normalize();
        requireWithinReviewRoot(candidate);
        return candidate;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("candidate-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new CandidateWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
        if (!resolved.get().equals(candidate) && !candidate.startsWith(stagedInstall.canonicalRoot())) {
            throw new CandidateWorkspaceConfinementException(candidate, resolved.get(), stagedInstall.canonicalRoot());
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }
}
```

**Correction anticipated during implementation:** `resolveWithinRoot` returning `Optional<Path>` collapses the original two distinct failure branches ("escapes root entirely" vs. "resolves outside via symlink") into one `Optional.empty()`, but the original `CandidateWorkspaceConfinementException` messages distinguished them (`candidate, candidate, reviewRoot` for the first, `candidate, resolvedCandidate, resolvedReviewRoot` for the second) purely for message wording — no test asserts on the exception's message text, only its type (`assertThrows(CandidateWorkspaceConfinementException.class, ...)` / `assertThrows(IllegalStateException.class, ...)`). The `requireWithinReviewRoot` shown above preserves a best-effort version of the richer message (using the resolved path when one was computable) without needing `resolveWithinRoot` to distinguish the two cases — simplify further to `throw new CandidateWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot())` unconditionally on `resolved.isEmpty()` if the two-branch version proves awkward; both are behavior-equivalent for every existing test, which checks type and side effects, never message text.

- [ ] **Step 5: Run tests to verify `FilesystemCandidateWorkspaceTest` still passes unchanged**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest`
Expected: PASS — same test count as before this task, 0 failures, no test file changed

- [ ] **Step 6: Rewrite `FilesystemApprovedSnapshotWorkspace` the same way**

Replace the whole file, applying the identical transformation as Step 4 (`"candidate"` to `"approved"`, `CandidateWorkspaceConfinementException` to `ApprovedSnapshotWorkspaceConfinementException`, `"candidate-staging-"` to `"approved-staging-"`, plus this file's own `read(...)` from Task 1 and `install(...)`'s create-only `Files.exists(destination)` guard):

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final StagedDirectoryInstall stagedInstall;

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");

        Path destination = approvedDirectory(identity);
        if (Files.exists(destination)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            requireWithinReviewRoot(destination);
            stagedInstall.moveIntoPlace(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path approvedDirectory = approvedDirectory(identity);
        Path ruPath = approvedDirectory.resolve("ru.md");
        Path enPath = approvedDirectory.resolve("en.md");
        if (Files.exists(ruPath) && Files.exists(enPath)) {
            return Optional.of(CandidatePaths.of(ruPath, enPath));
        }
        return Optional.empty();
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path approvedDirectory = approvedDirectory(identity);
        Path ruPath = approvedDirectory.resolve("ru.md");
        Path enPath = approvedDirectory.resolve("en.md");
        Path referencesPath = approvedDirectory.resolve("references.json");
        if (!Files.exists(ruPath) || !Files.exists(enPath) || !Files.exists(referencesPath)) {
            return Optional.empty();
        }
        try {
            String ruBody = Files.readString(ruPath, StandardCharsets.UTF_8);
            String enBody = Files.readString(enPath, StandardCharsets.UTF_8);
            ReferenceMap referenceMap = ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
            return Optional.of(CandidateSnapshot.of(ruBody, enBody, referenceMap));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Path approvedDirectory(PublicationIdentity identity) {
        Path approved = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("approved")
                .normalize();
        requireWithinReviewRoot(approved);
        return approved;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("approved-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new ApprovedSnapshotWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 7: Run tests to verify `FilesystemApprovedSnapshotWorkspaceTest` still passes unchanged**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemApprovedSnapshotWorkspaceTest`
Expected: PASS — same test count as before this task (Task 1 already added `read` tests earlier in this plan), 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing across the whole module

- [ ] **Step 8: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/fs/StagedDirectoryInstall.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspaceConfinementException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspaceConfinementException.java
git commit -m "refactor(publication-exporter): extract StagedDirectoryInstall from the two Filesystem*Workspace adapters"
```

---

### Task 5: `FilesystemReleaseOutputStore` — real create-only adapter

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/FilesystemReleaseOutputStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStoreConfinementException.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/FilesystemReleaseOutputStoreTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStore.java`

**Interfaces:**
- Consumes: `StagedDirectoryInstall` (Task 4), `ReleaseOutputStore` (Task 3, same contract proven by the fake).
- Produces: real-adapter `install`, plus `ReleaseOutputStore.create(Path)` — consumed by Task 8 (`BuildFromReviewCommand`).

**Also in this task:** add `create(Path)` to the interface, in the same commit as the class it references:

```java
    static ReleaseOutputStore create(Path outputRoot) {
        return new FilesystemReleaseOutputStore(outputRoot);
    }
```

placed directly after `install(...)` and before `createNull()`; add `import java.nio.file.Path;` to `ReleaseOutputStore.java`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemReleaseOutputStoreTest {

    @TempDir
    Path outputRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("RU body", Files.readString(releaseDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(releaseDir.resolve("en.md")));
        assertTrue(Files.readString(releaseDir.resolve("release-provenance.json")).contains("\"approvedRuHash\":\"ru-hash\""));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsAndLeavesTheFirstReleaseIntact() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);
        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertThrows(ReleaseAlreadyExistsException.class,
                () -> store.install(IDENTITY, "RU body 2", "EN body 2", PROVENANCE));

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("RU body", Files.readString(releaseDir.resolve("ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);

        store.install(IDENTITY, "RU", "EN", PROVENANCE);

        try (var entries = Files.list(outputRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("release-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = outputRoot.resolve("fresh-output-root");
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        assertThrows(IllegalStateException.class,
                () -> store.install(escapingIdentity, "RU", "EN",
                        ReleaseProvenance.of(escapingIdentity, "ru", "en", "ru", "en")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void buildingTheSameApprovedStateTwiceIntoTwoFreshRootsProducesIdenticalOutput(@TempDir Path secondOutputRoot)
            throws Exception {
        FilesystemReleaseOutputStore first = new FilesystemReleaseOutputStore(outputRoot);
        FilesystemReleaseOutputStore second = new FilesystemReleaseOutputStore(secondOutputRoot);

        first.install(IDENTITY, "RU body", "EN body", PROVENANCE);
        second.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        Path firstReleaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        Path secondReleaseDir = secondOutputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals(Files.readString(firstReleaseDir.resolve("ru.md")), Files.readString(secondReleaseDir.resolve("ru.md")));
        assertEquals(Files.readString(firstReleaseDir.resolve("en.md")), Files.readString(secondReleaseDir.resolve("en.md")));
        assertEquals(
                Files.readString(firstReleaseDir.resolve("release-provenance.json")),
                Files.readString(secondReleaseDir.resolve("release-provenance.json")));
    }
}
```

Note: `buildingTheSameApprovedStateTwiceIntoTwoFreshRootsProducesIdenticalOutput` is REL-03's determinism scenario ("Same approved state is built twice") — it builds into two DIFFERENT fresh roots because `install(...)` is create-only, matching design.md D2's rationale exactly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemReleaseOutputStoreTest`
Expected: FAIL — compile error, `FilesystemReleaseOutputStore` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.nio.file.Path;

public final class ReleaseOutputStoreConfinementException extends IllegalStateException {

    public ReleaseOutputStoreConfinementException(Path candidate, Path resolvedCandidate, Path outputRoot) {
        super("Release directory escapes output root: " + candidate
                + " resolved to " + resolvedCandidate + " outside " + outputRoot);
    }
}
```

```java
package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemReleaseOutputStore implements ReleaseOutputStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StagedDirectoryInstall stagedInstall;

    FilesystemReleaseOutputStore(Path outputRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(outputRoot, "outputRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(provenance, "provenance");

        Path destination = releaseDirectory(identity);
        if (Files.exists(destination)) {
            throw new ReleaseAlreadyExistsException(identity);
        }
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, provenance);
            requireWithinOutputRoot(destination);
            stagedInstall.moveIntoPlace(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    private Path releaseDirectory(PublicationIdentity identity) {
        Path release = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("release")
                .normalize();
        requireWithinOutputRoot(release);
        return release;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("release-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinOutputRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new ReleaseOutputStoreConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReleaseProvenance provenance)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("release-provenance.json"),
                MAPPER.writeValueAsString(provenance), StandardCharsets.UTF_8);
    }
}
```

This is `FilesystemApprovedSnapshotWorkspace`'s exact post-Task-4 shape, with `"release"`/`"release-staging-"`/`"release-provenance.json"` in place of `"approved"`/`"approved-staging-"`/`"references.json"`, and `ReleaseOutputStoreConfinementException` as its own distinct type (cannot reuse `approved`'s package-scoped type across packages).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemReleaseOutputStoreTest`
Expected: PASS — 5 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing. This test class is this slice's in-memory-then-real adapter contract pair, together with Task 3's `NullReleaseOutputStoreTest` — both remain comfortably under the plan's one-second in-memory-acceptance-subset budget.

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/release/FilesystemReleaseOutputStore.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStoreConfinementException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseOutputStore.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/release/FilesystemReleaseOutputStoreTest.java
git commit -m "feat(publication-exporter): add FilesystemReleaseOutputStore real adapter"
```

---

### Task 6: `ReleaseResult` — the CLI-facing result type

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/ReleaseResult.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/ReleaseResultTest.java`

**Interfaces:**
- Consumes: `ReleaseProvenance` (Task 2), `PublicationIdentity`.
- Produces: `ReleaseResult.released(PublicationIdentity, ReleaseProvenance): ReleaseResult`, `ReleaseResult.blocked(String message): ReleaseResult`, `#ok()`, `#identity()`, `#provenance()`, `#message()` — consumed by Task 7 (`BuildFromReviewHandler`) and Task 8 (`BuildFromReviewCommand`).

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.buildfromreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.release.ReleaseProvenance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseResultTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void releasedSerializesOkTrueWithIdentityAndProvenanceAndNoMessage() throws Exception {
        ReleaseResult result = ReleaseResult.released(IDENTITY, PROVENANCE);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals(PROVENANCE, result.provenance());
        assertNull(result.message());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(true, json.get("ok").asBoolean());
        assertEquals("my-essay", json.get("identity").get("publicId").asText());
        assertTrue(json.has("provenance"));
        assertTrue(json.get("message").isNull());
    }

    @Test
    void blockedSerializesOkFalseWithMessageAndNoIdentityOrProvenance() throws Exception {
        ReleaseResult result = ReleaseResult.blocked("No approved snapshot exists to release.");

        assertFalse(result.ok());
        assertNull(result.identity());
        assertNull(result.provenance());
        assertEquals("No approved snapshot exists to release.", result.message());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(false, json.get("ok").asBoolean());
        assertTrue(json.get("identity").isNull());
        assertTrue(json.get("provenance").isNull());
        assertEquals("No approved snapshot exists to release.", json.get("message").asText());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReleaseResultTest`
Expected: FAIL — compile error, `ReleaseResult` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.buildfromreview;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.release.ReleaseProvenance;

public final class ReleaseResult {

    private final boolean ok;
    private final PublicationIdentity identity;
    private final ReleaseProvenance provenance;
    private final String message;

    private ReleaseResult(boolean ok, PublicationIdentity identity, ReleaseProvenance provenance, String message) {
        this.ok = ok;
        this.identity = identity;
        this.provenance = provenance;
        this.message = message;
    }

    public static ReleaseResult released(PublicationIdentity identity, ReleaseProvenance provenance) {
        return new ReleaseResult(true, identity, provenance, null);
    }

    public static ReleaseResult blocked(String message) {
        return new ReleaseResult(false, null, null, message);
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("provenance")
    public ReleaseProvenance provenance() {
        return provenance;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }
}
```

No `equals`/`hashCode` — `ReleaseResult` is a one-shot CLI output value, never stored in a collection or compared, unlike `ReleaseProvenance`/`CandidateSnapshot`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReleaseResultTest`
Expected: PASS — 2 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/ReleaseResult.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/ReleaseResultTest.java
git commit -m "feat(publication-exporter): add ReleaseResult value type"
```

---

### Task 7: `BuildFromReviewHandler` — the real behavioural slice

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java`

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace#read(...)` (Task 1), `ReleaseOutputStore#install(...)` (Task 3/5), `ReleaseProvenance.of(...)` (Task 2), `ReleaseResult.released/blocked(...)` (Task 6), `ContentHash.sha256Hex(...)` (existing, S05).
- Produces: `BuildFromReviewHandler(ApprovedSnapshotWorkspace, ReleaseOutputStore)`, `#buildFromReview(PublicationIdentity): ReleaseResult` — consumed by Task 8 (`BuildFromReviewCommand`).

This is where REL-01's "Candidate differs from approved snapshot" / "Selected publication lacks a safe approved snapshot" and REL-02's "Approved snapshot has no semantic occurrences" and REL-03's determinism obligation all become observable.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.buildfromreview;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.NullReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFromReviewHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void noApprovedSnapshotIsBlockedBeforeAnyOutputWrite() {
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                ApprovedSnapshotWorkspace.createNull(), ReleaseOutputStore.createNull());

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertFalse(result.ok());
        assertEquals("No approved snapshot exists to release.", result.message());
    }

    @Test
    void approvedSnapshotIsReleasedWithMatchingApprovedAndOutputHashes() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        String ruHash = ContentHash.sha256Hex("# My Essay");
        String enHash = ContentHash.sha256Hex("# My Essay (EN)");
        approvedSnapshotWorkspace.install(IDENTITY, "# My Essay", "# My Essay (EN)",
                ReferenceMap.empty(IDENTITY, ruHash, enHash));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals(1, result.provenance().contractEdition());
        assertEquals(ruHash, result.provenance().approvedRuHash());
        assertEquals(enHash, result.provenance().approvedEnHash());
        assertEquals(ruHash, result.provenance().outputRuHash());
        assertEquals(enHash, result.provenance().outputEnHash());
        assertEquals(0, result.provenance().activationCount());
        assertEquals(0, result.provenance().deactivationCount());
        assertTrue(releaseOutputStore.installed().containsKey(IDENTITY));
        assertEquals("# My Essay", releaseOutputStore.installed().get(IDENTITY).ruBody());
        assertEquals("# My Essay (EN)", releaseOutputStore.installed().get(IDENTITY).enBody());
    }

    @Test
    void anExistingCandidateIsNeverConsultedOrReflectedInOutput() {
        // BuildFromReviewHandler takes no CandidateWorkspace collaborator at all — REL-01's
        // "candidate has no release authority" is enforced by the constructor's own shape,
        // not by a runtime check. There is no candidate parameter to ignore.
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(IDENTITY, "approved RU", "approved EN",
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("approved RU"), ContentHash.sha256Hex("approved EN")));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

        handler.buildFromReview(IDENTITY);

        assertEquals("approved RU", releaseOutputStore.installed().get(IDENTITY).ruBody());
    }

    @Test
    void aSecondReleaseAttemptForTheSameIdentityIsBlocked() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(IDENTITY, "RU", "EN",
                ReferenceMap.empty(IDENTITY, ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN")));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);
        handler.buildFromReview(IDENTITY);

        ReleaseResult second = handler.buildFromReview(IDENTITY);

        assertFalse(second.ok());
        assertEquals("A release already exists at this output root; replacing it is not yet supported.",
                second.message());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BuildFromReviewHandlerTest`
Expected: FAIL — compile error, `BuildFromReviewHandler` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.buildfromreview;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.release.ReleaseAlreadyExistsException;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseProvenance;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class BuildFromReviewHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final ReleaseOutputStore releaseOutputStore;

    public BuildFromReviewHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ReleaseOutputStore releaseOutputStore) {
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.releaseOutputStore = Objects.requireNonNull(releaseOutputStore, "releaseOutputStore");
    }

    public ReleaseResult buildFromReview(PublicationIdentity identity) {
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        if (approved.isEmpty()) {
            return noApprovedSnapshotResult();
        }
        return releaseApprovedSnapshot(identity, approved.get());
    }

    private ReleaseResult releaseApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        ReleaseProvenance provenance = provenanceFor(identity, approved);
        try {
            releaseOutputStore.install(identity, approved.ruBody(), approved.enBody(), provenance);
        } catch (ReleaseAlreadyExistsException raceLoser) {
            return alreadyReleasedResult();
        } catch (UncheckedIOException failure) {
            return ReleaseResult.blocked("Release installation failed.");
        }
        return ReleaseResult.released(identity, provenance);
    }

    private static ReleaseProvenance provenanceFor(PublicationIdentity identity, CandidateSnapshot approved) {
        String outputRuHash = ContentHash.sha256Hex(approved.ruBody());
        String outputEnHash = ContentHash.sha256Hex(approved.enBody());
        return ReleaseProvenance.of(identity,
                approved.referenceMap().ruHash(), approved.referenceMap().enHash(),
                outputRuHash, outputEnHash);
    }

    private static ReleaseResult noApprovedSnapshotResult() {
        return ReleaseResult.blocked("No approved snapshot exists to release.");
    }

    private static ReleaseResult alreadyReleasedResult() {
        return ReleaseResult.blocked("A release already exists at this output root; replacing it is not yet supported.");
    }
}
```

`buildFromReview(...)` stays a Composed Method table of contents (read approved snapshot → compute provenance → install) per `/applying-sbpp`, matching `PrepareHandler#prepare`'s, `InspectPublicationHandler#inspect`'s, and `MarkReviewedHandler#markReviewed`'s existing shape — the same one-dominant-public-method departure from heuristic 3.9 already accepted for those three classes. `outputRuHash`/`outputEnHash` are computed from `approved.ruBody()`/`approved.enBody()` directly — the exact bytes handed to `releaseOutputStore.install(...)` — not copied from `approved.referenceMap()`, per design.md D4.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BuildFromReviewHandlerTest`
Expected: PASS — 4 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java
git commit -m "feat(publication-exporter): add BuildFromReviewHandler"
```

---

### Task 8: `BuildFromReviewCommand` — CLI wiring, subcommand registration, and the acceptance test

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/BuildFromReviewCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/BuildFromReviewCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `BuildFromReviewHandler(ApprovedSnapshotWorkspace, ReleaseOutputStore)` (Task 7), `ApprovedSnapshotWorkspace.create(Path)` (existing), `ReleaseOutputStore.create(Path)` (Task 5).

This is the slice's system-boundary acceptance test — the real CLI, a real approved-store filesystem root, and a real fresh output root, with no fakes anywhere in the test.

- [ ] **Step 1: Write the failing test**

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

class BuildFromReviewCliAcceptanceTest {

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
        Path outputRoot = workRoot.resolve("output");

        int exitCode = buildFromReview(workRoot.resolve("review"), outputRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertEquals("No approved snapshot exists to release.", result.get("message").asText());
        assertTrue(Files.notExists(outputRoot));
    }

    @Test
    void approvedSnapshotProducesReleasedResultAndWritesBothEssayFilesPlusProvenance() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path outputRoot = workRoot.resolve("output");
        String ruHash = installApprovedSnapshot(reviewDirectory, "# My Essay", "# My Essay (EN)");

        int exitCode = buildFromReview(reviewDirectory, outputRoot);

        assertEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(true, result.get("ok").asBoolean());
        assertEquals("my-essay", result.get("identity").get("publicId").asText());
        assertEquals(1, result.get("provenance").get("contractEdition").asInt());
        assertEquals(ruHash, result.get("provenance").get("approvedRuHash").asText());
        assertEquals(ruHash, result.get("provenance").get("outputRuHash").asText());
        assertTrue(result.get("message").isNull());

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("# My Essay", Files.readString(releaseDir.resolve("ru.md")));
        assertEquals("# My Essay (EN)", Files.readString(releaseDir.resolve("en.md")));
        assertTrue(Files.readString(releaseDir.resolve("release-provenance.json")).contains("\"contractEdition\":1"));
    }

    private String installApprovedSnapshot(Path reviewDirectory, String ruBody, String enBody) {
        String ruHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(ruBody);
        String enHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(enBody);
        ApprovedSnapshotWorkspace.create(reviewDirectory)
                .install(IDENTITY, ruBody, enBody, ReferenceMap.empty(IDENTITY, ruHash, enHash));
        return ruHash;
    }

    private int buildFromReview(Path reviewDirectory, Path outputRoot) {
        return new CommandLine(new Main()).execute(
                "build-from-review",
                "--review", reviewDirectory.toString(),
                "--output", outputRoot.toString(),
                "--collection", "blog",
                "--content-type", "essay",
                "--id", "my-essay");
    }

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(),
                    () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BuildFromReviewCliAcceptanceTest`
Expected: FAIL — `build-from-review` is not a recognized subcommand (`BuildFromReviewCommand` and its `Main` registration do not exist yet)

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "build-from-review")
public final class BuildFromReviewCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--output", required = true)
    Path outputRoot;

    @Option(names = "--collection", required = true)
    String collection;

    @Option(names = "--content-type", required = true)
    String contentType;

    @Option(names = "--id", required = true)
    String publicId;

    @Override
    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ReleaseOutputStore releaseOutputStore = ReleaseOutputStore.create(outputRoot);
        PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
        ReleaseResult result = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore)
                .buildFromReview(identity);

        System.out.println(new ObjectMapper().writeValueAsString(result));
        return result.ok() ? 0 : 1;
    }
}
```

Unlike every other command, `build-from-review` takes no `--vault`/`--note`/`--jobs` — there is no note to admit, only an already-approved snapshot keyed by identity (design.md D6).

Update `Main.java`'s subcommand list:

```java
@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class })
public final class Main implements Runnable {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BuildFromReviewCliAcceptanceTest`
Expected: PASS — 2 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/BuildFromReviewCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/BuildFromReviewCliAcceptanceTest.java
git commit -m "feat(publication-exporter): wire build-from-review CLI command"
```

---

### Task 9: Full verification pass

**Files:** none (verification only)

- [ ] **Step 1: Run the complete `publication-exporter` suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (baseline 228 + this slice's new tests across Tasks 1-8)

- [ ] **Step 2: Run the obsidian-plugin conformance suite**

Run: `cd obsidian-plugin && node --test tests/*.test.cjs`
Expected: all tests passing except the one pre-existing, unrelated `community-plugins.json` environment-dependent skip that predates this slice — `build-from-review` is not a bridge command (design.md Context, evidence 2) and adds no new plugin-consumed surface, so this suite is unaffected by this slice's own changes.

- [ ] **Step 3: Validate the OpenSpec change**

Run: `openspec validate --changes "s06-materialize-approved-essay" --strict`
Expected: `✓ change/s06-materialize-approved-essay`

- [ ] **Step 4: Confirm the working tree is clean and every task's commit is present**

Run: `git log --oneline -8` and `git status --porcelain=v1`
Expected: 8 feature/refactor commits from this plan on top of the previous slice's final commit (Tasks 1-8; Task 9 has no code changes to commit), clean tree

- [ ] **Step 5: Report readiness for review**

Do not close Haft problem `prob-20260806-e107746a` or archive this OpenSpec change here — that happens after
the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the
final GPT-5.6 Sol max-effort review) confirms the slice is complete.
