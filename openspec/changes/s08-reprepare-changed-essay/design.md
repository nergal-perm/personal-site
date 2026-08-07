## Context

`PrepareHandler` (S03) is a straight-line pipeline: admit → translate whole body → install unconditionally.
`InspectPublicationHandler` never consults `ApprovedSnapshotWorkspace` — `approvedSnapshotState` is
hardcoded `"absent"` and it always returns `ReviewPlan.firstPublication(...)`. `ProcessTranslationWorker`
already runs each `translate()` call in its own OS temp scratch directory and reads result files with
`LinkOption.NOFOLLOW_LINKS`, but has no job-ID/source-fingerprint concept, so nothing distinguishes "this
result belongs to this request" beyond directory uniqueness. `ReferenceMap`/`CandidateSnapshot` carry no
diff or validation concepts today.

S08 must, when an approved RU snapshot exists for the note being prepared: compute the normalized diff
against it (TRP-02), validate the newly generated English candidate before installing it (PCM-06), install
the new RU/EN/`references.json` triple only as one coherent unit (TRP-03), and leave the existing candidate
untouched on any failure/staleness/wrong-job result (TRP-03, TRP-04). See `scope-pins.md` — no requirement
text changes, only new behavior under already-written scenarios.

## Goals / Non-Goals

**Goals**
- `prepare` diffs against the approved RU baseline when one exists, and does nothing (no new candidate) when
  the diff is empty.
- The English candidate is validated (structural fields, no `/ru/` route, external URLs retained) before
  install; a failed validation behaves exactly like a failed translation (prior candidate preserved).
- Worker results are authenticated to a unique bounded job (job ID + source fingerprint) before being
  eligible for validation/install, for both first-publication and changed-publication `prepare` calls.
- `inspect-publication` reports the changed-publication review plan (RVA-02) with the same diff.

**Non-Goals** (excluded per `proposal.md`)
- Approving the new candidate (`mark-reviewed`/RVA-03..06) — untouched.
- Updating the live release/site (`build-from-review`, `install-to-site`) — untouched.
- Semantic occurrence diffing (TRP-05) — no `references.json` entries exist before S19.
- Link/asset/protected-Markdown handling (PCM-03/04/05) — unaffected, out of scope until S12-S14.

## Decisions

### D1 — Normalized diff as a small value type, computed in-process, no new dependency

`RussianDiff`: an immutable value object over the approved RU body/title/description and the current
(normalized) source body/title/description, producing a line-based added/removed/unchanged sequence per
field via a minimal LCS diff (no new Maven dependency — this is combinatorial-enough logic to warrant its
own unit tests per the implementation-plan's outside-in discipline, item 6). `RussianDiff.isEmpty()` answers
TRP-02's "only serialization noise changed" scenario: prepare computes the diff against the exact approved
snapshot; if empty, `PrepareHandler` returns the current `not_prepared`/`ready_for_review` state unchanged
and does **not** invoke the translation worker or touch the candidate workspace.

Alternative considered: shell out to `git diff --no-index` or a Java diff library (java-diff-utils). Rejected
— adds a new dependency for a line-diff algorithm well inside "unclear at acceptance-test scope" territory,
not "genuinely combinatorial" enough to need one; the existing `ContentHash`/normalization utilities already
give us equality-detection for the empty-diff case, and a straightforward LCS is small in-process code.

### D2 — PCM-06 validation as a domain service over the current field model

`EnglishCandidateValidator.validate(CandidateSnapshot ru, TranslationResult en)` checks, given the project's
current field set (body/title/description only — no other structured fields exist before later kind/link
slices):
1. none of `enBody`/`enTitle`/`enDescription` blank (already enforced in `PrepareHandler`, moved here),
2. `enBody` contains no internal `/ru/`-prefixed route (regex over Markdown links `](/ru/...)`),
3. every external (`http://`/`https://`) URL present in `ruBody` is also present in `enBody` (set
   comparison after extraction — translation must not drop external links).

Returns a sealed `ValidationResult` (valid / invalid-with-diagnostics). `PrepareHandler` treats "invalid"
identically to "translation failed": prior candidate preserved, `translation_failed` response with
field-specific diagnostics (PCM-06's own wording).

Alternative considered: validate at candidate-install time inside `CandidateWorkspace.install(...)`.
Rejected — `CandidateWorkspace` is a storage port; validation is prepare-pipeline domain logic and must run
*before* any install, including the case where installation would otherwise silently replace a valid
candidate with an invalid one.

### D3 — Job isolation via a `TranslationJob` (jobId, sourceFingerprint) passed into `TranslationWorker`

`TranslationWorker.translate(...)` gains a `TranslationJob` parameter:
```java
TranslationResult translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription);
```
`TranslationJob.forSource(ruBody, ruTitle, ruDescription)` generates a random job ID and a source fingerprint
(reusing `ContentHash.sha256Hex` over the three fields, the same primitive `ReferenceMap` already uses).
`PrepareHandler` creates one `TranslationJob` per `prepare` call and passes it through.

- **In-memory fake** (`translation` test package): a `StubTranslationWorker`/enhanced `NullTranslationWorker`
  that can be configured to return a result tagged with a *different* job ID or fingerprint than requested,
  or a stale/late result — driving TRP-04's "Job result crosses a boundary" scenario without sleeps or
  processes, and TRP-03's staleness scenario, per the acceptance-boundary note in `proposal.md`.
- **Real adapter** (`ProcessTranslationWorker`): the one new production boundary surface this slice adds.
  Instead of an anonymous OS temp directory, it creates `<configured job root>/<job.id()>/`, writes a
  `job.fingerprint` marker file into it before invoking the worker process, and — mirroring the confinement
  pattern already used by `FilesystemCandidateWorkspace`/`StagedDirectoryInstall`
  (`requireWithinReviewRoot`) — validates every result file path resolves within that exact job directory
  (`LinkOption.NOFOLLOW_LINKS`, already present) and that `job.fingerprint` still matches the requested
  fingerprint before treating the result as eligible. A job root configured once (constructor parameter,
  same pattern as `CandidateWorkspace.create(reviewRoot)`), not a new CLI flag.

Alternative considered: keep `TranslationWorker.translate(...)`'s signature untouched and generate/validate
the job entirely inside `ProcessTranslationWorker`, relying on OS temp-directory uniqueness alone. Rejected
— TRP-04 explicitly requires rejecting a result "reached by ... wrong job ID, wrong source fingerprint," which
is untestable through the in-memory fake without the job/fingerprint being an explicit, worker-visible
concept; OS-level directory uniqueness gives no fingerprint check and no way for a fake to simulate a
wrong-job result deterministically.

### D4 — `ReviewPlan.changedPublication(...)` and wiring `InspectPublicationHandler` to `ApprovedSnapshotWorkspace`

`ReviewPlan` gains a second factory, `changedPublication(candidatePaths, ruTitle, enTitle, ruDescription,
enDescription, RussianDiff diff)`, setting `baselineState = "changed"` and a new `diff` field (schema-safe:
`bridge-contract/schema-v2.json`'s `reviewPlan` already declares `additionalProperties: true`, the same
mechanism S07 relied on for `ruTitle`/`enTitle`/etc.). `InspectPublicationHandler` takes a second constructor
dependency, `ApprovedSnapshotWorkspace`, and branches: candidate present + approved absent → existing
`firstPublication(...)`; candidate present + approved present → `changedPublication(...)` computing the same
`RussianDiff` `PrepareHandler` computed (D1), against the *currently read* approved snapshot (recomputed at
inspect time, not cached from prepare time, so an approval that lands between prepare and inspect is
reflected).

## Risks / Trade-offs

- [Risk] `TranslationWorker` interface change (D3) ripples into every existing caller/fake.
  → Mitigation: single call site (`PrepareHandler`); grep-verified before implementation per
  `feedback_java_interface_change_task_planning` — `TranslationWorker.create*` factories and
  `ProcessTranslationWorker`/`NullTranslationWorker` are the only implementors today.
- [Risk] LCS diff (D1) on very large essay bodies could be slow.
  → Mitigation: essay bodies are short-form blog content (KB-scale, not MB), and the acceptance-suite budget
  (<1s) will catch a regression immediately; no premature optimization.
- [Risk] External-URL-retention check (D2) false-positives if translation legitimately rewrites a URL's
  query string or trailing slash.
  → Mitigation: compare on exact string match only for this slice (matches PCM-06's literal wording,
  "retain external URLs"); documented as a known coarseness, not silently "smarter" matching that could mask
  real drops.

## Migration Plan

Pure additive/internal change to `publication-exporter`; no data migration. Existing on-disk candidates
(RU/EN/title/description/`references.json`) are read unchanged by the new validation/diff code paths.
Rollback is a plain revert — no persisted format changes.

## Open Questions

None outstanding — collaborative-design (functional and technical) resolved diff representation, validation
scope, and job-isolation mechanism above. `tasks.md` will confirm exact class/package placement during
`/writing-plans`.
