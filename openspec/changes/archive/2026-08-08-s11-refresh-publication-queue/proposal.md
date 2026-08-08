## Why

`prepare`, `inspect-publication`, `mark-reviewed`, `build-from-review`, and `install-to-site` all exist and work
end to end (Milestones A and B). `BridgeResponse` already carries the six-state workflow vocabulary
(`metadata_blocked`, `translating`, `ready_for_review`, `ready_to_publish`, `translation_failed`, `stale`), but
`InspectPublicationHandler` only ever produces three of those (`metadata_blocked`, `not_prepared`,
`ready_for_review`) and nothing anywhere persists a classification back to the source note. The plugin has no cheap
way to show an accurate queue-wide picture without re-inspecting every note individually, and once a workflow
scalar exists in frontmatter it has no mechanism to stay truthful. Without this slice, "workflow state" is only
ever a point-in-time answer computed fresh for one note, never a durable, reconcilable fact — and it isn't even a
complete answer yet.

## What Changes

- Add a fifth bridge command, `refresh-publication-queue`, that accepts no current-note path (BRG-01's refresh
  clause). Discovery is a narrow, cheap content pre-filter — `VaultReader` gains `listPublishCandidates()`, which
  greps for the literal `publish: true` line before any YAML parsing — followed by the existing, unmodified
  `NoteIntake.admit()` per candidate path. This is deliberately not S16's whole-vault discovery: no lookalike
  exclusion, no multi-kind Boolean selection, no ordering contract, no aggregate-invalid-manifest handling. A note
  that fails admission is simply one more per-note outcome (`metadata_blocked`), not a partial-manifest concern.
- Extract the six-state classification into one shared `WorkflowStateClassifier` used by both
  `InspectPublicationHandler` and the new `RefreshPublicationQueueHandler`, so the two commands cannot disagree for
  the same observation window (BRG-05). This also closes `InspectPublicationHandler`'s existing gap: an admitted
  note with an installed approved snapshot and no pending candidate currently reports `not_prepared`; it will
  correctly report `ready_to_publish`.
- Add a guarded, atomic frontmatter workflow-scalar editor (new production adapter) that writes exactly one
  declared key — `workflowStatus`, using the same string values the bridge JSON already emits — and preserves
  every other byte and the file's permissions (TRP-06). It reuses the exact "re-validate immediately before
  commit, block rather than clobber if the source changed" pattern `PrepareHandler.sourceStillMatches()` already
  established in S08, guarding against non-exporter actors (Obsidian's own autosave, a sync client, a concurrent
  external edit) rather than a second exporter invocation, which the CLI's synchronous, one-command-at-a-time
  model already rules out.
- `PrepareHandler` gains an additive side effect on its existing exit paths — no new branching logic — writing
  `workflowStatus` via the same guarded editor: `ready_for_review` on successful candidate install,
  `translation_failed` on translation/validation failure, `stale` on the existing "source changed while
  translation was in progress" exit. This is what makes TRP-06's literal text ("when *preparation* reports
  workflow state in the source note...") actually true, and it's what makes `translation_failed`/`stale` durably
  reconstructable at all — today neither leaves any trace on disk, since a failed `prepare` installs nothing, so
  a later, separate `inspect`/`refresh` call has no way to distinguish "never attempted" from "just failed"
  without this write. `MarkReviewedHandler` is not touched: `ready_to_publish` is fully derivable from
  already-durable approved-snapshot state (approved present, no pending candidate diff), no ephemeral
  operation-result knowledge required.
- `refresh-publication-queue` reconciles each queue member: if `workflowStatus` is absent or decisively disagrees
  with the classifier's current answer, it's corrected and counted `updated`; if it already matches, `unchanged`;
  if the note's bytes changed since this pass validated it, or workflow keys are duplicate/aliased/malformed, the
  write is blocked and the item is counted `uncertain` (TRP-06's "source changed concurrently" scenario, BRG-05's
  "evidence is uncertain" scenario).
- **Scope-pinned, not implemented:** an actively-translating publication being left untouched (BRG-06). `prepare`
  is fully synchronous end-to-end and never writes to the source note while translation is running (only after it
  completes, per the point above), so there is no window in which a concurrently-invoked `refresh` could observe
  or disturb in-flight translation state through the source note. Detecting an in-flight job at all would require
  a new identity-scoped, cross-process job marker that nothing today provides (`TranslationJob.id()` is an
  anonymous random UUID with no link to `PublicationIdentity`). This slice proceeds on the documented operational
  assumption that `refresh-publication-queue` is not invoked mid-translation; `translating` remains a valid
  BRG-05 vocabulary value that this slice's code never produces. See `scope-pins.md` once written.

**Out of scope:** whole-vault discovery/admission (S16), any change to `mark-reviewed`, `build-from-review`, or
`install-to-site`, active-translation-lock detection, and general frontmatter normalization or whole-file YAML
rewriting.

## Capabilities

### New Capabilities
(none — this slice realizes scenarios already specified in existing capabilities; it does not introduce a new
bounded capability)

### Modified Capabilities
- `workflow-bridge`: BRG-01 is realized for its refresh clause. BRG-05 and BRG-06 are realized as specified — no
  requirement-text change (BRG-06's translating clause is satisfied by construction, see above, not by detection).
  BRG-04 gets a genuine addition — a scenario for the previously-uncovered "approved snapshot exists with no
  pending candidate" case, since no existing BRG-04 scenario described it and the current code silently mishandles
  it as `not_prepared`.
- `translation-preparation`: TRP-06 is realized exactly as already written — no requirement-text change.

## Impact

- `publication-exporter` module: new `RefreshPublicationQueueCommand` (CLI) and `RefreshPublicationQueueHandler`
  (application); new shared `WorkflowStateClassifier` used by `InspectPublicationHandler` (modified) and the new
  handler; new guarded frontmatter workflow-scalar editor port with Filesystem and in-memory/Null adapters; new
  `VaultReader.listPublishCandidates()`; `PrepareHandler` gains an additive workflow-scalar write on its existing
  exit paths (no branching-logic change).
- Not touched: `mark-reviewed`, `build-from-review`, `install-to-site`, whole-vault discovery, additional content
  kinds, and semantic references.
