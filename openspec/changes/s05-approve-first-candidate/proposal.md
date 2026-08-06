## Why

S04 gave `inspect-publication` a real review plan once `prepare` (S03) installs a candidate, but there is still no way to actually approve anything: `mark-reviewed` is declared in `bridge-contract/schema-v2.json`'s command enum but has zero implementation anywhere in the codebase — no handler, no CLI command, no Java reference to the string `"mark-reviewed"` outside the schema. `BridgeResponse.approvedSnapshotState` is hard-coded to the literal `"absent"` in every response `InspectPublicationHandler` produces, and no approved-snapshot store concept exists at all (no port, no adapter, no test — this is greenfield). S05 is the next slice in `openspec/implementation-plan.md`: an explicit `mark-reviewed` command must install the exact reviewed candidate as the first durable approved triple (RU, EN, reference map) and return success only once it is readable back as one coherent snapshot. Milestone A (S01-S07) cannot progress to release materialization (S06) without a durable approved snapshot to build from. Governed by Haft problem `prob-20260805-3d747bed` under the slice-sequence decision `dec-20260803-76166a5e`.

## What Changes

- Add a `mark-reviewed` bridge command that, for a note with a complete S04-reviewed candidate, revalidates the candidate is still exact (RVA-04, scoped to this slice's first-approval case — no competing approval or existing approved snapshot exists yet to contend with) and installs it as the first approved snapshot: RU, EN, and the same reference map the candidate carried (RVA-03).
- Introduce an approved-snapshot store as a new production boundary adapter, reusing `CandidateWorkspace`'s already-proven conventions (Constructor Method factories, stage-then-`ATOMIC_MOVE` install, `requireWithinReviewRoot`-style confinement) rather than inventing new patterns — first an in-memory fake proving the contract, then a real create-only filesystem adapter proven against the same contract (RVA-05).
- A successful `mark-reviewed` returns `ok: true` only after the approved snapshot is durable and reads back exactly as the candidate that was approved.
- A second approval attempt (an approved snapshot already exists) fails closed in this slice rather than silently replacing it or being silently ignored — replacement is S09's job.
- Extend the Java-side and JS-side schema-v2 conformance tests so the new `mark-reviewed` response shape is validated against `bridge-contract/schema-v2.json`.

**Explicitly excluded from this change** (per the S05 slice boundary in the implementation plan): replacing an existing approved snapshot, crash recovery after a replacement starts, release generation, and competing/concurrent-approval lock contention (RVA-04's "per-publication exclusion lock" phrase has no reachable contention case until a second approval exists — S09's job). Those conditions fail closed as unsupported state, not as silently-passing partial behaviour.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

Only `review-and-approval` gets a real scenario-level delta: RVA-05 gains a new "A second approval is attempted" scenario, since no existing RVA-03/04/05 scenario describes what happens when `mark-reviewed` runs again for a publication that already has an approved snapshot — the plan's own explicit exclusion ("a second approval fails closed until S09") is genuinely new observable behavior a create-only install mechanism must produce, not a case any existing scenario's GIVEN clause already covers. RVA-03, RVA-04, `semantic-references` (SEM-03, reintroduced at the approval boundary per the plan's own traceability matrix), and `workflow-bridge` (BRG-01, extending the same note-scoped-command realization S01-S04 already gave `prepare`/`inspect-publication`) are pure scope pins — their existing baseline scenario text already says exactly what this slice does. RVA-05's own "Approval is interrupted" (crash-recovery) scenario is likewise satisfied by construction by the same atomic stage-then-move mechanism `CandidateWorkspace` already established: a crash before the atomic move leaves "no approved snapshot yet" (indistinguishable from never having approved), and a crash after means it already succeeded — there is no partial/mixed state an atomic move onto a fresh directory can produce, unlike the replacement case S09 must handle. RVA-04's per-publication exclusion lock is vacuously satisfied since nothing in this slice can attempt a second concurrent approval. Documented in this change's `specs/review-and-approval/spec.md` (the one real delta) and `scope-pins.md` (RVA-03, RVA-04, SEM-03, BRG-01).

## Impact

- **Modified:** `publication-exporter/` — a new `mark-reviewed` bridge command and handler; a new approved-snapshot-store port (in-memory fake + real create-only adapter); `BridgeResponse` gains whatever response shape `mark-reviewed` needs (a new factory, or reuse of an existing one — a design-phase decision). No change to `prepare`'s or `inspect-publication`'s existing behaviour or option surface, except that `InspectPublicationHandler` may eventually read the new store too — deferred unless the design phase finds it in scope (S05's own visible result is about `mark-reviewed` succeeding, not about `inspect-publication` reporting the new state, though that reporting gap should be checked during design since it mirrors S04's own inspect-reporting work).
- **Test-only:** `obsidian-plugin/` conformance test extended for the new `mark-reviewed` response shape; no runtime behaviour change to `bridge-client.js` or `main.js` expected, unless the plugin already contains orphaned `mark-reviewed` consumer logic analogous to what S04 found for `reviewPlan` — checked during design.
- **Untouched:** `exporter-java/` (read-only compatibility oracle), vault content, candidate store, release output, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260805-3d747bed`, under decision `dec-20260803-76166a5e` (slice sequence).
