## Why

S05 gave the exporter a durable, atomically-installed approved snapshot (`ApprovedSnapshotWorkspace#install`, `ru.md`/`en.md`/`references.json` under `<reviewRoot>/<collection>/<id>/approved/`), but nothing yet turns that snapshot into anything a site can build from. There is no `build-from-review` command, no release-output port, and no provenance concept anywhere in `publication-exporter/` — this is greenfield, matching every prior slice. S06 is the next slice in `openspec/implementation-plan.md`: materialize the S05 approved essay into deterministic Astro-input files plus minimum release provenance, reading approved state only and ignoring any candidate. Milestone A (S01-S07) cannot reach a real Astro build (S07) without a release-output artifact to install. Governed by Haft problem `prob-20260806-e107746a` under the slice-sequence decision `dec-20260803-76166a5e`.

## What Changes

- Add a `build-from-review` command that, for a note with a durable approved snapshot (S05), reads the approved RU body, EN body, and reference map and writes them into a brand-new, previously empty output root as one RU essay file and one EN essay file (REL-01, PCM-01/PCM-02 at the release boundary).
- Bind that output to deterministic minimum release provenance: exporter contract edition, the approved snapshot's own hashes (already recorded in `references.json`), and hashes of every file the release writes (REL-03). Since no semantic occurrence ever exists yet (`ReferenceMap#occurrences()` is unconditionally empty until S19), activation/deactivation counts are always zero and no semantic-projection input exists to record beyond "none" — this is the "minimum" the plan's S06 entry calls for, not a placeholder to fill in later.
- Introduce a release-output store as a new production boundary adapter, following S05's own established shape (a small port, an in-memory fake proven first, then a real filesystem adapter proven against the same contract) — first candidate this codebase has seen for extracting a shared staging/confinement base from `FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace`'s already-duplicated shape a third time (flagged as a design-phase question, not decided here).
- A release attempt for a publication with an absent, partial, or otherwise unsafe approved snapshot blocks before any output write — no partial output root is ever left behind.
- A release attempt ignores any existing candidate entirely: only the approved triple has release authority (REL-01).
- Extend the Java-side schema-v2 conformance coverage only if `build-from-review` turns out to need a bridge-envelope response at all — this command's visible result is a written output tree plus provenance, not necessarily a `BridgeResponse` the plugin consumes the way `inspect-publication`/`prepare`/`mark-reviewed` do. This is an open question for the technical collaborative-design pass, not assumed here.

**Explicitly excluded from this change** (per the S06 slice boundary in the implementation plan): replacing an existing live site tree, assets, links, multiple publications in one invocation, and recovery from a prior generation. Those are S07 (site install/build), S10 (replace a managed release safely), S13/S14 (links/assets), and S16 (whole-vault aggregate) — S06 fails closed or is simply inapplicable for every one of those cases (there is nothing to replace yet since the output root starts empty, and there is exactly one publication in scope).

## Capabilities

### New Capabilities

None — `build-from-review` realizes requirements already fully specified in the baseline (`release-materialization`, `public-content-model`); it does not introduce a new capability area.

### Modified Capabilities

- `release-materialization`: REL-01 and REL-03 look like pure scope pins on first reading (their existing scenario text already describes S06's blocking-on-unsafe-snapshot and determinism behavior). REL-02 ("Resolve bilingual semantic projection without private leaks") has two existing scenarios that both presuppose at least one approved semantic occurrence exists — S06 has zero occurrences by construction (S19 is the first slice that ever produces a non-empty `references.json`), so neither scenario's GIVEN clause is reachable yet. Whether this is a genuine scenario gap (a new "no semantic occurrences exist" case, mirroring how S03 added SEM-03's empty-map scenario) or a pure "not yet applicable" scope pin is exactly what the functional collaborative-design pass resolves; the answer lands in `specs/release-materialization/spec.md` (if a real delta) and/or `scope-pins.md` (if a pin).
- `public-content-model`: PCM-01/PCM-02 "at the release boundary" is very likely satisfied by construction — the release step copies the already-normalized approved bytes verbatim (REL-01's own "public content reflects the approved snapshot" wording), performing no new normalization of its own. Confirmed as a scope pin, or given a real delta if the collaborative-design pass finds a gap (e.g. an explicit release-time re-projection guarantee no existing PCM-01/02 scenario currently states).

## Impact

- **Modified:** `publication-exporter/` — a new `build-from-review` command/handler; a new release-output port (in-memory fake + real new-directory filesystem adapter); a small provenance value type and its JSON encoding. No change to `inspect-publication`, `prepare`, or `mark-reviewed`'s existing behavior or option surface.
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/` (this slice's visible result is a CLI-invoked artifact write, not a plugin-consumed bridge response, pending the technical design pass's confirmation), vault content, candidate store, approved-snapshot store, Astro `site/`, and deployment.
- **Governance:** implements Haft problem `prob-20260806-e107746a`, under decision `dec-20260803-76166a5e` (slice sequence).
