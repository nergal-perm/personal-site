# S07 scope pins

These notes record requirement scope that S07 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the one real delta (`release-materialization` REL-05's new
empty-destination-install scenario), while this change retains its scope evidence.

## Release materialization

`openspec/specs/release-materialization/spec.md` already fully specifies REL-01 through REL-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### Requirement: REL-04 Guard release inputs during materialization

Partially in scope for S07; only the "nothing to guard against yet" half is reachable.

- **In scope** — Scenario: Inputs remain stable. Trivially true for a first install: the only declared
  release input is the S06 generation itself, and nothing else exists to have drifted between planning
  and commit. The guard still runs (it is not special-cased away), it simply always finds a stable input
  in this slice.
- **Not yet applicable** — Scenario: Input changes concurrently. This describes a *second* release attempt
  racing a change to a declared input while a *first* generation already exists to protect. S07 writes into
  empty roots exactly once per acceptance run; there is no concurrent second attempt to race. Reachable
  once S10 (replace an existing generation) exists.

### Requirement: REL-05 Replace only exporter-managed site trees atomically

Partially in scope for S07 — see `specs/release-materialization/spec.md` for the real delta (the new
"Empty-destination install" scenario). The requirement's replace/recover half stays out of reach:

- **Not yet applicable** — Scenario: Staged content or filesystem is unsafe. "Live managed trees remain at
  the prior complete generation" presupposes a prior generation. S07's only unsafe-input case is a staged
  tree that fails validation before anything is ever committed to the managed roots — there is no "prior
  generation" state to remain at, only "still absent." The staging/validate-before-commit mechanics this
  scenario exercises are still built in S07 (the install never partially writes managed roots), just not
  proven against a pre-existing generation.
- **Not yet applicable** — Scenario: Installation is interrupted. Recovery "to one complete old or new
  generation" requires an old generation to be a valid recovery target. S07 has none; interrupted-install
  recovery is S10's job.

### Requirement: REL-06 Gate Astro builds on content ownership and provenance

Fully in scope for S07, and both existing scenarios already say exactly what this slice does — no gap,
first realization only:

- **In scope** — Scenario: Generated content is coherent. This is exactly S07's real-adapter contract test
  and the one slow Astro smoke test: the installed managed trees and the `.astro-export/release-provenance.json`
  manifest correspond exactly, `site/scripts/check-content.mjs` passes with `ASTRO_REQUIRE_RELEASE_PROVENANCE=1`,
  and `astro build` succeeds.
- **In scope** — Scenario: Generated content violates a gate. Exercised by a fixture that tampers with one
  managed file after install (mirroring `site/tests/release-provenance.test.mjs`'s "rejects a modified
  managed file" case) and asserts the gate fails before any build is considered successful.

**Required-page-contract note:** `check-content.mjs`'s page-parity check (`about`, `concepts`, `essays`,
`home`, `library`, `music`, `notes`, `search`, `claims` must exist in both locales under `src/data/pages`)
is a pre-existing, exporter-independent site contract. No requirement in this slice's scope — nor any
requirement introduced before S15 (`ADM-06`, publication contract) or S17f (`editorial/curated_page`,
gated by G7) — produces curated pages. S07's real-adapter and smoke-test fixtures pre-seed
`src/data/pages/{ru,en}/*.json` as static test data, the same way `site/tests/release-provenance.test.mjs`'s
`writeProvenanceFixture()` already does; only `src/content/blog/{ru,en}/<id>.md` and
`.astro-export/release-provenance.json` are ever written by this slice's adapter. This is a functional-design
decision (confirmed via `/collaborative-design`), not an implementation detail — recorded here because it
determines what REL-06's "Generated content is coherent" scenario can actually observe as coherent in this
slice's tests.

### Requirements REL-01, REL-02, REL-03

Not touched. **Correction (found by the final whole-branch review):** S07 does NOT read S06's release output
or `ReleaseProvenance` — it reads `ApprovedSnapshotWorkspace` directly, the same source S06 itself reads,
per design.md's Context point 5 (both are independent siblings realizing REL-01's "approved snapshots only"
authority, not a pipeline). `ReleaseOutputStore`/`ReleaseProvenance`/`BuildFromReviewHandler` are fully
untouched by this slice. S07 adds no new provenance concept for the review-root artifact; it computes its
own, structurally unrelated `SiteReleaseManifest` fresh from the just-installed managed-tree bytes.

## Title/description thread-through (ADM-04, TRP-01, PCM-01, PCM-02, REL-01, REL-03)

S07 is the first slice to write real content into `site/src/content`, and doing so surfaced a genuine gap:
`site/src/content.config.ts`'s Astro schema requires non-empty `title`/`description` on every essay entry,
but no requirement or domain type anywhere in S02-S06 carries either field. These are vault-author-provided
fields — the Obsidian plugin gates on their presence before allowing "Prepare to publication" — so this
slice extends `EssayAdmission` to admit them (the one real delta, `ADM-04`, see
`specs/publication-admission/spec.md`) and threads the resulting RU/EN pairs through the existing pipeline.
Every requirement that pipeline already touches is realized, not modified:

### Requirement: TRP-01 Prepare one bounded publication candidate

Already realized. "One candidate snapshot is installed for that publication identity" does not enumerate
which fields that snapshot carries — extending the translation-worker contract to translate title/description
alongside the body (the same worker invocation, three strings instead of one) is exactly "preparation
succeeds" with a wider candidate snapshot, not a new scenario.

### Requirement: PCM-01 Produce a deterministic normalized manifest

Already realized. "Deterministic manifest whose ... metadata projection ... depend[s] only on declared source
inputs" already covers title/description exactly as it covers every other admitted field — determinism is
asserted at the field-set level, not per named field.

### Requirement: PCM-02 Project only fields allowed by the publication kind

Already realized, and this is precisely the requirement `ADM-04`'s new scenario feeds: once title/description
are declared part of essay's kind-specific contract, "the normalized fields defined for its publication kind"
already includes them by construction. No second, redundant PCM-02 scenario is needed for a field ADM-04
already declared required.

### Requirement: REL-01 Read approved snapshots only

Already realized. "Public content reflects the approved snapshot" already covers whatever fields the approved
snapshot carries — it was never scoped to "body only."

### Requirement: REL-03 Bind output to deterministic release provenance

Already realized for the same reason S06 recorded: provenance binds "selected approved snapshot hashes,"
not an enumerated field list. **Correction (found by the final whole-branch review):** at the point this
note was first written, title/description did NOT actually flow through any hash-based integrity mechanism —
`ReferenceMap` carried only `ruHash`/`enHash` (RU/EN body hashes). This was a real gap, since it meant
approval and staleness detection (RVA-02/RVA-04) never caught a title/description change after `prepare` or
after approval. Fixed in a post-review round: `ReferenceMap` now also carries title/description hashes, and
`MarkReviewedHandler`'s staleness check now covers all six candidate strings, not just the two bodies — see
the fix commit for the exact shape. This note is scoped by the review process itself, not merely restated.

## Public content model (site-install boundary)

`openspec/specs/public-content-model/spec.md` already fully specifies PCM-01 through PCM-06. S07's site-install
step performs no new normalization or projection of its own: it copies the S06 release output's
already-normalized bytes (body, title, description) verbatim into `src/content`. PCM-01 and PCM-02 remain
satisfied by construction exactly as S06 recorded them at the release boundary; no new scenario is needed at
the site-install boundary specifically.

## Not touched by this change

`install-to-site` itself is not in `bridge-contract/schema-v2.json`'s command enum and produces no
`BridgeResponse` the plugin consumes; confirmed, not merely assumed, by the technical collaborative-design
pass (see `design.md`).

**Correction (found by the final whole-branch re-review):** the paragraph below originally claimed the whole
of `workflow-bridge`, `translation-preparation`, `review-and-approval`, and `semantic-references` were
untouched. That was true of the initial design but became false once the metadata-integrity fix (closing a
real review-visibility and tamper-integrity gap the first final review found) landed: `ReferenceMap`
(reference-map validation) now hashes title/description alongside body; `MarkReviewedHandler`
(review-and-approval) now blocks approval on title/description staleness, not only body staleness; and
`ReviewPlan` (part of `BridgeResponse`, returned by the already-existing `inspect-publication` command) grew
four new flat fields — `ruTitle`, `enTitle`, `ruDescription`, `enDescription` — so a reviewer can in principle
see the metadata alongside the two body-file targets. This is schema-safe (`reviewPlan`'s definition in
schema-v2.json has `"additionalProperties": true` and the four fields are not required), so no plugin or
schema change was strictly necessary for `inspect-publication` to keep working — but it does mean
`workflow-bridge` and `review-and-approval` were touched, not unaffected as originally stated.

**Known gap, deliberately deferred:** the Obsidian plugin (`obsidian-plugin/main.js`) never reads these four
new `ReviewPlan` fields — `validateReviewPlan(...)` only checks `baselineState` and `targets`, and
`launchReviewPlan(...)` only opens the two body files for review. A human reviewer therefore still never sees
title/description before approving, even though the backend now blocks approval if either has changed since
preparation. The operator's original instruction was specifically about the plugin gating "Prepare to
publication" on title/description's existence in source frontmatter — already implemented via
`EssayAdmission`'s admission-time check, which the plugin already surfaces generically through its existing
diagnostics mechanism. Displaying title/description during *review* (a stronger UX guarantee this slice's own
design added on top of that) is left to a follow-up slice: the safety-relevant property (a stale or tampered
title/description blocks approval regardless of whether a human visually reviewed it) is already fully
backend-enforced, so this gap is a UX completeness item, not a security hole.

`translation-preparation` and `semantic-references` remain unaffected beyond the above: S07 never touches jobs
or introduces new reference-map *concepts*, only widens the existing hash set. `legacy-transition` remains
entirely unimplemented and out of scope until S21+.
