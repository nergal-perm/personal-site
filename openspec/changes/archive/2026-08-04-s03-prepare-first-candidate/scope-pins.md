# S03 scope pins

These notes record requirement scope that S03 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the one real delta (`semantic-references`), while this change
retains its scope evidence.

## Translation preparation

`openspec/specs/translation-preparation/spec.md` already fully specifies TRP-01 through TRP-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### Requirement: TRP-01 Prepare one bounded publication candidate

Fully in scope for S03, and both existing baseline scenarios already say exactly what this slice does —
no gap, first realization only:

- **In scope** — Scenario: Preparation succeeds (one admitted source note and writable safe workspaces;
  one candidate snapshot installed for that publication identity; no approved snapshot or site tree
  changes).
- **In scope** — Scenario: Another publication is invalid (the requested source note is valid and an
  unrelated publication is invalid; no job or candidate is created for the unrelated publication). This
  is the concrete realization of ADM-05's "bounded request" behaviour for `prepare` specifically.

TRP-02 through TRP-06 remain fully specified in the baseline and are unimplemented until S08, S11, and
S19 respectively.

## Public content model

`openspec/specs/public-content-model/spec.md` already fully specifies PCM-01 through PCM-06 as the
target end state.

### Requirement: PCM-01 Produce a deterministic normalized manifest

In scope for S03, restricted to a single candidate built from a single in-memory note — no repeated
builds across filesystem enumeration orders and no whole-vault manifest exist yet (S16).

- **In scope** — Scenario: Same inputs are built twice, restricted to running `prepare` twice against the
  identical admitted note and asserting the RU/EN/`references.json` bytes are identical both times. The
  "different filesystem enumeration orders" clause is vacuous with one candidate; that becomes
  meaningful once whole-vault discovery (S16) enumerates more than one note.
- **In scope** — Scenario: Workflow metadata changes, restricted to exporter-owned workflow scalars (none
  of which are written by `prepare` itself in this slice, since TRP-06's workflow-scalar update is S11)
  not affecting the candidate's content hash.

### Requirement: PCM-02 Project only fields allowed by the publication kind

In scope for S03: `blog/essay` only, and restricted to the RU candidate body — the release-time manifest
built from an *approved* snapshot is S06's realization of the same requirement, not this slice's.

- **In scope** — Scenario: Kind-specific projection succeeds, restricted to essay: the RU candidate body
  is the admitted note's body only (no frontmatter fields — `id`, `publish`, `publicCollection`,
  `publicContentType`, `publicId` are all private/workflow-only and excluded from the candidate; per
  `dec-20260804-9f43c17f` (G4), the body itself is passed through verbatim for this slice's plain-essay
  scope, no link/asset/comment transforms yet).
- **Not applicable to essay** — Scenario: Unsupported value reaches projection. Essay carries no
  kind-specific body-section requirement beyond identity (per S02's ADM-04 scope pin), so there is no
  "malformed structured body" case reachable for essay yet; this becomes reachable with the first kind
  that has real body structure (`bibliography/book`, S17c).

PCM-03, PCM-04, PCM-05 remain fully specified in the baseline and are unimplemented until S12-S14.
PCM-06 remains fully specified and is unimplemented until S08/S17 (structural translation-alignment
validation is not exercised in this slice — the worker's English output is installed once it exists and
is non-empty, per TRP-01; deeper structural checks wait for PCM-06's own realization).

## Semantic references

### Requirement: SEM-01 Require stable source-owned semantic identities

Already realized by S02, restricted to the source note's own identity (no link targets exist to
evaluate yet, per S02's own scope pin). S03 does not change SEM-01's realization — it reuses S02's
`EssayAdmission` unchanged (aside from the pre-slice `sourceId`→`id` frontmatter-key rename landed as
its own preparatory commit; see `note-20260804-469b8022`) as the admission gate `prepare` runs before
requesting translation.

## Publication admission

`openspec/specs/publication-admission/spec.md` already fully specifies ADM-01 through ADM-06.

### Requirement: ADM-05 Validate the bounded request, not unrelated notes

In scope for S03, and its "Unrelated invalid note exists" scenario is realized concretely by TRP-01's
own "Another publication is invalid" scenario above — the same mechanism (single-note-scoped request
validation) that S01/S02 already built for `inspect-publication`'s vault/path confinement and essay
admission, now reused unchanged for `prepare`.

- **In scope** — Scenario: Unrelated invalid note exists (realized via TRP-01's "Another publication is
  invalid" scenario).
- **Not yet applicable** — Scenario: Whole-vault release is requested. No whole-vault operation exists
  until S16.

ADM-01, ADM-02, ADM-03, ADM-04, and ADM-06 are unaffected by this change; ADM-02/03/04 remain realized
as S02 left them (essay-only, single-note scope), and `prepare` inherits that realization unchanged by
running the same `EssayAdmission` before requesting translation.

## Workflow bridge

### Requirement: BRG-01 Support the plugin command set without shell interpretation

Already fully realized for `inspect-publication` by S01/S02; S03 extends the same realization to
`prepare` — the "Note-scoped command is invoked" scenario is generic across every note-scoped command
and already covers `prepare` without new scenario text.

### Requirement: BRG-02 Emit bridge schema v2 for the initial replacement release

Fully in scope, extended rather than newly realized: `prepare`'s success response (`ok: true`,
`status: "ready_for_review"`) and its failure responses (`metadata_blocked`, `translation_failed`) are
each exactly one schema-v2 JSON value, satisfying "Successful bridge command returns" and "Domain
operation is blocked" for a second command.

### Requirement: BRG-03 Keep the bridge contract single-sourced and conformance-tested

Fully in scope, extended rather than newly realized: the Java-side and JS-side conformance tests both
gain coverage of `prepare`'s response shapes against the same `bridge-contract/schema-v2.json`. No
schema file edit is required — `additionalProperties: true` and the free-form `status` string already
permit `prepare`'s response shape.

## Not touched by this change

BRG-04 through BRG-07 remain fully specified in the baseline and are unaffected here — `prepare` does
not report the four inspect-style state dimensions (identity + status only, per the operator's explicit
choice during the functional collaborative-design pass); a follow-up `inspect-publication` call is how
the plugin observes the newly-ready candidate.
