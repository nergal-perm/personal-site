## Why

S02 gave `inspect-publication` real identity and four independently-reported state dimensions for a valid `blog/essay`, but every dimension is hard-coded absent — the exporter still cannot produce anything. Milestone A cannot progress until a `prepare` command exists. S03 is the next slice in `openspec/implementation-plan.md`: preparing the S02 essay must create exactly one coherent first-publication candidate triple — a normalized Russian body, a worker-produced English body, and an empty valid `references.json` — and report schema-v2 `ready_for_review`, without touching approved snapshot or release/site state. This also closes two plan-level decision gates (G3 production translation-worker protocol, G4 compatibility depth for this slice's RU normalization) that the implementation plan lists as prerequisites for S03 and that no Haft decision has yet resolved.

## What Changes

- Add a `prepare` bridge command (BRG-01 prepare) that, for a note already admitted by S02's `EssayAdmission`, requests translation and installs a first-publication candidate: RU (the admitted essay's normalized body — no candidate exists yet, so this slice's RU projection is deliberately the minimal normalization PCM-01/PCM-02 require, not the richer Markdown safety work S12-S14 add later), EN (returned by a translation worker), and `references.json` (a schema-valid empty map — SEM-03, since no semantic links are resolved until S13/S19).
- Introduce a `TranslationWorker` port with an in-memory fake for the acceptance suite and one real adapter, resolving G3: the production protocol, cancellation/timeout behaviour, and result carrier, evidenced against `exporter-java`'s `CodexRunner` (a synchronous external-process invocation with a bounded timeout and a workdir-scoped result) as a compatibility oracle, not a code donor. Worker-facing tests stay worker-neutral per the plan's own gate wording.
- Introduce a candidate-workspace collaborator that writes the RU/EN/`references.json` triple as one coherent unit — either all three land or none do — first as an in-memory fake, then behind a real adapter proven against the same contract.
- Resolve G4 (compatibility depth) for this slice: RU normalization targets semantic/site acceptance, not byte-for-byte reproduction of legacy YAML/Markdown quirks — consistent with the plan's explicit non-goal of "preserving known defects" and its framing of `exporter-java` as evidence, not a template.
- A successful prepare returns `ok: true`, `status: "ready_for_review"` with the candidate's identity (PCM-01 determinism, PCM-02 essay-only projection). Preparing an unrelated invalid publication creates no job or candidate for it (ADM-05 bounded-request validation).
- Extend the Java-side and JS-side schema-v2 conformance tests so the new `ready_for_review` response shape is validated against `bridge-contract/schema-v2.json`.

**Explicitly excluded from this change** (per the S03 slice boundary): an existing candidate to replace, an approved baseline to diff against (TRP-02/03, S08), semantic occurrence IDs (TRP-05/SEM-02, S19), job isolation and concurrent-job handling (TRP-04, S08), links/assets/protected-Markdown transforms (PCM-03/04/05, S12-S14), review-plan generation (S04), and approval (S05). Those conditions fail closed as unsupported state, not as silently-passing partial behaviour.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

This change touches `translation-preparation` (TRP-01), `public-content-model` (PCM-01, PCM-02), `semantic-references` (SEM-03), `publication-admission` (ADM-05), and `workflow-bridge` (BRG-01), but only `semantic-references` gets a real scenario-level delta: SEM-03's baseline scenarios both assume a non-empty reference map, and S03 is the first slice able to observe the always-empty first-publication case (no semantic links are resolved until S13/S19). TRP-01, PCM-01, PCM-02, ADM-05, and BRG-01 are pure scope pins, same treatment S02 gave `publication-admission`/`semantic-references`: their existing baseline scenario text already covers exactly what this slice does — S03 is simply the first slice able to realize a mechanism that already-accurate requirement text described, not a gap in that text. They are documented in this change's `scope-pins.md`, not as `specs/` deltas. Archive the whole change normally so the one real delta reaches the baseline.

## Impact

- **Modified:** `publication-exporter/` — a new `prepare` bridge command and handler; a new `TranslationWorker` port (in-memory fake + real adapter, resolving G3); a new candidate-workspace collaborator (in-memory fake + real adapter); `BridgeResponse` gains the `ready_for_review` candidate-inspected shape. No change to the existing `inspect-publication` command's behaviour or option surface.
- **Test-only:** `obsidian-plugin/` conformance test extended for the new `ready_for_review` response shape; no runtime behaviour change to `bridge-client.js` or `main.js`.
- **Untouched:** `exporter-java/` (remains a read-only compatibility oracle), vault content, approved-snapshot store, release output, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260804-97ecd928`, under decision `dec-20260803-76166a5e` (slice sequence). G3 and G4 are each closed with their own Haft decision during this change's design phase, before `design.md` is finalized.
