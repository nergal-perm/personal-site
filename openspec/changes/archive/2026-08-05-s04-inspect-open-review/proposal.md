## Why

S03 gave `prepare` a working first-publication candidate (RU, worker-produced EN, empty `references.json`), but `InspectPublicationHandler` still unconditionally reports candidate, approved-snapshot, semantic-reference, and release state as `"absent"` — it has no dependency capable of reading back what `prepare` just installed. Separately, the obsidian-plugin (`main.js`) already contains fully-built, currently-orphaned consumer logic — `inspectAndOpenReview`, `validateReviewPlan`, `launchReviewPlan`, `runZedTarget` — that reads a `reviewPlan` field off the inspect response and expects `{baselineState: "absent"|"complete", targets: [{language, proposedPath, publishedPath}]}` to open RU/EN candidates as separate Zed editor windows. That logic predates this rebuild (added 2026-07-29) and is currently unfed: neither `BridgeResponse` nor `bridge-contract/schema-v2.json` produces or declares a `reviewPlan` shape. S04 is the next slice in `openspec/implementation-plan.md`: `inspect-publication` must return an exact first-publication review plan that the existing plugin can use to open both candidates, closing the gap between what S03 built and what the plugin already expects. Milestone A cannot progress to approval (S05) without a working review step in between. Governed by Haft problem `prob-20260805-d9f3aef2` under the slice-sequence decision `dec-20260803-76166a5e`.

## What Changes

- Give `InspectPublicationHandler` a read path onto the candidate a prior `prepare` installed, so candidate state is reported as ready (not hard-coded absent) when a complete candidate exists for the inspected publication.
- Add a `reviewPlan` to the `essayInspected` bridge response, populated only when a candidate exists: `baselineState: "absent"` (no approved snapshot exists yet — that case is S08's to add), and `targets` identifying the RU and EN candidate paths in that fixed order. This is exactly the shape the plugin's `validateReviewPlan`/`launchReviewPlan` already expect, so no plugin runtime code changes.
- Introduce the read side of the candidate-workspace boundary as an in-memory fake first (proving the acceptance contract), then a real filesystem-backed read path proven against the same contract — at most one new production boundary adapter, reusing the existing `CandidateWorkspace`/`FilesystemCandidateWorkspace` boundary rather than adding a parallel one.
- Extend the Java-side and JS-side schema-v2 conformance tests so the new `reviewPlan` shape is validated against `bridge-contract/schema-v2.json` and accepted by the plugin's own fixtures (`obsidian-plugin/tests/bridge-client.test.cjs` already encodes the expected `reviewPlan(baselineState)` shape).

**Explicitly excluded from this change** (per the S04 slice boundary in the implementation plan): approved-to-proposed diffs and `baselineState: "complete"` (RVA-02's "Existing publication changed" scenario, deferred to S08/S09 once an approved snapshot can exist), approval itself (S05), candidate replacement, and any editor-launch implementation detail — that logic is already owned and built by the plugin. Those conditions fail closed as unsupported/inapplicable state, not as silently-passing partial behaviour.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

Likely none at the real-delta level — `review-and-approval` (RVA-01 "Inspection observes a complete candidate", RVA-02 "First publication is reviewed") and `workflow-bridge` (BRG-04 "Candidate is ready but approved snapshot is absent", BRG-07 both scenarios) already carry baseline scenario text that describes exactly this slice's mechanism, written ahead of any implementation from `openspec/requirements-baseline.md`. The collaborative-design pass on functional requirements will confirm whether any of these four requirements need scenario-level text changes (a real delta) or whether all four are scope pins — first realizations of already-accurate requirement text, same treatment S03 gave most of its touched requirements. Whichever is decided is documented in `specs/` (real deltas) and/or this change's `scope-pins.md` (pins), not assumed here.

## Impact

- **Modified:** `publication-exporter/` — `InspectPublicationHandler` gains a dependency capable of reading an installed candidate; `BridgeResponse` gains an optional `reviewPlan` field on the `essayInspected` shape; the candidate-workspace boundary gains a read-capable method (in-memory fake first, real adapter proven against the same contract second). No change to `prepare`'s existing behaviour or option surface.
- **Test-only:** `obsidian-plugin/` conformance test extended for the new `reviewPlan` field; no runtime behaviour change to `bridge-client.js` or `main.js` — their consumer logic already exists and is exercised as-is.
- **Untouched:** `exporter-java/` (read-only compatibility oracle), vault content, approved-snapshot store, release output, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260805-d9f3aef2`, under decision `dec-20260803-76166a5e` (slice sequence).
