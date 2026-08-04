## Why

S01 restored a plugin-accepted schema-v2 boundary, but `InspectPublicationHandler` only ever reaches `metadata_blocked`: once a note passes vault/path safety, it throws `UnsupportedOperationException` rather than reporting anything true about the note. Milestone A cannot progress until inspection reports real state for a real publication. S02 is the next slice in `openspec/implementation-plan.md`: inspecting one valid `blog/essay` must report its publication identity and the four still-independently-absent state dimensions (candidate, approved snapshot, semantic reference, release), while a note with malformed identity or a missing source ID must still correctly report `metadata_blocked` — turning the current "everything blocked" placeholder into real admission and identity logic.

## What Changes

- Extend the inspection path so that, once S01's vault/path safety passes, it reads the note's frontmatter and evaluates publication identity: `publicCollection`, a lowercase-slug `publicId`, `publicContentType` resolving to exactly one supported kind (`blog/essay` only, this slice), and a required, unique, human-assigned source ID (ADM-03, ADM-04 essay-only, SEM-01 current-source-only).
- Add the smallest frontmatter-reading collaborator behind the existing vault-adapter boundary needed to evaluate identity; no new production port beyond what S01 already introduced unless frontmatter parsing proves it cannot live inline.
- A valid essay (unique identity fields, `essay` kind, a valid non-duplicate source ID) returns `ok: true` with the resolved publication identity and four independently reported state fields — candidate absent, approved-snapshot absent, semantic-reference absent, release absent (RVA-01 absent-state scenario; BRG-04 independent-dimension reporting). None of these states can be anything but absent in this slice, since no command that creates a candidate, approval, reference map, or release exists yet.
- A note with missing/ambiguous identity fields, an unsupported `publicContentType`, a duplicate publication identity, or a missing/duplicate source ID returns `ok: false`, `status: "metadata_blocked"`, with field-specific diagnostics (ADM-03, ADM-04, SEM-01 blocked scenarios) — extending S01's existing blocked-response shape, not replacing it.
- Extend the Java-side and JS-side schema-v2 conformance tests so both the new valid-essay response shape and the new blocked-identity diagnostics are validated against `bridge-contract/schema-v2.json`.

**Explicitly excluded from this change** (per the S02 slice boundary): whole-vault discovery (S16), every publication kind other than `blog/essay` (S17a-f), Markdown link/asset handling (S12-S14), candidate preparation and translation (S03), review-plan generation (S04), and approval (S05). Those states stay hard-coded absent, not computed, until their owning slice lands.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

This change modifies `review-and-approval` (RVA-01) and `workflow-bridge` (BRG-04) with the permanent scenario for an admitted essay whose candidate, approved-snapshot, semantic-reference, and release states are all absent. `publication-admission` and `semantic-references` are pure scope pins: they add no scenario text, while documenting the already-baselined ADM-02/03/04 and SEM-01 behaviour this slice realizes. Archive the whole change normally so both real deltas reach the baseline.

## Impact

- **Modified:** `publication-exporter/` — `InspectPublicationHandler` (or its smallest natural successor) gains identity evaluation; the vault-reading boundary gains frontmatter access behind the same adapter-extraction discipline as S01. No change to the CLI option surface (`--vault`, `--note`, `--review`, `--json`).
- **Test-only:** `obsidian-plugin/` conformance test extended for the new valid-essay response shape; no runtime behaviour change to `bridge-client.js` or `main.js`.
- **Untouched:** `exporter-java/` (remains a read-only compatibility oracle), vault content, review workspace, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260804-60dfda6c`, under decision `dec-20260803-76166a5e` (slice sequence).
