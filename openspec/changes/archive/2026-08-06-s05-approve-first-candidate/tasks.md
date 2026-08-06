# S05 — Approve the First Candidate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mark-reviewed --json`, given the S04-reviewed candidate, revalidates it is still exact (source unchanged, candidate files unchanged since `prepare`) and installs it as the first durable approved snapshot, returning `ok: true`, `status: "ready_to_publish"` only once durable; a stale candidate returns `status: "stale"`; a second approval attempt, a missing candidate, or an inadmissible note each return `status: "metadata_blocked"` with a diagnostic.

**Architecture:** One new production adapter pair (`ApprovedSnapshotWorkspace`, an in-memory fake proven first then a real create-only filesystem adapter), reusing `CandidateWorkspace`'s exact proven conventions but kept as its own port per `design.md` D1. `CandidateWorkspace` gains a second read method, `read(identity): Optional<CandidateSnapshot>` (content, not just paths) — its second interface change, so every existing implementor/test-double is updated in one task/commit, not split (per memory `feedback-java-interface-change-task-planning`). `ReferenceMapCodec` gains a `read` method so `references.json` becomes legible again. A small `ContentHash` utility is extracted from `PrepareHandler`'s existing private `sha256Hex` so `MarkReviewedHandler` doesn't duplicate it. `BridgeResponse` gains two new factories (`approved`, `stale`) and reuses `blocked` unchanged for admission/no-candidate/already-approved failures.

**Tech Stack:** Same as S01-S04 — Java 17, Maven, picocli, Jackson, com.networknt:json-schema-validator, JUnit Jupiter, obsidian-plugin's Node `node --test`. **No `pom.xml` change in this slice** — every new type uses only `java.nio.file`/`java.security`/`java.util`, already available.

## Global Constraints

- Requirements introduced: RVA-05 (real delta, `specs/review-and-approval/spec.md` — the new "A second approval is attempted" scenario), RVA-03, RVA-04, SEM-03, BRG-01 (all four scope pins, `scope-pins.md`) — no other requirement is pulled in.
- `publication-exporter/pom.xml` is not modified in this slice.
- Functional collaborative-design decisions (binding, do not re-litigate): RVA-05 gets a new scenario for the second-approval-attempt case; RVA-03/04, SEM-03, BRG-01 are pure scope pins with no text changes.
- Technical collaborative-design decisions (binding, do not re-litigate): (1) the approved snapshot gets its own new port, `ApprovedSnapshotWorkspace` — not an extension of `CandidateWorkspace` (design.md D1). (2) A successful approval reports `status: "ready_to_publish"`; a revalidation failure reports `status: "stale"` — both adopt BRG-05's eventual six-state vocabulary ahead of its formal S11 introduction, the same precedent S04 set for `"ready_for_review"` (D6). (3) No read-back-and-verify step after `ApprovedSnapshotWorkspace#install`'s atomic move — the atomic-move guarantee alone is relied on, matching `CandidateWorkspace#install`'s already-reviewed convention (D9).
- `/nullables`: `ApprovedSnapshotWorkspace` gets `create()`/`createNull()` factories from the start, in-memory fake proven before the real adapter; no mocking library anywhere in this plan.
- `/applying-sbpp`: every new value type (`CandidateSnapshot`) is built via a named Constructor Method with a `private` constructor — never bare `new` from outside its own package/class, matching `CandidatePaths`/`PublicationIdentity`/`ReferenceMap` precedent (do NOT convert any of these to `record`s). `MarkReviewedHandler#markReviewed` is a Composed Method table of contents, mirroring `PrepareHandler#prepare`'s and `InspectPublicationHandler#inspect`'s existing shape.
- `/oo-design-guide`: `ApprovedSnapshotWorkspace` and `CandidateWorkspace` stay two separate interfaces (design.md D1) — candidate and approved lifecycles are distinct abstractions that will diverge further at S09, not converge. `MarkReviewedHandler` keeps the same one-dominant-public-method heuristic-3.9 departure already established for `PrepareHandler`/`InspectPublicationHandler`.
- **Interface-change discipline** (memory `feedback-java-interface-change-task-planning`): `CandidateWorkspace` has exactly four known implementors/test-doubles today — `NullCandidateWorkspace`, `FilesystemCandidateWorkspace` (both `src/main`), and two anonymous-class test doubles (`InspectPublicationHandlerTest.candidateWorkspaceThrowing(...)` at line 220, `PrepareHandlerTest`'s `failingWorkspace` at line 282). Task 3 below updates all four in one commit — do not split it. Introducing the brand-new `ApprovedSnapshotWorkspace` interface (Task 5) has no such constraint: a new interface with only one initial implementor is never "incomplete" the way adding a method to an *existing, already-implemented* interface is — Task 6 (the real adapter) may safely follow as its own commit.
- Out of scope for S05 — do not implement: replacing an existing approved snapshot, crash recovery after a replacement starts, release generation, per-publication exclusion locking under real contention (S09's), `inspect-publication` reporting the new approved-snapshot state (not one of S05's introduced requirements), non-empty reference-map occurrence validation (SEM-02/PCM-03, S13/S19).
- Governance: implements Haft problem `prob-20260805-3d747bed`; do not close it or archive this OpenSpec change until the final task's full verification pass is green.

---

### Task 1: `ReferenceMapCodec#read(...)` — make `references.json` legible again

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`

**Interfaces:**
- Produces: `ReferenceMapCodec.read(String json): ReferenceMap` — consumed by Task 3 (`FilesystemCandidateWorkspace#read`).

- [x] **Step 1: Write the failing tests (append to `ReferenceMapCodecTest`)**

```java
    @Test
    void readReturnsTheIdentityAndHashesTheJsonCarries() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String json = ReferenceMapCodec.write(ReferenceMap.empty(identity, "ru-hash", "en-hash"));

        ReferenceMap parsed = ReferenceMapCodec.read(json);

        assertEquals(identity, parsed.identity());
        assertEquals("ru-hash", parsed.ruHash());
        assertEquals("en-hash", parsed.enHash());
        assertTrue(parsed.occurrences().isEmpty());
    }

    @Test
    void writeThenReadRoundTripsToAnEqualMap() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap original = ReferenceMap.empty(identity, "ru-hash", "en-hash");

        ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

        assertEquals(original, roundTripped);
    }
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReferenceMapCodecTest`
Expected: FAIL — compile error, `ReferenceMapCodec.read` is undefined

- [x] **Step 3: Write minimal implementation**

Replace the whole `ReferenceMapCodec.java` file:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class ReferenceMapCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReferenceMapCodec() {
    }

    public static String write(ReferenceMap referenceMap) {
        try {
            return MAPPER.writeValueAsString(referenceMap);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }

    public static ReferenceMap read(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode identityNode = root.get("publicationIdentity");
            PublicationIdentity identity = PublicationIdentity.of(
                    identityNode.get("publicCollection").asText(),
                    identityNode.get("publicContentType").asText(),
                    identityNode.get("publicId").asText());
            return ReferenceMap.empty(identity, root.get("ruHash").asText(), root.get("enHash").asText());
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReferenceMapCodecTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java
git commit -m "feat(publication-exporter): add ReferenceMapCodec#read"
```

---

### Task 2: `CandidateSnapshot` — a content-bearing Whole Value

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidateSnapshotTest.java`

**Interfaces:**
- Produces: `CandidateSnapshot.of(String ruBody, String enBody, ReferenceMap referenceMap): CandidateSnapshot`, `#ruBody()`, `#enBody()`, `#referenceMap()` — consumed by Task 3 (`CandidateWorkspace#read`) and Task 8 (`MarkReviewedHandler`).

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateSnapshotTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReferenceMap REFERENCE_MAP = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

    @Test
    void accessorsReturnConstructedValues() {
        CandidateSnapshot snapshot = CandidateSnapshot.of("RU body", "EN body", REFERENCE_MAP);

        assertEquals("RU body", snapshot.ruBody());
        assertEquals("EN body", snapshot.enBody());
        assertEquals(REFERENCE_MAP, snapshot.referenceMap());
    }

    @Test
    void equalSnapshotsBuiltSeparatelyAreEqual() {
        assertEquals(
                CandidateSnapshot.of("RU", "EN", REFERENCE_MAP),
                CandidateSnapshot.of("RU", "EN", REFERENCE_MAP));
    }

    @Test
    void ruBodyIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidateSnapshot.of(null, "EN", REFERENCE_MAP));
        assertEquals("ruBody", exception.getMessage());
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CandidateSnapshotTest`
Expected: FAIL — compile error, `CandidateSnapshot` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.Objects;

public final class CandidateSnapshot {

    private final String ruBody;
    private final String enBody;
    private final ReferenceMap referenceMap;

    private CandidateSnapshot(String ruBody, String enBody, ReferenceMap referenceMap) {
        this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
        this.enBody = Objects.requireNonNull(enBody, "enBody");
        this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
    }

    public static CandidateSnapshot of(String ruBody, String enBody, ReferenceMap referenceMap) {
        return new CandidateSnapshot(ruBody, enBody, referenceMap);
    }

    public String ruBody() {
        return ruBody;
    }

    public String enBody() {
        return enBody;
    }

    public ReferenceMap referenceMap() {
        return referenceMap;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidateSnapshot that)) {
            return false;
        }
        return ruBody.equals(that.ruBody) && enBody.equals(that.enBody) && referenceMap.equals(that.referenceMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruBody, enBody, referenceMap);
    }

    @Override
    public String toString() {
        return "CandidateSnapshot[ruBody=" + ruBody + ", enBody=" + enBody + ", referenceMap=" + referenceMap + "]";
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CandidateSnapshotTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidateSnapshotTest.java
git commit -m "feat(publication-exporter): add CandidateSnapshot value type"
```

---

### Task 3: `CandidateWorkspace#read(...)` — every implementor and test double, one commit

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Consumes: `CandidateSnapshot.of(...)` (Task 2), `ReferenceMapCodec.read(...)` (Task 1).
- Produces: `CandidateWorkspace#read(PublicationIdentity): Optional<CandidateSnapshot>` — consumed by Task 8 (`MarkReviewedHandler`).

**This is the interface-change task.** All four known implementors/test-doubles are updated here in one commit — see this plan's Global Constraints. Do not commit a partial subset.

- [x] **Step 1: Write the failing tests**

Append to `NullCandidateWorkspaceTest`:

```java
    @Test
    void readIsAbsentBeforeAnyInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }
```

Append to `FilesystemCandidateWorkspaceTest`:

```java
    @Test
    void readIsAbsentBeforeInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentityAfterInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }
```

Add `import java.util.Optional;` to `NullCandidateWorkspaceTest` if not already present (it is, from S04's `find()` tests), and confirm `FilesystemCandidateWorkspaceTest` already imports `Optional` (it does, from S04's `find()` tests).

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest`
Expected: FAIL — compile error, `read` is undefined on both classes

- [x] **Step 3: Write minimal implementation**

In `CandidateWorkspace.java`, add the method to the interface:

```java
    Optional<CandidateSnapshot> read(PublicationIdentity identity);
```

placed directly after the existing `find(...)` declaration.

In `NullCandidateWorkspace.java`, add:

```java
    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity)
                .map(candidate -> CandidateSnapshot.of(candidate.ruBody(), candidate.enBody(), candidate.referenceMap()));
    }
```

placed directly after `find(...)`, reusing the existing private `lastInstalledMatching` helper `find` already uses.

In `FilesystemCandidateWorkspace.java`, add:

```java
    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path candidateDirectory = candidateDirectory(identity);
        Path ruPath = candidateDirectory.resolve("ru.md");
        Path enPath = candidateDirectory.resolve("en.md");
        Path referencesPath = candidateDirectory.resolve("references.json");
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

placed directly after `find(...)`. No new imports are needed — `ReferenceMap` and `ReferenceMapCodec` are both already imported in this file.

Fix the two existing test doubles so the module compiles again:

In `InspectPublicationHandlerTest.java`, add a `read` override to `candidateWorkspaceThrowing(...)` (the anonymous class at line ~220) — this test double exercises only `find`'s failure path, so `read` mirrors the same thrown failure for consistency:

```java
            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
```

Add `import dev.eugene.publicationexporter.candidate.CandidateSnapshot;` to this file.

In `PrepareHandlerTest.java`, add a `read` override to the anonymous `failingWorkspace` (at line ~282) — this test double only exercises `install`'s failure path, so `read` returns absent (never called by `PrepareHandler`, but must compile):

```java
            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                return Optional.empty();
            }
```

Add `import dev.eugene.publicationexporter.candidate.CandidateSnapshot;` to this file.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest,InspectPublicationHandlerTest,PrepareHandlerTest`
Expected: PASS, 0 failures across all four classes

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS — every pre-existing test across the whole module still passes unchanged

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(publication-exporter): add CandidateWorkspace#read with both adapters and test doubles"
```

---

### Task 4: `ContentHash` — extract the shared SHA-256 helper

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/hash/ContentHashTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

**Interfaces:**
- Produces: `ContentHash.sha256Hex(String content): String` — consumed by Task 8 (`MarkReviewedHandler`) and by this task's own refactor of `PrepareHandler`.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.hash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentHashTest {

    @Test
    void sha256HexProducesTheKnownDigestOfAnEmptyString() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentHash.sha256Hex(""));
    }

    @Test
    void sha256HexProducesTheKnownDigestOfAbc() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ContentHash.sha256Hex("abc"));
    }

    @Test
    void sha256HexIsDeterministic() {
        assertEquals(ContentHash.sha256Hex("same content"), ContentHash.sha256Hex("same content"));
    }
}
```

Both digests above are the real, verified SHA-256 hex digests (64 hex characters each) for `""` and `"abc"` — computed via `printf '' | shasum -a 256` and `printf 'abc' | shasum -a 256`. Use them as written.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ContentHashTest`
Expected: FAIL — compile error, `ContentHash` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", impossible);
        }
    }
}
```

Now remove the private `sha256Hex` method from `PrepareHandler.java` (it currently sits near the bottom of the file, after `ioFailureMessage`) and replace its one call site:

```java
        ReferenceMap referenceMap = ReferenceMap.empty(identity, sha256Hex(ruBody), sha256Hex(enBody));
```

becomes:

```java
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity, ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody));
```

Add `import dev.eugene.publicationexporter.hash.ContentHash;` to `PrepareHandler.java` and remove its now-unused `java.security.MessageDigest`, `java.security.NoSuchAlgorithmException`, and `java.util.HexFormat` imports (keep `java.nio.charset.StandardCharsets` only if another line in the file still uses it — check before removing).

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ContentHashTest,PrepareHandlerTest`
Expected: PASS — `ContentHashTest` 3/3; `PrepareHandlerTest` unchanged pass count, no behavior change

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (this is a pure refactor — every existing `ReferenceMap` hash value `PrepareHandler` produces must stay byte-identical)

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/hash/ContentHashTest.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java
git commit -m "refactor(publication-exporter): extract ContentHash from PrepareHandler's private sha256Hex"
```

---

### Task 5: `ApprovedSnapshotWorkspace` — new port and in-memory fake

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotAlreadyExistsException.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java`

**Interfaces:**
- Consumes: `CandidatePaths.of(Path, Path)` (existing, `dev.eugene.publicationexporter.candidate`), `PublicationIdentity`, `ReferenceMap`.
- Produces: `ApprovedSnapshotWorkspace.install(...)`, `#find(identity): Optional<CandidatePaths>`, `ApprovedSnapshotWorkspace.createNull()` — consumed by Task 6 (real adapter, same contract) and Task 8 (`MarkReviewedHandler`).

**Correction found during implementation:** the interface must NOT declare `static ApprovedSnapshotWorkspace create(Path reviewRoot)` in this task — that factory method's body would reference `FilesystemApprovedSnapshotWorkspace`, which does not exist until Task 6, so the module would not compile. `create(Path)` is added in Task 6, in the same commit as the class it instantiates. This task's interface declares `install(...)`, `find(...)`, and `createNull()` only.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullApprovedSnapshotWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void findIsAbsentBeforeAnyInstall() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void installThenFindReturnsPathsEndingInRuMdAndEnMd() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);
        Optional<dev.eugene.publicationexporter.candidate.CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertThrows(ApprovedSnapshotAlreadyExistsException.class,
                () -> workspace.install(IDENTITY, "RU body 2", "EN body 2", referenceMap));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyWorkspace() {
        ApprovedSnapshotWorkspace workspace = ApprovedSnapshotWorkspace.createNull();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest`
Expected: FAIL — compile error, none of these classes exist yet

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class ApprovedSnapshotAlreadyExistsException extends IllegalStateException {

    public ApprovedSnapshotAlreadyExistsException(PublicationIdentity identity) {
        super("An approved snapshot already exists for " + identity);
    }
}
```

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.Optional;

public interface ApprovedSnapshotWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    static ApprovedSnapshotWorkspace createNull() {
        return new NullApprovedSnapshotWorkspace();
    }
}
```

Note: `Path` is still imported for use elsewhere (`find`/`install` don't need it directly, but keep the import only if your IDE/compiler flags it unused — if so, remove `import java.nio.file.Path;` from this file in this task; Task 6 will need to re-add it when it adds `create(Path reviewRoot)` to this same interface).

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NullApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Map<PublicationIdentity, ReferenceMap> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");
        if (installed.containsKey(identity)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
        installed.put(identity, referenceMap);
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!installed.containsKey(identity)) {
            return Optional.empty();
        }
        Path approvedDirectory = Path.of("/approved", identity.publicCollection(), identity.publicId(), "approved");
        return Optional.of(CandidatePaths.of(approvedDirectory.resolve("ru.md"), approvedDirectory.resolve("en.md")));
    }
}
```

Note: `NullApprovedSnapshotWorkspace` deliberately does not keep the installed RU/EN body text at all (unlike `NullCandidateWorkspace.InstalledCandidate`) — this slice's fake only needs to prove `install`-then-`find` and the create-only guard; nothing in this slice's acceptance tests reads approved body content back out.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullApprovedSnapshotWorkspaceTest`
Expected: PASS — 4 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotAlreadyExistsException.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java
git commit -m "feat(publication-exporter): add ApprovedSnapshotWorkspace port with in-memory fake"
```

---

### Task 6: `FilesystemApprovedSnapshotWorkspace` — real create-only adapter

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspaceConfinementException.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace` (Task 5, same contract proven by the fake).
- Produces: real-adapter `install`/`find`, plus `ApprovedSnapshotWorkspace.create(Path)` (added in this task, see the correction note on Task 5) — consumed by Task 8/9.

**Also in this task:** add `create(Path)` back to the interface, in the same commit as the class it references:

```java
    static ApprovedSnapshotWorkspace create(Path reviewRoot) {
        return new FilesystemApprovedSnapshotWorkspace(reviewRoot);
    }
```

placed directly after `install(...)`/`find(...)` and before `createNull()` (matching `CandidateWorkspace`'s existing method order); add back `import java.nio.file.Path;` to `ApprovedSnapshotWorkspace.java` if Task 5 removed it.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemApprovedSnapshotWorkspaceTest {

    @TempDir
    Path reviewRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(approvedDir.resolve("en.md")));
        assertTrue(Files.readString(approvedDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void findIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsAbsolutePathsToTheInstalledFiles() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals(approvedDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsAndLeavesTheFirstSnapshotIntact() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertThrows(ApprovedSnapshotAlreadyExistsException.class,
                () -> workspace.install(IDENTITY, "RU body 2", "EN body 2", referenceMap));

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("approved-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity, "RU", "EN",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(freshRoot));
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemApprovedSnapshotWorkspaceTest`
Expected: FAIL — compile error, `FilesystemApprovedSnapshotWorkspace` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.approved;

import java.nio.file.Path;

public final class ApprovedSnapshotWorkspaceConfinementException extends IllegalStateException {

    ApprovedSnapshotWorkspaceConfinementException(Path candidate, Path resolvedCandidate, Path reviewRoot) {
        super("Approved directory escapes review root: " + candidate
                + " resolved to " + resolvedCandidate + " outside " + reviewRoot);
    }
}
```

```java
package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Path canonicalReviewRoot;

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this.canonicalReviewRoot = canonicalize(Objects.requireNonNull(reviewRoot, "reviewRoot"));
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
            publishStagingApproved(staging, destination);
        } catch (IOException error) {
            deleteRecursively(staging);
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

    private void publishStagingApproved(Path staging, Path destination) throws IOException {
        requireWithinReviewRoot(destination);
        Files.createDirectories(destination.getParent());
        requireWithinReviewRoot(destination);
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path approvedDirectory(PublicationIdentity identity) {
        Path approved = canonicalReviewRoot.resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("approved")
                .normalize();
        requireWithinReviewRoot(approved);
        return approved;
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(canonicalReviewRoot);
            return Files.createTempDirectory(canonicalReviewRoot, "approved-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        if (!candidate.startsWith(canonicalReviewRoot)) {
            throw new ApprovedSnapshotWorkspaceConfinementException(
                    candidate, candidate, canonicalReviewRoot);
        }
        if (Files.notExists(canonicalReviewRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path resolvedCandidate = resolveThroughNearestExistingAncestor(candidate);
        Path resolvedReviewRoot = realPathOf(canonicalReviewRoot).orElse(canonicalReviewRoot);
        if (!resolvedCandidate.startsWith(resolvedReviewRoot)) {
            throw new ApprovedSnapshotWorkspaceConfinementException(
                    candidate, resolvedCandidate, resolvedReviewRoot);
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

    private static Path canonicalize(Path reviewRoot) {
        return realPathOf(reviewRoot).orElseGet(() -> reviewRoot.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(FilesystemApprovedSnapshotWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort staging cleanup after a failed install
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

This is `FilesystemCandidateWorkspace`'s exact shape (design.md D1) with two differences: the create-only `Files.exists(destination)` guard before staging (D5), and `"approved"` in place of `"candidate"` as the leaf directory/staging-prefix name. Duplication is accepted per design.md's own Risk note — extracting a shared base now would be premature abstraction from two data points.

Update `CandidateWorkspaceConfinementException`'s test... — no, this file is untouched; only note that `ApprovedSnapshotWorkspaceConfinementException` is a distinct type in the `approved` package (cannot reuse `candidate`'s package-private-constructor exception across packages).

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemApprovedSnapshotWorkspaceTest`
Expected: PASS — 7 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspaceConfinementException.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java
git commit -m "feat(publication-exporter): add FilesystemApprovedSnapshotWorkspace real adapter"
```

---

### Task 7: `BridgeResponse.approved(...)` and `.stale(...)` — new factories, additive only

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java`

**Interfaces:**
- Produces: `BridgeResponse.approved(String command, PublicationIdentity identity): BridgeResponse`, `BridgeResponse.stale(String command, List<Diagnostic>|Diagnostic diagnostics): BridgeResponse` — consumed by Task 8 (`MarkReviewedHandler`).

No existing call site changes — both factories are brand new, delegating to the existing private canonical constructor exactly as `prepared(...)`/`translationFailed(...)` already do.

- [x] **Step 1: Write the failing tests**

Append to `BridgeResponseJsonTest`:

```java
    @Test
    void approvedResponseSerializesToLeanShapeWithReadyToPublishStatus() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.approved("mark-reviewed", identity);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("ready_to_publish", parsed.get("status").asText());
        assertEquals("my-essay", parsed.get("identity").get("publicId").asText());
        assertEquals(0, parsed.get("diagnostics").size());
        assertFalse(parsed.has("candidateState"));
        assertFalse(parsed.has("reviewPlan"));
    }

    @Test
    void staleResponseCarriesTheStaleStatusAndDiagnostics() throws Exception {
        BridgeResponse response = BridgeResponse.stale(
                "mark-reviewed", Diagnostic.blocking("candidate", "Source note has changed since preparation."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("stale", parsed.get("status").asText());
        assertEquals(1, parsed.get("diagnostics").size());
        assertFalse(parsed.has("identity"));
    }
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: FAIL — compile error, `BridgeResponse.approved`/`.stale` are undefined

- [x] **Step 3: Write minimal implementation**

Add to `BridgeResponse.java`, directly after the existing `translationFailed(...)` overloads:

```java
    public static BridgeResponse approved(String command, PublicationIdentity identity) {
        return new BridgeResponse(2, command, true, "ready_to_publish",
                List.of(), List.of(), Objects.requireNonNull(identity, "identity"),
                null, null, null, null, null);
    }

    public static BridgeResponse stale(String command, Diagnostic diagnostic) {
        return stale(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse stale(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "stale",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: PASS — 12 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java
git commit -m "feat(publication-exporter): add BridgeResponse.approved and .stale factories"
```

---

### Task 8: `MarkReviewedHandler` — the real behavioural slice

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`

**Interfaces:**
- Consumes: `NoteIntake.admit(...)` (existing), `CandidateWorkspace#read(...)` (Task 3), `ApprovedSnapshotWorkspace#install/find(...)` (Task 5/6), `ContentHash.sha256Hex(...)` (Task 4), `BridgeResponse.approved/stale/blocked(...)` (Task 7, plus existing `blocked`).
- Produces: `MarkReviewedHandler(CandidateWorkspace, ApprovedSnapshotWorkspace)`, `#markReviewed(VaultRelativePath, VaultReader): BridgeResponse` — consumed by Task 9 (`MarkReviewedCommand`).

This is where RVA-03's "Operator approves an exact candidate", RVA-04's "Candidate remains exact"/"Candidate or source changed", and RVA-05's "Approval completes"/"A second approval is attempted" all become observable.

- [x] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkReviewedHandlerTest {

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # My Essay""";

    private static final String ESSAY_BODY = "# My Essay";

    @Test
    void unsafePathIsBlocked() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(
                VaultRelativePath.of("../../etc/passwd.md"), VaultReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
    }

    @Test
    void noCandidateIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("No candidate exists to approve.", response.diagnostics().get(0).message());
    }

    @Test
    void exactCandidateIsApproved() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String enHash = ContentHash.sha256Hex("EN body");
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", ReferenceMap.empty(identity, ruHash, enHash));
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        MarkReviewedHandler handler = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_to_publish", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertTrue(approvedSnapshotWorkspace.find(identity).isPresent());
    }

    @Test
    void alreadyApprovedIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String enHash = ContentHash.sha256Hex("EN body");
        ReferenceMap referenceMap = ReferenceMap.empty(identity, ruHash, enHash);
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", referenceMap);
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(identity, ESSAY_BODY, "EN body", referenceMap);
        MarkReviewedHandler handler = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("An approved snapshot already exists; replacing it is not yet supported.",
                response.diagnostics().get(0).message());
    }

    @Test
    void sourceChangedSinceCandidateWasPreparedIsStale() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        // Candidate was prepared from a DIFFERENT body than the source note now has.
        String staleRuHash = ContentHash.sha256Hex("# An old version of My Essay");
        String enHash = ContentHash.sha256Hex("EN body");
        candidateWorkspace.install(identity, "# An old version of My Essay", "EN body",
                ReferenceMap.empty(identity, staleRuHash, enHash));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("stale", response.status());
    }

    @Test
    void candidateFileTamperedWithSincePreparationIsStale() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        // referenceMap records a hash for DIFFERENT English content than what's actually installed —
        // simulates en.md having been overwritten after prepare recorded its hash.
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String staleEnHash = ContentHash.sha256Hex("original EN body prepare recorded");
        candidateWorkspace.install(identity, ESSAY_BODY, "tampered EN body",
                ReferenceMap.empty(identity, ruHash, staleEnHash));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("stale", response.status());
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedHandlerTest`
Expected: FAIL — compile error, `MarkReviewedHandler` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotAlreadyExistsException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MarkReviewedHandler {

    private static final String COMMAND = "mark-reviewed";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;

    public MarkReviewedHandler(CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse markReviewed(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return markReviewedAdmittedEssay(intake.identity(), intake.body());
    }

    private BridgeResponse markReviewedAdmittedEssay(PublicationIdentity identity, String sourceBody) {
        Optional<CandidateSnapshot> candidate = candidateWorkspace.read(identity);
        if (candidate.isEmpty()) {
            return noCandidateResponse();
        }
        if (approvedSnapshotWorkspace.find(identity).isPresent()) {
            return alreadyApprovedResponse();
        }
        List<Diagnostic> staleness = stalenessDiagnostics(sourceBody, candidate.get());
        if (!staleness.isEmpty()) {
            return BridgeResponse.stale(COMMAND, staleness);
        }
        return installApprovedSnapshot(identity, candidate.get());
    }

    private List<Diagnostic> stalenessDiagnostics(String sourceBody, CandidateSnapshot candidate) {
        ReferenceMap referenceMap = candidate.referenceMap();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!ContentHash.sha256Hex(sourceBody).equals(referenceMap.ruHash())) {
            diagnostics.add(Diagnostic.blocking("candidate",
                    "Source note has changed since the candidate was prepared."));
        }
        if (!ContentHash.sha256Hex(candidate.ruBody()).equals(referenceMap.ruHash())) {
            diagnostics.add(Diagnostic.blocking("candidate",
                    "Candidate Russian body has changed since it was prepared."));
        }
        if (!ContentHash.sha256Hex(candidate.enBody()).equals(referenceMap.enHash())) {
            diagnostics.add(Diagnostic.blocking("candidate",
                    "Candidate English body has changed since it was prepared."));
        }
        return diagnostics;
    }

    private BridgeResponse installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot candidate) {
        try {
            approvedSnapshotWorkspace.install(
                    identity, candidate.ruBody(), candidate.enBody(), candidate.referenceMap());
        } catch (ApprovedSnapshotAlreadyExistsException raceLoser) {
            return alreadyApprovedResponse();
        } catch (UncheckedIOException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("candidate", "Approved installation failed."));
        }
        return BridgeResponse.approved(COMMAND, identity);
    }

    private static BridgeResponse noCandidateResponse() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("candidate", "No candidate exists to approve."));
    }

    private static BridgeResponse alreadyApprovedResponse() {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate",
                "An approved snapshot already exists; replacing it is not yet supported."));
    }
}
```

`markReviewed(...)` stays a Composed Method table of contents (admit → read candidate → check not-already-approved → revalidate → install) per `/applying-sbpp`, matching `PrepareHandler#prepare`'s and `InspectPublicationHandler#inspect`'s existing shape — the same one-dominant-public-method departure from heuristic 3.9 already accepted for those two classes.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedHandlerTest`
Expected: PASS — 6 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java
git commit -m "feat(publication-exporter): add MarkReviewedHandler"
```

---

### Task 9: `MarkReviewedCommand` — CLI wiring and subcommand registration

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `MarkReviewedHandler(CandidateWorkspace, ApprovedSnapshotWorkspace)` (Task 8), `CandidateWorkspace.create(Path)`/`ApprovedSnapshotWorkspace.create(Path)` (existing/Task 5-6).

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkReviewedCliAcceptanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path vaultRoot;

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
    void unsafeNotePathProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = markReviewed("../../etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("mark-reviewed", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
    }

    @Test
    void noCandidateProducesBlockedSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);

        int exitCode = markReviewed("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("No candidate exists to approve.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void exactCandidateProducesApprovedSchemaV2ResponseAndInstallsTheApprovedSnapshot() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);
        Path reviewDirectory = vaultRoot.resolve("review");
        installExactCandidate(reviewDirectory);

        int exitCode = markReviewed("blog/my-essay.md");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_to_publish", response.get("status").asText());
        assertEquals("my-essay", response.get("identity").get("publicId").asText());
        assertTrue(Files.exists(reviewDirectory.resolve("blog/my-essay/approved/ru.md")));
    }

    private void installExactCandidate(Path reviewDirectory) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String ruBody = "# My Essay";
        String enBody = "# My Essay (EN)";
        String ruHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(ruBody);
        String enHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(enBody);
        CandidateWorkspace.create(reviewDirectory)
                .install(identity, ruBody, enBody, ReferenceMap.empty(identity, ruHash, enHash));
    }

    private int markReviewed(String notePath) {
        return new CommandLine(new Main()).execute(
                "mark-reviewed",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");
    }

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # My Essay""";

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(),
                    () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }

    private void assertConformsToSchemaV2(JsonNode response) throws Exception {
        Set<ValidationMessage> errors = schemaV2().validate(response);
        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    private JsonSchema schemaV2() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(Files.newInputStream(SCHEMA_PATH));
    }
}
```

Note: `installExactCandidate` writes a candidate whose `ru.md`/`en.md` content and recorded reference-map hashes are all mutually consistent, and whose RU body (`"# My Essay"`) matches `VALID_ESSAY`'s frontmatter-stripped body exactly — this is what makes the third test's approval succeed rather than come back `"stale"`.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedCliAcceptanceTest`
Expected: FAIL — `mark-reviewed` is not a recognized subcommand (`MarkReviewedCommand` and its `Main` registration do not exist yet)

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "mark-reviewed")
public final class MarkReviewedCommand implements Callable<Integer> {

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--note", required = true)
    String notePath;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--jobs", required = true)
    Path jobsDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        BridgeResponse response = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace)
                .markReviewed(VaultRelativePath.of(notePath), vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

`--jobs` is required (matching what `bridge-client.js` always sends for `mark-reviewed`) but unused in this slice's `call()` body — identical to `PrepareCommand`'s own current treatment.

Update `Main.java`'s subcommand list:

```java
@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class })
public final class Main implements Runnable {
```

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=MarkReviewedCliAcceptanceTest`
Expected: PASS — 3 tests, 0 failures

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java
git commit -m "feat(publication-exporter): wire mark-reviewed CLI command"
```

---

### Task 10: Extend schema-v2 conformance for `mark-reviewed`'s new response shapes

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`
- Modify: `obsidian-plugin/tests/schema-conformance.test.cjs`

**Interfaces:**
- Consumes: `BridgeResponse.approved(...)`/`.stale(...)` (Task 7).

`bridge-contract/schema-v2.json` needs no content changes: `status` is already an unconstrained free-form string (no enum), and `command`'s enum already lists `"mark-reviewed"` (declared ahead of implementation, same precedent every other command's enum entry already followed). This task is fixture-only, proving the new shapes conform to the existing contract — mirroring S04 Task 12/S03's precedent for lean, no-state-field response shapes.

- [x] **Step 1: Write the failing tests**

Append to `SchemaConformanceTest.java`:

```java
    @Test
    void approvedResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.approved(
                "mark-reviewed", PublicationIdentity.of("blog", "essay", "my-essay"));

        assertConformsToSchemaV2(response);
    }

    @Test
    void staleResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.stale("mark-reviewed",
                Diagnostic.blocking("candidate", "Source note has changed since the candidate was prepared."));

        assertConformsToSchemaV2(response);
    }
```

(`PublicationIdentity` and `Diagnostic` are in the same `bridge` package as this test class — no new imports needed.)

Append to `obsidian-plugin/tests/schema-conformance.test.cjs`, directly after `translationFailedFixture()`:

```javascript
function approvedFixture() {
  return {
    schemaVersion: 2,
    command: "mark-reviewed",
    ok: true,
    status: "ready_to_publish",
    identity: { publicCollection: "blog", publicContentType: "essay", publicId: "my-essay" },
    diagnostics: [],
    workspaceHealth: [],
  };
}

function staleFixture() {
  return {
    schemaVersion: 2,
    command: "mark-reviewed",
    ok: false,
    status: "stale",
    diagnostics: [
      { field: "candidate", message: "Source note has changed since the candidate was prepared.", blocking: true },
    ],
    workspaceHealth: [],
  };
}

test("approved fixture conforms to bridge-contract/schema-v2.json", () => {
  const errors = validateAgainstSchema(loadSchema(), approvedFixture());
  assert.deepEqual(errors, []);
});

test("stale fixture conforms to bridge-contract/schema-v2.json", () => {
  const errors = validateAgainstSchema(loadSchema(), staleFixture());
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant approved response", async () => {
  const fixture = approvedFixture();
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 0 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("mark-reviewed", "blog/my-essay.md");
  assert.deepEqual(result, fixture);
});
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: FAIL — compile error, the two new test methods reference nothing undefined (both factories already exist from Task 7) — this step should actually compile and PASS immediately; if so, note in the report that this step's "RED" is vacuous (the schema already tolerates every shape `BridgeResponse` can produce, matching S04 Task 9's same observation) and proceed to Step 4 directly

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: FAIL only if the new fixtures/tests are malformed JS syntax — otherwise also vacuously green immediately, same reasoning

- [x] **Step 3: No production code changes required**

This task is fixture-only, matching Task 9's own note in S04's plan: `BridgeResponse.approved`/`.stale` (Task 7) and `bridge-client.js`'s existing `mark-reviewed` command entry (`{ note: true, jobs: true }`, already present) already exist; this task only proves the new response shapes conform and that the plugin's real client accepts them.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS — 7 tests, 0 failures

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: PASS — all tests including the 3 new ones

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java \
        obsidian-plugin/tests/schema-conformance.test.cjs
git commit -m "test(publication-exporter,obsidian-plugin): extend schema-v2 conformance for mark-reviewed"
```

---

### Task 11: Full verification pass

**Files:** none (verification only)

- [x] **Step 1: Run the complete `publication-exporter` suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (baseline 181 + this slice's new tests across Tasks 1-10)

- [x] **Step 2: Run the obsidian-plugin conformance suite**

Run: `cd obsidian-plugin && node --test tests/*.test.cjs`
Expected: all tests passing except the one pre-existing, unrelated `community-plugins.json` environment-dependent skip (predates this slice; see the S04-slice fix commit `df70a49` for why it's a skip, not a fail)

- [x] **Step 3: Validate the OpenSpec change**

Run: `openspec validate s05-approve-first-candidate --strict`
Expected: `Change 's05-approve-first-candidate' is valid`

- [x] **Step 4: Confirm the working tree is clean and every task's commit is present**

Run: `git log --oneline -11` and `git status --porcelain=v1`
Expected: 10 feature/refactor/test commits from this plan on top of the previous slice's final commit (Tasks 1-10; Task 11 has no code changes to commit), clean tree

- [x] **Step 5: Report readiness for review**

Do not close Haft problem `prob-20260805-3d747bed` or archive this OpenSpec change here — that happens after
the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`, and the
final GPT-5.6 Sol max-effort review) confirms the slice is complete.
