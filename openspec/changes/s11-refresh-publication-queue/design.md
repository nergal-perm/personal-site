## Context

`InspectPublicationHandler` today only ever produces three of the six BRG-05 states (`metadata_blocked`,
`not_prepared`, `ready_for_review`) — an admitted note with an installed approved snapshot and no pending
candidate falls through to `not_prepared`. Nothing anywhere persists a workflow classification back to the
source note: `VaultReader` has no enumeration capability at all (`exists`/`readSource` only), and a failed
`prepare` installs nothing, so `translation_failed`/`stale` leave no durable trace once the CLI process exits.
`PrepareHandler.sourceStillMatches()` (S08) already proved the "re-validate immediately before commit, block
rather than clobber" guard pattern this slice needs for its own write. The functional collaborative-design pass
(`proposal.md`) resolved the shape; this pass fixes the concrete mechanism.

## Goals / Non-Goals

**Goals**
- `refresh-publication-queue` discovers already-admitted `publish: true` notes cheaply (bounded by actual queue
  size, not vault size in the expensive sense) and reconciles each against one shared classification.
- `InspectPublicationHandler` and the new `RefreshPublicationQueueHandler` share one `WorkflowStateClassifier`
  so they cannot disagree for the same observation window (BRG-05), fixing the approved-only case (BRG-04).
- `PrepareHandler` persists `workflowStatus` on its existing exit paths so `translation_failed`/`stale` become
  genuinely reconstructable, not just live one-shot responses.
- The write is byte-preserving outside the one declared key, atomic, and guarded against non-exporter actors
  (Obsidian autosave, sync clients, external edits) mutating the file mid-operation.

**Non-Goals** (excluded per `proposal.md`)
- Whole-vault discovery/admission — S16's job.
- Any change to `mark-reviewed`, `build-from-review`, `install-to-site`.
- Active-translation-lock detection — scope-pinned; `prepare` never writes to the source note while translation
  is running, only after, so there is no window to protect.
- Duplicate/aliased workflow-key detection beyond what `NoteIntake` already provides — scope-pinned, see D2.
- General frontmatter normalization or whole-file rewriting.

## Decisions

### D1 — Discovery via the existing `Frontmatter` parser as a pre-filter, not a bespoke grep

`VaultReader.listPublishCandidates()` walks the vault for `.md` files and, per file, calls the existing
`Frontmatter.parse(source).flag("publish")` — the same cheap, already-tested parser every other command uses,
not a new hand-rolled line-matching routine. `Frontmatter.parseHeader` already bails at the closing delimiter or
the first malformed line, so this never reads past the header block; it does not parse `body`, `title`, or
`description`, and pays no cost proportional to essay length. `NoteIntake.admit()` then re-runs in full only for
files that pass this filter (~30 notes in the real vault, not ~2000).

Alternative considered: a hand-written literal-string grep for `"publish: true"` ahead of any structured parse.
Rejected — it would duplicate `Frontmatter`'s delimiter/line logic with a second, less-tested implementation for
a marginal saving; `Frontmatter.parse` is already the cheap path.

### D2 — `WorkflowStateClassifier` is a pure function; two of six states come from the persisted scalar, not
new detection machinery

```java
public String classify(boolean candidatePresent, boolean approvedPresent,
                        Optional<String> persistedWorkflowStatus)
```

`candidatePresent` → `ready_for_review`. Else `approvedPresent` → `ready_to_publish` (the BRG-04 fix). Else,
`persistedWorkflowStatus` is trusted only for `translation_failed`/`stale` (the two states with no other durable
evidence); anything else, including absent, defaults to `not_prepared`. No I/O, no ports — takes booleans and an
`Optional<String>` the caller has already derived, making it trivially unit-testable and reused verbatim by
`InspectPublicationHandler` and `RefreshPublicationQueueHandler`.

`translating` is a valid `WorkflowState` constant this classifier never returns (per the functional pass's
scope-pin). TRP-06's "duplicate, aliased, or malformed workflow keys" scenario is also not implemented as a
distinct case: `Frontmatter.parseHeader` is all-or-nothing (any malformed/duplicate key anywhere in the block
collapses the whole frontmatter map to empty — `Frontmatter.java:75-87`), so such a note already fails
`EssayAdmission` upstream (`metadata_blocked`, missing `publicId`/etc.) before either the classifier or the
editor is reached. "Aliased" has no concrete target — no second/legacy key name exists anywhere in this codebase
to alias `workflowStatus` against. Building detection against an unreachable input and a fictional key would be
speculative, not defensive.

### D3 — `Frontmatter` gains `withScalarSet(key, value)`, a surgical rewrite over raw text, not a
parse-then-reserialize round-trip

`Frontmatter` currently discards the raw header lines after parsing into `Map<String, FrontmatterScalar>` —
reserializing from that map would risk silently reformatting untouched lines (quoting, spacing, key order).
`withScalarSet` instead operates on the original source text: locate the line for `key` between the delimiters
(reusing the same line-scanning `parseHeader` already does) and replace only that line's value, or insert a new
line immediately before the closing `---` if the key is absent — returning the complete new source with every
other byte identical. This requires `Frontmatter` to retain the original source string (one new field) rather
than only the post-parse `body`.

Alternative considered: a separate text-surgery class outside `Frontmatter`. Rejected — the caller's brief was
explicit (extend `Frontmatter`, don't replace it), and delimiter/line-scanning logic already lives there; a
second implementation of the same scanning rules elsewhere would drift from it over time.

### D4 — New `WorkflowStatusEditor` port in a new `workflow` package; its `Filesystem` adapter implements its
own vault-root confinement, matching every existing adapter's convention

```java
public interface WorkflowStatusEditor {
    Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue);
    static WorkflowStatusEditor create(Path vaultRoot) { return new FilesystemWorkflowStatusEditor(vaultRoot); }
    static WorkflowStatusEditor createNull() { return new NullWorkflowStatusEditor(); }
}
```

`FilesystemWorkflowStatusEditor.write(...)`: resolve `notePath` within the vault root (canonicalize-at-construction
+ resolve/realpath/`startsWith` — the identical shape `FilesystemVaultReader` already uses, checked separately
here because no shared confinement utility exists in this codebase to extract into; `FilesystemCandidateWorkspace`,
`FilesystemApprovedSnapshotWorkspace`, and `FilesystemManagedSiteInstaller` each already implement their own root
confinement locally rather than sharing one) → read current bytes → `ContentHash.sha256Hex(current)` compared
against `expectedSourceHash` (computed by the caller at validation time) → mismatch returns `Blocked`, no write →
else `Frontmatter.parse(current).withScalarSet("workflowStatus", newValue)` → write the result to a temp file
created in the *same directory* (guarantees same filesystem for the atomic move) → copy the original file's POSIX
permissions onto the temp file → `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.

`NullWorkflowStatusEditor` holds an in-memory `Map<VaultRelativePath, String>` of current-source-hash-by-path
(seeded by the test) and the same compare-then-replace logic, no filesystem — proven against the real adapter via
a shared contract test, per this project's nullables discipline.

The port lives in a new top-level `workflow` package alongside `WorkflowState` and `WorkflowStateClassifier` —
mirroring how `candidate`/`approved`/`site` are each a shared port package consumed by multiple handler packages
(`prepare`, `inspect`, `markreviewed`), not owned by any single one. `RefreshPublicationQueueHandler` gets its own
new `refresh` package, matching the one-package-per-command-handler convention (`prepare`, `markreviewed`,
`inspect`, `installtosite`, `buildfromreview`).

### D5 — `PrepareHandler` writes are additive calls on existing exit paths, no new branching

Three call sites, each already an existing `return` statement in `prepareAdmittedEssay(...)`:
- `installCandidate(...)` success → after `candidateWorkspace.install(...)` succeeds, before returning
  `BridgeResponse.prepared(...)`, call `workflowStatusEditor.write(notePath, sourceHash, WorkflowState.READY_FOR_REVIEW)`.
- `translationFailure(...)` / the `EnglishCandidateValidator` failure branch → write `TRANSLATION_FAILED`.
- The `sourceStillMatches` failure branch → write `STALE`.

The write's own failure (blocked or I/O error) does not change the `BridgeResponse` `prepare` already returns —
the workflow scalar is best-effort bookkeeping for later `inspect`/`refresh` calls, not part of `prepare`'s own
success/failure contract. This keeps `PrepareHandler`'s existing branching and existing tests' assertions on
`BridgeResponse` shape untouched; only new assertions on the resulting frontmatter are added.

### D6 — `WorkflowState` is the single source of the six-state vocabulary strings

A small `public final class WorkflowState` holding the six `public static final String` constants (mirroring the
existing `IoFailureMessages`/`ContentHash` static-utility style already in this codebase). `WorkflowStateClassifier`,
`RefreshPublicationQueueHandler`, and `PrepareHandler`'s new writes all reference these constants rather than
literal strings. `BridgeResponse`'s existing private literals (`"metadata_blocked"`, `"ready_for_review"`, etc.)
are left as-is — pointing them at `WorkflowState` too would be a purely mechanical, behavior-invisible cleanup
with no test-observable difference, left to the implementation task's discretion rather than mandated here.

## Risks / Trade-offs

- [Risk] `listPublishCandidates()` still does one cheap parse per vault file (~2000), not per queue member
  (~30). → Mitigation: `Frontmatter.parse` already bails at the header's closing delimiter for well-formed notes
  and at the first malformed line otherwise — cost is proportional to header size, not essay length, and this is
  the same cost every other command already pays once per note it touches.
- [Risk] The guard window between `FilesystemWorkflowStatusEditor`'s hash check and its `ATOMIC_MOVE` is not
  zero. → Mitigation: identical residual to `PrepareHandler.sourceStillMatches()`'s already-accepted window
  (S08); optimistic-concurrency guards narrow a race, they do not require eliminating it, and the actors this
  guards against (autosave, sync, external edit) are not adversarial.
- [Risk] `PrepareHandler` now requires write access to the vault, not just read — a new capability. →
  Mitigation: this is the explicit purpose of the slice (TRP-06 requires preparation to report workflow state in
  the source note); the exporter already requires write access to the review root and job root today.
- [Risk] `Frontmatter` retaining its original source string as a new field is a small, permanent memory-shape
  change to a class every command already uses. → Mitigation: one `String` reference per parsed note, negligible
  relative to `ruBody`/`enBody` already held in memory during the same operations.

## Migration Plan

Pure additive change. No existing on-disk format changes: an existing note without `workflowStatus` simply gains
the key on its next `prepare` or `refresh-publication-queue` pass. Rollback is a plain revert; a `workflowStatus`
key left behind by a reverted build is inert to any code that doesn't know about it (Obsidian renders it as an
ordinary frontmatter property).

## Open Questions

None outstanding. Active-translation detection and duplicate/aliased-key detection were resolved as scope-pins
during the functional pass and reconfirmed as satisfied-by-construction during this technical pass (D2); the
write mechanism directly reuses S08's proven guard shape rather than introducing a new one.
