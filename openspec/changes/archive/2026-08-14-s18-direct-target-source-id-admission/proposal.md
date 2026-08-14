## Why

SEM-01 already requires every direct private link target, not just the selected source note, to carry a stable, unique, human-assigned source ID before semantic preparation — but only the source note's own identity is checked today (S02's scope pin explicitly defers "the direct-private-target part" to this slice). Neither `PublicNoteIndex` (which only maps already-admitted *public* notes to routes by filename stem, dropping ambiguous stems silently) nor `LinkResolver` looks at a link target's own `id` frontmatter at all. A direct private target with a missing or duplicate source ID can reach translation/candidate mutation unchecked today, which is exactly the path-derived-identity risk SEM-01 exists to close before S19's occurrence maps and S20's late-bound activation build on top of it.

## What Changes

- Add a small vault-wide source-identity index (in-memory first) that `prepare` consults for every note reachable as a direct private link target from the note being prepared, not only the note itself.
- `prepare` fails closed as `metadata_blocked`, before any translation job or candidate mutation, when a direct private target is missing a source ID or shares one with another note in the index — matching the existing SEM-01 scenario wording exactly.
- No path-derived or automatically-allocated identity exists anywhere in this path: an unindexable/missing target ID is a block, never a fallback.
- Explicitly excluded: occurrence IDs (SEM-02), `references.json` population (SEM-03's non-empty-map case), and late-bound link activation at release (SEM-04/SEM-05) — those remain S19/S20.

## Capabilities

### New Capabilities
(none — this slice realizes more of an already-declared capability rather than introducing a new one)

### Modified Capabilities
- `semantic-references`: SEM-01's already-fully-specified requirement text is realized for the direct-target half of its two existing scenarios (source-side identity was realized in S02). Whether this needs a `MODIFIED Requirements` wording delta or only a `scope-pins.md` claim (S02's precedent for ADM-02) is decided during the collaborative-design pass on spec.md, not assumed here.

## Impact

- New code: an in-memory vault source-identity index (fake first, per this project's outside-in/nullables discipline), consulted from the `prepare` path right after `LinkResolver` resolves the body (see design.md).
- `VaultReader` (existing port) gains one unfiltered listing method reused by the new index; `LinkResolver` is additively extended to also report the private target stems it already parses, so identity-checking does not re-scan the body with a second link parser. No behavior change to what `LinkResolver`/`PublicNoteIndex` render (routes, labels, blocked-transclusion messages) — see design.md for exactly what widens and why.
- No changes expected to release or approval — this slice only gates identity ahead of the existing link-routing behavior S13 already built; it does not change what gets resolved or activated.
- Governed by Haft problem `prob-20260814-9d502f85`, sub-problem of `prob-20260803-fe9b3011` (greenfield exporter slice sequencing).
