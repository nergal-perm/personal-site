## Why

S18-S20 closed Milestone D (semantic publication frontier): direct-target source-ID admission, a stable
semantic occurrence map, and late-bound target activation. `openspec/implementation-plan.md` now opens
Milestone E (legacy transition) with S21. Requirements MIG-01, MIG-02, and MIG-05 (incomplete-state gate) are
already normatively specified in `openspec/specs/legacy-transition/spec.md`, but the new exporter
(`publication-exporter`) has no implementation of any of them today: there is no inventory command, no
legacy-shaped-state detector, and no fail-closed guard on `prepare`/`build-from-review`.

The legacy `exporter-java` oracle already solved an equivalent problem for its own predecessor workspace
shape (`migrate-semantic-links --report ... --json`, documented in `exporter-java/README.md`'s "Semantic
migration and release commands" section): a read-only inventory phase that reports legacy approved/candidate
pairs, existing identities, semantic occurrences, ambiguities, and blockers without mutating anything, kept
strictly separate from a later, explicitly-authorized apply phase. `publication-exporter` has never had a
concept of "legacy state" at all — every acceptance test to date starts from an in-memory vault and empty
approved/candidate stores, i.e. a workspace that is by construction never legacy-shaped. This slice
introduces that concept for the first time, as a read-only diagnostic only.

G6 (Cutover) — whether legacy pairs must be migrated in place or the new exporter starts only from
already-current triples — is a decision gate the implementation plan places before S21. The operator has
selected in-place migration (recorded as Haft note `note-20260818-...-a548cfbe`; a durable `dec-` record
still needs manual CLI binding). That selection keeps S22 (non-executable migration decisions) and S23
(conditional apply/recovery) in scope for later slices; S21 itself does not implement or presuppose either.

## What Changes

- **A new explicitly-invoked, read-only migration inventory** produces a deterministic report of a legacy
  workspace: approved pairs, candidate pairs, existing source identities, semantic occurrences, ambiguities,
  and unsafe/blocked paths — without touching source, review, or site files (MIG-02). "Legacy" here means a
  review workspace that predates this slice's semantic-schema activation marker (introduced by this slice, per
  MIG-05) — concretely, any workspace with approved or candidate triples on disk but no `schema-v1.active.json`
  equivalent recording that it has been inventoried under the current schema edition.
- **Normal `prepare`/`build-from-review` gain a fail-closed guard**: when either observes a legacy-shaped
  workspace (approved/candidate content present, activation marker absent or inconsistent), it blocks with
  migration-required evidence in its bridge response rather than mutating candidate, approved, or release
  state (MIG-01, MIG-05). A workspace with no prior content at all (the shape every existing acceptance test
  uses) is never legacy-shaped and is entirely unaffected — this is the same "current semantic workspace"
  path S01-S20 already exercise.
- **No new mutation surface.** Inventory only reads; the fail-closed guard only blocks. Neither installs,
  allocates, or rewrites anything. Decision draft generation (MIG-03), apply (MIG-04), and activation-marker
  installation as a side effect of apply are explicitly out of scope — this slice only *detects and reports*
  legacy state; a later slice (S23, conditional on G6) is what actually transitions a workspace out of it.

## Capabilities

### New Capabilities

(none — MIG-01, MIG-02, and MIG-05 are already specified in the baseline; see design.md for the concrete
mechanism)

### Modified Capabilities

- `legacy-transition`: MIG-01, MIG-02, and MIG-05 are implemented for the first time. MIG-03 and MIG-04
  remain specified but unimplemented (S22/S23).

## Impact

- New read-only inventory command/handler and adapter(s) in `publication-exporter` (in-memory first, then a
  real filesystem-scanning adapter under a no-mutation contract).
- `PrepareHandler` and `BuildFromReviewHandler` each gain a legacy-state check at their existing entry point,
  short-circuiting before any mutation when the check fails. No change to their success-path behavior for a
  workspace that has no legacy content (every existing acceptance test's baseline).
- No change to `MarkReviewedHandler`, `InstallToSiteHandler`, candidate/approval locking, or any release
  materialization behavior beyond the new pre-mutation guard.
- No change to `exporter-java` — it remains a read-only compatibility oracle for the inventory report shape,
  not a code donor.
- Governed by Haft problem `prob-20260818-40bccb11`, sub-problem of `prob-20260803-fe9b3011` (greenfield
  exporter slice sequencing). G6 (in-place migration) recorded per Haft note
  `note-20260818-prob-20260818-40bccb11-slice-s21-read-only-legac-a548cfbe`.
