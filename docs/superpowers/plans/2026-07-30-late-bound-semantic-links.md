# Late-Bound Semantic Wikilinks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make approved inline Obsidian wikilinks activate and deactivate at
release time from target approval state, without rewriting, retranslating, or
reapproving referring pages.

**Architecture:** Approved pages become atomic RU/EN/reference triples.
Preparation resolves raw vault wikilinks to stable private target identities
and puts occurrence IDs in both review documents; release materialization loads
only selected approved triples and projects each occurrence to a localized link
or approved plain label. A journaled migration installs all triples before a
schema marker switches the repository from legacy to semantic mode, and an
Astro provenance manifest proves that the generated tree came from one
approved-only release.

**Tech Stack:** Java 21, Maven, JUnit 6, Jackson 2.22, SnakeYAML Engine, JNA
atomic exchange, Picocli, Node.js 22, Astro 7, Node test runner.

## Global Constraints

- Only non-embedded inline Obsidian wikilinks in Markdown bodies are semantic
  references; current transclusion blocking remains unchanged.
- Structured frontmatter `links: [public-id, ...]` and editorial reference
  tokens keep their existing representation.
- Target publication state, `publicId`, and route must never enter a referring
  page's translation source hash.
- RU occurrence order, EN occurrence order, and `references.json.order` must be
  exactly equal.
- Multiple occurrences may share a target, but every occurrence ID is unique
  within its page and lookup is always by ID.
- A selected note with no complete approved triple blocks new materialization.
- A selected note with an approved triple is released from that triple even
  when current source or translation candidates are newer.
- Generated or stale candidates never replace approved release input.
- Removing or restoring `publish: true` requires no translation review.
- Public output must contain no `ref:` URI, vault path, stable private
  reference ID, catalog data, or migration provenance.
- Legacy and semantic snapshots may be supported by the binary during
  migration, but one build may consume only one schema mode.
- Migration writes the semantic activation marker only after all page triples
  and the catalog are valid; an incomplete journal blocks builds.
- No new runtime dependency is needed; use existing Jackson, JNA, and
  `MarkdownScanner` infrastructure.
- Do not deploy automatically.
- Do not commit unless the user explicitly authorizes commits. Every commit
  step below is conditional and must otherwise be replaced with a `git diff`
  checkpoint.

---

## File and Responsibility Map

### Semantic-reference core

- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/ReferencePlan.java`
  for an unbound page reference plan before RU/EN byte hashes exist.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMap.java`
  for the persisted schema-version-1 sidecar.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMapCodec.java`
  for strict JSON serialization, hashing, and triple validation.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferenceMarkdown.java`
  for protected-context parsing, strict order extraction, and release
  projection.

### Vault identities and preparation

- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteDescriptor.java`
  and
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultReferenceCatalog.java`
  for private stable note identities.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultReferenceResolver.java`
  for whole-vault exact, unresolved, and ambiguous resolution.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferencePlanner.java`
  for occurrence-ID reconciliation.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/review/CandidateSnapshotStore.java`
  for atomic `candidate/{ru.md,en.md,references.json}` installation.
- Modify `LinkProcessor`, `ManifestBuilder`, `ManifestResult`,
  `PrepareWorkflow`, `ReviewWorkspace`, `ReviewLaunchPlanner`, and their tests.

### Approval and release

- Extend `PublishedSnapshotStore` to own an atomic three-file snapshot.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java`
  and
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
  for safe snapshot loading and selected-source matching.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedTargetRegistry.java`,
  `ReferenceImpactIndex.java`, `ReleaseInputGuard.java`, and
  `ApprovedReleaseMaterializer.java`.
- Modify `AstroExportCommand`, `CommandServices`, `BridgeResponse`,
  `ReportBuilder`, `SiteWriter`, and their tests.

### Migration and provenance

- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationAligner.java`,
  `ReferenceMigrationInventory.java`, and `SemanticMigrationService.java`.
- Create
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenance.java`
  and `ReleaseProvenanceWriter.java`.
- Modify `TreeHasher`, `SiteWriter`, `site/scripts/check-content.mjs`, site
  tests, CLI/native parity tests, e2e scripts, and exporter documentation.

## Task 1: Semantic Reference Schema, Parser, and Projector

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/ReferencePlan.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMap.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMapCodec.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferenceMarkdown.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/references/PageReferenceMapCodecTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferenceMarkdownTest.java`

**Interfaces:**

- Produces:
  `ReferencePlan(String pageRef, String sourcePath, List<String> order,
  Map<String, PageReferenceMap.Reference> references)`.
- Produces:
  `PageReferenceMap.bind(ReferencePlan, byte[] russian, byte[] english)`.
- Produces:
  `PageReferenceMapCodec.read(byte[], String)`,
  `PageReferenceMapCodec.write(PageReferenceMap)`, and
  `PageReferenceMapCodec.validate(PageReferenceMap, byte[], byte[])`.
- Produces:
  `SemanticReferenceMarkdown.occurrences(String)` and
  `SemanticReferenceMarkdown.project(String, PageReferenceMap,
  Function<PageReferenceMap.Reference, Optional<String>>)`.
- Produces:
  `SemanticReferenceMarkdown.normalizeHeadingFragment(String)`,
  `PageReferenceMap.withHashes(String, String)`,
  `PageReferenceMapCodec.sha256(byte[])`, and
  `PageReferenceMapCodec.ReferenceValidationException`.

- [ ] **Step 1: Write failing schema and strict-order tests**

```java
@Test
void bindsHashesAndRoundTripsDuplicateTargetsWithoutDependingOnMapOrder() {
  ReferencePlan plan = new ReferencePlan(
      "vault-ref-page",
      "blog/A.md",
      List.of("ref-0001", "ref-0002"),
      Map.of(
          "ref-0002", new PageReferenceMap.Reference(
              "vault-ref-target", "Target", "Second"),
          "ref-0001", new PageReferenceMap.Reference(
              "vault-ref-target", "Target", null)));
  byte[] ru = bytes("[первый](ref:ref-0001) [второй](ref:ref-0002)\n");
  byte[] en = bytes("[first](ref:ref-0001) [second](ref:ref-0002)\n");

  PageReferenceMap bound = PageReferenceMap.bind(plan, ru, en);
  byte[] json = PageReferenceMapCodec.write(bound);
  PageReferenceMap decoded = PageReferenceMapCodec.read(json, "references.json");

  PageReferenceMapCodec.validate(decoded, ru, en);
  assertEquals(plan.order(), decoded.order());
  assertEquals("vault-ref-target",
      decoded.references().get("ref-0002").targetRef());
}

@Test
void rejectsAnEnglishOrderSwapEvenWhenTheSameIdsExist() {
  ReferencePlan plan = plan("ref-0001", "ref-0002");
  byte[] ru = bytes("[один](ref:ref-0001) [два](ref:ref-0002)");
  byte[] en = bytes("[two](ref:ref-0002) [one](ref:ref-0001)");
  PageReferenceMap map = PageReferenceMap.bind(plan, ru, en);

  PageReferenceMapCodec.ReferenceValidationException error = assertThrows(
      PageReferenceMapCodec.ReferenceValidationException.class,
      () -> PageReferenceMapCodec.validate(map, ru, en));
  assertEquals("reference-order-mismatch", error.code());
}
```

- [ ] **Step 2: Run the focused tests and confirm the red state**

Run from `exporter-java/`:

```bash
mvn -q -Dtest=PageReferenceMapCodecTest,SemanticReferenceMarkdownTest test
```

Expected: compilation fails because the `references` package does not exist.

- [ ] **Step 3: Implement the immutable schema and strict JSON codec**

Use records with defensive copies and strict duplicate-key detection:

```java
public record PageReferenceMap(
    int schemaVersion,
    String pageRef,
    String sourcePath,
    String ruSha256,
    String enSha256,
    List<String> order,
    Map<String, Reference> references) {

  public static final int SCHEMA_VERSION = 1;

  public PageReferenceMap {
    order = List.copyOf(order);
    references = Map.copyOf(references);
  }

  public record Reference(
      String targetRef,
      String authoredTarget,
      String heading) { }
}
```

`PageReferenceMapCodec.validate` must, in this order:

1. require `schemaVersion == 1`;
2. require nonblank `pageRef` and normalized relative `sourcePath`;
3. compare `ruSha256` and `enSha256` with exact bytes;
4. parse semantic occurrences outside protected Markdown spans;
5. require RU order = EN order = sidecar order;
6. reject duplicate, missing, and unknown IDs;
7. require every sidecar record to occur exactly once per language.

Serialize `references` in sorted occurrence-ID order and preserve `order`
exactly. This makes JSON bytes deterministic without giving object order
semantic meaning.

`PageReferenceMap.bind` binds hashes without silently repairing semantic
content; callers must invoke `validate` before persistence. Put the current
heading normalization algorithm in
`SemanticReferenceMarkdown.normalizeHeadingFragment` and make legacy
`LinkProcessor` delegate to it so there is one rule.

- [ ] **Step 4: Implement protected-context parsing and ID-based projection**

Reuse `MarkdownScanner.protectedSpans(markdown)`. Parse only custom Markdown
destinations matching `ref:[A-Za-z0-9-]+`; ordinary links, escaped syntax,
inline code, fenced code, HTML comments, `<pre>` blocks, and Obsidian comments
must remain untouched.

```java
public static String project(
    String markdown,
    PageReferenceMap map,
    Function<PageReferenceMap.Reference, Optional<String>> href) {
  return replaceOutsideProtectedContexts(markdown, occurrence -> {
    PageReferenceMap.Reference reference =
        requiredReference(map, occurrence.id());
    Optional<String> destination = href.apply(reference);
    return destination
        .map(value -> "[" + occurrence.label() + "](" + value + ")")
        .orElse(occurrence.label());
  });
}
```

Add tests for repeated labels, repeated targets, different heading fragments,
escaped links, protected spans, missing IDs, duplicate IDs, and a resolver that
returns `Optional.empty()`.

- [ ] **Step 5: Run focused and adjacent Markdown tests**

```bash
mvn -q -Dtest=PageReferenceMapCodecTest,SemanticReferenceMarkdownTest,LinkProcessorTest,MarkdownNormalizationTest test
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Conditional checkpoint commit**

If and only if commits were explicitly authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/references exporter-java/src/test/java/dev/eugene/astroexport/references
git commit -m "feat: add semantic reference schema"
```

Otherwise inspect `git diff --check` and leave the changes uncommitted.

## Task 2: Stable Vault Catalog and Conservative Resolution

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteDescriptor.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultReferenceCatalog.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultReferenceResolver.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferencePlanner.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultReferenceCatalogTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultReferenceResolverTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferencePlannerTest.java`

**Interfaces:**

- Consumes: `ReferencePlan` and `PageReferenceMap.Reference` from Task 1.
- Produces:
  `VaultReferenceCatalog.load(Path reviewRoot)`,
  `VaultReferenceCatalog.reconcile(Path vaultRoot, List<VaultNoteDescriptor>)`,
  and `VaultReferenceCatalog.writeAtomically(Path reviewRoot)`.
- Produces:
  `VaultReferenceResolver.resolve(String sourcePath, String authoredTarget)`.
- Produces:
  `SemanticReferencePlanner.prepare(String sourcePath, String pageRef,
  String body, Optional<PageReferenceMap> previous,
  VaultReferenceResolver resolver)`.

- [ ] **Step 1: Write failing catalog and resolution tests**

```java
@Test
void exactPathReusesIdentityAndConfirmedStableIdReconcilesRename() {
  writeNote("notes/Old.md", "---\nid: stable-note\naliases: [Known]\n---\n");
  VaultReferenceCatalog catalog = VaultReferenceCatalog.empty();
  VaultReferenceCatalog first = catalog.reconcile(
      vault, VaultNoteDescriptor.scan(vault));
  String ref = first.requireByCurrentPath("notes/Old.md").pageRef();

  Files.move(vault.resolve("notes/Old.md"), vault.resolve("notes/New.md"));
  VaultReferenceCatalog renamed = first.reconcile(
      vault, VaultNoteDescriptor.scan(vault));

  assertEquals(ref, renamed.requireByCurrentPath("notes/New.md").pageRef());
  assertEquals(List.of("notes/Old.md"),
      renamed.requireByCurrentPath("notes/New.md").previousPaths());
}

@Test
void duplicateAliasesAreAmbiguousAndMissingTargetsStayUnresolved() {
  VaultReferenceResolver resolver = resolver(
      descriptor("notes/One.md", List.of("Shared")),
      descriptor("notes/Two.md", List.of("Shared")));

  assertEquals(Status.AMBIGUOUS,
      resolver.resolve("blog/A.md", "Shared").status());
  assertEquals(Status.UNRESOLVED,
      resolver.resolve("blog/A.md", "Future").status());
}
```

- [ ] **Step 2: Run the focused tests and confirm the red state**

```bash
mvn -q -Dtest=VaultReferenceCatalogTest,VaultReferenceResolverTest,SemanticReferencePlannerTest test
```

Expected: compilation fails for the new catalog and planner types.

- [ ] **Step 3: Implement safe whole-vault descriptors and catalog storage**

Store the catalog at:

```text
<review>/.semantic-links/catalog-v1.json
```

Use this schema:

```json
{
  "schemaVersion": 1,
  "entries": {
    "vault-ref-opaque": {
      "currentPath": "notes/Target.md",
      "stableNoteId": "optional-authored-id",
      "aliases": ["Known alias"],
      "previousPaths": [],
      "state": "active"
    }
  }
}
```

`VaultNoteDescriptor.scan` reads Markdown files without following symlinks and
extracts only vault-relative path, filename stem, frontmatter `id`, `title`,
and string aliases. Invalid UTF-8, escaping paths, duplicate stable IDs, and
copied identities produce per-note diagnostics. Exact-path identities for
unaffected notes remain usable; preparation blocks only when the current page
references an unsafe or ambiguous descriptor.

Reconcile in the approved order: exact current path, unique stable note `id`,
explicit previous path/alias already recorded in the catalog, then operator
reconciliation. Filename, title, and content similarity may appear in
diagnostics but may not merge catalog entries.

Write with a temporary sibling, file `force`, atomic exchange, and parent
directory `force`. Preserve deleted entries as `state: tombstone`.

- [ ] **Step 4: Implement Obsidian resolution precedence**

`VaultReferenceResolver` builds separate precedence layers matching existing
link behavior:

1. vault path without `.md`;
2. exact stable note `id`;
3. timestamp-stripped filename stem;
4. frontmatter title;
5. alias.

A match in an earlier layer wins. More than one match in the winning layer is
`AMBIGUOUS`; no match is `UNRESOLVED`. A unique descriptor returns its catalog
`pageRef`, current path, and raw heading.

- [ ] **Step 5: Implement occurrence planning and conservative ID reuse**

Return:

```java
public record PreparedSemanticBody(
    String markdown,
    ReferencePlan plan,
    List<PublicationDiagnostic> diagnostics) { }
```

For each raw inline wikilink outside protected contexts:

- unique target: emit `[approved label](ref:ref-NNNN)` and add a plan record;
- unresolved target: emit only the visible label and add a nonblocking
  `unresolved-reference` diagnostic;
- ambiguous target: throw `ReferencePlanningException` with code
  `ambiguous-reference-target`;
- embed: retain current asset handling or transclusion blocking, never create a
  semantic occurrence.

Reuse an old ID only when target ref, heading, label, order, and surrounding
context identify one previous occurrence. Align repeated identical
occurrences in order. If a move or edit permits more than one old-ID
assignment, return a blocking `reference-reconciliation-required` diagnostic.
Allocate new IDs as the lowest unused `ref-%04d` values above the previous
maximum.

- [ ] **Step 6: Run catalog, planner, and legacy link tests**

```bash
mvn -q -Dtest=VaultReferenceCatalogTest,VaultReferenceResolverTest,SemanticReferencePlannerTest,LinkProcessorTest test
```

Expected: all selected tests pass; the legacy `LinkProcessor` contract remains
unchanged for pre-migration mode.

- [ ] **Step 7: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/references exporter-java/src/test/java/dev/eugene/astroexport/references
git commit -m "feat: resolve stable vault references"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 3: Semantic Preparation and Atomic Candidate Triples

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/CandidateSnapshotStore.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticOperationLock.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticLinkContext.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/fs/JnaFileDescriptor.java:18-200`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/CandidateSnapshotStoreTest.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticSchemaStateTest.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticOperationLockTest.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/model/ManifestResult.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/links/LinkProcessor.java:16-96`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/manifest/ManifestBuilder.java:85-211`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java:271-740`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java:979-1238`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:59-109`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java:31-126`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/translation/TranslationProjection.java:42-80`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/manifest/ManifestBuilderTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`

**Interfaces:**

- Consumes: `SemanticReferencePlanner.PreparedSemanticBody` from Task 2.
- Produces: `ManifestResult.referencePlans()` keyed by `sourcePath`.
- Produces:
  `CandidateSnapshotStore.stage(Path pageDirectory, byte[] ru, byte[] en,
  byte[] references)`.
- Produces:
  `CandidateSnapshotStore.PendingCandidate.commit(
  List<WorkflowStateService.SnapshotGuard>)`.
- Produces:
  `SemanticLinkContext(VaultReferenceCatalog, VaultReferenceResolver,
  Map<String, Optional<PageReferenceMap>>)`.
- Produces:
  `ReviewWorkspace.readCandidateReferences(Path reviewRoot, String collection,
  String publicId)`.
- Produces:
  `SemanticSchemaState.mode(Path reviewRoot)` with `LEGACY`, `SEMANTIC`, and
  `MIGRATION_INCOMPLETE` modes.
- Produces:
  `SemanticOperationLock.acquireShared(Path reviewRoot)` and
  `SemanticOperationLock.acquireExclusive(Path reviewRoot)`.

- [ ] **Step 1: Write failing preparation invariance and atomicity tests**

```java
@Test
void targetPublicationStateDoesNotChangeReferrerHashOrOccurrencePlan() {
  Prepared referrerWhilePrivate = prepareFixture(false);
  Prepared referrerWhilePublic = prepareFixture(true);

  assertEquals(referrerWhilePrivate.entry().translationSourceHash(),
      referrerWhilePublic.entry().translationSourceHash());
  assertEquals(referrerWhilePrivate.referencePlan(),
      referrerWhilePublic.referencePlan());
  assertEquals("[label](ref:ref-0001)",
      referrerWhilePrivate.entry().body());
}

@Test
void installsCandidateRuEnAndReferencesAsOneDirectorySwap() {
  CandidateSnapshotStore store = new CandidateSnapshotStore();
  try (PendingCandidate pending = store.stage(
      page, bytes("ru"), bytes("en"), bytes("{\"schemaVersion\":1}"))) {
    pending.commit(List.of());
  }
  assertEquals(Set.of("ru.md", "en.md", "references.json"),
      leafNames(page.resolve("candidate")));
}
```

Add a `PrepareWorkflowTest` where the translation runner swaps two semantic
occurrence IDs in English and assert status `translation_failed` with
`reference-order-mismatch`.

Add `SemanticOperationLockTest` cases proving two shared leases coexist, an
exclusive lease is rejected while either shared lease exists, shared leases
are rejected during an exclusive lease, and closing the lease releases the
cross-process file lock.

- [ ] **Step 2: Run the focused tests and confirm the red state**

```bash
mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,SemanticOperationLockTest,PrepareWorkflowTest,ManifestBuilderTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest test
```

Expected: new tests fail because manifests do not carry reference plans,
candidate files are still independent `ru.md`/`en.md` leaves, and schema mode
and the operation lock do not exist.

- [ ] **Step 3: Add the schema-mode boundary before wiring new paths**

`SemanticSchemaState` owns:

```text
<review>/.semantic-links/schema-v1.active.json
```

At this task it needs strict marker parsing and the rule that any migration
journal without a complete matching marker yields `MIGRATION_INCOMPLETE`.
Task 9 adds journal lifecycle and recovery details. Production preparation and
review path selection remain legacy while marker and journal are absent, use
semantic candidate triples only when the marker is valid, and block while
migration is incomplete.

Use `<review>/.semantic-links/operations.lock` for a cross-process lock.
Preparation, approval, and build acquire a shared lease; migration apply and
recovery acquire an exclusive lease. Acquire the lease before reading schema
mode and retain it through each operation's commit boundary.

Add `LOCK_SH = 1` and `trySharedLock()` to `JnaFileDescriptor`, mirroring its
existing nonblocking exclusive `flock` behavior and errno handling.

- [ ] **Step 4: Add semantic link mode to manifest construction**

Extend `ManifestResult` with a final field:

```java
Map<String, ReferencePlan> referencePlans
```

Keep existing convenience constructors defaulting to `Map.of()` so unrelated
tests remain source-compatible.

Add:

```java
public ManifestResult buildRussianManifest(
    SelectionResult selection,
    SemanticLinkContext semanticLinks)
```

The existing one-argument method remains the legacy implementation until
cutover. In semantic mode, non-editorial body processing calls the planner
against the whole-vault resolver instead of resolving against selected public
notes. It still collects assets, blocks unpublished non-asset transclusions,
and records unresolved inline links as stripped diagnostics. Compute
`sourceHash` and `translationSourceHash` only after semantic Markdown is in the
body.

`SemanticLinkContext` supplies the catalog, resolver, page identity, and
previous approved map by source path. Do not pass selected-target routes into
it.

- [ ] **Step 5: Bind the candidate map after English validation**

Update the translation prompt with the exact contract:

```text
Every Markdown destination of the form ref:ref-NNNN is an invariant semantic
occurrence ID. Preserve every ID exactly once and in the exact order found in
ru.md. Translate only the visible label. Do not invent, remove, duplicate,
renumber, or reorder semantic occurrence IDs.
```

After `candidate.en.md` passes the existing translation validator:

1. obtain the entry's `ReferencePlan`;
2. render exact candidate RU bytes;
3. mark EN status `generated`;
4. call `PageReferenceMap.bind(plan, ruBytes, enBytes)`;
5. validate the full triple;
6. atomically install it under `candidate/`.

Do not overwrite a prior candidate before all three new leaves validate.
`CandidateSnapshotStore` should reuse the proven directory-swap, guard,
rollback, recovery-path, and safe-layout mechanics of
`PublishedSnapshotStore`, but target `candidate/`.

- [ ] **Step 6: Move review readers and launch planning to `candidate/`**

Update paths to:

```text
review/<collection>/<publicId>/candidate/ru.md
review/<collection>/<publicId>/candidate/en.md
review/<collection>/<publicId>/candidate/references.json
```

`ReviewLaunchPlanner` still opens only RU and EN for human review, but it must
safe-read and validate the sidecar before returning a plan. Its published
baseline check must regard two-file snapshots as legacy and three-file
snapshots as complete only after semantic activation.

- [ ] **Step 7: Run preparation and review tests**

```bash
mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,SemanticOperationLockTest,PrepareWorkflowTest,ManifestBuilderTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,TranslationProjectionTest test
```

Expected: all selected tests pass, including private/public target hash
invariance, duplicate target occurrences, unresolved labels, ambiguous target
blocking, and RU/EN order rejection.

- [ ] **Step 8: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport exporter-java/src/test/java/dev/eugene/astroexport
git commit -m "feat: prepare semantic review triples"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 4: Atomic Approved Triples and Approval Integration

**Files:**

- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/PublishedSnapshotStore.java:22-374`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:237-306`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java:31-126`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:155-284`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:474-841`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/PublishedSnapshotStoreTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`

**Interfaces:**

- Consumes: candidate triples from Task 3.
- Changes:
  `PublishedSnapshotStore.stageSemantic(Path, byte[], byte[], byte[])` and
  `PublishedSnapshotStore.stageLegacy(Path, byte[], byte[])`.
- Changes:
  `CommandServices.stageApprovedSnapshot(Path reviewRoot, String collection,
  String publicId, byte[] ru, byte[] en, byte[] references)`.
- Produces: complete
  `published/{ru.md,en.md,references.json}` snapshots.

- [ ] **Step 1: Extend atomic-store tests to a three-file invariant**

Replace pair-only helpers with triple helpers and add:

```java
@Test
void commitsAndRollsBackRuEnAndReferenceMapTogether() throws Exception {
  Path page = existingTriple("ru-v1", "en-v1", mapV1());
  PublishedSnapshotStore store = storeThatChangesGuardAfterVisibleCommit();

  try (PendingSnapshot pending = store.stageSemantic(
      page, bytes("ru-v2"), bytes("en-v2"), bytes(mapV2()))) {
    assertThrows(ConcurrentPublishedSnapshotException.class,
        () -> pending.commit(List.of(sourceGuard())));
  }

  assertTriple(page, "ru-v1", "en-v1", mapV1());
}

@Test
void rejectsLegacyPartialOrExtraPublishedLayoutsInSemanticMode() {
  writePublished("ru.md", "ru");
  writePublished("en.md", "en");
  assertThrows(IllegalArgumentException.class,
      () -> store.stageSemantic(
          page, bytes("new-ru"), bytes("new-en"), bytes(map())));
}
```

Retain every existing first-publish, exchange failure, post-visible failure,
guard conflict, hard-link, symlink, cleanup, recovery-disposition, and fsync
test, changing the assertion from pair to triple.

- [ ] **Step 2: Run the store tests and confirm the red state**

```bash
mvn -q -Dtest=PublishedSnapshotStoreTest test
```

Expected: failures show `references.json` is neither staged nor layout-checked.

- [ ] **Step 3: Generalize `PublishedSnapshotStore`**

Change:

```java
private static final Set<String> PUBLISHED_FILES =
    Set.of("ru.md", "en.md", "references.json");
```

Clone and retain all three byte arrays in `FilePendingSnapshot`. Write and
`force` all leaves before forcing the staging directory. Replace
`visiblePairMatches` with `visibleTripleMatches`; update every diagnostic to
say “snapshot” or “triple,” not “pair.” Exact layout validation must reject
missing, extra, symbolic, non-regular, or multiply hard-linked leaves.

Retain a separately named `stageLegacy(Path, byte[], byte[])` path used only
when `SemanticSchemaState.mode` is `LEGACY`. `stageSemantic` must reject a
two-file live layout, and `stageLegacy` must reject a three-file live layout.
No single approval or build may combine them.

- [ ] **Step 4: Make `mark-reviewed` validate and stage the exact candidate triple**

At the start of the locked approval section, safe-read:

```java
Path candidate = reviewDirectory.resolve("candidate");
byte[] candidateRu = readSafeRegularFile(candidate.resolve("ru.md"));
byte[] candidateEn = readSafeRegularFile(candidate.resolve("en.md"));
byte[] candidateReferences =
    readSafeRegularFile(candidate.resolve("references.json"));
```

After changing only EN `translationStatus` to `reviewed`, rebind
`enSha256` in the sidecar to the reviewed EN bytes and validate:

```java
PageReferenceMap candidateMap = PageReferenceMapCodec.read(
    candidateReferences, "candidate/references.json");
PageReferenceMap reviewedMap = candidateMap.withHashes(
    PageReferenceMapCodec.sha256(candidateRu),
    PageReferenceMapCodec.sha256(reviewedEn));
byte[] reviewedReferences = PageReferenceMapCodec.write(reviewedMap);
PageReferenceMapCodec.validate(reviewedMap, candidateRu, reviewedEn);
```

Use this exact boundary order:

1. acquire the shared semantic-operation lease, then atomically replace the
   candidate triple with the reviewed EN bytes and
   rebound sidecar, guarded by the original source and candidate leaves;
2. stage the exact candidate RU, reviewed EN, and reviewed sidecar bytes for
   `published/`;
3. revalidate source identity and all candidate leaves;
4. commit the approved triple;
5. only after the approved triple is durable, update source workflow state.

If step 5 fails, do not roll back the valid approved triple; return a workflow
diagnostic and let refresh reconcile frontmatter. Approval of one page must
never enumerate or write referrer snapshot directories.

- [ ] **Step 5: Update review-plan safety and approval responses**

`ReviewLaunchPlanner` must safe-read `published/references.json`, verify its
hashes and RU/EN order, and return
`published_snapshot_inconsistent` for partial or invalid triples. Do not expose
the sidecar as an editor target.

After approval, derive an impact count through the Task 6 interface when
available; until then return zero counts without referrer writes. Keep the
approval success boundary the durable triple commit, not impact-report
generation.

- [ ] **Step 6: Run approval and recovery tests**

```bash
mvn -q -Dtest=PublishedSnapshotStoreTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,AstroExportCommandTest test
```

Expected: all selected tests pass, including failures injected at every visible
swap and rollback boundary.

- [ ] **Step 7: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/review exporter-java/src/main/java/dev/eugene/astroexport/cli exporter-java/src/test/java/dev/eugene/astroexport
git commit -m "feat: approve atomic semantic snapshots"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 5: Approved Snapshot Repository and Selection Gate

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/SnapshotHashes.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseException.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`

**Interfaces:**

- Consumes: approved triples from Task 4 and the catalog from Task 2.
- Produces:
  `ApprovedSnapshotRepository.loadSelected(SelectionResult, Path reviewRoot,
  VaultReferenceCatalog)`.
- Produces:
  `ApprovedPageSnapshot(String collection, String publicId, String pageRef,
  String sourcePath, ManifestEntry russian, ManifestEntry english,
  PageReferenceMap references, SnapshotHashes hashes)`.

- [ ] **Step 1: Write failing selected-snapshot gate tests**

```java
@Test
void selectedNoteWithoutApprovedTripleBlocksWithItsSourcePath() {
  ApprovedReleaseException error = assertThrows(
      ApprovedReleaseException.class,
      () -> repository.loadSelected(selection("blog/New.md"), review, catalog));

  assertEquals("missing-approved-snapshot", error.code());
  assertEquals("blog/New.md", error.sourcePath());
}

@Test
void pendingCandidateDoesNotReplaceAnApprovedSnapshot() {
  writeApprovedTriple("blog/A.md", "approved body");
  writeCandidateTriple("blog/A.md", "pending body");

  ApprovedPageSnapshot snapshot = repository
      .loadSelected(selection("blog/A.md"), review, catalog)
      .getFirst();

  assertEquals("approved body", snapshot.russian().body());
}

@Test
void malformedPendingMetadataStillSelectsTheLastApprovedSnapshotBySourcePath() {
  writeApprovedTriple("blog/A.md", "approved body");
  writeCurrentSource("blog/A.md", "---\npublish: true\n---\npending");

  ApprovedPageSnapshot snapshot = repository
      .loadSelected(discover(), review, catalog)
      .getFirst();

  assertEquals("approved body", snapshot.russian().body());
}
```

Also test exact-path loading without a catalog, confirmed rename loading with a
catalog, ambiguous rename blocking, duplicate `pageRef`, duplicate `publicId`,
duplicate route, invalid hashes, wrong `sourcePath`, and unsafe leaves.

- [ ] **Step 2: Run repository tests and confirm the red state**

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest test
```

Expected: compilation fails because no approved repository exists.

- [ ] **Step 3: Expose strict approved Markdown parsing**

Refactor `ReviewWorkspace` so serialization and parsing share a package-visible
approved-document API:

```java
static ApprovedMarkdown parseApprovedMarkdown(
    byte[] bytes,
    String collection,
    String language,
    String displayPath)
```

The parser must:

- use strict YAML duplicate detection already present in `ReviewWorkspace`;
- reject control-field aliases and unsafe layouts;
- require RU `language: ru`, EN `language: en`, matching IDs, and EN
  `translationStatus: reviewed`;
- derive target path and route from approved collection, ID, and content type;
- never consult current draft metadata for released identity or wording.

- [ ] **Step 4: Implement exact and reconciled selection matching**

Build the selected source-path set from every parsed exact `publish: true`
source, including `SelectionResult.excluded()` entries whose current draft is
missing or has invalid publication metadata. Scan only
`review/<collection>/<publicId>/published/` directories. For every selected
source path:

1. match a sidecar `sourcePath` exactly;
2. otherwise accept one catalog-confirmed current-path reconciliation;
3. otherwise throw `missing-approved-snapshot`;
4. load all three leaves with no-follow, regular-file, one-hard-link checks;
5. validate map hashes and strict order;
6. construct immutable RU and EN `ManifestEntry` values.

Current draft metadata never owns released `publicId`, collection, content
type, route, or wording. Therefore an invalid pending draft still releases its
last approved snapshot, while the same selected path with no approved triple
blocks as `missing-approved-snapshot`.

After loading, reject duplicate page refs, public IDs, target paths, or routes
before returning any release input. Treat an incomplete migration journal as
`migration-incomplete`.

- [ ] **Step 5: Run repository and review parser tests**

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest,ReviewWorkspaceTest test
```

Expected: all selected tests pass.

- [ ] **Step 6: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/review exporter-java/src/main/java/dev/eugene/astroexport/release exporter-java/src/test/java/dev/eugene/astroexport/review
git commit -m "feat: load selected approved snapshots"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 6: Late-Bound Release Projection, Reverse Impact, and Unpublish

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedTargetRegistry.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ReferenceImpactIndex.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseInputGuard.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/release/ReferenceImpactIndexTest.java`

**Interfaces:**

- Consumes: `List<ApprovedPageSnapshot>` from Task 5.
- Produces:
  `ApprovedTargetRegistry.from(List<ApprovedPageSnapshot>)`.
- Produces:
  `ReferenceImpactIndex.from(List<ApprovedPageSnapshot>)`.
- Produces:
  `ApprovedReleaseMaterializer.materialize(List<ApprovedPageSnapshot>,
  Path vaultRoot)`.
- Returns:
  `ApprovedReleaseMaterializer.MaterializedRelease(ManifestResult manifest,
  ApprovedReleaseMaterializer.ActivationAudit audit,
  List<ApprovedReleaseMaterializer.IgnoredDraft> ignoredDrafts,
  ApprovedTargetRegistry registry,
  ReleaseInputGuard inputGuard)`.

Define `MaterializedRelease`, `ActivationAudit`, `Activation`, and
`IgnoredDraft` as public nested records in `ApprovedReleaseMaterializer`.
Define `InboundReference` as a public nested record in
`ReferenceImpactIndex`.

- [ ] **Step 1: Write the decisive projection tests**

```java
@Test
void activatingTargetChangesOutputButNotReferrerApprovedHashes() {
  ApprovedPageSnapshot a = approved(
      "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
      reference("ref-0001", "vault-ref-b"));
  SnapshotHashes before = a.hashes();

  MaterializedRelease privateTarget =
      materializer.materialize(List.of(a), vault);
  MaterializedRelease publicTarget =
      materializer.materialize(List.of(a, approvedTargetB()), vault);

  assertEquals("Б", body(privateTarget, "A", "ru"));
  assertEquals("[Б](/ru/notes/b/)", body(publicTarget, "A", "ru"));
  assertEquals("[B](/en/notes/b/)", body(publicTarget, "A", "en"));
  assertEquals(before, a.hashes());
}

@Test
void duplicateTargetOccurrencesUseIdsAndKeepStrictOrder() {
  ApprovedPageSnapshot a = approvedWithReferences(
      List.of("ref-0007", "ref-0008", "ref-0009"),
      Map.of(
          "ref-0007", target("b", null),
          "ref-0008", target("c", "Limits"),
          "ref-0009", target("b", "Experiments")));

  ActivationAudit audit = materializer
      .materialize(List.of(a, approvedTargetB(), approvedTargetC()), vault)
      .audit();

  assertEquals(List.of("ref-0007", "ref-0008", "ref-0009"),
      audit.forPage(a.pageRef()).stream()
          .map(Activation::occurrenceId).toList());
}
```

Add parameterized cases for one, twenty, and one hundred inbound occurrences;
pending target drafts; route changes; heading normalization; private target;
unpublish; republish; RU/EN locale routes; and concurrent source, catalog, or
approved-snapshot changes.

- [ ] **Step 2: Run release tests and confirm the red state**

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest test
```

Expected: compilation fails for the release package.

- [ ] **Step 3: Build the immutable approved-target registry**

Index:

```java
public record Target(
    String pageRef,
    String publicId,
    String ruRoute,
    String enRoute) { }
```

Only selected, complete, unique approved snapshots enter the registry. The
repository has already enforced the selected set; do not read candidate state
or raw target metadata here.

Build the registry and reverse index once with hash maps, then project each
page in one pass. Do not rescan all pages for each target or occurrence; total
materialization work must remain O(approved pages + semantic occurrences).

Normalize an occurrence heading with
`SemanticReferenceMarkdown.normalizeHeadingFragment`, which Task 1 made the
single home of the existing `LinkProcessor.headingFragment` algorithm. Append
the same normalized fragment in both languages, preserving current behavior.

- [ ] **Step 4: Project each approved body and enforce the public-output gate**

For each language:

```java
String projected = SemanticReferenceMarkdown.project(
    approvedBody,
    page.references(),
    reference -> registry.find(reference.targetRef())
        .map(target -> target.route(language)
            + SemanticReferenceMarkdown.normalizeHeadingFragment(
                reference.heading())));
```

Preserve all non-reference bytes. Build `ManifestResult` from projected
approved entries and collect assets from approved RU Markdown, not current
draft bodies.

Before returning, reject:

- any remaining semantic `ref:` destination;
- any `vault-ref-` identifier;
- any catalog path or `authoredTarget` serialization;
- a RU route in EN body or EN route in RU body;
- an activation sequence different from sidecar order.

Capture exact byte guards for every selected source file, every loaded
approved RU/EN/reference leaf, and the catalog when it supplied a rename
reconciliation. `ReleaseInputGuard.verify()` returns normally only while all
paths remain safe regular files with the captured bytes.

- [ ] **Step 5: Derive, do not persist, the reverse index**

Build:

```java
Map<String, List<InboundReference>>
```

where `InboundReference` contains referring `pageRef`, `publicId`,
`occurrenceId`, and order index. Derive counts for target approval,
unpublication, republish, and route-change reporting. No method in this class
may write referrer files.

- [ ] **Step 6: Verify unpublish and republish semantics**

The test sequence must:

1. materialize A + approved B and see links;
2. materialize only A and see approved labels;
3. materialize A + the same approved B and see links return;
4. compare A's RU, EN, and map hashes after every materialization;
5. assert zero translation, review, approval, and snapshot-write callbacks.

Also assert B's approved snapshot remains on disk while unpublished. Inject a
source selection change and an approved-snapshot replacement after staging;
the release input guard must abort and allow retry rather than install a mixed
release.

Run:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,SemanticReferenceMarkdownTest test
```

Expected: all selected tests pass.

- [ ] **Step 7: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/release exporter-java/src/test/java/dev/eugene/astroexport/release
git commit -m "feat: project approved semantic links"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 7: Approved-Only CLI, Independent State Dimensions, and Reports

**Files:**

- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:34-309`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:155-263`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:426-1042`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:1550-1660`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java:13-197`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/report/ReportBuilder.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:153-438`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/report/ReportBuilderTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/nativeimage/NativeCliParityTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/fs/SiteWriterTest.java`

**Interfaces:**

- Consumes: approved repository, materializer, and impact index.
- Produces:
  `CommandServices.buildApprovedRelease(Path vault, Path review)`.
- Changes:
  `SiteWriter.writeSiteAtomic(Path, ManifestResult, Consumer<Path>,
  SiteWriter.CommitGuard)`.
- Changes bridge response schema from version 2 to version 3.
- Produces independent fields:
  `candidateState`, `approvedSnapshotState`, `semanticReferencesState`, and
  `releaseState`.

- [ ] **Step 1: Write failing CLI gate and state tests**

```java
@Test
void buildFromReviewBlocksSelectedUnapprovedNote() {
  fixture.writeSelectedNote("blog/new.md");

  CommandResult result = command(
      "build-from-review", "--vault", vault, "--review", review,
      "--out", site);

  assertEquals(1, result.exitCode());
  assertThat(result.stderr())
      .contains("missing-approved-snapshot")
      .contains("blog/new.md");
}

@Test
void buildFromReviewIgnoresFreshGeneratedCandidateWhenApprovedExists() {
  fixture.writeApprovedTriple("blog/a.md", "approved");
  fixture.writeCandidateTriple("blog/a.md", "generated draft");

  assertEquals(0, buildFromReview().exitCode());
  assertEquals("approved", generatedBody("a", "ru"));
}
```

Add bridge assertions:

```json
{
  "candidateState": "stale",
  "approvedSnapshotState": "valid",
  "semanticReferencesState": "valid",
  "releaseState": "releasable"
}
```

- [ ] **Step 2: Run focused CLI and report tests**

```bash
mvn -q -Dtest=AstroExportCommandTest,ReportBuilderTest,NativeCliParityTest test
```

Expected: failures show current `runExport` still rebuilds current RU and
generated/reviewed EN candidates.

- [ ] **Step 3: Route release writes through approved materialization**

Add:

```java
public ApprovedReleaseMaterializer.MaterializedRelease buildApprovedRelease(
    Path vault,
    Path review) {
  SelectionResult selection = select(vault);
  VaultReferenceCatalog catalog = VaultReferenceCatalog.loadIfPresent(review);
  List<ApprovedPageSnapshot> snapshots =
      approvedSnapshots.loadSelected(selection, review, catalog);
  return approvedReleaseMaterializer.materialize(snapshots, vault);
}
```

`build-from-review` and every non-dry-run site write must use this method after
semantic activation. Dry-run may inspect current candidates, but its report
must say that it is not release input. Do not call `writeRuReview` during an
approved release.

Pass `release.inputGuard()::verify` through `CommandServices.writeSite` as the
generic `SiteWriter.CommitGuard`. Invoke it after the Astro gate, immediately
before the first live managed-tree move, at each existing forward boundary,
and once after all roots are installed but before displaced-tree cleanup. A
guard failure uses the existing rollback path and reports
`concurrent-approved-snapshot-change`.

Acquire the shared semantic-operation lease before reading schema mode and
retain it until `SiteWriter` has committed or rolled back all managed roots.

Before the activation marker exists, keep the legacy code path intact for the
migration window. If a migration journal exists without a completed marker,
both modes block with `migration-incomplete`.

- [ ] **Step 4: Separate inspection state dimensions**

Increment `BridgeResponse.SCHEMA_VERSION` to `3` and add nullable builder fields
in this exact JSON order after `translationStatus`:

```text
candidateState
approvedSnapshotState
semanticReferencesState
releaseState
```

Derive them independently:

- candidate: `absent|generated|reviewed|stale`;
- approved snapshot: `absent|valid|invalid`;
- semantic references: `valid|migration-required|invalid`;
- release: `releasable|blocked`.

Do not add new workflow frontmatter. Retain old pair fields for one bridge
schema transition and mark them compatibility-only in documentation.

- [ ] **Step 5: Report approval impact and ignored drafts**

`mark-reviewed` success uses the derived reverse index to report:

```text
Inbound links activated: 107
Affected approved pages: 31
Pending-draft referrers: 4
```

The release report lists ignored candidates separately from blocking
diagnostics. Add stable diagnostic codes for every code in the design:
`missing-approved-snapshot`, `approved-snapshot-incomplete`,
`reference-map-hash-mismatch`, `reference-order-mismatch`,
`duplicate-reference-occurrence`, `missing-reference-occurrence`,
`unknown-reference-occurrence`, `catalog-reconciliation-required`,
`duplicate-approved-identity`, `private-reference-leak`,
`migration-incomplete`, `concurrent-approved-snapshot-change`, and
`release-provenance-mismatch`.

- [ ] **Step 6: Run CLI, report, and native parity tests**

```bash
mvn -q -Dtest=AstroExportCommandTest,ReportBuilderTest,NativeCliParityTest,ReviewLaunchPlannerTest,SiteWriterTest test
```

Expected: all selected tests pass and bridge fixtures use schema version 3.

- [ ] **Step 7: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/cli exporter-java/src/main/java/dev/eugene/astroexport/report exporter-java/src/test/java/dev/eugene/astroexport
git commit -m "feat: gate releases on approved snapshots"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 8: Read-Only Legacy Migration Inventory and Aggregate Decisions

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationAligner.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationAlignerTest.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/nativeimage/NativeCliParityTest.java`

**Interfaces:**

- Consumes: current raw vault notes, legacy approved RU/EN pairs, current
  routes, catalog resolution, and semantic parser.
- Produces:
  `ReferenceMigrationInventory.inspect(Path vault, Path review)`.
- Produces deterministic JSON with `inventorySha256`, page/occurrence
  classifications, contexts, and proposed triples.
- Adds CLI:
  `migrate-semantic-links --vault --review --astro --report --json`.

- [ ] **Step 1: Write failing alignment-classification tests**

```java
@Test
void classifiesExactDirectAndStrippedLegacyReferences() {
  MigrationPage page = aligner.align(
      raw("See [[Public|public]] then [[Private|private]]."),
      approvedRu("See [public](/ru/notes/public/) then private."),
      approvedEn("See [public](/en/notes/public/) then private."),
      resolver(publicTarget(), privateTarget()));

  assertEquals(List.of(EXACT, EXACT),
      page.occurrences().stream().map(MigrationOccurrence::classification)
          .toList());
}

@Test
void classifiesEnglishOrderSwapInsteadOfPairingByOrdinal() {
  MigrationPage page = aligner.align(
      raw("[[B|one]] [[C|two]]"),
      approvedRu("[one](/ru/b/) [two](/ru/c/)"),
      approvedEn("[two](/en/c/) [one](/en/b/)"),
      resolver(targetB(), targetC()));

  assertEquals(ORDER_MISMATCH, page.status());
  assertFalse(page.automatic());
}
```

Add cases for duplicate targets, duplicate visible labels, unresolved raw
target, multiple possible EN spans, protected contexts, partial legacy pair,
symlink, hard link, unreadable UTF-8, and a legacy route no longer matching the
current target route. Add a current-source-drift case where raw context cannot
be proven to correspond to the approved RU bytes; classify it as
`unsafe-input`, never as an exact mapping.

- [ ] **Step 2: Run migration inventory tests and confirm the red state**

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest test
```

Expected: compilation fails because the migration package and command do not
exist.

- [ ] **Step 3: Implement sequence-aware legacy alignment**

The aligner receives ordered raw wikilink occurrences plus parsed approved RU
and EN documents. It must never zip arrays blindly. Build candidate spans from:

- exact legacy Markdown links with retained routes;
- exact approved visible labels outside protected contexts;
- paragraph and surrounding-text anchors;
- monotonic document positions.

Use dynamic programming to enumerate monotonic assignments. Classification is:

- `exact` when exactly one RU/EN/target alignment exists with equal order;
- `unresolved-target` when the raw target has no unique vault identity;
- `ambiguous-translation` when more than one EN span assignment survives;
- `order-mismatch` when unique RU and EN assignments have different target
  order;
- `unsafe-input` for unsafe or incomplete approved bytes.

Never infer an English occurrence from translated label equality alone.

- [ ] **Step 4: Implement deterministic inventory JSON**

For each occurrence include:

```json
{
  "occurrenceKey": "vault-ref-page/ref-0007",
  "classification": "ambiguous-translation",
  "rawWikilink": "[[Target|label]]",
  "sourceContext": "before ... after",
  "ruContext": "before ... after",
  "proposedEnContext": "before ... after",
  "sourceOrdinal": 7,
  "targetRef": "vault-ref-target",
  "heading": null,
  "reason": "two monotonic EN spans remain"
}
```

`unresolved-target` occurrences are proposed as approved plain text with no
sidecar record. `exact` occurrences receive deterministic occurrence IDs and a
proposed semantic triple. Ambiguous and order-mismatch pages do not receive an
applicable triple until confirmed.

Reuse every existing catalog ID. When inventory must propose the first catalog,
derive provisional opaque `vault-ref-` IDs from SHA-256 over a
schema-domain separator plus normalized vault path. These IDs are private and
become durable only at apply. This keeps repeated read-only inventories
byte-deterministic without mutating the review workspace.

Canonicalize the full inventory without its `inventorySha256`, hash it, then
write the hash field. Inventory may write its requested report file but must
not mutate the review workspace, catalog, approved pairs, candidates, source
notes, Astro tree, or activation state.

- [ ] **Step 5: Define and validate aggregate decision input**

The optional decisions JSON consumed by Task 9 uses:

```json
{
  "schemaVersion": 1,
  "inventorySha256": "exact inventory hash",
  "decisions": {
    "vault-ref-page/ref-0007": {
      "decision": "confirm",
      "enSpan": {"start": 120, "end": 125}
    },
    "vault-ref-page/order": {
      "decision": "approve-corrected-order",
      "correctedEnglishPath": "corrected/page-en.md",
      "correctedEnglishSha256": "..."
    }
  }
}
```

Resolve `correctedEnglishPath` relative to the decisions file's directory and
reject absolute or escaping paths. Reject missing, unknown, duplicate,
stale-inventory, hash-mismatch, or unsupported decisions. A corrected English
document must validate as a complete review and have exact RU reference order.

- [ ] **Step 6: Wire the read-only CLI and native metadata**

Add Picocli command:

```text
migrate-semantic-links
  --vault <path>
  --review <path>
  --astro <path>
  --report <path>
  --json
```

Without `--apply`, it only writes the inventory report and returns nonzero when
decisions are required. Its bridge payload includes exact, confirmed-needed,
unresolved, order-mismatch, and unsafe page/occurrence counts.

- [ ] **Step 7: Run inventory and CLI tests**

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Expected: all selected tests pass, and a filesystem snapshot proves inventory
changed only its explicit report path.

- [ ] **Step 8: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/migration exporter-java/src/main/java/dev/eugene/astroexport/cli exporter-java/src/test/java/dev/eugene/astroexport
git commit -m "feat: inventory legacy reference migration"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 9: Journaled All-or-Nothing Semantic Cutover

**Files:**

- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- Modify:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticSchemaStateTest.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`

**Interfaces:**

- Consumes: a fresh inventory plus validated decisions from Task 8.
- Produces:
  `<review>/.semantic-links/schema-v1.active.json`.
- Owns:
  `<review>/.semantic-links/migration-v1.lock`,
  `<review>/.semantic-links/migration-v1.journal.json`, and private staging
  directories.
- Adds CLI flags:
  `--apply --decisions <path>` and recovery
  `--roll-forward|--roll-back`.

- [ ] **Step 1: Write failure-injection tests for every cutover boundary**

```java
@Test
void activationMarkerIsWrittenOnlyAfterEveryTripleIsInstalled() {
  MigrationHooks hooks = failAfterInstalledPage(2);

  assertThrows(MigrationIncompleteException.class,
      () -> service.apply(request(), hooks));

  assertFalse(Files.exists(schemaState.activationMarker()));
  assertEquals("installed", journal().pages().get(0).state());
  assertEquals("installed", journal().pages().get(1).state());
  assertEquals("staged", journal().pages().get(2).state());
  assertBuildBlocked("migration-incomplete");
}

@Test
void rollBackRestoresEveryLegacyPairByteForByte() {
  byte[] before = snapshotReviewBytes();
  failAfterInstalledPage(2);

  service.recover(request(), RecoveryMode.ROLL_BACK);

  assertArrayEquals(before, snapshotReviewBytes());
  assertFalse(Files.exists(schemaState.activationMarker()));
}
```

Cover failure after catalog stage, each page stage, each page install, parity
projection, Astro gate, marker write, journal force, displaced cleanup, and
lock release.

- [ ] **Step 2: Run cutover tests and confirm the red state**

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Expected: compilation fails for the migration service and new journal-aware
schema-state API.

- [ ] **Step 3: Implement schema-state gating**

Use:

```text
<review>/.semantic-links/schema-v1.active.json
```

with:

```json
{
  "schemaVersion": 1,
  "inventorySha256": "...",
  "catalogSha256": "...",
  "activatedAt": "2026-07-30T00:00:00Z"
}
```

Retain the Task 3 `LEGACY|SEMANTIC|MIGRATION_INCOMPLETE` API and strengthen its
journal validation against the full schema below. Any journal without a
matching complete marker returns `MIGRATION_INCOMPLETE`; builds, normal
approval, and preparation block rather than guessing.

- [ ] **Step 4: Stage and validate the complete cutover**

Under the Task 3 no-follow exclusive semantic-operation lock:

1. recompute inventory and require the decisions' `inventorySha256`;
2. stage the reconciled catalog;
3. stage every proposed approved triple;
4. validate hashes, identities, exact layouts, and strict RU/EN order;
5. project a full semantic release against the current approved target set;
6. compare exact pages byte-for-byte or structurally with legacy output;
7. require every corrected difference to have an explicit decision;
8. run the Astro content gate against staged output;
9. persist journal state and `force` after each irreversible boundary.

Do not change live approved pairs during these steps.

- [ ] **Step 5: Install with durable roll-forward and rollback evidence**

For each page, use the atomic published-snapshot exchange and retain the
displaced legacy pair in a journal-owned recovery directory. Journal states
are:

```text
planned -> staged -> installed -> verified -> cleanup-pending -> complete
```

After every page is `verified`, atomically install the catalog, write and force
the activation marker last, then clean displaced legacy bytes. Cleanup failure
after marker installation is nonblocking but reports exact recovery paths.

`--roll-forward` resumes from recorded staged bytes after revalidating their
hashes. `--roll-back` exchanges installed triples back to the exact displaced
legacy pairs and removes no evidence until the rollback is verified.

- [ ] **Step 6: Wire apply and recovery CLI modes**

Examples:

```bash
./target/astro-export migrate-semantic-links \
  --vault "$VAULT_ROOT" \
  --review "$REVIEW_ROOT" \
  --astro "$ASTRO_ROOT" \
  --report migration-inventory.json \
  --decisions migration-decisions.json \
  --apply \
  --json
```

```bash
./target/astro-export migrate-semantic-links \
  --review "$REVIEW_ROOT" \
  --roll-forward \
  --json
```

Require exactly one of normal inventory, `--apply`, `--roll-forward`, or
`--roll-back`. Error messages name the journal and recovery paths.

- [ ] **Step 7: Run cutover, repository, and CLI tests**

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,ApprovedSnapshotRepositoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Expected: all selected tests pass, including activation-last and byte-exact
rollback.

- [ ] **Step 8: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/migration exporter-java/src/main/java/dev/eugene/astroexport/cli exporter-java/src/main/java/dev/eugene/astroexport/review exporter-java/src/test/java/dev/eugene/astroexport
git commit -m "feat: migrate semantic snapshots atomically"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 10: Release Provenance and Astro Build Gate

**Files:**

- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenance.java`
- Create:
  `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseProvenanceWriter.java`
- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/release/ReleaseProvenanceWriterTest.java`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/fs/TreeHasher.java:14-75`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:40-160`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java:267-438`
- Modify:
  `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:279-309`
- Modify: `site/scripts/check-content.mjs`
- Modify: `site/package.json`
- Create: `site/tests/release-provenance.test.mjs`
- Modify: `site/tests/task4-content-boundaries.test.mjs`
- Test:
  `exporter-java/src/test/java/dev/eugene/astroexport/fs/SiteWriterTest.java`

**Interfaces:**

- Consumes: `MaterializedRelease` from Task 6.
- Produces:
  `<astro>/.astro-export/release-provenance.json`.
- Produces:
  `ReleaseProvenanceWriter.write(Path stagedRoot, MaterializedRelease release)`.
- Adds environment:
  `ASTRO_RELEASE_MANIFEST`.

- [ ] **Step 1: Write failing Java provenance tests**

```java
@Test
void manifestHashesPayloadWithoutHashingItself() {
  ReleaseProvenance provenance =
      writer.write(stagedSite, materializedRelease());

  assertEquals(List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages"),
      provenance.managedTrees().stream()
          .map(ManagedTreeHash::relative).toList());
  assertFalse(provenance.managedFiles().stream()
      .anyMatch(file -> file.path()
          .equals(".astro-export/release-provenance.json")));
  assertEquals(provenance,
      writer.verify(stagedSite));
}
```

Add tests for deterministic output, selected snapshot hashes, projection
hashes, activation/deactivation counts, tampered content, tampered manifest,
extra managed files, and missing manifest.

- [ ] **Step 2: Write failing Node gate tests**

```js
test("accepts the last provenance-valid materialized release", async () => {
  const fixture = await writeProvenanceFixture();
  const result = await runGate(fixture.env);
  assert.match(result.stdout, /Content validation passed/);
});

test("rejects a modified managed file", async () => {
  const fixture = await writeProvenanceFixture();
  await appendFile(fixture.ruMarkdown, "\nmanual change\n");
  await assertGateRejects(fixture.env, /release-provenance-mismatch/i);
});
```

- [ ] **Step 3: Run focused tests and confirm the red state**

```bash
mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest test
node --test tests/release-provenance.test.mjs
```

Run Maven from `exporter-java/` and Node from `site/`.
Expected: Java compilation fails and the Node gate currently ignores
provenance.

- [ ] **Step 4: Separate payload hashes from atomic replaced roots**

Keep payload roots:

```java
public static final List<String> PAYLOAD_ROOTS = List.of(
    "public/assets/vault",
    "src/content",
    "src/data/pages");
```

Add `.astro-export` to the roots atomically replaced by `SiteWriter`, but not
to `PAYLOAD_ROOTS`. The writer sequence is:

1. serialize content, pages, and assets;
2. compute canonical ordered file records and payload root hashes;
3. create `.astro-export/`;
4. write and force `release-provenance.json`;
5. compute ordinary transaction evidence over all replaced roots, including
   the now-complete provenance directory;
6. run the gate;
7. atomically replace all roots together.

This avoids a recursive digest while ensuring manifest tampering is covered by
the existing staged-site capability and transaction evidence.

- [ ] **Step 5: Serialize exact provenance**

Use:

```json
{
  "schemaVersion": 1,
  "selectedPages": [
    {
      "pageRef": "vault-ref-a",
      "publicId": "a",
      "sourcePath": "blog/A.md",
      "ruSha256": "...",
      "enSha256": "...",
      "referencesSha256": "...",
      "ruProjectionSha256": "...",
      "enProjectionSha256": "..."
    }
  ],
  "managedTrees": [{"relative": "src/content", "sha256": "..."}],
  "managedFiles": [{"path": "src/content/blog/ru/a.md", "sha256": "..."}],
  "activationCount": 1,
  "deactivationCount": 0,
  "payloadDigest": "..."
}
```

Sort selected pages by Unicode code point order and managed files by normalized
forward-slash path. Hash the canonical manifest payload excluding only the
manifest file itself.

- [ ] **Step 6: Enforce provenance in `check-content.mjs`**

Resolve `ASTRO_RELEASE_MANIFEST` when supplied by Java; otherwise default to:

```js
path.join(workspaceRoot, ".astro-export", "release-provenance.json")
```

Verify provenance when `ASTRO_RELEASE_MANIFEST` is set, when the default
manifest exists, or when `ASTRO_REQUIRE_RELEASE_PROVENANCE=1`. Set
`ASTRO_REQUIRE_RELEASE_PROVENANCE=1` in the `npm run build` script so direct
production builds cannot bypass the gate; keep `npm run check` usable for
isolated content-contract fixtures that supply neither flag nor manifest.

Before existing content validation when provenance is required:

- require a regular non-symbolic manifest;
- parse schema version 1;
- enumerate exactly the payload roots;
- reject symlinks and unsupported entries;
- compare file set, file hashes, root hashes, and payload digest;
- scan public Markdown/JSON for `ref:ref-`, `vault-ref-`, and catalog marker
  serialization;
- emit `[Release Provenance Error] release-provenance-mismatch: ...`.

Tests that override content/page roots must also pass a fixture manifest path;
do not weaken the live default.

- [ ] **Step 7: Run Java and site gates**

```bash
mvn -q -Dtest=ReleaseProvenanceWriterTest,SiteWriterTest,AstroExportCommandTest test
node --test tests/release-provenance.test.mjs tests/task4-content-boundaries.test.mjs
npm run check
```

Run Maven from `exporter-java/`; run Node and npm from `site/`.
Expected: all commands pass. A direct `npm run build` can rebuild the unchanged
last materialized release and rejects modified or provenance-less managed
content.

- [ ] **Step 8: Conditional checkpoint commit**

If authorized:

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport exporter-java/src/test/java/dev/eugene/astroexport site/scripts/check-content.mjs site/tests
git commit -m "feat: gate Astro builds with release provenance"
```

Otherwise run `git diff --check` and retain the uncommitted checkpoint.

## Task 11: End-to-End Acceptance, Real-Vault Dry Run, and Documentation

**Files:**

- Create:
  `exporter-java/src/test/java/dev/eugene/astroexport/acceptance/LateBoundSemanticLinksAcceptanceTest.java`
- Modify:
  `exporter-java/src/test/java/dev/eugene/astroexport/nativeimage/NativeCliParityTest.java`
- Create: `e2e/run-synthetic.sh`
- Create: `e2e/fixtures/semantic-vault/`
- Create: `e2e/fixtures/semantic-review/`
- Modify: `e2e/run.sh`
- Modify: `e2e/README.md`
- Modify: `exporter-java/README.md`
- Modify: `exporter-java/scripts/build-from-review.sh`
- Modify: `exporter-java/scripts/build-astro-site.sh`

**Interfaces:**

- Consumes all preceding tasks.
- Produces one automated acceptance scenario and one explicit, non-mutating
  real-vault migration inventory procedure.

- [ ] **Step 1: Write the complete acceptance test before final wiring**

```java
@Test
void targetApprovalActivatesBothLanguagesWithoutReferrerWrites() {
  approveAWhileBPrivate();
  TripleHashes aBefore = approvedHashes("a");

  MaterializedRelease first = buildApprovedRelease();
  assertEquals("B label", body(first, "a", "ru"));
  assertEquals("B label EN", body(first, "a", "en"));

  setPublishTrue("b");
  assertBuildBlocked("missing-approved-snapshot", "b");
  prepareReviewAndApprove("b");

  MaterializedRelease second = buildApprovedRelease();
  assertEquals(aBefore, approvedHashes("a"));
  assertEquals("[B label](/ru/notes/b/)", body(second, "a", "ru"));
  assertEquals("[B label EN](/en/notes/b/)", body(second, "a", "en"));
  assertEquals(0, translationJobsFor("a"));
  assertEquals(0, snapshotWritesFor("a"));

  removePublish("b");
  assertPlainLabelsAndMissingTarget(buildApprovedRelease());
  setPublishTrue("b");
  assertLinksAndTargetRestored(buildApprovedRelease());
  assertEquals(aBefore, approvedHashes("a"));
}
```

Add a second scenario with 100 inbound occurrences across 20 referrers,
including repeated references to the same target and different fragments. Save
every referrer triple hash before target approval and compare afterward.

- [ ] **Step 2: Run the acceptance test and fix only integration gaps**

```bash
mvn -q -Dtest=LateBoundSemanticLinksAcceptanceTest test
```

Expected before final wiring: failures identify missing integration only; do
not relax component invariants.

- [ ] **Step 3: Update native CLI parity**

Exercise real native subcommands, not only `--help`:

```text
migrate-semantic-links --report ... --json
migrate-semantic-links --apply --decisions ... --json
prepare --json
inspect-publication --json
mark-reviewed --json
build-from-review
```

Assert bridge schema version 3 and all four independent state fields. Include
missing approval, migration-incomplete, order mismatch, and successful
activation cases.

- [ ] **Step 4: Update workflow scripts and documentation**

Document:

- `candidate/` and `published/` triple layouts;
- catalog, marker, journal, and recovery paths;
- target approval as link activation boundary;
- pending candidates being ignored in releases;
- unpublish and republish requiring no review;
- direct `npm run build` rebuilding only the last provenance-valid release;
- exact migration inventory, decision, apply, roll-forward, and rollback
  commands;
- the fact that no command deploys automatically.

Make `build-from-review.sh` call only approved materialization. Make
`build-astro-site.sh` materialize, gate, then run Astro build.

Add a minimal committed synthetic vault and semantic review workspace
containing A, approved B, the catalog, activation marker, candidate triples,
and approved triples. `e2e/run-synthetic.sh` copies the Astro application
without managed output into a temporary directory, links the existing
`node_modules`, materializes the fixture release there, runs `npm run build`,
asserts RU/EN links in generated HTML, and removes only its own temporary
directory.

- [ ] **Step 5: Run the full Java and site test suites**

```bash
mvn -q test
npm run check
node --test tests/*.test.mjs
../e2e/run-synthetic.sh
```

Run Maven from `exporter-java/` and Node commands from `site/`.
Run the synthetic e2e command from `site/` or invoke it by absolute path.
Expected: all commands exit 0 with no test failures, and the production Astro
build consumes a provenance-valid approved fixture release.

- [ ] **Step 6: Build and exercise the native exporter**

```bash
mvn -Pnative native:compile
mvn -q -Dtest=NativeCliParityTest test
```

Expected: native compilation succeeds and real native semantic commands match
JVM behavior.

- [ ] **Step 7: Run a read-only inventory against the real vault**

Use a fresh report path and the real review workspace, without `--apply`:

```bash
./scripts/build-from-review.sh --dry-run
./target/astro-export migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /absolute/path/to/live-review \
  --astro /absolute/path/to/astro-site \
  --report /private/tmp/semantic-link-migration-inventory.json \
  --json
```

Verify:

- no vault or review bytes changed;
- every approved page is classified;
- exact migrations have public-output parity;
- unresolved targets remain plain;
- ambiguity and strict-order mismatch counts are explicit;
- no activation marker or journal was created.

Do not run `--apply` against real approved data until the user reviews and
approves this inventory.

- [ ] **Step 8: Run final hygiene and inspect the complete diff**

```bash
git diff --check
git status --short
```

Review that generated output, migration reports, temporary review workspaces,
and real-vault artifacts are not included in the source diff.

- [ ] **Step 9: Conditional final commit**

If and only if the user explicitly authorized commits:

```bash
git add exporter-java site e2e docs/superpowers
git commit -m "feat: add late-bound semantic publication links"
```

Otherwise leave the verified implementation uncommitted and report the exact
modified files.

## Implementation Completion Gate

Do not claim completion until fresh evidence confirms all of the following:

- focused reference, catalog, candidate, approval, repository, projection,
  migration, provenance, CLI, and acceptance tests pass;
- full `mvn -q test` passes;
- site Node tests, content gate, and production build pass;
- native CLI parity exercises real semantic subcommands;
- referrer approved RU, EN, and sidecar hashes remain unchanged through target
  approval, unpublish, and republish;
- a selected unapproved target blocks materialization;
- pending drafts never enter released output;
- public output contains no semantic or private reference data;
- read-only real-vault inventory changes no vault, review, or Astro content;
- the user reviews the real migration inventory before any live `--apply`.
