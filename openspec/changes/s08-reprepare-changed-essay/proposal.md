## Why

`PrepareHandler` (built for S03, first-publication only) unconditionally translates the whole source body and installs whatever the worker returns, with no diff against an approved baseline, no validation of the resulting English candidate, and no protection of the previous candidate when the worker fails, is stale, or answers the wrong job. Milestone B (S08–S11) requires that an already-published essay can be changed and re-prepared safely; without this slice, editing a live essay has no safe path back into review.

## What Changes

- `prepare` on a note with an approved Russian snapshot computes the complete normalized diff between the approved RU and the current source, instead of always treating the source as entirely new.
- The review plan (`inspect-publication`) reports that complete approved-versus-candidate Russian diff for a changed publication, distinguishing it from first-publication review (RVA-02).
- A newly generated English candidate is validated for structural/identity/route-safety invariants (PCM-06 — same required fields as its Russian counterpart, no missing/extra fields, retained external URLs, no internal `/ru/` routes) before it is installed.
- The new RU/EN/`references.json` candidate triple is installed as one coherent unit only after validation succeeds (TRP-03).
- Translation failure, a malformed/blank result, a stale result, or a result belonging to a different job/source fingerprint leaves the existing valid English candidate bytes untouched and reports `translation_failed`/`stale` with diagnostics (TRP-03).
- Each `prepare` request runs in a unique bounded job workspace and only accepts a result matching its own job ID and source fingerprint; results reached by traversal, symlink/hard-link substitution, wrong job ID, wrong fingerprint, or a concurrent stale writer are rejected before candidate installation (TRP-04).
- First-publication behavior (no approved baseline yet, S03) is unchanged.

**Out of scope:** approving the new candidate (`mark-reviewed`) and updating the live release/site — both remain gated behind the existing S05/S09+ approval path.

## Capabilities

### New Capabilities
(none — this slice extends existing capabilities' requirements, it does not introduce a new bounded capability)

### Modified Capabilities
- `translation-preparation`: adds TRP-02 (diff against approved RU baseline), TRP-03 (preserve known-good English candidate until replacement is valid), TRP-04 (isolate and authenticate translation jobs)
- `review-and-approval`: adds the changed-publication half of RVA-02 (exact review plan showing the complete approved-versus-candidate diff)
- `public-content-model`: adds PCM-06 (keep English content structurally aligned and route-safe)

## Impact

- `publication-exporter` module: `prepare` command/handler (`PrepareHandler`), `CandidateWorkspace` port and its Filesystem/InMemory adapters, `TranslationWorker` port and adapters, review-plan construction under `inspect-publication`.
- New surface: a translation job workspace providing isolation/authentication (job ID + source fingerprint) for worker results — the one new production boundary adapter this slice is permitted.
- Not touched: `mark-reviewed` (`MarkReviewedHandler`), `build-from-review`, `install-to-site`.
