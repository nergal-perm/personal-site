<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance (this codebase's existing idiom — LinkResolver, OccurrenceLabelMarkers,
  ContentHash are all stateless utility classes; match it), Optional over null for "maybe absent" (never a
  bare null return), immutable value types with private constructors + static factories (CandidateSnapshot,
  ReferenceMap, Occurrence are the existing pattern — match it exactly), guard clauses over nested
  conditionals, Composed Method (small single-purpose private methods), package-private visibility by
  default (public only where a different package needs the type), nullable test doubles over mocking
  (NullApprovedSnapshotWorkspace, NullReleaseOutputStore already exist and are extended, not replaced, by
  this plan — never introduce a mocking library). No comments in production code beyond what non-obvious
  rationale demands — this file's own comments are plan scaffolding, not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, design.md, specs/**/*.md — all in
  openspec/changes/s20-late-bound-target-activation/. design.md's five numbered Decisions map directly onto
  Tasks 1-7 below; read it first if anything here is unclear on *why*, not just *what*.
- Additive-overload discipline: `ReferenceMap.of(...)`/`.empty(...)` have 109 existing call sites across 25
  files. Task 1 adds a NEW overload; the existing 7-arg factories are untouched and keep defaulting
  `sourceId` to `Optional.empty()`. Do not touch any existing call site as part of this plan unless a task
  explicitly says to.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor. The `ref:` marker
  shape is reused as a validated convention, not as imported code.
- Whole prior acceptance suite (863 tests as of this slice's baseline, 2026-08-18, `mvn -q test` exits 0)
  must stay green after every task's step that runs the full suite. If anything outside this task's own
  new/modified tests turns red, stop and investigate before continuing — do not proceed past an unexplained
  regression.
- Governed by Haft problem prob-20260818-bc96093f. Do not archive the OpenSpec change or touch Haft
  artifacts from this task list — those steps are owned by the orchestrating session, not an implementer.
-->

# S20 — Late-bound target activation: implementation plan

**Goal:** An approved referrer's semantic link occurrence resolves to a real, locale-correct public route only
when its target currently has a selected, complete approved snapshot — re-evaluated on every release,
independently of when the referrer itself was last approved — without ever rewriting the referrer's approved
bytes.

**Architecture:** `LinkResolver` stops baking a route for an admitted non-embed link target; it emits a durable
`[label](ref:<targetSourceId>)` marker instead (reusing exporter-java's validated `ref:` convention as a shape,
not as code). `PublicNoteIndex` gains a parallel `sourceIdFor()` lookup built in its existing single vault
scan. Approved snapshots durably record their own `sourceId` via a new `ReferenceMap.of(...)` overload.
`ApprovedSnapshotWorkspace` gains `findBySourceId(String)`. A new `ApprovedTargetRegistry` (built once per
release call from the snapshot's own occurrences) and a stateless `OccurrenceMarkerResolver` (regex
scan-and-replace against the registry) are invoked from both `BuildFromReviewHandler` and `InstallToSiteHandler`
before their respective output writes. `ReleaseProvenance` gains real `activationCount`/`deactivationCount`.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson (`ObjectMapper`), this project's existing nullable-object test
doubles (`ApprovedSnapshotWorkspace.createNull()` / `NullApprovedSnapshotWorkspace`,
`ReleaseOutputStore.createNull()` / `NullReleaseOutputStore`) — no mocking library.

**Spec:** openspec/changes/s20-late-bound-target-activation/proposal.md,
openspec/changes/s20-late-bound-target-activation/design.md,
openspec/changes/s20-late-bound-target-activation/specs/semantic-references/spec.md,
openspec/changes/s20-late-bound-target-activation/specs/release-materialization/spec.md,
openspec/changes/s20-late-bound-target-activation/specs/public-content-model/spec.md

## Global Constraints

(see HTML comment block above — this repo's convention keeps machine-readable constraints there so they
travel with the file into archive/ unedited; both blocks say the same thing)

---

## Task 1: `ReferenceMap` gains an additive `sourceId` overload

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`

**Interfaces:**
- Produces: `ReferenceMap.of(PublicationIdentity identity, String sourceId, String ruHash, String enHash, String ruFieldsHash, String enFieldsHash, String structuredDataHash, List<Occurrence> occurrences)` — new 8-arg overload, `sourceId` never null at the call site.
- Produces: `Optional<String> ReferenceMap.sourceId()` — `Optional.empty()` for every `ReferenceMap` built via the existing 7-arg `.of(...)`/`.empty(...)` factories (unchanged, still used by all 109 existing call sites).
- Consumes (Task 4): the new 8-arg overload, from `PrepareHandler.buildReferenceMap`.
- Consumes (Task 5): `ReferenceMap.sourceId()`, from `ApprovedSnapshotWorkspace.findBySourceId`.

- [x] **Step 1: Write the failing test for the new field**

```java
// ReferenceMapTest.java — add to the existing test class
@Test
void ofWithSourceIdRecordsIt() {
    ReferenceMap referenceMap = ReferenceMap.of(
            IDENTITY, "vault-source-id-a", "ru-hash", "en-hash",
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());

    assertEquals(Optional.of("vault-source-id-a"), referenceMap.sourceId());
}

@Test
void existingSevenArgFactoriesDefaultSourceIdToEmpty() {
    ReferenceMap viaOf = ReferenceMap.of(
            IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
    ReferenceMap viaEmpty = ReferenceMap.empty(
            IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-hash");

    assertEquals(Optional.empty(), viaOf.sourceId());
    assertEquals(Optional.empty(), viaEmpty.sourceId());
}

@Test
void sourceIdParticipatesInEqualsAndHashCode() {
    ReferenceMap withSourceId = ReferenceMap.of(
            IDENTITY, "vault-source-id-a", "ru-hash", "en-hash",
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
    ReferenceMap withoutSourceId = ReferenceMap.of(
            IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());

    assertNotEquals(withSourceId, withoutSourceId);
}
```

(Add `import java.util.Optional;` and `static org.junit.jupiter.api.Assertions.assertNotEquals;` to the test file's imports if not already present. Reuse whatever `IDENTITY` constant the existing test class already defines.)

- [x] **Step 2: Run test to verify it fails**

Run: `cd publication-exporter && mvn -q -Dtest=ReferenceMapTest test`
Expected: FAIL — `ReferenceMap.of(PublicationIdentity, String, String, String, String, String, String, List)` does not exist; `sourceId()` does not exist.

- [x] **Step 3: Add the field, the new overload, and `sourceId()` to `ReferenceMap`**

```java
// ReferenceMap.java — add alongside the existing fields
import java.util.Optional;
// ...
private final Optional<String> sourceId;

private ReferenceMap(
        PublicationIdentity identity,
        Optional<String> sourceId,
        String ruHash,
        String enHash,
        String ruFieldsHash,
        String enFieldsHash,
        String structuredDataHash,
        List<Occurrence> occurrences) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
    this.enHash = Objects.requireNonNull(enHash, "enHash");
    this.ruFieldsHash = Objects.requireNonNull(ruFieldsHash, "ruFieldsHash");
    this.enFieldsHash = Objects.requireNonNull(enFieldsHash, "enFieldsHash");
    this.structuredDataHash = Objects.requireNonNull(structuredDataHash, "structuredDataHash");
    this.occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
}

public static ReferenceMap of(
        PublicationIdentity identity,
        String sourceId,
        String ruHash,
        String enHash,
        String ruFieldsHash,
        String enFieldsHash,
        String structuredDataHash,
        List<Occurrence> occurrences) {
    return new ReferenceMap(identity, Optional.of(Objects.requireNonNull(sourceId, "sourceId")),
            ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences);
}

public static ReferenceMap of(
        PublicationIdentity identity,
        String ruHash,
        String enHash,
        String ruFieldsHash,
        String enFieldsHash,
        String structuredDataHash,
        List<Occurrence> occurrences) {
    return new ReferenceMap(identity, Optional.empty(),
            ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences);
}

@JsonProperty("sourceId")
public Optional<String> sourceId() {
    return sourceId;
}
```

Update the existing `empty(...)` factories to delegate through the 7-arg `of(...)` (unchanged behavior — they already do this). Update `equals`/`hashCode`/`toString` to include `sourceId`, following this class's existing pattern for every other field exactly.

- [x] **Step 4: Update `ReferenceMapCodec` to round-trip `sourceId`**

```java
// ReferenceMapCodec.java, inside referenceMapFrom(JsonNode root):
JsonNode sourceIdNode = root.get("sourceId");
String sourceId = (sourceIdNode == null || sourceIdNode.isNull()) ? null : sourceIdNode.asText();
return sourceId == null
        ? ReferenceMap.of(identity, root.get("ruHash").asText(), root.get("enHash").asText(),
                root.get("ruFieldsHash").asText(), root.get("enFieldsHash").asText(),
                root.get("structuredDataHash").asText(), occurrencesFrom(root.get("occurrences")))
        : ReferenceMap.of(identity, sourceId, root.get("ruHash").asText(), root.get("enHash").asText(),
                root.get("ruFieldsHash").asText(), root.get("enFieldsHash").asText(),
                root.get("structuredDataHash").asText(), occurrencesFrom(root.get("occurrences")));
```

Add a `ReferenceMapCodecTest` case round-tripping a `ReferenceMap` built with the new 8-arg `of(...)` through `write()`/`read()`, asserting `sourceId()` survives, and a second case reading a hand-written JSON string with no `"sourceId"` key at all, asserting `sourceId()` comes back `Optional.empty()` (this is the realistic shape of every `references.json` already on disk from prior slices — it must keep parsing).

- [x] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=ReferenceMapTest,ReferenceMapCodecTest test`
Expected: PASS

- [x] **Step 6: Run the full suite to confirm no existing call site broke**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 863+ tests (the added tests bring the count up), 0 failures

- [x] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java \
        src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java \
        src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java \
        src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java
git commit -m "feat: add additive sourceId overload to ReferenceMap"
```

---

## Task 2: `PublicNoteIndex` gains `sourceIdFor()`; `NoteIntake.Result` exposes `sourceId()`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PublicNoteIndexTest.java` (create if it does not already exist — check first; if `PublicNoteIndex` is currently only exercised indirectly through `PrepareHandlerTest`, create this file)

**Interfaces:**
- Produces: `String NoteIntake.Result.sourceId()`.
- Produces: `Optional<String> PublicNoteIndex.sourceIdFor(String linkTarget)`.
- Consumes (Task 3): `PublicNoteIndex.sourceIdFor`, from `LinkResolver.appendLink`.

- [x] **Step 1: Write the failing test**

Find `PublicNoteIndexTest.java`; if it doesn't exist, model this on how `PrepareHandlerTest` constructs a `VaultReader`/`NoteIntake` fixture pair (`VaultReader.createNull(...)`-style nullable fixtures — read `PrepareHandlerTest`'s setup for the exact fixture-construction helpers already in use, and reuse them, don't invent new ones).

```java
@Test
void sourceIdForReturnsTheAdmittedSourceId() {
    // Reuse this test class's (or PrepareHandlerTest's) existing helper for building a
    // VaultReader + NoteIntake pair with one admitted note whose frontmatter "id" is
    // "vault-source-id-target" and whose stem is "Target".
    PublicNoteIndex index = PublicNoteIndex.from(vaultReaderWithOneAdmittedNote(), noteIntake());

    assertEquals(Optional.of("vault-source-id-target"), index.sourceIdFor("Target"));
}

@Test
void sourceIdForIsAbsentForAnAmbiguousStem() {
    // Two admitted notes sharing the same filename stem in different vault directories.
    PublicNoteIndex index = PublicNoteIndex.from(vaultReaderWithTwoNotesSharingAStem(), noteIntake());

    assertEquals(Optional.empty(), index.sourceIdFor("Target"));
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd publication-exporter && mvn -q -Dtest=PublicNoteIndexTest test`
Expected: FAIL — `sourceIdFor` does not exist.

- [x] **Step 3: Add `sourceId()` to `NoteIntake.Result`**

```java
// NoteIntake.java, inside the Result class, alongside the existing identity()/kind()/title() accessors:
public String sourceId() {
    return admission.sourceId();
}
```

- [x] **Step 4: Widen `PublicNoteIndex` with a parallel `sourceIdsByFilenameStem` map**

```java
// PublicNoteIndex.java — replace the single-field constructor/factory with a two-map version
private final Map<String, String> routesByFilenameStem;
private final Map<String, String> sourceIdsByFilenameStem;

PublicNoteIndex(Map<String, String> routesByFilenameStem, Map<String, String> sourceIdsByFilenameStem) {
    this.routesByFilenameStem = Map.copyOf(Objects.requireNonNull(routesByFilenameStem, "routesByFilenameStem"));
    this.sourceIdsByFilenameStem =
            Map.copyOf(Objects.requireNonNull(sourceIdsByFilenameStem, "sourceIdsByFilenameStem"));
}

static PublicNoteIndex from(VaultReader vaultReader, NoteIntake noteIntake) {
    Objects.requireNonNull(vaultReader, "vaultReader");
    Objects.requireNonNull(noteIntake, "noteIntake");
    Map<String, String> routes = new LinkedHashMap<>();
    Map<String, String> sourceIds = new LinkedHashMap<>();
    Set<String> ambiguousStems = new HashSet<>();
    for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
        registerIfAdmitted(vaultReader, candidate, noteIntake, routes, sourceIds, ambiguousStems);
    }
    ambiguousStems.forEach(routes::remove);
    ambiguousStems.forEach(sourceIds::remove);
    return new PublicNoteIndex(routes, sourceIds);
}

Optional<String> routeFor(String linkTarget) {
    return Optional.ofNullable(routesByFilenameStem.get(linkTarget));
}

Optional<String> sourceIdFor(String linkTarget) {
    return Optional.ofNullable(sourceIdsByFilenameStem.get(linkTarget));
}

private static void registerIfAdmitted(
        VaultReader vaultReader, VaultRelativePath candidate, NoteIntake noteIntake,
        Map<String, String> routes, Map<String, String> sourceIds, Set<String> ambiguousStems) {
    NoteIntake.Result intake = noteIntake.admit(candidate, vaultReader);
    if (!intake.accepted()) {
        return;
    }
    String stem = filenameStem(candidate);
    if (routes.containsKey(stem)) {
        ambiguousStems.add(stem);
        return;
    }
    String routePrefix = intake.kind().routePrefix();
    String route = routePrefix == null
            ? "/" + intake.identity().publicId() + "/"
            : "/" + routePrefix + "/" + intake.identity().publicId() + "/";
    routes.put(stem, route);
    sourceIds.put(stem, intake.sourceId());
}
```

- [x] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=PublicNoteIndexTest test`
Expected: PASS

- [x] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures — `PublicNoteIndex`'s public interface (`routeFor`) is unchanged for every existing caller.

- [x] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java \
        src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PublicNoteIndexTest.java
git commit -m "feat: track source ID alongside route in PublicNoteIndex"
```

---

## Task 3: `LinkResolver` emits a durable `ref:` marker instead of a baked route

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java`

**Interfaces:**
- Consumes: `PublicNoteIndex.sourceIdFor` (Task 2).
- Produces: `LinkResolver.resolve(...)` output now contains `[label](ref:<sourceId>)` for admitted non-embed link targets, unchanged for every other case (embed, private/unresolved/ambiguous).

- [x] **Step 1: Write the failing test**

Find the existing `LinkResolverTest` scenario asserting a baked route for an admitted target (search for a test named something like `resolvesAdmittedTargetToItsRoute` or similar — read the file to find its exact name and fixture style) and read it fully before writing the new one below, so the fixture-construction style matches exactly.

```java
@Test
void admittedNonEmbedTargetGetsADurableReferenceMarkerNotABakedRoute() {
    // Reuse this test class's existing PublicNoteIndex-construction fixture, admitting one
    // target with filename stem "Target" and source ID "vault-source-id-target".
    LinkResolutionOutcome outcome = LinkResolver.resolve("See [[Target]].", knownNotesWithOneAdmittedTarget());

    assertTrue(outcome.resolved());
    assertEquals("See [Target](ref:vault-source-id-target).", outcome.body());
}

@Test
void aliasedAdmittedTargetKeepsItsAliasAsTheLabel() {
    LinkResolutionOutcome outcome = LinkResolver.resolve("See [[Target|My Alias]].", knownNotesWithOneAdmittedTarget());

    assertTrue(outcome.resolved());
    assertEquals("See [My Alias](ref:vault-source-id-target).", outcome.body());
}
```

Then locate every existing assertion in `LinkResolverTest` (and cross-check `PrepareHandlerTest` — search for the string `](/` or a route-shaped assertion like `/essays/` on an admitted-target body) that currently expects a baked route (e.g. `[Target](/essays/target/)`) for an *admitted, non-embed* link. Do not change embed-target assertions or private/unresolved/ambiguous-target assertions — those are unaffected by this task. List every such test found (write the list down as a code comment at the top of this task's diff, for the reviewer) before touching production code — this is the PCM-03 delta's expected fallout, not a regression, but every instance must be located and updated deliberately, not blanket-replaced.

- [x] **Step 2: Run tests to verify the new ones fail and confirm the located existing ones currently pass (pre-change baseline)**

Run: `cd publication-exporter && mvn -q -Dtest=LinkResolverTest test`
Expected: the two new tests FAIL (marker not yet emitted); every other test in the file still PASSES (this is your before-snapshot — re-run after Step 3 and diff against this list).

- [x] **Step 3: Change `appendLink`'s admitted-non-embed branch**

```java
// LinkResolver.java — replace the route-baking branch inside appendLink(...)
Optional<String> route = knownNotes.routeFor(target);
if (route.isPresent()) {
    if (isEmbed) {
        output.append('[');
        int spanStart = output.length();
        output.append(label);
        int spanEnd = output.length();
        output.append("](").append(route.get()).append(')');
        return Optional.empty();
    }
    String targetSourceId = knownNotes.sourceIdFor(target).orElseThrow();
    output.append('[');
    int spanStart = output.length();
    output.append(label);
    int spanEnd = output.length();
    output.append("](ref:").append(targetSourceId).append(')');
    occurrences.add(new LinkOccurrence(lastPathSegment(target), label, route, spanStart, spanEnd));
    return Optional.empty();
}
```

Read the current full `appendLink` method body first (prepare/LinkResolver.java:55-86) and apply this change surgically — the `isEmbed && AssetTargets.isAssetTarget(target)` early-return (line 62-65) and the `route.isPresent()` == false branch (line 78-85, private/unresolved/ambiguous) are unchanged. Note the embed sub-branch above still bakes the route immediately (per design.md's decision: embeds are unaffected by this slice) — only the non-embed sub-branch changes to emit the marker. `LinkOccurrence` continues to carry `route` (`Optional<String>`, still populated — needed for `PrepareHandler`'s existing direct-target-identity and translation-preservation logic, unrelated to this slice) even though the *body* text no longer contains that route directly.

- [x] **Step 4: Run the new tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=LinkResolverTest test`
Expected: the two new tests PASS. Every test from your Step 2 "before" list that asserted a baked route for an admitted non-embed target now FAILS — this is expected; update each one's assertion from the baked-route form to the `ref:`-marker form (`[Label](ref:<sourceId>)`), matching Step 1's pattern. Do not touch any embed-target or private/unresolved/ambiguous-target assertion.

- [x] **Step 5: Update `PrepareHandlerTest`'s fallout**

Run `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test` and update every failing assertion the same way — a baked-route assertion for an admitted non-embed link becomes a `ref:`-marker assertion. Do not touch assertions about `referenceMap().occurrences()` contents (those describe `Occurrence.id`/`order`/`targetSourceId`/labels, unaffected by this task) or anything about private/embed targets.

- [x] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java \
        src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat: emit durable ref: marker instead of baking a route for admitted link targets"
```

---

## Task 4: Thread the preparing note's own `sourceId` to `buildReferenceMap`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Consumes: `ReferenceMap.of(identity, sourceId, ...)` (Task 1's new overload).
- Produces: every installed `CandidateSnapshot.referenceMap().sourceId()` is now `Optional.of(<the note's own "id" frontmatter value>)`, for every candidate installed from this point on.

- [x] **Step 1: Write the failing test**

```java
@Test
void installedCandidateReferenceMapRecordsTheNotesOwnSourceId() {
    // Reuse this test class's existing standard fixture for preparing a plain admitted essay
    // whose frontmatter "id" is, e.g., "vault-source-id-essay".
    BridgeResponse response = handler.prepare(notePath(), vaultReader(), vaultAssetReader());

    assertTrue(response.ok());
    CandidateSnapshot installed = candidateWorkspace.read(IDENTITY).orElseThrow();
    assertEquals(Optional.of("vault-source-id-essay"), installed.referenceMap().sourceId());
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest#installedCandidateReferenceMapRecordsTheNotesOwnSourceId test`
Expected: FAIL — `sourceId()` currently comes back `Optional.empty()`.

- [x] **Step 3: Thread `sourceId` through the five intermediate signatures**

Add a `String sourceId` parameter to each of the following (in this exact order, since each calls the next):

```java
// prepareNormalizedEssay — add sourceId as the last parameter
private BridgeResponse prepareNormalizedEssay(
        VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
        String normalizedBody, List<CandidateAsset> assets, PublicNoteIndex knownNotes,
        VaultAssetReader vaultAssetReader, OccurrenceContext occurrenceContext, String sourceId) {
    // body unchanged except: pass sourceId onward in both call sites inside this method
    // (prepareWithInstallLock(...) calls at what are today lines 171-172) — do NOT thread it
    // into mirrorApprovedCandidate's call (line 169) — that path never reaches buildReferenceMap.
```

```java
// prepareWithInstallLock — add sourceId as the last parameter, pass to prepareAdmittedEssay
private BridgeResponse prepareWithInstallLock(
        VaultRelativePath notePath, VaultReader vaultReader,
        NoteIntake.Result intake, String normalizedBody, List<CandidateAsset> assets,
        PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
        OccurrenceContext occurrenceContext, String sourceId) {
    ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
    installLock.lock();
    try {
        return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                intake.sourceHash(), normalizedBody, fieldsOf(intake), intake.structuredData(), assets,
                knownNotes, vaultAssetReader, occurrenceContext, sourceId);
    } finally {
        installLock.unlock();
    }
}
```

```java
// prepareAdmittedEssay — add sourceId as the last parameter, pass to prepareTranslatedEssay
private BridgeResponse prepareAdmittedEssay(
        VaultRelativePath notePath, VaultReader vaultReader,
        PublicationIdentity identity, String sourceHash,
        String ruBody, List<PublicField> ruFields, String structuredData, List<CandidateAsset> assets,
        PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
        OccurrenceContext occurrenceContext, String sourceId) {
    // body unchanged up to the translateCandidate(...).resolve(...) call; in the success branch:
    return translateCandidate(job, delimitedRuBody, ruFields).resolve(
            translation -> prepareTranslatedEssay(
                    notePath, vaultReader, identity, sourceHash,
                    delimitedRuBody, ruFields, structuredData, assets, job, translation, knownNotes,
                    vaultAssetReader, assignedRu, sourceId),
            failure -> {
                recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                return translationFailure(failure);
            });
}
```

```java
// prepareTranslatedEssay — add sourceId as the last parameter, pass to buildReferenceMap
private BridgeResponse prepareTranslatedEssay(
        VaultRelativePath notePath, VaultReader vaultReader,
        PublicationIdentity identity, String sourceHash,
        String delimitedRuBody, List<PublicField> ruFields, String structuredData,
        List<CandidateAsset> assets, TranslationJob job,
        EnglishTranslation translation, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
        List<OccurrenceAssignment.AssignedOccurrence> assignedRu, String sourceId) {
    // body unchanged up to the buildReferenceMap(...) call; change that one call site to:
    ReferenceMap referenceMap = buildReferenceMap(
            identity, sourceId, ruBody, enBody, ruFields, enFields, structuredData, occurrences);
```

```java
// buildReferenceMap — add sourceId as the second parameter, use the new ReferenceMap.of overload
private static ReferenceMap buildReferenceMap(
        PublicationIdentity identity, String sourceId, String ruBody, String enBody,
        List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
        List<Occurrence> occurrences) {
    return ReferenceMap.of(
            identity,
            sourceId,
            ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
            ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
            ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
            ContentHash.sha256Hex(structuredData),
            occurrences);
}
```

Finally, update the two call sites of `prepareNormalizedEssay` inside `prepareAfterIdentityCheck` (today's lines 114-116, 126-128, 137-139, 143-145 — all four call sites in that method) to pass the already-in-scope `sourceId` parameter (that method already receives `sourceId` — it just isn't forwarding it into `prepareNormalizedEssay` today).

- [x] **Step 4: Run test to verify it passes**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test`
Expected: PASS

- [x] **Step 5: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 6: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat: record the preparing note's own source ID in its reference map"
```

---

## Task 5: `ApprovedSnapshotWorkspace.findBySourceId`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`
- Test: create `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java` if one does not already exist covering this class; otherwise add to it.

**Interfaces:**
- Produces: `Optional<CandidateSnapshot> ApprovedSnapshotWorkspace.findBySourceId(String sourceId)` (interface method, default-free — both implementations must provide it).
- Consumes (Task 6): from `ApprovedTargetRegistry`.

- [x] **Step 1: Write the failing tests**

```java
// NullApprovedSnapshotWorkspaceTest.java
@Test
void findBySourceIdReturnsTheSnapshotWhoseReferenceMapHasThatSourceId() {
    NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "target");
    ReferenceMap referenceMap = ReferenceMap.of(identity, "vault-source-id-target",
            ContentHash.sha256Hex("Target RU"), ContentHash.sha256Hex("Target EN"),
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
    workspace.install(identity, CandidateSnapshot.of(
            "Target RU", "Target EN", List.of(), List.of(), "", referenceMap));

    Optional<CandidateSnapshot> found = workspace.findBySourceId("vault-source-id-target");

    assertTrue(found.isPresent());
    assertEquals("Target RU", found.get().ruBody());
}

@Test
void findBySourceIdIsAbsentWhenNoInstalledSnapshotMatches() {
    NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

    assertEquals(Optional.empty(), workspace.findBySourceId("no-such-source-id"));
}
```

```java
// FilesystemApprovedSnapshotWorkspaceTest.java — add alongside the existing install/read round-trip tests
@Test
void findBySourceIdLocatesAnApprovedSnapshotByItsOwnSourceId() {
    // Reuse this test class's existing fixture for installing one approved snapshot (locate the
    // helper method it already uses — do not invent a new install pathway).
    installApprovedSnapshotWithSourceId("vault-source-id-target", TARGET_IDENTITY);

    Optional<CandidateSnapshot> found = workspace.findBySourceId("vault-source-id-target");

    assertTrue(found.isPresent());
}

@Test
void findBySourceIdIsAbsentForAnUnknownSourceId() {
    assertEquals(Optional.empty(), workspace.findBySourceId("no-such-source-id"));
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest test`
Expected: FAIL — `findBySourceId` does not exist.

- [x] **Step 3: Add the interface method**

```java
// ApprovedSnapshotWorkspace.java — add alongside the existing read(PublicationIdentity) method
Optional<CandidateSnapshot> findBySourceId(String sourceId);
```

- [x] **Step 4: Implement in `NullApprovedSnapshotWorkspace`**

```java
@Override
public Optional<CandidateSnapshot> findBySourceId(String sourceId) {
    Objects.requireNonNull(sourceId, "sourceId");
    return installed.values().stream()
            .filter(snapshot -> snapshot.referenceMap().sourceId().equals(Optional.of(sourceId)))
            .findFirst();
}
```

- [x] **Step 5: Implement in `FilesystemApprovedSnapshotWorkspace`**

Read this class's existing directory-scan logic first (search for how it enumerates approved-snapshot
directories — likely a method already used by some other scan/listing operation in this class, or in a
sibling class it delegates to; reuse that enumeration, do not write a new one). For each approved directory
found, read its `references.json` (the same `readReferenceMap` helper this class already uses at line ~366),
and return the first `CandidateSnapshot` (loaded the same way `readSnapshot`-equivalent logic in this class
already loads one, at the `read(PublicationIdentity)` method) whose `referenceMap().sourceId()` equals
`Optional.of(sourceId)`. If no directory-enumeration helper already exists on this class, add a small
private one (`private List<PublicationIdentity> allApprovedIdentities()` or similar, package-private-testable
if that better matches this class's existing style) rather than duplicating path-construction logic inline.

- [x] **Step 6: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest,FilesystemApprovedSnapshotWorkspaceTest test`
Expected: PASS

- [x] **Step 7: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures. (Adding a new interface method with no default implementation is a compile-time
check that every implementor was updated — if this doesn't compile, you missed one.)

- [x] **Step 8: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        src/test/java/dev/eugene/publicationexporter/approved/
git commit -m "feat: add findBySourceId to ApprovedSnapshotWorkspace"
```

---

## Task 6: `ApprovedTargetRegistry` and `OccurrenceMarkerResolver`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistry.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolver.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/OccurrenceResolution.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistryTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolverTest.java`

**Interfaces:**
- Produces: `ApprovedTargetRegistry.forOccurrences(List<Occurrence> occurrences, ApprovedSnapshotWorkspace approvedSnapshotWorkspace): ApprovedTargetRegistry` — package-private static factory.
- Produces: `Optional<ApprovedTargetRegistry.Target> ApprovedTargetRegistry.find(String targetSourceId)`, with `record Target(String ruRoute, String enRoute)`.
- Produces: `OccurrenceResolution.of(String body, int activated, int deactivated): OccurrenceResolution`, with `String body()`, `int activatedCount()`, `int deactivatedCount()`.
- Produces: `OccurrenceMarkerResolver.resolve(String body, ApprovedTargetRegistry registry, List<Occurrence> occurrences, String language): OccurrenceResolution` — stateless static method.
- Consumes (Task 7): both, from `BuildFromReviewHandler` and `InstallToSiteHandler`.

- [x] **Step 1: Write the failing tests for `ApprovedTargetRegistry`**

```java
// ApprovedTargetRegistryTest.java
@Test
void findReturnsRoutesForACurrentlyApprovedTarget() {
    NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
    PublicationIdentity targetIdentity = PublicationIdentity.of("blog", "note", "target");
    ReferenceMap targetReferenceMap = ReferenceMap.of(targetIdentity, "vault-source-id-target",
            ContentHash.sha256Hex("Target RU"), ContentHash.sha256Hex("Target EN"),
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
    approvedSnapshotWorkspace.install(targetIdentity, CandidateSnapshot.of(
            "Target RU", "Target EN", List.of(), List.of(), "", targetReferenceMap));
    Occurrence occurrence = new Occurrence("ref-0001", 0, "vault-source-id-target", "Label RU", "Label EN");

    ApprovedTargetRegistry registry =
            ApprovedTargetRegistry.forOccurrences(List.of(occurrence), approvedSnapshotWorkspace);

    Optional<ApprovedTargetRegistry.Target> found = registry.find("vault-source-id-target");
    assertTrue(found.isPresent());
    assertEquals("/ru/notes/target/", found.get().ruRoute());
    assertEquals("/en/notes/target/", found.get().enRoute());
}

@Test
void findIsAbsentWhenTheTargetHasNoApprovedSnapshot() {
    ApprovedTargetRegistry registry = ApprovedTargetRegistry.forOccurrences(
            List.of(new Occurrence("ref-0001", 0, "vault-source-id-missing", "Label RU", "Label EN")),
            new NullApprovedSnapshotWorkspace());

    assertEquals(Optional.empty(), registry.find("vault-source-id-missing"));
}
```

- [x] **Step 2: Run to verify failure, then implement `ApprovedTargetRegistry`**

Run: `cd publication-exporter && mvn -q -Dtest=ApprovedTargetRegistryTest test` — expect compile failure (class doesn't exist).

```java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Live routes for the distinct targets one approved snapshot's occurrences point to, resolved once per release call. */
public final class ApprovedTargetRegistry {

    private final Map<String, Target> targetsBySourceId;

    private ApprovedTargetRegistry(Map<String, Target> targetsBySourceId) {
        this.targetsBySourceId = Map.copyOf(targetsBySourceId);
    }

    public static ApprovedTargetRegistry forOccurrences(
            List<Occurrence> occurrences, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            String targetSourceId = occurrence.targetSourceId();
            if (targets.containsKey(targetSourceId)) {
                continue;
            }
            approvedSnapshotWorkspace.findBySourceId(targetSourceId)
                    .ifPresent(target -> targets.put(targetSourceId, routeFor(target)));
        }
        return new ApprovedTargetRegistry(targets);
    }

    public Optional<Target> find(String targetSourceId) {
        return Optional.ofNullable(targetsBySourceId.get(Objects.requireNonNull(targetSourceId, "targetSourceId")));
    }

    private static Target routeFor(CandidateSnapshot target) {
        String collection = target.referenceMap().identity().publicCollection();
        String publicId = target.referenceMap().identity().publicId();
        // Kind-correct route prefix: mirrors PublicNoteIndex's own route-building shape
        // ("/" + routePrefix + "/" + publicId + "/"), with a language segment now added
        // (REL-02's "kind-correct, locale-prefixed route" — see design.md Decision on
        // ApprovedTargetRegistry). routePrefix is not stored on CandidateSnapshot/ReferenceMap
        // today; resolve it via the same PublicationKind registry PrepareHandler's admission
        // path already uses for `collection` — check PublicationKindRegistry (or equivalent
        // lookup-by-collection-and-contentType type already in the admission package) before
        // inventing a new lookup mechanism.
        String routePrefix = PublicationKindRegistry.routePrefixFor(
                collection, target.referenceMap().identity().publicContentType());
        return new Target("/ru/" + routePrefix + "/" + publicId + "/", "/en/" + routePrefix + "/" + publicId + "/");
    }

    public record Target(String ruRoute, String enRoute) { }
}
```

`PublicationKindRegistry.routePrefixFor(collection, contentType)` in the snippet above is a placeholder
name — before implementing this method, search the `admission` package for however `PublicationKind`
implementations are already looked up by `(collection, contentType)` elsewhere in this codebase (e.g.
wherever `NoteIntake` or a contract-writing command resolves a `PublicationKind` from an identity) and reuse
that exact mechanism/type name. Do not introduce a second kind-lookup mechanism if one already exists.

- [x] **Step 3: Run `ApprovedTargetRegistryTest` again, resolve any remaining compile issues, confirm PASS**

Run: `cd publication-exporter && mvn -q -Dtest=ApprovedTargetRegistryTest test`
Expected: PASS

- [x] **Step 4: Write the failing tests for `OccurrenceMarkerResolver`**

```java
// OccurrenceMarkerResolverTest.java
@Test
void activatesAMarkerWhoseTargetIsCurrentlyApproved() {
    List<Occurrence> occurrences = List.of(
            new Occurrence("ref-0001", 0, "vault-source-id-target", "Target Label RU", "Target Label EN"));
    ApprovedTargetRegistry registry = registryResolving("vault-source-id-target",
            "/ru/notes/target/", "/en/notes/target/");

    OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
            "See [Target Label RU](ref:vault-source-id-target).", registry, occurrences, "ru");

    assertEquals("See [Target Label RU](/ru/notes/target/).", resolution.body());
    assertEquals(1, resolution.activatedCount());
    assertEquals(0, resolution.deactivatedCount());
}

@Test
void deactivatesAMarkerWhoseTargetHasNoCurrentApprovedSnapshot() {
    List<Occurrence> occurrences = List.of(
            new Occurrence("ref-0001", 0, "vault-source-id-missing", "Target Label RU", "Target Label EN"));
    ApprovedTargetRegistry registry = emptyRegistry();

    OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
            "See [Target Label RU](ref:vault-source-id-missing).", registry, occurrences, "ru");

    assertEquals("See Target Label RU.", resolution.body());
    assertEquals(0, resolution.activatedCount());
    assertEquals(1, resolution.deactivatedCount());
}

@Test
void aBodyWithNoMarkersIsReturnedUnchangedWithZeroCounts() {
    OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
            "No links here.", emptyRegistry(), List.of(), "ru");

    assertEquals("No links here.", resolution.body());
    assertEquals(0, resolution.activatedCount());
    assertEquals(0, resolution.deactivatedCount());
}
```

Add two small private helpers to the test class — `registryResolving(String sourceId, String ruRoute, String enRoute)` building a one-entry `ApprovedTargetRegistry` via the real `forOccurrences` factory against a `NullApprovedSnapshotWorkspace` seeded with one matching approved snapshot (reuse Task 6 Step 1's fixture-construction pattern), and `emptyRegistry()` built the same way against zero installed snapshots.

- [x] **Step 5: Run to verify failure, then implement `OccurrenceResolution` and `OccurrenceMarkerResolver`**

```java
// OccurrenceResolution.java
package dev.eugene.publicationexporter.release;

import java.util.Objects;

public record OccurrenceResolution(String body, int activatedCount, int deactivatedCount) {

    public OccurrenceResolution {
        Objects.requireNonNull(body, "body");
    }

    public static OccurrenceResolution of(String body, int activatedCount, int deactivatedCount) {
        return new OccurrenceResolution(body, activatedCount, deactivatedCount);
    }
}
```

```java
// OccurrenceMarkerResolver.java
package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless regex substitution of ref: markers against a live ApprovedTargetRegistry. */
public final class OccurrenceMarkerResolver {

    private static final Pattern MARKER = Pattern.compile("\\[(?<label>[^\\]]*)]\\(ref:(?<sourceId>[^)]+)\\)");

    private OccurrenceMarkerResolver() {
    }

    public static OccurrenceResolution resolve(
            String body, ApprovedTargetRegistry registry, List<Occurrence> occurrences, String language) {
        Map<String, Occurrence> occurrencesByTargetSourceId = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            occurrencesByTargetSourceId.putIfAbsent(occurrence.targetSourceId(), occurrence);
        }
        Matcher matcher = MARKER.matcher(body);
        StringBuilder rewritten = new StringBuilder(body.length());
        int cursor = 0;
        int activated = 0;
        int deactivated = 0;
        while (matcher.find()) {
            rewritten.append(body, cursor, matcher.start());
            String targetSourceId = matcher.group("sourceId");
            String label = storedLabel(occurrencesByTargetSourceId, targetSourceId, language, matcher.group("label"));
            var target = registry.find(targetSourceId);
            if (target.isPresent()) {
                String route = "ru".equals(language) ? target.get().ruRoute() : target.get().enRoute();
                rewritten.append('[').append(label).append("](").append(route).append(')');
                activated++;
            } else {
                rewritten.append(label);
                deactivated++;
            }
            cursor = matcher.end();
        }
        rewritten.append(body, cursor, body.length());
        return OccurrenceResolution.of(rewritten.toString(), activated, deactivated);
    }

    private static String storedLabel(
            Map<String, Occurrence> occurrencesByTargetSourceId, String targetSourceId, String language,
            String fallbackLabel) {
        Occurrence occurrence = occurrencesByTargetSourceId.get(targetSourceId);
        if (occurrence == null) {
            return fallbackLabel;
        }
        return "ru".equals(language) ? occurrence.ruLabel() : occurrence.enLabel();
    }
}
```

- [x] **Step 6: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=ApprovedTargetRegistryTest,OccurrenceMarkerResolverTest test`
Expected: PASS

- [x] **Step 7: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 8: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistry.java \
        src/main/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolver.java \
        src/main/java/dev/eugene/publicationexporter/release/OccurrenceResolution.java \
        src/test/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistryTest.java \
        src/test/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolverTest.java
git commit -m "feat: add ApprovedTargetRegistry and OccurrenceMarkerResolver"
```

---

## Task 7: Wire resolution into `BuildFromReviewHandler`; real `ReleaseProvenance` activation counts

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ReleaseProvenance.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java`

**Interfaces:**
- Produces: `ReleaseProvenance.of(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash, activationCount, deactivationCount)` — new 7-arg overload; existing 5-arg `.of(...)` (12 call sites) untouched, still defaults both counts to `0`.
- Consumes: `ApprovedTargetRegistry`, `OccurrenceMarkerResolver` (Task 6).

- [x] **Step 1: Write the failing test**

```java
@Test
void anActivatedOccurrenceIsResolvedToARouteAndCountedInProvenance() {
    NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
    PublicationIdentity targetIdentity = PublicationIdentity.of("blog", "note", "target");
    approvedSnapshotWorkspace.install(targetIdentity, CandidateSnapshot.of(
            "Target RU", "Target EN", List.of(), List.of(), "",
            ReferenceMap.of(targetIdentity, "vault-source-id-target",
                    ContentHash.sha256Hex("Target RU"), ContentHash.sha256Hex("Target EN"),
                    "ru-fields-hash", "en-fields-hash", "structured-hash", List.of())));
    Occurrence occurrence = new Occurrence("ref-0001", 0, "vault-source-id-target", "See it", "See it EN");
    ReferenceMap referrerReferenceMap = ReferenceMap.of(IDENTITY,
            ContentHash.sha256Hex("[See it](ref:vault-source-id-target)"),
            ContentHash.sha256Hex("[See it EN](ref:vault-source-id-target)"),
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of(occurrence));
    approvedSnapshotWorkspace.install(IDENTITY, CandidateSnapshot.of(
            "[See it](ref:vault-source-id-target)", "[See it EN](ref:vault-source-id-target)",
            List.of(), List.of(), "", referrerReferenceMap));
    NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
    BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

    ReleaseResult result = handler.buildFromReview(IDENTITY);

    assertTrue(result.ok());
    assertEquals("[See it](/ru/notes/target/)", releaseOutputStore.installed().get(IDENTITY).ruBody());
    assertEquals("[See it EN](/en/notes/target/)", releaseOutputStore.installed().get(IDENTITY).enBody());
    assertEquals(1, result.provenance().activationCount());
    assertEquals(0, result.provenance().deactivationCount());
}

@Test
void anOccurrenceWithNoApprovedTargetIsStrippedToItsLabelAndCountedAsDeactivated() {
    NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
    Occurrence occurrence = new Occurrence("ref-0001", 0, "vault-source-id-missing", "See it", "See it EN");
    ReferenceMap referrerReferenceMap = ReferenceMap.of(IDENTITY,
            ContentHash.sha256Hex("[See it](ref:vault-source-id-missing)"),
            ContentHash.sha256Hex("[See it EN](ref:vault-source-id-missing)"),
            "ru-fields-hash", "en-fields-hash", "structured-hash", List.of(occurrence));
    approvedSnapshotWorkspace.install(IDENTITY, CandidateSnapshot.of(
            "[See it](ref:vault-source-id-missing)", "[See it EN](ref:vault-source-id-missing)",
            List.of(), List.of(), "", referrerReferenceMap));
    NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
    BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

    ReleaseResult result = handler.buildFromReview(IDENTITY);

    assertTrue(result.ok());
    assertEquals("See it", releaseOutputStore.installed().get(IDENTITY).ruBody());
    assertEquals(0, result.provenance().activationCount());
    assertEquals(1, result.provenance().deactivationCount());
}
```

Note: `referenceMap().ruHash()`/`enHash()` in these fixtures are computed over the *approved* (marker-bearing)
body, matching how every existing fixture in this file already builds a `ReferenceMap` — `BuildFromReviewHandler`
never re-hashes against the resolved body; `provenanceFor` continues to hash `approved.ruBody()`/`enBody()`
verbatim (unchanged), only the *output* written to `releaseOutputStore` is the resolved text.

- [x] **Step 2: Run tests to verify they fail**

Run: `cd publication-exporter && mvn -q -Dtest=BuildFromReviewHandlerTest test`
Expected: FAIL — output still contains the raw `ref:` marker; provenance counts still `0`/`0` unconditionally (fine for the deactivation-count case coincidentally, but the body assertion fails).

- [x] **Step 3: Add the `ReleaseProvenance` overload**

```java
// ReleaseProvenance.java — new overload alongside the existing `of(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash)`
private final int activationCount;
private final int deactivationCount;

private ReleaseProvenance(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
        String outputRuHash, String outputEnHash, int activationCount, int deactivationCount) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.approvedRuHash = Objects.requireNonNull(approvedRuHash, "approvedRuHash");
    this.approvedEnHash = Objects.requireNonNull(approvedEnHash, "approvedEnHash");
    this.outputRuHash = Objects.requireNonNull(outputRuHash, "outputRuHash");
    this.outputEnHash = Objects.requireNonNull(outputEnHash, "outputEnHash");
    this.activationCount = activationCount;
    this.deactivationCount = deactivationCount;
}

public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
        String outputRuHash, String outputEnHash, int activationCount, int deactivationCount) {
    return new ReleaseProvenance(
            identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash, activationCount, deactivationCount);
}

public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
        String outputRuHash, String outputEnHash) {
    return new ReleaseProvenance(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash, 0, 0);
}
```

Remove the old hardcoded `@JsonProperty("activationCount") public int activationCount() { return 0; }` stub
and replace with `return activationCount;` (same for `deactivationCount`). Update `equals`/`hashCode` to
include the two new fields, matching this class's existing pattern.

- [x] **Step 4: Wire resolution into `BuildFromReviewHandler`**

```java
// BuildFromReviewHandler.java
private ReleaseResult releaseApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
    ApprovedTargetRegistry registry = ApprovedTargetRegistry.forOccurrences(
            approved.referenceMap().occurrences(), approvedSnapshotWorkspace);
    List<Occurrence> occurrences = approved.referenceMap().occurrences();
    OccurrenceResolution ruResolution =
            OccurrenceMarkerResolver.resolve(approved.ruBody(), registry, occurrences, "ru");
    OccurrenceResolution enResolution =
            OccurrenceMarkerResolver.resolve(approved.enBody(), registry, occurrences, "en");
    ReleaseProvenance provenance = provenanceFor(identity, approved,
            ruResolution.activatedCount() + enResolution.activatedCount(),
            ruResolution.deactivatedCount() + enResolution.deactivatedCount());
    try {
        releaseOutputStore.install(identity, ruResolution.body(), enResolution.body(), provenance);
    } catch (ReleaseAlreadyExistsException raceLoser) {
        return alreadyReleasedResult();
    } catch (ReleaseOutputStoreConfinementException failure) {
        return ReleaseResult.blocked("Release installation failed: " + failure.getMessage());
    } catch (UncheckedIOException failure) {
        return ReleaseResult.blocked(IoFailureMessages.describe("Release installation failed", failure));
    }
    return ReleaseResult.released(identity, provenance);
}

private static ReleaseProvenance provenanceFor(
        PublicationIdentity identity, CandidateSnapshot approved, int activationCount, int deactivationCount) {
    String outputRuHash = ContentHash.sha256Hex(approved.ruBody());
    String outputEnHash = ContentHash.sha256Hex(approved.enBody());
    return ReleaseProvenance.of(identity,
            approved.referenceMap().ruHash(), approved.referenceMap().enHash(),
            outputRuHash, outputEnHash, activationCount, deactivationCount);
}
```

Note: `outputRuHash`/`outputEnHash` deliberately stay hashes of `approved.ruBody()`/`enBody()` (the
marker-bearing approved text), not the resolved output — this matches every existing REL-03 test's
expectation that `provenance().outputRuHash()` equals `provenance().approvedRuHash()` when nothing else has
mutated the approved bytes (`approvedSnapshotIsReleasedWithMatchingApprovedAndOutputHashes`, already in this
test file), and design.md's determinism decision doesn't require the resolved-text hash to be part of this
particular field. If a later step in this task reveals that assumption is wrong (an existing test asserts
`outputRuHash` must reflect resolved content), stop and re-read design.md's REL-03 decision before changing
this — it's a deliberate choice, not an oversight.

Import `dev.eugene.publicationexporter.release.ApprovedTargetRegistry`, `OccurrenceMarkerResolver`,
`OccurrenceResolution`, and `dev.eugene.publicationexporter.reference.Occurrence` at the top of
`BuildFromReviewHandler.java`.

- [x] **Step 5: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=BuildFromReviewHandlerTest test`
Expected: PASS

- [x] **Step 6: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 7: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandler.java \
        src/main/java/dev/eugene/publicationexporter/release/ReleaseProvenance.java \
        src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java
git commit -m "feat: resolve ref: markers at release time with real activation provenance"
```

---

## Task 8: Wire resolution into `InstallToSiteHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java`
- Test: find and modify the existing test file for this handler (e.g. `InstallToSiteHandlerTest.java`)

**Interfaces:**
- Consumes: `ApprovedTargetRegistry`, `OccurrenceMarkerResolver` (Task 6).

- [x] **Step 1: Write the failing test**

Reuse Task 7 Step 1's two-snapshot fixture pattern (one target, one referrer whose approved body carries a
`ref:` marker), but drive it through `InstallToSiteHandler.installToSite(identity)` and assert on whatever
this handler's existing tests already assert about the `CandidateSnapshot` passed to
`ManagedSiteInstaller.installWithOutcome` (read the existing test file first for the exact assertion style —
likely a fake/null `ManagedSiteInstaller` recording what it was called with).

```java
@Test
void installedSiteSnapshotHasResolvedOccurrenceRoutesNotRawMarkers() {
    // ... fixture setup mirroring Task 7 Step 1 ...
    InstallToSiteResult result = handler.installToSite(IDENTITY);

    assertTrue(result.ok());
    CandidateSnapshot installed = fakeManagedSiteInstaller.lastInstalled(); // or this file's equivalent accessor
    assertEquals("[See it](/ru/notes/target/)", installed.ruBody());
    assertEquals("[See it EN](/en/notes/target/)", installed.enBody());
}
```

- [x] **Step 2: Run to verify it fails**

Run: `cd publication-exporter && mvn -q -Dtest=InstallToSiteHandlerTest test`

- [x] **Step 3: Wire resolution in, before the `managedSiteInstaller.installWithOutcome` call**

```java
// InstallToSiteHandler.java, inside installApprovedSnapshotUnderLock, replacing the direct
// `managedSiteInstaller.installWithOutcome(identity, planned)` call:
ApprovedTargetRegistry registry = ApprovedTargetRegistry.forOccurrences(
        planned.referenceMap().occurrences(), approvedSnapshotWorkspace);
List<Occurrence> occurrences = planned.referenceMap().occurrences();
String resolvedRuBody =
        OccurrenceMarkerResolver.resolve(planned.ruBody(), registry, occurrences, "ru").body();
String resolvedEnBody =
        OccurrenceMarkerResolver.resolve(planned.enBody(), registry, occurrences, "en").body();
CandidateSnapshot resolvedPlan = CandidateSnapshot.of(
        resolvedRuBody, resolvedEnBody, planned.ruFields(), planned.enFields(),
        planned.structuredData(), planned.referenceMap());
ManagedSiteInstallOutcome outcome;
try {
    outcome = managedSiteInstaller.installWithOutcome(identity, resolvedPlan);
```

(Keep every existing catch block and every other reference to `planned` inside this method — e.g. the
`planned.referenceMap().sameContentAs(...)` optimistic-concurrency check earlier in the method — pointed at
the original `planned`, not `resolvedPlan`: that check is about *approved-content* drift, unrelated to
occurrence resolution, and must keep comparing the actual approved snapshot.)

Import `ApprovedTargetRegistry`, `OccurrenceMarkerResolver`, `dev.eugene.publicationexporter.reference.Occurrence`.

- [x] **Step 4: Run tests to verify they pass**

Run: `cd publication-exporter && mvn -q -Dtest=InstallToSiteHandlerTest test`
Expected: PASS

- [x] **Step 5: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 6: Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java \
        src/test/java/dev/eugene/publicationexporter/installtosite/
git commit -m "feat: resolve ref: markers before installing to the managed site"
```

---

## Task 9: Acceptance: the full late-bound activation state sequence

**Files:**
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java` (create)

**Interfaces:**
- Consumes: everything above — this is the one true system-boundary acceptance test for this slice, matching
  SEM-04's three scenarios and SEM-05's second scenario end to end through real `PrepareHandler` →
  `MarkReviewedHandler` (or whatever this codebase's approval handler is actually named — confirm by reading
  `markreviewed/MarkReviewedHandler.java`, already read during design.md's investigation) → `BuildFromReviewHandler`.

- [x] **Step 1: Write the failing acceptance test**

```java
package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.release.NullReleaseOutputStore;
// ... plus whatever vault/intake/translation-worker nullable fixtures PrepareHandlerTest already uses —
// read that file's constructor-injection setup before writing this, and reuse the same wiring style
// (this codebase's nullable-adapter test doubles throughout: VaultReader.createNull, etc.)

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateBoundTargetActivationAcceptanceTest {

    @Test
    void referrerActivatesDeactivatesAndReactivatesAsTargetApprovalChangesWithoutReferrerRewrites() {
        // 1. Prepare and approve a referrer note whose body links [[Target]] to a target note
        //    that is NOT yet admitted (publish: false or absent from the vault). Confirm the
        //    resulting approved body carries the plain label (PCM-03's private/unresolved path —
        //    unaffected by this slice), and that build-from-review renders a plain label too.
        //
        // 2. Add the target note to the vault, admitted (publish: true), re-prepare the referrer
        //    (its raw body is unchanged, so re-preparing produces the same approved-eligible
        //    candidate — matching PrepareHandlerTest's existing "same content, no candidate churn"
        //    precedent) so the body now contains [Label](ref:<target's sourceId>); approve it.
        //    Record the referrer's approved reference-map hash at this point.
        //
        // 3. Prepare and approve the target note itself.
        //
        // 4. build-from-review the referrer: assert the release output contains a real,
        //    kind-correct, locale-prefixed route for the target in both RU and EN, and that the
        //    referrer's own approved snapshot bytes/hash are IDENTICAL to what was recorded before
        //    step 3 (SEM-04's "Target is approved later" — no referrer candidate or reapproval).
        //
        // 5. Un-approve or unpublish the target (however this codebase's existing tests simulate
        //    "target ceases to be selected" — check for an existing helper/pattern, e.g. removing
        //    its approved snapshot, before inventing a new mechanism) and build-from-review the
        //    referrer again: assert the release output now contains the plain label again
        //    (SEM-04's "Target becomes unpublished"), and the referrer's approved hash is STILL
        //    identical to step 2's recorded value.
    }
}
```

Write out the concrete fixture code for each numbered step above, following exactly the nullable-adapter
wiring `PrepareHandlerTest`'s and `BuildFromReviewHandlerTest`'s constructors already use — do not introduce
a new test-double style. This is the one task in this plan where you must read `PrepareHandlerTest.java`'s
setup section in full before writing a single line, since it owns the canonical fixture-construction pattern
this new acceptance test must match.

- [x] **Step 2: Run to verify it fails initially for the right reason**

Run: `cd publication-exporter && mvn -q -Dtest=LateBoundTargetActivationAcceptanceTest test`
Expected: FAIL only if any of Tasks 1-8 were skipped or mis-wired — by this point in the plan every
production piece should already exist, so this test should PASS on first run if Tasks 1-8 are complete and
correct. If it fails, that is signal a prior task's wiring has a gap — diagnose against design.md's five
Decisions before patching this test's expectations to match broken behavior.

- [x] **Step 3: If it fails, fix the specific wiring gap it surfaces (not the test)**

Do not weaken this test's assertions to make it pass. If genuinely mistaken about the fixture's expected
behavior (as opposed to production code being wrong), fix the test — but treat that as the exception, not
the default explanation.

- [x] **Step 4: Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: PASS, 0 failures.

- [x] **Step 5: Commit**

```bash
cd publication-exporter
git add src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java
git commit -m "test: acceptance-cover the full late-bound target activation state sequence"
```

---

## Self-review checklist (for whoever executes this plan)

- Every spec scenario in specs/semantic-references/spec.md (SEM-04's three scenarios, SEM-05's "New target
  becomes eligible" scenario) is covered by Task 9's acceptance test. SEM-05's "Target note moves" scenario
  is *not* re-covered here — it was already closed by S18/S19's `VaultSourceIdentityIndex` machinery and is
  unaffected by this slice (this slice only changes what happens once a target IS resolved; it does not
  change how a moved target's identity is resolved).
- Every spec scenario in specs/release-materialization/spec.md (REL-02's four scenarios including the new
  kind-correct-route one, REL-03's two scenarios) is covered by Tasks 6-7's unit tests plus Task 9's
  acceptance test — REL-02's "zero occurrences" scenario is implicitly covered by `OccurrenceMarkerResolver`
  returning the body unchanged when `occurrences` is empty (Task 6 Step 4's third test); no separate task
  needed.
- Every spec scenario in specs/public-content-model/spec.md (PCM-03's five scenarios) is covered: Task 3
  covers "Public target is unambiguous" (new marker form) and "Routable embed target is unaffected"; the
  existing, untouched `LinkResolverTest`/`PrepareHandlerTest` coverage continues to cover "Private,
  unresolved, or ambiguous," "Private target is transcluded," and "Embed target is a publishable asset."
