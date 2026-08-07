# S08 scope pins

These notes record the functional collaborative-design pass over S08's requirement set. They live outside
`specs/` because that pass found **no genuine requirement-text gap** — every scenario S08 needs to satisfy
is already fully worded in the baseline, written generically enough (ahead of any implementation) to cover
first-publication and changed-publication alike. This mirrors S06's `REL-01`/`REL-03`/`PCM-01`/`PCM-02` pins
and S07's title/description thread-through pins: realization, not modification.

## Translation preparation

`openspec/specs/translation-preparation/spec.md` already fully specifies TRP-01 through TRP-06.

### Requirement: TRP-02 Diff against the approved Russian baseline

Fully in scope for S08, and both existing scenarios already say exactly what this slice does — no gap:

- **In scope** — Scenario: Approved baseline is absent. Already realized by S03/S04 (`ReviewPlan.firstPublication`);
  unchanged by this slice — `PrepareHandler` must keep routing here when `ApprovedSnapshotWorkspace#find`
  returns empty.
- **In scope** — Scenario: Only serialization noise changed. New behavior this slice adds: computing the
  normalized diff and finding it empty must **not** produce a new candidate or new review-plan diff entry.
  The requirement text ("no semantic translation scope is reported") already covers this; the gap is
  implementation, not wording.
- **In scope** — Scenario: Public meaning changed. S08's primary path — an approved RU snapshot exists and
  the source differs — is exactly this scenario.

### Requirement: TRP-03 Preserve a known-good English candidate until replacement is valid

Fully in scope, no gap. "Generated English is valid" already covers the happy path (install as one
coherent RU+EN+`references.json` unit); "Translation fails or is stale" already covers every failure mode
this slice must protect against (worker failure, malformed/blank result, staleness, wrong-job result) —
the requirement text enumerates all four causes generically ("fails, is malformed, is stale, or does not
match the requested job") rather than requiring a scenario per cause.

### Requirement: TRP-04 Isolate and authenticate translation jobs

Fully in scope, no gap. Note this requirement is worded for "each preparation request," not only
changed-publication ones — S03's first-publication path (`PrepareHandler`, today a direct synchronous
`translationWorker.translate()` call with no job concept at all) also comes under this requirement once
S08 lands. "Matching job completes" and "Job result crosses a boundary" already enumerate the full
authentication surface (job ID, source fingerprint, traversal/symlink/hard-link/wrong-job/wrong-fingerprint/
concurrent-stale-writer). No new scenario is needed; this is an implementation change to `TranslationWorker`
and its adapters (see `design.md`), not a requirement-text gap.

### Requirements TRP-01, TRP-05, TRP-06

Not touched. TRP-01 (first candidate) is realized by S03 and unaffected — S08 only adds the diffed-scope
and validated-replacement behavior on top of it. TRP-05 (stable occurrence IDs) and TRP-06 (queue refresh)
are out of scope until S19 and S11 respectively; this slice introduces no semantic-reference or workflow-scalar
concept.

## Review and approval

`openspec/specs/review-and-approval/spec.md` already fully specifies RVA-01 through RVA-06.

### Requirement: RVA-02 Produce an exact review plan

Fully in scope, no gap. "First publication is reviewed" is realized by S04 and unchanged. "Existing
publication changed" ("the plan identifies both candidate languages and the complete approved-versus-candidate
Russian diff") is worded exactly for S08's need — the gap is that `InspectPublicationHandler` today never
consults `ApprovedSnapshotWorkspace` at all (it hardcodes `approvedSnapshotState` to `absent` and always
returns `ReviewPlan.firstPublication(...)`), and `ReviewPlan` has no changed-publication factory. Both are
implementation gaps `design.md` addresses, not requirement-text gaps.

### Requirements RVA-01, RVA-03, RVA-04, RVA-05, RVA-06

Not touched. RVA-01 (absent/complete candidate state) is realized by S02/S04. RVA-03 through RVA-06
(explicit `mark-reviewed`, replacement, atomic install, recovery) govern approval itself, which this slice
explicitly excludes per `proposal.md` — S08 produces a new candidate for review, it does not approve it.

## Public content model

`openspec/specs/public-content-model/spec.md` already fully specifies PCM-01 through PCM-06.

### Requirement: PCM-06 Keep English content structurally aligned and route-safe

Fully in scope, no gap. Both scenarios ("Structurally valid translation is checked" / "Translation changes
an invariant or route locale") are worded generically over "an English candidate" — they do not distinguish
first-publication from changed-publication candidates, and today's `PrepareHandler` performs none of this
validation for either case. Implementing PCM-06 is therefore a new validation step in the prepare pipeline
that also strengthens S03's existing first-publication path, not a changed-publication-only addition. No new
scenario is needed.

### Requirements PCM-01 through PCM-05

Not touched. PCM-01/PCM-02 (normalized manifest, kind-projected fields) are realized by S02/S03/S07 and
unaffected — S08 changes *which* candidate gets installed and *when*, not how a candidate is normalized or
projected. PCM-03 (links), PCM-04 (protected Markdown), PCM-05 (assets) remain out of scope until
S12–S14.

## Conclusion

No file is written under `specs/` for this change: the functional collaborative-design pass found that
TRP-02, TRP-03, TRP-04, RVA-02, and PCM-06 are all realized as-worded by this slice, with zero requirement
text or scenario changes required. All gaps found are implementation gaps, tracked in `design.md` and
`tasks.md`.
