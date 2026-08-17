# S19 scope pins

These notes record requirement scope that S19 realizes but does not modify, following the same convention
established in S02's `scope-pins.md` and S18's `scope-pins.md`. They are stored outside `specs/` so
OpenSpec archives only a real wording delta when one exists, while this change retains its scope evidence.
Unlike S18 (which needed a genuine `MODIFIED Requirements` wording fix for SEM-01), S19 introduces no
wording delta at all: SEM-02, SEM-03's non-empty-map scenario, and TRP-05 are already fully and correctly
specified in `openspec/requirements-baseline.md` / `openspec/specs/` as the target end state. What follows
are the implementation-scoping interpretations reached during the collaborative-design session, decided by
the operator, that make those already-correct requirements concrete enough to build against.

## Semantic references

`openspec/specs/semantic-references/spec.md` already fully specifies SEM-01 through SEM-05.

### SEM-02 — Assign stable occurrence references in source order

- **Eligibility** — An "eligible semantic link occurrence" is any resolvable plain wikilink (`[[...]]`, not
  `![[...]]` embed) in the prepared body, **regardless of whether its target is currently an admitted
  public note or a private one**. Both get an occurrence ID and both are written to `references.json`.
  This is a deliberate widening beyond S18's "direct private target" framing: SEM-04/SEM-05 (S20, later)
  require that a referrer's occurrence survive its target's public/private state changing in either
  direction without a referrer re-prepare or reapproval, which only works if occurrence identity exists
  for currently-public targets too, not only currently-private ones.
- **Not eligible** (unchanged from S13's existing behavior) — a transclusion (`![[...]]`), a link inside a
  protected region (code block, Obsidian comment), a malformed/unparseable link, and a wikilink that does
  not resolve to any existing vault file (S13's existing "safe label" case — SEM-01's own scope-pins
  already established that broken/typo links are a link-syntax concern, not an identity concern; the same
  reasoning excludes them from occurrence tracking here).
- **Reuse matching ("still correspond exactly")** — matching is **positional**: the current occurrence at
  source-order index *i* reuses the previous candidate's occurrence ID at index *i* only if both target the
  same source ID. The previous map comes from `CandidateWorkspace.read(identity)`, which already returns
  the prior installed `CandidateSnapshot` (embedding its `referenceMap`) for that identity. The match key is
  target source identity, not the visible label text, so an editor rewording an anchor's display text does
  not mint a new occurrence for the same target. A link inserted, removed, or reordered breaks positional
  correspondence from that index forward; every occurrence from that point gets a fresh ID. This is a
  deliberately minimal interpretation matching the scenario's literal wording ("still correspond exactly")
  and needs no diffing algorithm — S19's acceptance boundary (one referrer, one target, one previous map
  fixture) does not exercise insertion/reorder cases, so a more permissive any-position lookup is deferred
  rather than built speculatively.
- **Self-referencing link** — not specially excluded; a source note linking to itself is a real occurrence
  like any other, since SEM-02 has no self-link carve-out (unlike SEM-01's duplicate-identity check, which
  is specifically about identity collision, not occurrence counting).

### SEM-03 — Validate the reference map as a bound snapshot member (non-empty-map scenario)

- **Occurrence entry shape** — each `references.json` occurrence entry binds: `id` (the stable occurrence
  ID), `order` (zero-based source-order index), `targetSourceId` (the target's stable source ID, the
  authoritative move-resistant lookup key per SEM-05), `ruLabel`, and `enLabel` (the localized anchor text
  in each candidate, needed by S20's later release-time substitution without re-parsing the whole body).
- **"Source paths" interpretation** — the baseline SEM-03 text lists "target source IDs, source paths" as
  bound elements. The operator's explicit decision: "source paths" is read as the *referrer's own* source
  path, already bound at the top level via `publicationId` (established in earlier release-provenance
  slices), not a second per-occurrence field alongside `targetSourceId`. No per-occurrence `targetSourcePath`
  is written. If a future slice needs a target-path snapshot for diagnostics (e.g. debugging which vault
  file an occurrence pointed to at prepare time, independent of looking up the target's current path via its
  source ID), that is a new, separately justified addition — not implied by this slice's SHALL clause under
  this reading.
- **Empty-map scenario** — unaffected and already implemented since S03/S05; this slice only makes the
  non-empty case real. A prepared body with zero eligible occurrences still writes a schema-valid empty
  `occurrences: []`, not an absent map.

SEM-01 remains realized as of S18. SEM-04 and SEM-05 remain fully specified in the baseline and are
unimplemented until S20.

## Translation preparation

`openspec/specs/translation-preparation/spec.md` already fully specifies TRP-01 through TRP-06.

### TRP-05 — Preserve semantic occurrence identity through preparation

- **Validation mechanism** — implemented by re-resolving the *translated* English body through the same
  widened link-resolution pass used for Russian (yielding a fresh, unassigned target-identity sequence for
  EN — no reuse-matching applied to the EN pass itself), then comparing EN's target-identity sequence
  against RU's assigned occurrence sequence positionally (same target source ID, same order, same count).
  On a match, EN's occurrences inherit RU's occurrence IDs positionally. On any divergence (different
  target, different order, different count), the candidate is blocked before `installCandidate` writes
  anything, per the existing scenario text.
- **No translation-contract change** — this reading deliberately makes **zero changes** to
  `TranslationWorker`, `TranslationJob`, `EnglishTranslation`, or any of their Null/fake implementations and
  existing test call sites. A conforming translation worker satisfies TRP-05 by preserving the same links in
  its translated prose — a content-fidelity expectation on the worker's output, not a new data field it must
  populate. This keeps the slice's blast radius to widening `LinkResolver`'s result and adding one
  comparison step, matching the plan's "at most one new production boundary adapter" budget (this slice
  introduces zero).

TRP-01 through TRP-04 and TRP-06 remain unaffected by this change.
