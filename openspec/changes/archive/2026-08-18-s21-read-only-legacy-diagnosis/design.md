## Context

`publication-exporter` has never had a concept of "legacy state." Every acceptance test to date constructs
`ApprovedSnapshotWorkspace.createNull()` / `CandidateWorkspace.createNull()` starting empty, and `PrepareHandler`/
`BuildFromReviewHandler` (both read in full for this design) go straight from their entry point into
admission/translation/release work with zero precondition on prior workspace content. The legacy
`exporter-java` oracle already solved an analogous problem for its own predecessor shape:
`SemanticSchemaState.mode(reviewRoot)` (read in full) checks two fixed files under the review root —
`.semantic-links/schema-v1.active.json` (activation marker: `schemaVersion`, `inventorySha256`,
`catalogSha256`, `activatedAt`) and `.semantic-links/migration-v1.journal.json` (migration journal) — and
returns `LEGACY` (neither present), `SEMANTIC` (marker present, valid, and, if a journal exists, consistent
with it), or `MIGRATION_INCOMPLETE` (marker present but invalid, or journal present but inconsistent). Critically,
that oracle's `LEGACY` result fires purely from marker/journal absence — it never asks whether the workspace
has any approved/candidate content, because every real invocation targets an already-existing production
vault. `publication-exporter`'s acceptance tests are the opposite case: freshly constructed, empty, in-memory
workspaces are the *normal* baseline for 903 existing tests, and none of them may start failing closed. This is
the one place S21's model must deliberately diverge from the oracle's, and it is the main technical decision
this design records.

`ApprovedSnapshotWorkspace` already gained `findBySourceId(String)` in S20 (`FilesystemApprovedSnapshotWorkspace`
scans its approved directories internally to answer it — the exact enumeration mechanism this slice reuses).
`CandidateWorkspace` has no equivalent scan today. `BridgeResponse` (read in full) already has four sibling
static factories shaped exactly like what MIG-01's "migration is requested implicitly" scenario needs —
`blocked`, `translationFailed`, `stale` — each `(command, diagnostics) → BridgeResponse` with `ok=false` and a
distinct `status` string; `ReleaseResult.blocked(String message)` is the equivalent for `BuildFromReviewHandler`
(no `status` enum there — plain reason text).

## Goals / Non-Goals

**Goals:**
- A read-only `ActivationMarkerStore` port (in-memory first, then filesystem) recording whether a review
  workspace has been explicitly activated for the current semantic schema edition — read-only in this slice;
  nothing ever writes a marker until S22/S23.
- A `SchemaActivationGuard` stateless check, invoked once at the top of `PrepareHandler.prepare()` and
  `BuildFromReviewHandler.buildFromReview()`, that blocks with migration-required evidence before any other
  work when the workspace is legacy-shaped, and is a complete no-op (returns immediately, zero extra I/O
  beyond one marker read) otherwise.
- A new `LegacyWorkspaceInventoryHandler` producing a deterministic `LegacyWorkspaceInventory` report —
  approved pairs, candidate pairs, ambiguities, blockers, a stable fingerprint — from `ApprovedSnapshotWorkspace`
  and `CandidateWorkspace`, without mutating either.
- `CandidateWorkspace` gains an `allIdentities()` enumeration, matching `ApprovedSnapshotWorkspace`'s existing
  internal scan shape, implemented in both `Filesystem*` and `Null*`.

**Non-Goals:**
- No decision draft generation (MIG-03), no apply, no catalog mutation, no activation-marker *writing* (MIG-04,
  S22/S23's concern — this slice's `ActivationMarkerStore` is read-only by construction: it has no `write`
  method at all).
- No migration journal, recovery root, or roll-forward/roll-back machinery — `MIGRATION_INCOMPLETE` as a
  distinct third state (vs. a validated marker or its absence) is deferred to S23, where a real journal first
  exists to be inconsistent with. This slice's guard collapses "marker absent" and "marker present but
  malformed" into one `LEGACY` outcome — both fail closed identically today; nothing observable requires
  telling them apart before a journal exists to cross-check against.
- No CLI wiring decision beyond the handler's own public method — whether/how `LegacyWorkspaceInventoryHandler`
  gets a CLI subcommand is a tasks.md-level detail, not a design decision (it follows the exact pattern every
  prior Handler already uses).

## Decisions

**Decision: a workspace is legacy-shaped only when it has approved or candidate content AND no valid
activation marker — not merely "no marker."**

The oracle's `LEGACY` fires on marker absence alone because every real `exporter-java` invocation targets an
already-populated vault; that assumption does not hold here. `publication-exporter`'s own S01-S20 acceptance
suite — 903 tests — universally starts from an empty `ApprovedSnapshotWorkspace.createNull()` /
`CandidateWorkspace.createNull()` pair with no marker, and MIG-01's own first scenario ("current semantic
workspace is used... no legacy inventory... invoked") only makes sense if an empty, never-touched workspace
counts as current. So `SchemaActivationGuard.check(...)` is:

```java
static SchemaActivationCheck check(
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
        CandidateWorkspace candidateWorkspace,
        ActivationMarkerStore activationMarkerStore) {
    if (activationMarkerStore.read().filter(ActivationMarker::isValid).isPresent()) {
        return SchemaActivationCheck.current();
    }
    boolean hasLegacyContent = !approvedSnapshotWorkspace.allIdentities().isEmpty()
            || !candidateWorkspace.allIdentities().isEmpty();
    return hasLegacyContent
            ? SchemaActivationCheck.legacy()
            : SchemaActivationCheck.current();
}
```

An empty workspace with no marker is current (nothing to migrate, proceeds normally — every existing test's
shape). A workspace with any approved or candidate content and no valid marker is legacy (fails closed). Once
a marker exists and validates, the check short-circuits before ever touching either workspace — the common
case after S22/S23 activation is a single cheap file read, not a directory scan.

**Decision: `ActivationMarker` drops `catalogSha256`; keeps `schemaVersion` + `inventorySha256` + `activatedAt`.**

The oracle's marker binds a *catalog* hash (`VaultReferenceCatalog`, a separate stable-page-reference mapping
file) alongside the inventory hash — `publication-exporter` has no catalog concept: S18/S19 already resolve
stable identity straight from each note's own `id` frontmatter field via `VaultSourceIdentityIndex`, with no
intermediate catalog artifact. `ActivationMarker` is therefore a smaller value type:

```java
public record ActivationMarker(int schemaVersion, String inventorySha256, Instant activatedAt) {
    public ActivationMarker {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
    public boolean isValid() {
        return schemaVersion == 1 && SHA256.matcher(inventorySha256).matches();
    }
}
```

`isValid()` lives on the value type itself (matching this codebase's existing preference for behavior-bearing
value objects over external validators — e.g. `VaultRelativePath`'s own confinement checks) rather than in
`SchemaActivationGuard`.

**Decision: `ActivationMarkerStore` is a two-implementation port (`NullActivationMarkerStore`,
`FilesystemActivationMarkerStore`), read-only, following this codebase's nullable-adapter convention exactly.**

```java
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

`FilesystemActivationMarkerStore` reads `<reviewRoot>/.migration/schema-v1.active.json` if present, parsing
with the same strict-duplicate-detection `ObjectMapper` style `ReferenceMapCodec` already uses; any parse
failure or missing file yields `Optional.empty()` (never throws — "missing/invalid marker" is an ordinary,
expected state for this slice, not an I/O error). No writer exists on this interface or either implementation
— a later slice (S22/S23) adds a `write`/`activate` capability when there is finally a real migration to
record; adding it now would be speculative surface with no caller.

**Decision: `CandidateWorkspace.allIdentities()` mirrors `ApprovedSnapshotWorkspace`'s existing internal
scan, promoted to a real interface method.**

`FilesystemApprovedSnapshotWorkspace.findBySourceId` (S20) already enumerates every approved identity
internally to answer its lookup; this slice needs the same enumeration surfaced directly (not filtered by
source ID) for both approved and candidate workspaces, so `SchemaActivationGuard` and
`LegacyWorkspaceInventoryHandler` share one query instead of two differently-shaped ones:

```java
// ApprovedSnapshotWorkspace — new interface method
List<PublicationIdentity> allIdentities();

// CandidateWorkspace — new interface method (candidate had no scan capability before this slice)
List<PublicationIdentity> allIdentities();
```

`FilesystemApprovedSnapshotWorkspace` refactors its existing private directory-enumeration helper (added in
S20) to be reusable by both `findBySourceId` and the new public `allIdentities()`, rather than duplicating the
walk. `FilesystemCandidateWorkspace` gains an equivalent helper, following the identical pattern.
`NullApprovedSnapshotWorkspace`/`NullCandidateWorkspace` return `installed.keySet()` as a sorted `List`
(deterministic order — MIG-02's "inventory is repeated" scenario requires stable output).

**Decision: `LegacyWorkspaceInventory` is a plain immutable report value, `LegacyWorkspaceInventoryHandler`
is a stateless-per-call collaborator (no state between calls) that builds it and never mutates.**

```java
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

`approvedPairs`/`candidatePairs` come straight from the new `allIdentities()` calls, sorted (by
`PublicationIdentity`'s natural string form — collection/contentType/publicId — already how S16-style ordering
contracts elsewhere in this codebase sort identities) before hashing, so two runs against an unchanged
workspace produce byte-identical `inventorySha256` (MIG-02's determinism scenario) regardless of any
non-deterministic directory-listing order the filesystem returns. `ambiguities` records one entry per identity
present in *both* `approvedPairs` and `candidatePairs` with a source-ID mismatch between the two triples (the
concrete, checkable notion of "ambiguous pair correspondence" available at S21's scope — no vault link
resolution is involved yet, unlike the oracle's occurrence-alignment ambiguity, which does not apply here since
S19 already gives every occurrence a stable ID from the start). `blockers` records one entry per identity whose
`ReferenceMap.sourceId()` is `Optional.empty()` (content installed with no recorded stable source identity —
the literal, checkable signature of "predates this exporter's own identity-recording capability," since
`sourceId()` only became populated starting with S20's `PrepareHandler` threading). An inventory over an empty
workspace returns all four lists empty and a fixed hash of the empty payload — never an error.

**Decision: the guard's blocked response reuses `BridgeResponse.blocked`/`ReleaseResult.blocked` verbatim,
distinguished only by diagnostic message text — no new `status` value.**

Adding a `"migration_required"` status would touch `BridgeResponse`'s constructor, every existing `status`
switch on the plugin side, and the schema-v2 contract simultaneously, for a slice whose entire job is
diagnosis, not a new bridge vocabulary term. `PrepareHandler`'s guard returns
`BridgeResponse.blocked("prepare", Diagnostic.blocking("workspace", "Legacy workspace requires migration inventory before prepare can run."))`
— identical shape to every other blocked path already in this handler (e.g. `intake.diagnostics()` on
rejection), distinguishable by message text and by running an inventory to see why, not by a new machine
status. `BuildFromReviewHandler`'s guard returns
`ReleaseResult.blocked("Legacy workspace requires migration inventory before release can run.")`, matching
`noApprovedSnapshotResult()`'s existing plain-text-reason shape exactly. If a later slice's plugin integration
needs a distinguishable machine status, that is S22/S23's call to make once there is an actual apply flow for
the plugin to route toward — speculative now.

## Risks / Trade-offs

- [Risk] `allIdentities()` inherits `FilesystemApprovedSnapshotWorkspace`'s pre-existing interrupted-approval
  recovery behavior on `candidateDirectoriesInOrder()`; a workspace with a stale `approved-backup-*`
  directory present may be recovered as a side effect of inventory, matching the same behavior `read()`
  already has. This is a known, pre-existing limitation of the underlying workspace adapter, not new to S21
  — fully isolating inventory from it is deferred to a future slice.
- [Risk] `SchemaActivationGuard.check` runs `allIdentities()` on *every* `prepare`/`buildFromReview` call once
  any legacy content exists (until S22/S23 install a marker), which is an O(n) directory scan per call. →
  Mitigation: identical performance shape and precedent to S20's `findBySourceId` (also O(n) per release call,
  accepted as fine at this codebase's scale); once a valid marker exists, the check is a single file read with
  zero scan cost, so the O(n) path is inherently transitional (present only in an unmigrated workspace, which
  cannot successfully `prepare` or release anyway).
- [Risk] Defining "legacy content" as "any approved or candidate identity with no marker" means a single
  test/dev workspace that has *ever* run one real (non-Null) `prepare` before S22/S23 exists will start
  blocking on every subsequent call, including in local manual testing against a real filesystem review root.
  → Mitigation: this is the specified, intended MIG-01 behavior ("blocks with migration-required evidence
  rather than mutating the workspace") — not a defect. It only affects real filesystem workspaces already
  carrying pre-S21 content; every acceptance test's `Null*` fixtures stay empty and are never affected.
- [Risk] `blockers` keyed on `ReferenceMap.sourceId().isEmpty()` will flag *every* pre-S20 approved/candidate
  snapshot as legacy, even ones that are otherwise perfectly current-schema, because `sourceId` only started
  being recorded in S20. → Mitigation: this is accurate, not a false positive — before S20, no snapshot
  durably recorded its own stable identity at all, which is exactly the kind of gap MIG-02's "existing
  identities" inventory field exists to surface; a real production workspace reaching S21 will already be past
  S20 in the slice sequence (S18-S20 shipped first) and this case is expected to be empty in practice, only
  exercised here via a deliberately old fixture in tests.
