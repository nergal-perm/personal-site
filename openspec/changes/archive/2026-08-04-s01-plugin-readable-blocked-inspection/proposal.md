## Why

The replacement exporter has zero real, plugin-consumable behaviour today, and the two systems that must agree on its output already disagree: `exporter-java`'s `BridgeResponse.SCHEMA_VERSION` is `3` while `obsidian-plugin`'s `bridge-client.js`/`main.js` both hard-reject anything but `schemaVersion === 2`. Before any admission, translation, review, or release behaviour is built, the exporter needs one real system boundary that the plugin actually accepts — otherwise every later slice is built against an unverified contract. S01 is the first slice in `openspec/implementation-plan.md` and restores that boundary with the smallest possible surface: a blocked inspection response.

## What Changes

- Add a new `publication-exporter` Java 17/Maven CLI project (sibling to `exporter-java`, per gate decision `dec-20260803-dd8d5f61`) exposing an `inspect-publication` command.
- Add `bridge-contract/schema-v2.json`, a single-sourced JSON Schema for the bridge response contract (per gate decision `dec-20260803-4834d689`), covering at minimum the schema-v2 envelope and the blocked-response shape.
- `inspect-publication --json`, given an unsafe or absent note, returns exactly one JSON response on stdout with integer `schemaVersion: 2`, `command: "inspect-publication"`, `ok: false`, a workflow status, and structured diagnostics, and the process exits non-zero.
- The note path argument is passed at process argument boundaries (no shell invocation), so paths containing spaces or shell metacharacters are treated as literal data.
- Add a Java-side conformance test in `publication-exporter` that validates real `inspect-publication` output against `bridge-contract/schema-v2.json`.
- Add or extend a JS-side conformance test in `obsidian-plugin` that validates against the same `bridge-contract/schema-v2.json` file, so both sides fail together if either drifts from the shared contract.

**Explicitly excluded from this change** (per the S01 slice boundary): Markdown parsing, the valid-note success response, review workspace behaviour, any workflow status beyond `metadata_blocked`, and every mutating command (`prepare`, `mark-reviewed`, `refresh-publication-queue`). Those arrive in later slices (S02, S03, S05, S11).

## Capabilities

### New Capabilities

None. This change adds no new requirement surface.

### Modified Capabilities

None. `openspec/specs/workflow-bridge/spec.md` already fully specifies BRG-01 through BRG-07 (derived directly from `openspec/requirements-baseline.md`, ahead of any implementation). This change implements a subset of already-specified requirements — **BRG-01 (inspect-only obligations), BRG-02, and BRG-03** — without altering their requirement text or scenarios. No delta spec is produced; the `specs` artifact for this change instead pins the exact existing scenarios this slice satisfies and confirms no new ones are needed.

## Impact

- **New:** `publication-exporter/` — Maven project root, `inspect-publication` CLI entry point, application-layer blocked-inspection logic, Java-side bridge conformance test.
- **New:** `bridge-contract/schema-v2.json` — shared JSON Schema, consumed by both `publication-exporter` and `obsidian-plugin` test suites.
- **Test-only:** `obsidian-plugin/` gains or extends a conformance test against the shared schema; no runtime behaviour change to `bridge-client.js` or `main.js`.
- **Untouched:** `exporter-java/` (remains a read-only compatibility oracle), vault, review workspace, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260803-a75ab1d8`, under decisions `dec-20260803-76166a5e` (slice sequence), `dec-20260803-dd8d5f61` (G1), `dec-20260803-4834d689` (G2).
