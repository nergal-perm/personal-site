<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: nullable adapters with
  create()/createNull() factory pairs (ApprovedSnapshotWorkspace.createNull(), CandidateWorkspace.createNull()
  are the existing pattern — match it exactly for every new port), immutable value types with private
  constructors + static factories (PublicationIdentity, ReferenceMap, ActivationMarker are the pattern),
  Optional over null for "maybe absent" (never a bare null return), guard clauses over nested conditionals,
  Composed Method (small single-purpose private methods, each body reads as a table of contents), package-
  private visibility by default (public only where a different package needs the type), stateless static
  utility classes for pure transformation logic (LinkResolver, OccurrenceMarkerResolver, and this plan's own
  SchemaActivationGuard — a deliberate, codebase-consistent departure from Elegant Objects' "avoid static
  methods" heuristic, matching the codebase's own established idiom over the general pattern). No comments in
  production code beyond what non-obvious rationale demands — this file's own comments are plan scaffolding,
  not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, design.md, specs/legacy-transition/spec.md
  — all in openspec/changes/s21-read-only-legacy-diagnosis/. design.md's Decisions map directly onto Tasks 1-4
  below; read it first if anything here is unclear on *why*, not just *what*.
- Additive-overload discipline: `PrepareHandler` and `BuildFromReviewHandler` each have roughly 90-100 existing
  test construction call sites plus one CLI call site. Tasks 5 and 6 add a NEW overload constructor to each,
  taking one extra `ActivationMarkerStore` parameter; the existing shorter constructor is untouched and
  delegates to the new one with `ActivationMarkerStore.createNull()` (no marker, so the guard is a pure no-op
  for every existing empty-workspace test — see design.md's Decision on legacy-shaped detection). Do not modify
  any existing call site as part of this plan unless a task explicitly says to (only the two CLI command files
  need updating, to pass a real filesystem-backed store).
- New package: `dev.eugene.publicationexporter.legacy` holds every new type in this plan (`ActivationMarker`,
  `ActivationMarkerStore` + its two implementations, `SchemaActivationCheck`, `SchemaActivationGuard`,
  `LegacyWorkspaceInventory`, `LegacyWorkspaceInventoryHandler`).
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
  `SemanticSchemaState.java`'s `Mode` concept and marker-file shape are reused as a validated convention, not
  as imported code; this slice's own `ActivationMarker` is deliberately smaller (drops `catalogSha256` — see
  design.md).
- Whole prior acceptance suite (903 tests as of this slice's baseline, 2026-08-18, `mvn -q test` exits 0) must
  stay green after every task's full-suite step. If anything outside this task's own new/modified tests turns
  red, stop and investigate before continuing — do not proceed past an unexplained regression.
- Governed by Haft problem prob-20260818-40bccb11. Do not archive the OpenSpec change or touch Haft artifacts
  from this task list — those steps are owned by the orchestrating session, not an implementer.
-->

# S21 — Read-only legacy diagnosis: implementation plan

**Goal:** An explicitly invoked, read-only inventory reports a legacy workspace's approved/candidate content,
ambiguities, and identity gaps without mutating anything; normal `prepare`/`build-from-review` fail closed with
migration-required evidence, before any mutation, whenever they observe approved or candidate content with no
valid current-schema activation marker. A workspace with no content at all (every existing acceptance test's
shape) is unaffected.

**Architecture:** A new `legacy` package introduces a read-only `ActivationMarker`/`ActivationMarkerStore` pair
(nullable adapter: `NullActivationMarkerStore`, `FilesystemActivationMarkerStore`) recording whether a review
workspace has been activated for the current semantic schema. `ApprovedSnapshotWorkspace` and
`CandidateWorkspace` each gain an `allIdentities()` enumeration. A stateless `SchemaActivationGuard` combines
marker state and content presence into a `SchemaActivationCheck`; `PrepareHandler` and `BuildFromReviewHandler`
each gain an additive-overload constructor accepting an `ActivationMarkerStore` and call the guard as the very
first step of their entry point. A `LegacyWorkspaceInventoryHandler` produces a deterministic
`LegacyWorkspaceInventory` report from the same `allIdentities()` enumeration, entirely separately from the
guard (diagnosis and blocking are two different reads of the same underlying state, never conflated into one
type).

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson (`ObjectMapper` with `STRICT_DUPLICATE_DETECTION`, matching
`ReferenceMapCodec`'s existing style), this project's existing nullable-object test doubles — no mocking
library.

**Spec:** openspec/changes/s21-read-only-legacy-diagnosis/proposal.md,
openspec/changes/s21-read-only-legacy-diagnosis/design.md,
openspec/changes/s21-read-only-legacy-diagnosis/specs/legacy-transition/spec.md

## Global Constraints

(see HTML comment block above — this repo's convention keeps machine-readable constraints there so they
travel with the file into archive/ unedited; both blocks say the same thing)

---

## Task 1: `ActivationMarker` value type

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarker.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/ActivationMarkerTest.java`

**Interfaces:**
- Produces: `ActivationMarker(int schemaVersion, String inventorySha256, Instant activatedAt)` — public record,
  compact constructor requiring non-null `inventorySha256`/`activatedAt`.
- Produces: `boolean ActivationMarker.isValid()` — `true` only when `schemaVersion == 1` and `inventorySha256`
  matches `^[0-9a-f]{64}$`.
- Consumes (Task 2): none yet — this is a leaf value type.
- Consumes (Task 4): `ActivationMarker.isValid()`, from `SchemaActivationGuard`.

- [ ] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivationMarkerTest {

    private static final String VALID_SHA256 =
            "a".repeat(64);

    @Test
    void isValidForACorrectlyShapedMarker() {
        ActivationMarker marker = new ActivationMarker(1, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertTrue(marker.isValid());
    }

    @Test
    void isInvalidForAnUnsupportedSchemaVersion() {
        ActivationMarker marker = new ActivationMarker(2, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForAMalformedInventoryHash() {
        ActivationMarker marker = new ActivationMarker(1, "not-a-sha256", Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void rejectsNullInventorySha256() {
        assertThrows(NullPointerException.class,
                () -> new ActivationMarker(1, null, Instant.parse("2026-08-18T00:00:00Z")));
    }

    @Test
    void rejectsNullActivatedAt() {
        assertThrows(NullPointerException.class, () -> new ActivationMarker(1, VALID_SHA256, null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd publication-exporter && mvn -q -Dtest=ActivationMarkerTest test`
Expected: FAIL — compile error, `dev.eugene.publicationexporter.legacy.ActivationMarker` does not exist.

- [ ] **Step 3: Implement `ActivationMarker`**

```java
package dev.eugene.publicationexporter.legacy;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ActivationMarker(int schemaVersion, String inventorySha256, Instant activatedAt) {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public ActivationMarker {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }

    public boolean isValid() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && SHA256.matcher(inventorySha256).matches();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd publication-exporter && mvn -q -Dtest=ActivationMarkerTest test`
Expected: PASS

- [ ] **Step 5: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 903+ tests (the added tests bring the count up), 0 failures.

- [ ] **Step 6: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarker.java \
        src/test/java/dev/eugene/publicationexporter/legacy/ActivationMarkerTest.java
git commit -m "feat: add ActivationMarker value type"
```

---

## Task 2: `ActivationMarkerStore` — nullable port with Null/Filesystem implementations

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarkerStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/NullActivationMarkerStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/NullActivationMarkerStoreTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStoreTest.java`

**Interfaces:**
- Produces: `Optional<ActivationMarker> ActivationMarkerStore.read()`.
- Produces: `ActivationMarkerStore.create(Path reviewRoot)`, `ActivationMarkerStore.createNull()`,
  `ActivationMarkerStore.createNull(ActivationMarker preset)`.
- Consumes (Task 1): `ActivationMarker`.
- Consumes (Task 4): `ActivationMarkerStore.read()`, from `SchemaActivationGuard`.

- [ ] **Step 1: Write the failing tests**

```java
// NullActivationMarkerStoreTest.java
package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NullActivationMarkerStoreTest {

    @Test
    void bareCreateNullHasNoMarker() {
        ActivationMarkerStore store = ActivationMarkerStore.createNull();

        assertEquals(Optional.empty(), store.read());
    }

    @Test
    void createNullWithAPresetReturnsIt() {
        ActivationMarker preset = new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));
        ActivationMarkerStore store = ActivationMarkerStore.createNull(preset);

        assertEquals(Optional.of(preset), store.read());
    }
}
```

```java
// FilesystemActivationMarkerStoreTest.java
package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemActivationMarkerStoreTest {

    @Test
    void readIsAbsentWhenNoMarkerFileExists(@TempDir Path reviewRoot) {
        ActivationMarkerStore store = ActivationMarkerStore.create(reviewRoot);

        assertEquals(Optional.empty(), store.read());
    }

    @Test
    void readParsesAValidMarkerFile(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, """
                {"schemaVersion":1,"inventorySha256":"%s","activatedAt":"2026-08-18T00:00:00Z"}
                """.formatted("a".repeat(64)));

        Optional<ActivationMarker> marker = ActivationMarkerStore.create(reviewRoot).read();

        assertTrue(marker.isPresent());
        assertTrue(marker.get().isValid());
    }

    @Test
    void readIsAbsentForMalformedJson(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, "not json");

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    @Test
    void readIsAbsentWhenRequiredFieldsAreMissing(@TempDir Path reviewRoot) throws IOException {
        writeMarker(reviewRoot, "{\"schemaVersion\":1}");

        assertEquals(Optional.empty(), ActivationMarkerStore.create(reviewRoot).read());
    }

    private static void writeMarker(Path reviewRoot, String json) throws IOException {
        Path markerFile = reviewRoot.resolve(".migration").resolve("schema-v1.active.json");
        Files.createDirectories(markerFile.getParent());
        Files.writeString(markerFile, json, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=NullActivationMarkerStoreTest,FilesystemActivationMarkerStoreTest test`
Expected: FAIL — `ActivationMarkerStore` does not exist.

- [ ] **Step 3: Implement the interface**

```java
package dev.eugene.publicationexporter.legacy;

import java.nio.file.Path;
import java.util.Optional;

public interface ActivationMarkerStore {

    Optional<ActivationMarker> read();

    static ActivationMarkerStore create(Path reviewRoot) {
        return new FilesystemActivationMarkerStore(reviewRoot);
    }

    static ActivationMarkerStore createNull() {
        return new NullActivationMarkerStore(Optional.empty());
    }

    static ActivationMarkerStore createNull(ActivationMarker preset) {
        return new NullActivationMarkerStore(Optional.of(preset));
    }
}
```

- [ ] **Step 4: Implement `NullActivationMarkerStore`**

```java
package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.Optional;

final class NullActivationMarkerStore implements ActivationMarkerStore {

    private final Optional<ActivationMarker> marker;

    NullActivationMarkerStore(Optional<ActivationMarker> marker) {
        this.marker = Objects.requireNonNull(marker, "marker");
    }

    @Override
    public Optional<ActivationMarker> read() {
        return marker;
    }
}
```

- [ ] **Step 5: Implement `FilesystemActivationMarkerStore`**

Follow `ReferenceMapCodec`'s existing `ObjectMapper` style exactly (`new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)`).

```java
package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class FilesystemActivationMarkerStore implements ActivationMarkerStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Path markerFile;

    FilesystemActivationMarkerStore(Path reviewRoot) {
        this.markerFile = Objects.requireNonNull(reviewRoot, "reviewRoot")
                .resolve(".migration").resolve("schema-v1.active.json");
    }

    @Override
    public Optional<ActivationMarker> read() {
        if (!Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(markerFile, StandardCharsets.UTF_8));
            return markerFrom(root);
        } catch (IOException | RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<ActivationMarker> markerFrom(JsonNode root) {
        JsonNode schemaVersion = root.get("schemaVersion");
        JsonNode inventorySha256 = root.get("inventorySha256");
        JsonNode activatedAt = root.get("activatedAt");
        if (schemaVersion == null || inventorySha256 == null || activatedAt == null) {
            return Optional.empty();
        }
        return Optional.of(new ActivationMarker(
                schemaVersion.asInt(), inventorySha256.asText(), Instant.parse(activatedAt.asText())));
    }
}
```

`read()` never throws — a missing file, malformed JSON, missing field, or an unparseable `activatedAt` all
yield `Optional.empty()` (an ordinary, expected "no valid marker" outcome for this slice, not an I/O error;
`Instant.parse`'s `DateTimeParseException` is a `RuntimeException`, caught by the same broad catch as JSON
parse failures — this is deliberate, not sloppy: every failure mode here means the same thing to a caller,
"no valid marker").

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=NullActivationMarkerStoreTest,FilesystemActivationMarkerStoreTest test`
Expected: PASS

- [ ] **Step 7: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [ ] **Step 8: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarkerStore.java \
        src/main/java/dev/eugene/publicationexporter/legacy/NullActivationMarkerStore.java \
        src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java \
        src/test/java/dev/eugene/publicationexporter/legacy/NullActivationMarkerStoreTest.java \
        src/test/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStoreTest.java
git commit -m "feat: add read-only ActivationMarkerStore port"
```

---

## Task 3: `allIdentities()` on `ApprovedSnapshotWorkspace` and `CandidateWorkspace`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java` (create if it does not already exist — check first)
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`

**Interfaces:**
- Produces: `List<PublicationIdentity> ApprovedSnapshotWorkspace.allIdentities()` — sorted, deterministic.
- Produces: `List<PublicationIdentity> CandidateWorkspace.allIdentities()` — sorted, deterministic.
- Consumes (Task 4): both, from `SchemaActivationGuard`.
- Consumes (Task 7): both, from `LegacyWorkspaceInventoryHandler`.

- [ ] **Step 1: Write the failing tests**

```java
// NullApprovedSnapshotWorkspaceTest.java — add alongside existing tests
@Test
void allIdentitiesReturnsEveryInstalledIdentitySorted() {
    NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
    PublicationIdentity zebra = PublicationIdentity.of("blog", "essay", "zebra");
    PublicationIdentity apple = PublicationIdentity.of("blog", "essay", "apple");
    workspace.install(zebra, someSnapshot(zebra));
    workspace.install(apple, someSnapshot(apple));

    assertEquals(List.of(apple, zebra), workspace.allIdentities());
}

@Test
void allIdentitiesIsEmptyForAFreshWorkspace() {
    assertEquals(List.of(), new NullApprovedSnapshotWorkspace().allIdentities());
}
```

Reuse this test class's existing helper for building a minimal `CandidateSnapshot` for one identity (search for
however other tests in this file construct one via `ReferenceMap.of`/`CandidateSnapshot.of`; do not invent a
second construction path).

```java
// FilesystemApprovedSnapshotWorkspaceTest.java — add alongside the existing findBySourceId tests
@Test
void allIdentitiesEnumeratesEveryApprovedDirectorySorted() {
    // Reuse this test class's existing fixture for installing two approved snapshots under different
    // identities (locate the helper it already uses for findBySourceId's tests).
    installApprovedSnapshot(PublicationIdentity.of("blog", "essay", "zebra"));
    installApprovedSnapshot(PublicationIdentity.of("blog", "essay", "apple"));

    List<PublicationIdentity> identities = workspace.allIdentities();

    assertEquals(List.of(
            PublicationIdentity.of("blog", "essay", "apple"),
            PublicationIdentity.of("blog", "essay", "zebra")), identities);
}

@Test
void allIdentitiesIsEmptyForAFreshReviewRoot() {
    assertEquals(List.of(), workspace.allIdentities());
}
```

```java
// NullCandidateWorkspaceTest.java — create if it does not exist; model on NullApprovedSnapshotWorkspaceTest
@Test
void allIdentitiesReturnsEveryInstalledIdentitySorted() {
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PublicationIdentity zebra = PublicationIdentity.of("blog", "essay", "zebra");
    PublicationIdentity apple = PublicationIdentity.of("blog", "essay", "apple");
    workspace.install(zebra, someSnapshot(zebra), List.of());
    workspace.install(apple, someSnapshot(apple), List.of());

    assertEquals(List.of(apple, zebra), workspace.allIdentities());
}

@Test
void allIdentitiesIsEmptyForAFreshWorkspace() {
    assertEquals(List.of(), new NullCandidateWorkspace().allIdentities());
}
```

```java
// FilesystemCandidateWorkspaceTest.java — add alongside existing install/read tests
@Test
void allIdentitiesEnumeratesEveryCandidateDirectorySorted() {
    // Reuse this test class's existing install fixture for two identities.
    installCandidate(PublicationIdentity.of("blog", "essay", "zebra"));
    installCandidate(PublicationIdentity.of("blog", "essay", "apple"));

    assertEquals(List.of(
            PublicationIdentity.of("blog", "essay", "apple"),
            PublicationIdentity.of("blog", "essay", "zebra")), workspace.allIdentities());
}

@Test
void allIdentitiesIsEmptyForAFreshReviewRoot() {
    assertEquals(List.of(), workspace.allIdentities());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest,NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest test`
Expected: FAIL — compile error, `allIdentities()` does not exist on either interface.

- [ ] **Step 3: Add the interface methods**

```java
// ApprovedSnapshotWorkspace.java — add alongside the existing findBySourceId(String) method
List<PublicationIdentity> allIdentities();
```

```java
// CandidateWorkspace.java — add alongside the existing read(PublicationIdentity) method
List<PublicationIdentity> allIdentities();
```

- [ ] **Step 4: Implement in `NullApprovedSnapshotWorkspace`**

```java
@Override
public List<PublicationIdentity> allIdentities() {
    return installed.keySet().stream()
            .sorted(Comparator.comparing(PublicationIdentity::toString))
            .toList();
}
```

Add `import java.util.Comparator;` and `import java.util.List;` if not already present (`List` is already
imported by this class for `find`'s callers today — check before adding a duplicate import).

Sorting by `PublicationIdentity::toString` is intentional: `PublicationIdentity` has no `Comparable`
implementation and none of its three fields alone is a stable, collision-free sort key on its own (two
identities can share a `publicId` across different collections) — `toString()`'s existing
`publicCollection`/`publicContentType`/`publicId` concatenation is already the closest thing to a canonical
string form this class has, and is sufficient for deterministic ordering (MIG-02's determinism requirement),
not for display.

- [ ] **Step 5: Implement in `FilesystemApprovedSnapshotWorkspace`**

Reuse the existing private `candidateDirectoriesInOrder()` helper (already returns approved directories sorted
by collection then identity, per its existing `Files.list(...).sorted()` calls) — do not duplicate the walk.

```java
@Override
public List<PublicationIdentity> allIdentities() {
    return candidateDirectoriesInOrder().stream()
            .map(this::readReferenceMapOrUnchecked)
            .map(ReferenceMap::identity)
            .toList();
}
```

`candidateDirectoriesInOrder()` already walks collections then identities in sorted directory order (its
existing two `.sorted()` calls over `Files.list(...)`), so this list is already deterministic without an
additional sort step — verify this by reading the method's current body (approved/FilesystemApprovedSnapshotWorkspace.java)
before assuming it; if a future reader changes that method's ordering guarantee, this method's own determinism
test (Step 1) will catch the regression.

- [ ] **Step 6: Implement in `NullCandidateWorkspace`**

```java
@Override
public List<PublicationIdentity> allIdentities() {
    return installed.stream()
            .map(InstalledCandidate::identity)
            .distinct()
            .sorted(Comparator.comparing(PublicationIdentity::toString))
            .toList();
}
```

`installed` is a `List<InstalledCandidate>` here (not a `Map`, unlike the approved workspace — this class
allows re-installing the same identity, keeping every install call, per its existing `lastInstalledMatching`
logic) — `.distinct()` before sorting is required so a re-installed identity appears once, not once per
install call.

- [ ] **Step 7: Implement in `FilesystemCandidateWorkspace`**

Read this class's existing directory layout first (`candidateDirectory(PublicationIdentity)` and how
`install`/`read` locate identity directories under the review root — mirror `FilesystemApprovedSnapshotWorkspace`'s
`candidateDirectoriesInOrder()` shape exactly, adapted to whatever subdirectory name this class uses instead of
`"approved"` — check the class for its own directory-name constant before assuming `"candidate"`). Add a
private `candidateDirectoriesInOrder()` enumeration mirroring the approved workspace's pattern (sorted
`Files.list` over collection, then identity), and:

```java
@Override
public List<PublicationIdentity> allIdentities() {
    return candidateDirectoriesInOrder().stream()
            .map(this::readReferenceMapOrUnchecked)
            .map(ReferenceMap::identity)
            .toList();
}
```

(Name the private helper methods to match whatever this class's existing `readReferenceMap`-equivalent method
is already called — read the file first; do not assume `readReferenceMapOrUnchecked` exists here already, it
is `FilesystemApprovedSnapshotWorkspace`'s name, not necessarily this class's.)

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest,NullCandidateWorkspaceTest,FilesystemCandidateWorkspaceTest test`
Expected: PASS

- [ ] **Step 9: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures. (Adding a new interface method with no default implementation is a compile-time
check that every implementor was updated — if this doesn't compile, you missed one.)

- [ ] **Step 10: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        src/test/java/dev/eugene/publicationexporter/approved/ \
        src/test/java/dev/eugene/publicationexporter/candidate/
git commit -m "feat: add allIdentities() enumeration to both workspace ports"
```

---

## Task 4: `SchemaActivationCheck` and `SchemaActivationGuard`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/SchemaActivationCheck.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuard.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuardTest.java`

**Interfaces:**
- Produces: `SchemaActivationCheck.current()`, `SchemaActivationCheck.legacy(String blockingReason)`;
  `boolean requiresMigration()`; `String blockingReason()` (throws `NoSuchElementException` if called on a
  `current()` check — mirrors `Optional.orElseThrow()`'s contract, not a new error style).
- Produces: `SchemaActivationGuard.check(ApprovedSnapshotWorkspace, ActivationMarkerStore): SchemaActivationCheck`
  — approved-content-only overload, for release.
- Produces: `SchemaActivationGuard.check(ApprovedSnapshotWorkspace, CandidateWorkspace, ActivationMarkerStore): SchemaActivationCheck`
  — approved-and-candidate overload, for prepare.
- Consumes (Task 1, 2, 3): `ActivationMarker.isValid()`, `ActivationMarkerStore.read()`,
  `ApprovedSnapshotWorkspace.allIdentities()`, `CandidateWorkspace.allIdentities()`.
- Consumes (Task 5, 6): both `check` overloads, from `PrepareHandler` and `BuildFromReviewHandler`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaActivationGuardTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "target");

    @Test
    void emptyWorkspaceWithNoMarkerIsCurrent() {
        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace(), ActivationMarkerStore.createNull());

        assertFalse(check.requiresMigration());
    }

    @Test
    void approvedContentWithNoMarkerIsLegacy() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull());

        assertTrue(check.requiresMigration());
    }

    @Test
    void candidateContentWithNoMarkerIsLegacy() {
        CandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, someSnapshot(), List.of());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), candidate, ActivationMarkerStore.createNull());

        assertTrue(check.requiresMigration());
    }

    @Test
    void contentWithAValidMarkerIsCurrent() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());
        ActivationMarker validMarker =
                new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull(validMarker));

        assertFalse(check.requiresMigration());
    }

    @Test
    void contentWithAnInvalidMarkerIsLegacy() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());
        ActivationMarker invalidMarker =
                new ActivationMarker(1, "not-a-sha256", Instant.parse("2026-08-18T00:00:00Z"));

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull(invalidMarker));

        assertTrue(check.requiresMigration());
    }

    @Test
    void approvedOnlyOverloadIgnoresCandidateContent() {
        CandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, someSnapshot(), List.of());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), ActivationMarkerStore.createNull());

        assertFalse(check.requiresMigration());
    }

    private static CandidateSnapshot someSnapshot() {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=SchemaActivationGuardTest test`
Expected: FAIL — `SchemaActivationCheck`/`SchemaActivationGuard` do not exist.

- [ ] **Step 3: Implement `SchemaActivationCheck`**

```java
package dev.eugene.publicationexporter.legacy;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class SchemaActivationCheck {

    private final Optional<String> blockingReason;

    private SchemaActivationCheck(Optional<String> blockingReason) {
        this.blockingReason = Objects.requireNonNull(blockingReason, "blockingReason");
    }

    public static SchemaActivationCheck current() {
        return new SchemaActivationCheck(Optional.empty());
    }

    public static SchemaActivationCheck legacy(String blockingReason) {
        return new SchemaActivationCheck(Optional.of(Objects.requireNonNull(blockingReason, "blockingReason")));
    }

    public boolean requiresMigration() {
        return blockingReason.isPresent();
    }

    public String blockingReason() {
        return blockingReason.orElseThrow(
                () -> new NoSuchElementException("No blocking reason: this workspace is current."));
    }
}
```

- [ ] **Step 4: Implement `SchemaActivationGuard`**

```java
package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;

import java.util.Objects;

public final class SchemaActivationGuard {

    private static final String BLOCKING_REASON =
            "Workspace has approved or candidate content with no valid semantic schema activation marker. "
                    + "Run the read-only migration inventory before retrying.";

    private SchemaActivationGuard() {
    }

    public static SchemaActivationCheck check(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ActivationMarkerStore activationMarkerStore) {
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        if (hasValidMarker(activationMarkerStore)) {
            return SchemaActivationCheck.current();
        }
        return approvedSnapshotWorkspace.allIdentities().isEmpty()
                ? SchemaActivationCheck.current()
                : SchemaActivationCheck.legacy(BLOCKING_REASON);
    }

    public static SchemaActivationCheck check(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, CandidateWorkspace candidateWorkspace,
            ActivationMarkerStore activationMarkerStore) {
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        if (hasValidMarker(activationMarkerStore)) {
            return SchemaActivationCheck.current();
        }
        boolean hasLegacyContent = !approvedSnapshotWorkspace.allIdentities().isEmpty()
                || !candidateWorkspace.allIdentities().isEmpty();
        return hasLegacyContent ? SchemaActivationCheck.legacy(BLOCKING_REASON) : SchemaActivationCheck.current();
    }

    private static boolean hasValidMarker(ActivationMarkerStore activationMarkerStore) {
        return activationMarkerStore.read().filter(ActivationMarker::isValid).isPresent();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=SchemaActivationGuardTest test`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [ ] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/legacy/SchemaActivationCheck.java \
        src/main/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuard.java \
        src/test/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuardTest.java
git commit -m "feat: add SchemaActivationCheck and SchemaActivationGuard"
```

---

## Task 5: Wire the guard into `PrepareHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Produces: new `PrepareHandler(NoteIntake, TranslationWorker, CandidateWorkspace, ApprovedSnapshotWorkspace,
  WorkflowStatusEditor, ActivationMarkerStore)` — additive 6-arg overload; existing 5-arg constructor untouched,
  delegates to it with `ActivationMarkerStore.createNull()`.
- Consumes (Task 2, 4): `ActivationMarkerStore`, `SchemaActivationGuard.check(ApprovedSnapshotWorkspace,
  CandidateWorkspace, ActivationMarkerStore)`.

- [ ] **Step 1: Write the failing test**

```java
// PrepareHandlerTest.java — add to the existing test class
@Test
void legacyWorkspaceBlocksPrepareBeforeAnyMutation() {
    // Reuse this test class's existing standard fixture for a plain admitted essay note (the same one
    // every other prepare-success test in this file uses), but install unmigrated approved content first
    // and construct the handler via the new 6-arg constructor with an empty ActivationMarkerStore.
    approvedSnapshotWorkspace.install(EXISTING_UNRELATED_IDENTITY, someUnrelatedApprovedSnapshot());
    PrepareHandler legacyAwareHandler = new PrepareHandler(
            noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor,
            ActivationMarkerStore.createNull());

    BridgeResponse response = legacyAwareHandler.prepare(notePath(), vaultReader(), vaultAssetReader());

    assertFalse(response.ok());
    assertTrue(candidateWorkspace.installed().isEmpty());
}

@Test
void bareFiveArgConstructorNeverBlocksOnAnEmptyWorkspace() {
    // The existing 5-arg constructor (used by every other test in this file) must keep behaving exactly
    // as before this task — this is the additive-overload contract, not new behavior for existing callers.
    BridgeResponse response = handler.prepare(notePath(), vaultReader(), vaultAssetReader());

    assertTrue(response.ok());
}
```

Read the existing test class's field names for `approvedSnapshotWorkspace`, `candidateWorkspace`, `noteIntake`,
`translationWorker`, `workflowStatusEditor`, and its standard `notePath()`/`vaultReader()`/`vaultAssetReader()`
fixture helpers before writing this — reuse them exactly, do not invent parallel ones. `EXISTING_UNRELATED_IDENTITY`
should be a `PublicationIdentity` distinct from whatever identity the standard fixture note itself resolves
to, and `someUnrelatedApprovedSnapshot()` a minimal valid `CandidateSnapshot` for it (model on Task 4's
`someSnapshot()` helper).

- [ ] **Step 2: Run tests to verify the new ones fail and the rest of the file still passes**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test`
Expected: `legacyWorkspaceBlocksPrepareBeforeAnyMutation` and `bareFiveArgConstructorNeverBlocksOnAnEmptyWorkspace`
FAIL to compile (6-arg constructor, `ActivationMarkerStore` import do not exist yet); every other test in the
file is unaffected once compilation succeeds after Step 3.

- [ ] **Step 3: Add the additive constructor and the guard call**

```java
// PrepareHandler.java
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.SchemaActivationCheck;
import dev.eugene.publicationexporter.legacy.SchemaActivationGuard;
// ... existing imports unchanged

private final ActivationMarkerStore activationMarkerStore;

public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker,
        CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
        WorkflowStatusEditor workflowStatusEditor) {
    this(noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor,
            ActivationMarkerStore.createNull());
}

public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker,
        CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
        WorkflowStatusEditor workflowStatusEditor, ActivationMarkerStore activationMarkerStore) {
    this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
    this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
    this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    this.approvedSnapshotWorkspace =
            Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    this.activationMarkerStore = Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
}
```

Change `prepare(...)`'s first line — the guard runs before `noteIntake.admit(...)`, the current first
operation:

```java
public BridgeResponse prepare(
        VaultRelativePath notePath, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
    SchemaActivationCheck activation = SchemaActivationGuard.check(
            approvedSnapshotWorkspace, candidateWorkspace, activationMarkerStore);
    if (activation.requiresMigration()) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("workspace", activation.blockingReason()));
    }
    NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);
    // ... rest of the method unchanged
```

- [ ] **Step 4: Update `PrepareCommand` to pass a real filesystem-backed store**

```java
// PrepareCommand.java
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
// ... existing imports unchanged

ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
ActivationMarkerStore activationMarkerStore = ActivationMarkerStore.create(reviewDirectory);
WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.create(vaultRoot);
TranslationWorker translationWorker = translationWorkerForJobRoot.apply(jobsDirectory);
NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
BridgeResponse response = new PrepareHandler(
        noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor,
        activationMarkerStore)
        .prepare(VaultRelativePath.of(notePath), vaultReader, vaultAssetReader);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures — every existing 5-arg `new PrepareHandler(...)` call site across ~90 test
construction sites is untouched and behaves identically (empty `Null*` workspaces + `createNull()` marker
store is always `current()`).

- [ ] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat: fail closed on a legacy-shaped workspace before prepare mutates anything"
```

---

## Task 6: Wire the guard into `BuildFromReviewHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/BuildFromReviewCommand.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java`

**Interfaces:**
- Produces: new `BuildFromReviewHandler(ApprovedSnapshotWorkspace, ReleaseOutputStore, ActivationMarkerStore)`
  — additive 3-arg overload; existing 2-arg constructor untouched, delegates to it with
  `ActivationMarkerStore.createNull()`.
- Consumes (Task 2, 4): `ActivationMarkerStore`, `SchemaActivationGuard.check(ApprovedSnapshotWorkspace,
  ActivationMarkerStore)` (the approved-only overload — `BuildFromReviewHandler` has no `CandidateWorkspace`
  collaborator today and does not gain one for this slice; release only ever concerns approved state, and a
  workspace with candidate-only legacy content but no approved content already blocks release today via the
  existing `noApprovedSnapshotResult()` path, benignly).

- [ ] **Step 1: Write the failing test**

```java
// BuildFromReviewHandlerTest.java — add to the existing test class
@Test
void legacyWorkspaceBlocksReleaseBeforeAnyMutation() {
    // Reuse this test class's existing fixture for an unrelated approved snapshot present in the
    // workspace (any identity other than the one under test) to make the workspace legacy-shaped, and
    // construct the handler via the new 3-arg constructor with an empty ActivationMarkerStore.
    approvedSnapshotWorkspace.install(EXISTING_UNRELATED_IDENTITY, someUnrelatedApprovedSnapshot());
    BuildFromReviewHandler legacyAwareHandler = new BuildFromReviewHandler(
            approvedSnapshotWorkspace, releaseOutputStore, ActivationMarkerStore.createNull());

    ReleaseResult result = legacyAwareHandler.buildFromReview(TARGET_IDENTITY);

    assertFalse(result.ok());
}

@Test
void bareTwoArgConstructorNeverBlocksOnAnEmptyWorkspace() {
    // The existing 2-arg constructor (used by every other test in this file) must keep behaving exactly
    // as before this task.
    approvedSnapshotWorkspace.install(TARGET_IDENTITY, someApprovedSnapshot());

    ReleaseResult result = handler.buildFromReview(TARGET_IDENTITY);

    assertTrue(result.ok());
}
```

Read the existing test class's field names for `approvedSnapshotWorkspace`, `releaseOutputStore`, and its
existing approved-snapshot install fixtures before writing this — reuse them exactly.

- [ ] **Step 2: Run tests to verify the new ones fail to compile**

Run: `cd publication-exporter && mvn -q -Dtest=BuildFromReviewHandlerTest test`
Expected: FAIL — 3-arg constructor and `ActivationMarkerStore` import do not exist yet.

- [ ] **Step 3: Add the additive constructor and the guard call**

```java
// BuildFromReviewHandler.java
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.SchemaActivationCheck;
import dev.eugene.publicationexporter.legacy.SchemaActivationGuard;
// ... existing imports unchanged

private final ActivationMarkerStore activationMarkerStore;

public BuildFromReviewHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ReleaseOutputStore releaseOutputStore) {
    this(approvedSnapshotWorkspace, releaseOutputStore, ActivationMarkerStore.createNull());
}

public BuildFromReviewHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ReleaseOutputStore releaseOutputStore,
        ActivationMarkerStore activationMarkerStore) {
    this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    this.releaseOutputStore = Objects.requireNonNull(releaseOutputStore, "releaseOutputStore");
    this.activationMarkerStore = Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
}
```

Change `buildFromReview(...)`'s first line:

```java
public ReleaseResult buildFromReview(PublicationIdentity identity) {
    SchemaActivationCheck activation = SchemaActivationGuard.check(approvedSnapshotWorkspace, activationMarkerStore);
    if (activation.requiresMigration()) {
        return ReleaseResult.blocked(activation.blockingReason());
    }
    Optional<CandidateSnapshot> approved;
    // ... rest of the method unchanged
```

- [ ] **Step 4: Update `BuildFromReviewCommand` to pass a real filesystem-backed store**

```java
// BuildFromReviewCommand.java
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
// ... existing imports unchanged

ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
ActivationMarkerStore activationMarkerStore = ActivationMarkerStore.create(reviewDirectory);
ReleaseOutputStore releaseOutputStore = ReleaseOutputStore.create(outputRoot);
PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
ReleaseResult result = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore, activationMarkerStore)
        .buildFromReview(identity);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=BuildFromReviewHandlerTest test`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [ ] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java \
        src/main/java/dev/eugene/publicationexporter/cli/BuildFromReviewCommand.java \
        src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java
git commit -m "feat: fail closed on a legacy-shaped workspace before release mutates anything"
```

---

## Task 7: `LegacyWorkspaceInventory` and `LegacyWorkspaceInventoryHandler`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventory.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventoryHandler.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventoryHandlerTest.java`

**Interfaces:**
- Produces: `LegacyWorkspaceInventory(List<PublicationIdentity> approvedPairs, List<PublicationIdentity>
  candidatePairs, List<String> ambiguities, List<String> blockers, String inventorySha256)` — public record.
- Produces: `LegacyWorkspaceInventoryHandler(ApprovedSnapshotWorkspace, CandidateWorkspace)`;
  `LegacyWorkspaceInventory inspect()`.
- Consumes (Task 3): `allIdentities()` from both workspace ports.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyWorkspaceInventoryHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "target");

    @Test
    void inspectingAnEmptyWorkspaceReturnsAllFourListsEmpty() {
        LegacyWorkspaceInventoryHandler handler = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(List.of(), inventory.approvedPairs());
        assertEquals(List.of(), inventory.candidatePairs());
        assertEquals(List.of(), inventory.ambiguities());
        assertEquals(List.of(), inventory.blockers());
    }

    @Test
    void inspectingIsRepeatableAndDeterministic() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithSourceId("vault-source-id-target"));
        LegacyWorkspaceInventoryHandler handler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory first = handler.inspect();
        LegacyWorkspaceInventory second = handler.inspect();

        assertEquals(first.inventorySha256(), second.inventorySha256());
        assertEquals(List.of(IDENTITY), first.approvedPairs());
    }

    @Test
    void anApprovedSnapshotWithNoRecordedSourceIdIsABlocker() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithNoSourceId());
        LegacyWorkspaceInventoryHandler handler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(1, inventory.blockers().size());
    }

    @Test
    void mismatchedSourceIdsBetweenApprovedAndCandidateForTheSameIdentityIsAnAmbiguity() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithSourceId("vault-source-id-a"));
        NullCandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, snapshotWithSourceId("vault-source-id-b"), List.of());
        LegacyWorkspaceInventoryHandler handler = new LegacyWorkspaceInventoryHandler(approved, candidate);

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(1, inventory.ambiguities().size());
    }

    @Test
    void emptyWorkspaceHashIsStableAcrossRuns() {
        LegacyWorkspaceInventoryHandler first = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());
        LegacyWorkspaceInventoryHandler second = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());

        assertEquals(first.inspect().inventorySha256(), second.inspect().inventorySha256());
        assertTrue(first.inspect().inventorySha256().matches("^[0-9a-f]{64}$"));
    }

    private static CandidateSnapshot snapshotWithSourceId(String sourceId) {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY, sourceId,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot snapshotWithNoSourceId() {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=LegacyWorkspaceInventoryHandlerTest test`
Expected: FAIL — `LegacyWorkspaceInventory`/`LegacyWorkspaceInventoryHandler` do not exist.

- [ ] **Step 3: Implement `LegacyWorkspaceInventory`**

```java
package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;

public record LegacyWorkspaceInventory(
        List<PublicationIdentity> approvedPairs,
        List<PublicationIdentity> candidatePairs,
        List<String> ambiguities,
        List<String> blockers,
        String inventorySha256) {

    public LegacyWorkspaceInventory {
        approvedPairs = List.copyOf(approvedPairs);
        candidatePairs = List.copyOf(candidatePairs);
        ambiguities = List.copyOf(ambiguities);
        blockers = List.copyOf(blockers);
    }
}
```

- [ ] **Step 4: Implement `LegacyWorkspaceInventoryHandler`**

```java
package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LegacyWorkspaceInventoryHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final CandidateWorkspace candidateWorkspace;

    public LegacyWorkspaceInventoryHandler(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, CandidateWorkspace candidateWorkspace) {
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public LegacyWorkspaceInventory inspect() {
        List<PublicationIdentity> approvedPairs = approvedSnapshotWorkspace.allIdentities();
        List<PublicationIdentity> candidatePairs = candidateWorkspace.allIdentities();
        List<String> ambiguities = ambiguitiesAcross(approvedPairs, candidatePairs);
        List<String> blockers = blockersAcross(approvedPairs, candidatePairs);
        String inventorySha256 = fingerprint(approvedPairs, candidatePairs, ambiguities, blockers);
        return new LegacyWorkspaceInventory(approvedPairs, candidatePairs, ambiguities, blockers, inventorySha256);
    }

    private List<String> ambiguitiesAcross(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs) {
        List<String> ambiguities = new ArrayList<>();
        for (PublicationIdentity identity : approvedPairs) {
            if (!candidatePairs.contains(identity)) {
                continue;
            }
            Optional<String> approvedSourceId = sourceIdOfApproved(identity);
            Optional<String> candidateSourceId = sourceIdOfCandidate(identity);
            if (!approvedSourceId.equals(candidateSourceId)) {
                ambiguities.add(identity + ": approved sourceId " + approvedSourceId
                        + " does not match candidate sourceId " + candidateSourceId);
            }
        }
        return ambiguities;
    }

    private List<String> blockersAcross(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs) {
        List<String> blockers = new ArrayList<>();
        for (PublicationIdentity identity : approvedPairs) {
            if (sourceIdOfApproved(identity).isEmpty()) {
                blockers.add(identity + ": approved snapshot has no recorded source ID");
            }
        }
        for (PublicationIdentity identity : candidatePairs) {
            if (sourceIdOfCandidate(identity).isEmpty()) {
                blockers.add(identity + ": candidate snapshot has no recorded source ID");
            }
        }
        return blockers;
    }

    private Optional<String> sourceIdOfApproved(PublicationIdentity identity) {
        return approvedSnapshotWorkspace.read(identity).flatMap(snapshot -> snapshot.referenceMap().sourceId());
    }

    private Optional<String> sourceIdOfCandidate(PublicationIdentity identity) {
        return candidateWorkspace.read(identity).flatMap(snapshot -> snapshot.referenceMap().sourceId());
    }

    private static String fingerprint(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs,
            List<String> ambiguities, List<String> blockers) {
        StringBuilder canonical = new StringBuilder();
        approvedPairs.forEach(identity -> canonical.append("approved:").append(identity).append('\n'));
        candidatePairs.forEach(identity -> canonical.append("candidate:").append(identity).append('\n'));
        ambiguities.forEach(entry -> canonical.append("ambiguity:").append(entry).append('\n'));
        blockers.forEach(entry -> canonical.append("blocker:").append(entry).append('\n'));
        return ContentHash.sha256Hex(canonical.toString());
    }
}
```

`allIdentities()` (Task 3) already returns each list sorted, so `fingerprint`'s concatenation order is
deterministic without an extra sort here — the determinism guarantee lives at the source, not duplicated at
every consumer.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=LegacyWorkspaceInventoryHandlerTest test`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [ ] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventory.java \
        src/main/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventoryHandler.java \
        src/test/java/dev/eugene/publicationexporter/legacy/LegacyWorkspaceInventoryHandlerTest.java
git commit -m "feat: add read-only LegacyWorkspaceInventoryHandler"
```

---

## Task 8: End-to-end acceptance test covering MIG-01, MIG-02, MIG-05

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/ReadOnlyLegacyDiagnosisAcceptanceTest.java`

**Interfaces:**
- Consumes: every type from Tasks 1-7 — this task wires no new production code, it only proves the full
  spec'd scenario sequence end-to-end at the acceptance boundary the implementation plan specifies (in-memory
  legacy pairs and semantic-state markers).

- [ ] **Step 1: Write the acceptance test**

Model this test's fixture construction on `LateBoundTargetActivationAcceptanceTest.java` (read it first for
this codebase's established acceptance-test shape: `Null*` adapter construction, `VaultReader.createNull(...)`
patterns, and how it structures a multi-scenario narrative test) — reuse its established fixture-building
style, not a new one.

```java
package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventory;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventoryHandler;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyLegacyDiagnosisAcceptanceTest {

    private static final PublicationIdentity LEGACY_IDENTITY = PublicationIdentity.of("blog", "essay", "legacy-essay");
    private static final PublicationIdentity CURRENT_IDENTITY = PublicationIdentity.of("blog", "essay", "current-essay");

    @Test
    void currentEmptyWorkspaceNeverBlocksOrdinaryRelease() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(CURRENT_IDENTITY, snapshotWithSourceId(CURRENT_IDENTITY, "vault-source-id-current"));
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, ReleaseOutputStore.createNull(), ActivationMarkerStore.createNull());

        ReleaseResult result = handler.buildFromReview(CURRENT_IDENTITY);

        assertTrue(result.ok());
    }

    @Test
    void legacyContentWithNoMarkerFailsClosedWithoutMutation() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithNoSourceId(LEGACY_IDENTITY));
        ReleaseOutputStore releaseOutputStore = ReleaseOutputStore.createNull();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, releaseOutputStore, ActivationMarkerStore.createNull());

        ReleaseResult result = handler.buildFromReview(LEGACY_IDENTITY);

        assertFalse(result.ok());
        // Reuse ReleaseOutputStore's existing nullable tracking accessor (check NullReleaseOutputStore /
        // ReleaseOutputStore.createNull()'s existing test usages for its exact installed-releases query
        // method name) to assert nothing was installed.
    }

    @Test
    void inventoryOverTheSameLegacyWorkspaceIsDeterministicAndReportsTheMissingIdentity() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithNoSourceId(LEGACY_IDENTITY));
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory first = inventoryHandler.inspect();
        LegacyWorkspaceInventory second = inventoryHandler.inspect();

        assertEquals(List.of(LEGACY_IDENTITY), first.approvedPairs());
        assertEquals(1, first.blockers().size());
        assertEquals(first.inventorySha256(), second.inventorySha256());
    }

    @Test
    void aValidActivationMarkerLetsALegacyShapedWorkspaceReleaseNormallyAgain() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithSourceId(LEGACY_IDENTITY, "vault-source-id-legacy"));
        ActivationMarker validMarker =
                new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, ReleaseOutputStore.createNull(), ActivationMarkerStore.createNull(validMarker));

        ReleaseResult result = handler.buildFromReview(LEGACY_IDENTITY);

        assertTrue(result.ok());
    }

    private static CandidateSnapshot snapshotWithSourceId(PublicationIdentity identity, String sourceId) {
        ReferenceMap referenceMap = ReferenceMap.of(identity, sourceId,
                ContentHash.sha256Hex("ru body"), ContentHash.sha256Hex("en body"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru body", "en body", List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot snapshotWithNoSourceId(PublicationIdentity identity) {
        ReferenceMap referenceMap = ReferenceMap.of(identity,
                ContentHash.sha256Hex("ru body"), ContentHash.sha256Hex("en body"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru body", "en body", List.of(), List.of(), "", referenceMap);
    }
}
```

Before finalizing, check `ReleaseOutputStore.createNull()`'s existing test usages elsewhere in this codebase
(e.g. `BuildFromReviewHandlerTest`) for its exact "assert nothing was installed" query method and fill in the
commented line above with a real assertion — do not leave the comment in the committed test.

- [ ] **Step 2: Run the new test to verify every scenario passes**

Run: `cd publication-exporter && mvn -q -Dtest=ReadOnlyLegacyDiagnosisAcceptanceTest test`
Expected: PASS — every scenario here should already pass given Tasks 1-7's implementation; this task is
verification, not new production code. If anything fails, the failure means an earlier task's implementation
has a gap relative to the spec — fix the earlier task, not this test.

- [ ] **Step 3: Run the full suite one final time**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures, test count at or above 903 + all tests added across Tasks 1-8.

- [ ] **Step 4: Commit**

```bash
cd publication-exporter
git add src/test/java/dev/eugene/publicationexporter/ReadOnlyLegacyDiagnosisAcceptanceTest.java
git commit -m "test: acceptance-cover the full read-only legacy diagnosis scenario sequence"
```
