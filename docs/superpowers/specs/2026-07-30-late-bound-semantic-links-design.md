# Late-Bound Semantic Wikilinks and Approved-Only Releases

## Context

A published vault note can contain an inline Obsidian wikilink to another note
that is not yet published:

```md
See [[Target note|this related note]].
```

The current exporter resolves links against the current `publish: true`
selection before translation:

- while the target is private, the public Russian body contains only the
  visible label;
- once the target gains `publish: true`, the same source becomes a Markdown
  link;
- that normalized-body change changes the referring note's translation source
  hash;
- its approved English review becomes stale even though no wording changed.

Approving one highly referenced target can therefore force preparation,
translation review, and approval for tens or hundreds of referring notes.

The current release boundary has a second problem: `build-from-review` accepts
both `translationStatus: generated` and `translationStatus: reviewed`.
Approval advances the durable `published/` baseline, but it is not presently a
release gate.

## Goal

Treat the clickability of an already-authored inline wikilink as a deterministic
release projection rather than an editorial change.

After this design is implemented:

1. Russian and English approved wording retain a stable semantic reference to
   the target independently of the target's publication state.
2. Approving a target automatically activates every approved inbound reference
   in both languages.
3. Referring notes are not rewritten, retranslated, rehashed, or reapproved.
4. Site materialization consumes only approved snapshots.
5. A current `publish: true` note with no approved snapshot blocks a new release.
6. A current `publish: true` note with an approved snapshot is built from that
   snapshot even when newer drafts exist.
7. Removing `publish: true` deactivates inbound links and removes the target
   without review.
8. Restoring `publish: true` reuses the last valid approved snapshot and
   reactivates inbound links without review.

## Scope

This design covers non-embedded inline Obsidian wikilinks in Markdown bodies,
including aliased labels and supported heading fragments.

It also defines:

- the approved-only release gate;
- a private stable-reference catalog;
- per-page bilingual reference maps;
- migration of existing approved snapshots;
- release provenance for the Astro build;
- diagnostics, recovery, and verification.

## Non-goals

This change will not:

- automatically approve textual, metadata, or structural edits;
- use title recognition or client-side autolinking;
- expose vault paths, private titles, catalog identifiers, or semantic `ref:`
  URIs in public output;
- make unresolved or ambiguous wikilinks automatically bind to future notes;
- change the existing rule that unpublished transclusions block export;
- make structured frontmatter `links: [public-id, ...]` use this body-reference
  representation;
- delete approved snapshots when a note is unpublished;
- deploy the site automatically.

## Terminology

### Candidate

The current proposed RU/EN/reference triple derived from the vault source.
Candidates may be absent, generated, reviewed, or stale. They are never release
input merely because they exist.

### Approved snapshot

The durable, approval-owned RU/EN/reference triple for one publication. Human
approval advances editorial content. Migration may advance a legacy snapshot
only through the migration review boundary defined below.

### Selected note

A current vault note whose parsed frontmatter contains exact
`publish: true`. Selection expresses release intent, not approval.

### Semantic reference

One authored inline wikilink occurrence whose visible RU and EN labels are
approved content while its site clickability is derived at release time.

### Target reference

An opaque, stable, exporter-local identity for a vault note. It is independent
of `publicId`, route, language, and publication state.

### Approved-target registry

A build-local mapping from selected approved page references to their approved
RU and EN routes. It is derived for each materialization and is not a mutable
source of truth.

### Release projection

The deterministic transformation that turns a semantic reference into either a
localized internal link or its approved plain-text label.

## Alternatives Considered

### Eager referrer cascade

When a target becomes approved, derive its reverse dependencies, regenerate
every referring RU/EN pair, and auto-approve link-only diffs.

This makes activation explicit in each referrer's files, but turns one target
approval into a cross-page write transaction. It requires exact diff
classification, rollback across many snapshots, and ongoing reverse-index
invalidation. Its reference-map and migration ideas are retained here without
the cascade.

### Re-resolve raw vault links at build time

Build approved wording while consulting the current raw vault source to recover
wikilink targets.

This avoids persistent semantic markers, but mixes unapproved current source
with approved release input. Referrer edits, renames, missing files, and RU/EN
translation alignment can change release behavior outside approval, so this
option is rejected.

### Late-bound semantic links

Approve visible bilingual wording and stable target identity together, then
derive only clickability and localized routes at release time.

This is the selected design. It keeps target approval as the activation
boundary, makes an approval O(1) in persisted writes, and uses per-page
reference maps plus one-time migration to retain the strongest safety
properties of the eager approach.

## Core Invariants

1. Target approval status and route are not translation inputs for a referring
   note.
2. Each semantic reference occurrence has one unique occurrence ID within its
   page.
3. Multiple occurrences may share the same target reference.
4. RU and EN contain exactly the same occurrence IDs in exactly the same order.
5. Occurrence-to-target binding is by occurrence ID, never by array position,
   label text, or target order.
6. An approved snapshot is complete only when RU, EN, and the reference map are
   all present, safe, internally consistent, and committed as one unit.
7. Generated or stale candidates never replace an approved release.
8. A selected note without a complete approved snapshot blocks new release
   materialization.
9. Pending changes do not block release when a complete approved snapshot
   exists.
10. Public output contains no semantic `ref:` URI or private reference
    provenance.

## Approved Artifact Model

Each page's approval-owned directory becomes:

```text
review/<collection>/<publicId>/published/
├── ru.md
├── en.md
└── references.json
```

`PublishedSnapshotStore` generalizes its exact-layout and atomic-directory
contract from a two-file pair to this three-file snapshot.

The Russian and English Markdown remain the human-readable approved documents.
The sidecar contains identity, ordering, provenance, and integrity information
that must not be duplicated as translatable prose.

### Semantic Markdown form

Private candidate and approved Markdown use a local inert destination:

```md
Прочитайте [Бережливый стартап](ref:ref-0007).
Read [The Lean Startup](ref:ref-0007).
```

Reviewers may edit visible labels. They must not edit, duplicate, remove, or
reorder occurrence IDs. The English translation contract requires the exact RU
occurrence sequence.

The semantic Markdown is private review data. Site materialization consumes it
but never copies `ref:` destinations verbatim.

### Reference map schema

The logical schema is:

```json
{
  "schemaVersion": 1,
  "pageRef": "vault-ref-a91f...",
  "sourcePath": "blog/Essay.md",
  "ruSha256": "...",
  "enSha256": "...",
  "order": ["ref-0007", "ref-0008", "ref-0009"],
  "references": {
    "ref-0007": {
      "targetRef": "vault-ref-b27c...",
      "authoredTarget": "bibliography/2025/The Lean Startup",
      "heading": null
    },
    "ref-0008": {
      "targetRef": "vault-ref-c31d...",
      "authoredTarget": "concepts/Feedback loop",
      "heading": "Limits"
    },
    "ref-0009": {
      "targetRef": "vault-ref-b27c...",
      "authoredTarget": "bibliography/2025/The Lean Startup",
      "heading": "Experiments"
    }
  }
}
```

Properties:

- `pageRef` is the stable vault-reference identity of the page itself.
- `sourcePath` binds the approved page to its normal current vault identity.
- `ruSha256` and `enSha256` bind the map to the exact approved bytes.
- `order` is the one authoritative RU/EN occurrence sequence.
- `references` is keyed by occurrence ID so JSON object ordering has no
  semantic meaning.
- `targetRef` may repeat, while the occurrence key may not.
- `authoredTarget` is private diagnostic and migration provenance.
- `heading` is the raw authored fragment and belongs to the occurrence because
  two links to the same target may use different fragments.
- Routes and target `publicId` values are absent because they are release-time
  facts.

This design preserves the exporter's current heading-fragment behavior: the
projector normalizes the approved raw fragment with the existing
`headingFragment` rule and appends that same fragment in both languages.
Validating that a translated target heading exposes a locale-specific anchor is
a separate concern; this change must not silently invent a bilingual heading
alignment.

## Ordering and Multiplicity Contract

For each language, the validator parses semantic links outside protected
Markdown contexts and obtains their occurrence IDs in document order.

Approval and build require:

```text
parsed RU order = parsed EN order = references.json order
```

They also require:

- every ordered ID appears exactly once in each language;
- every ordered ID has exactly one reference-map record;
- no reference-map record is omitted from `order`;
- no unknown `ref:` ID occurs in either document;
- occurrence IDs are not inferred from labels;
- duplicate targets and duplicate visible labels remain distinct occurrences;
- heading fragments remain bound to their exact occurrence IDs.

The projector uses occurrence-ID lookup for every replacement. It never zips
the RU and EN link arrays or pairs references by ordinal. Strict ordered
validation is an additional guard against accidental reassignment.

## Stable Vault Reference Catalog

The exporter owns a private, versioned catalog under the review workspace. The
catalog assigns opaque `vault-ref-*` identities to referenced notes, including
notes that are not selected for publication.

The catalog is used during source preparation and migration. It is never copied
to the Astro tree.

### Conservative identity resolution

The catalog reuses or reconciles identity in this order:

1. exact recorded current vault path;
2. unique existing stable note `id`, when present;
3. explicitly confirmed previous path or alias;
4. explicit operator reconciliation.

Content similarity, filenames, and titles may provide diagnostic evidence but
must not automatically merge identities.

An ambiguous rename, copied note, or replacement blocks preparation for the
affected page. It does not silently retarget approved content.

Unpublishing or deleting a target retains its catalog identity as a tombstone.
That permits safe restoration or explicit reconciliation later.

### Build dependency boundary

Normal builds match selected notes to approved snapshots by exact approved
`sourcePath`. A previously confirmed catalog reconciliation may provide a
renamed-path match.

If the catalog is unavailable, exact-path approved snapshots remain buildable.
A selected renamed note that cannot be matched safely is treated as lacking an
approved snapshot and blocks materialization.

Once selected approved triples are loaded, the build resolves links from the
triples' `pageRef` and `targetRef` values. It does not need private catalog
paths or titles for release projection.

## Derived Reverse Index

Inspection, approval reporting, and build derive a reverse index from approved
reference maps:

```text
targetRef -> [referring pageRef + occurrenceId, ...]
```

The reverse index is not persisted as a second authority. Derivation avoids
cross-page invalidation and makes the reference maps the only durable
occurrence truth.

The index supports:

- target-approval impact counts;
- per-page and per-language activation reports;
- unresolved-reference diagnostics;
- route-change impact reporting;
- unpublication and republish impact reporting;
- migration completeness checks.

## Preparation Flow

Preparing one selected note performs:

1. Stable source preflight.
2. Whole-vault wikilink resolution for non-embedded body links.
3. Catalog target-reference assignment or conservative reconciliation.
4. Semantic RU body construction with occurrence IDs in source order.
5. Reconciliation with the previous approved reference map to preserve
   occurrence IDs where the authored reference survives.
6. Allocation of new occurrence IDs only for new authored references.
7. Candidate `references.json` creation and RU hash binding.
8. English translation with an explicit requirement to preserve every
   occurrence ID and the exact RU order.
9. Candidate validation, including ordered RU/EN equality.
10. Atomic installation of the proposed RU/EN/reference triple.

Resolution has three explicit outcomes:

- exactly one existing vault note: allocate or reuse its target reference and
  create a semantic occurrence;
- no existing vault note: retain the approved visible label as plain text,
  record a non-blocking unresolved diagnostic, and create no target reference;
- more than one possible vault note: block preparation until the target is
  disambiguated.

An unresolved link cannot later activate merely because a matching note
appears. Once it resolves uniquely, adding its semantic occurrence is a normal
referrer candidate change and requires referrer approval. This keeps future
binding from changing approved meaning without a review boundary.

### Reference reconciliation across editorial revisions

Previous occurrence IDs are matched conservatively using target reference,
heading fragment, visible source label, document order, and surrounding source
context.

Repeated identical references are aligned in order. If insertions, deletions,
or moves make identity ambiguous, preparation may generate a candidate but must
mark the affected mapping for human review. It must not silently transfer an
old occurrence ID to a different authored reference.

Target approval status, target route, and target `publicId` do not participate
in the referring note's translation source hash. Authored target identity,
occurrence order, and visible source wording do participate.

## Approval Flow

`mark-reviewed` continues to be the only normal editorial approval boundary.

It:

1. Rebuilds and stabilizes the current candidate.
2. Validates the exact RU/EN/reference triple.
3. Verifies strict occurrence-set and order equality.
4. Marks the exact reviewed English content as reviewed without reserializing
   unrelated bytes.
5. Stages the complete approved triple.
6. Commits the triple atomically with source and candidate guards.
7. Updates the source workflow state only after the approved triple is durable.

Approval touches only the target page's artifacts. It never rewrites referring
approved snapshots.

After approval, the response derives the reverse impact and may report:

```text
Approved: concept-b
Inbound links activated: 107
Affected approved pages: 31
Pending-draft referrers: 4
```

Pending referrer drafts are informational. Their last approved snapshots remain
the release inputs.

## Approved-Only Release Materialization

`build-from-review` changes from rebuilding current Russian source plus fresh
English candidates to materializing a release from approved snapshots.

### Selection and approved-snapshot gate

For every current selected source path:

1. Locate its complete approved triple by exact source path or an explicitly
   confirmed catalog reconciliation.
2. If no approved triple exists, block the materialization and name the note.
3. If a triple exists, ignore newer current source, RU candidate, EN candidate,
   and candidate workflow status.
4. Validate the approved triple's safe layout, hashes, identities, and ordered
   semantic references.

Current source still owns whether the note is selected through `publish: true`.
Approved snapshot metadata owns released `publicId`, collection, content type,
route, and wording.

Duplicate approved `pageRef`, `publicId`, target path, or route values block the
release.

### Approved-target registry

The materializer derives, from selected approved triples:

```text
pageRef -> approved publicId + RU route + EN route
```

A target is linkable if and only if:

- its current source is selected with `publish: true`;
- it has a complete valid approved triple;
- its approved identity is unique in the release.

The registry is immutable for one materialization attempt.

### Per-language projection

For each semantic occurrence:

- target reference present in the registry: emit a standard Markdown link to
  that target's approved route in the current language, plus the occurrence's
  normalized approved raw heading fragment when present;
- target reference absent from the registry: emit only the approved visible
  label.

Projection preserves all non-reference bytes. It emits an ordered activation
audit used by the release report and validation.

Before site staging, the materializer rejects:

- any remaining `ref:` URI;
- any vault path or private catalog identity;
- any RU internal route in EN output or EN internal route in RU output;
- an activation audit whose occurrence sequence differs from the approved map.

### Pending drafts

Pending source or translation changes do not block release when a valid
approved snapshot exists. They appear in the report as ignored drafts.

This produces two independent truths:

- approved snapshot: what may be released;
- current candidate: what still needs editorial work.

## Unpublish and Republish

Removing `publish: true` is the explicit unpublish action and requires no
translation review.

On the next materialization:

- the target is omitted from the generated site;
- its `pageRef` is absent from the approved-target registry;
- every inbound semantic occurrence renders as its approved plain-text label;
- its approved snapshot and catalog identity remain intact.

Restoring `publish: true` automatically reuses the last complete approved
snapshot:

- the target returns with its last approved wording and route;
- inbound references reactivate in both languages;
- newer drafts remain ignored until separately approved.

If no approved snapshot exists, restoring or adding `publish: true` blocks
materialization until first approval.

## Astro Build Provenance Gate

The exporter writes a release-provenance manifest into the staged managed Astro
tree. It records:

- schema version;
- selected approved page identities;
- approved triple hashes;
- projection hashes;
- generated managed-tree digest;
- link activation and deactivation counts.

The managed-tree digest is computed over a canonical ordered file list that
excludes the provenance manifest itself. The manifest is then written last and
may have its own ordinary file hash; no digest field is recursively included in
the digest it declares.

The Astro content gate validates that managed generated content matches this
manifest. Manual or stale modifications to managed generated files block the
build.

`npm run build` may rebuild the last provenance-valid materialized release. It
does not claim to incorporate current vault changes. Creating a new release
from current selection must pass through approved-only materialization first.

This distinction permits safe rebuilds of a previously approved release while
preventing generated or manually altered content from entering a new release.

## Migration

Existing approved snapshots contain two legacy reference forms:

- direct public Markdown links for targets that were public at approval time;
- plain translated labels whose private target identity has been discarded.

Migration pays the RU/EN alignment cost once and creates the durable semantic
substrate used by all future builds.

### Read-only inventory

Migration first compares:

- raw current vault wikilinks;
- approved legacy RU;
- approved legacy EN;
- existing retained public routes;
- current public-target identities.

Each occurrence is classified as:

- `exact`: deterministic RU/EN/target alignment;
- `confirmed`: alignment accepted through aggregate review;
- `unresolved-target`: the raw wikilink target does not resolve;
- `ambiguous-translation`: more than one EN phrase could be the occurrence;
- `order-mismatch`: legacy RU and EN reference orders differ;
- `unsafe-input`: the legacy approved snapshot is partial, corrupt, linked,
  unreadable, or otherwise unsafe.

The dispositions are:

- `exact` enters the semantic representation automatically;
- `confirmed` enters after aggregate human confirmation;
- `unresolved-target` remains approved plain text and is omitted from the
  reference map, preserving existing public output without future auto-binding;
- `ambiguous-translation` and `order-mismatch` require aggregate human review;
- `unsafe-input` blocks migration of the affected page until repaired or
  explicitly recovered.

Only `exact` and `confirmed` occurrences may enter the semantic reference map.

### Strict-order migration rule

Automatic migration requires the same inferred RU and EN occurrence order.

Any mismatch enters aggregate review. Because the new schema requires strict
order equality, confirmation may require editing and explicitly approving the
English migration candidate so that it matches RU reference order.

For automatically migrated pages, projected public output must be byte- or
structure-equivalent to the existing public output.

For human-corrected order mismatches, the migration review is an explicit
approval boundary for the documented output difference. Such pages are not
misreported as parity-preserving.

### Aggregate ambiguity review

The review presents, per occurrence:

- raw authored wikilink and source context;
- approved RU context;
- proposed EN occurrence and context;
- occurrence ordinal;
- target note identity;
- heading fragment;
- reason automatic mapping was rejected.

The user confirms or corrects mappings in one aggregate migration review rather
than approving each page independently.

### Global cutover

Legacy and semantic release modes may not be mixed.

The migration command:

1. Acquires a global migration/build lock.
2. Stages the catalog and every candidate semantic approved triple.
3. Validates exact IDs, hashes, safe layouts, and strict RU/EN ordering.
4. Projects a full staged release against the current approved-target set.
5. Runs output parity checks for exact migrations and records explicitly
   approved differences for corrected migrations.
6. Runs the Astro content gate against staged output.
7. Installs each page triple through the atomic snapshot mechanism.
8. Writes the semantic-schema activation marker last.
9. Releases the lock only after the full set is coherent.

A durable journal records staged, installed, confirmed, and pending pages. An
interrupted migration keeps builds blocked until an explicit roll-forward or
rollback completes. Legacy approved bytes remain recoverable.

After cutover:

- two-file approved snapshots are rejected;
- every approval produces a complete semantic triple;
- all builds consume approved semantic snapshots;
- target approval causes no referrer writes.

## State and Diagnostics

Inspection derives independent dimensions rather than overloading `stale`:

- candidate: absent, generated, reviewed, or stale;
- approved snapshot: absent, valid, or invalid;
- semantic references: valid, migration-required, or invalid;
- release: releasable or blocked.

These are exporter response and report fields. They do not require additional
workflow frontmatter.

Blocking diagnostics include:

- `missing-approved-snapshot`;
- `approved-snapshot-incomplete`;
- `reference-map-hash-mismatch`;
- `reference-order-mismatch`;
- `duplicate-reference-occurrence`;
- `missing-reference-occurrence`;
- `unknown-reference-occurrence`;
- `catalog-reconciliation-required`;
- `duplicate-approved-identity`;
- `private-reference-leak`;
- `migration-incomplete`;
- `concurrent-approved-snapshot-change`;
- `release-provenance-mismatch`.

Every diagnostic identifies the page and, where applicable, occurrence ID and
target reference. User-facing messages may include private authored targets;
public reports and generated site files may not.

## Concurrency and Recovery

### Normal approval

Normal target approval commits one complete page triple. It never opens a
cross-page transaction.

The reverse impact report is derived after the target triple is durable. Failure
to compute a nonessential impact count does not undo valid approval, but the
next build independently revalidates all maps and projection.

### Build

The build:

1. snapshots all selected approved triple bytes;
2. derives the registry and projections from that immutable in-memory set;
3. stages and gates the managed Astro tree;
4. rechecks selected snapshot guards before committing generated output.

Concurrent approval, unpublication, republish, or source selection changes
cause a safe retry rather than a mixed release.

### Catalog

Catalog writes are atomic and guarded. Catalog failure blocks new preparation
or reconciliation. It does not mutate approved snapshots.

### Migration

Migration owns the global journal and lock described above. Recovery never
silently guesses whether a partially installed semantic snapshot is active.

## Security and Privacy

The following artifacts are private and exporter-local:

- vault-reference catalog;
- `references.json`;
- raw `authoredTarget` provenance;
- semantic `ref:` destinations;
- migration ambiguity records.

The public-output gate rejects any of their identifiers or paths.

Unapproved targets contribute no body, metadata, route, title, or path to public
output. Only the already approved visible label in the referring page remains.

## Performance

One materialization loads each approved triple once and derives one in-memory
reverse index:

```text
O(approved pages + semantic reference occurrences)
```

Approving a target does not start translation jobs proportional to its inbound
degree. One target with one hundred inbound references changes the build-local
registry once; projection processes the same references during the normal page
pass.

No persisted reverse index or referrer cache requires invalidation.

## Testing

Development follows a red-green-refactor cycle.

### Reference parsing and validation

Tests cover:

1. One occurrence in both languages.
2. Multiple distinct targets.
3. Multiple occurrences of the same target.
4. Repeated identical labels.
5. Different heading fragments for the same target.
6. Duplicate occurrence IDs.
7. Missing and unknown occurrence IDs.
8. RU/EN order swaps.
9. Sidecar order mismatches.
10. Wikilinks and semantic-looking text inside protected Markdown contexts.
11. External links and assets remaining unchanged.

### Preparation and approval

Tests cover:

1. Private and public targets produce the same semantic RU translation input.
2. Target approval status and route do not change a referrer's translation
   source hash.
3. English generation preserves the exact occurrence order.
4. Manual occurrence reordering is rejected.
5. Repeated references preserve distinct IDs across a normal editorial edit.
6. Ambiguous reconciliation blocks silent ID reassignment.
7. An unresolved target remains plain text and does not bind when a future note
   appears.
8. An ambiguous source target blocks preparation.
9. Approval atomically commits RU, EN, and the map.
10. Approval modifies no referrer snapshot.

### Approved-only release

Tests cover:

1. A selected note without an approved snapshot blocks materialization.
2. A selected note with an approved snapshot and pending edits builds the
   approved version.
3. Generated English candidates never enter release output.
4. Approving a target activates inbound RU and EN links.
5. Target approval leaves referrer approved hashes unchanged.
6. One, twenty, and one hundred inbound references activate deterministically.
7. Unpublishing emits labels and removes the target without review.
8. Republishing restores the approved target and links without review.
9. Approved route changes update inbound links without referrer approval.
10. Concurrent snapshot changes cause retry rather than mixed output.
11. No private identifier or semantic URI survives public projection.
12. The Astro provenance gate rejects modified managed content.
13. The managed-tree digest excludes the manifest and verifies deterministically.

### Migration

Tests cover:

1. Legacy direct public links.
2. Legacy stripped private links.
3. Duplicate targets and duplicate labels.
4. Strict-order mismatch classification.
5. Aggregate confirmation and corrected mappings.
6. Exact-migration output parity.
7. Explicitly approved non-parity for corrected order.
8. Failure and recovery at each global cutover boundary.
9. Activation marker written only after every semantic triple is valid.

Before mutation of real approved data, run a read-only migration inventory
against the real vault and review workspace. Then run a full staged projection,
Astro content check, and output-parity report.

## Acceptance Scenario

The decisive end-to-end scenario is:

1. Approve A while B is private.
2. Confirm A's approved RU and EN contain the same semantic occurrence ID in
   the same ordered position.
3. Materialize the site and confirm both languages show the approved label as
   text.
4. Record hashes of A's approved RU, EN, and reference map.
5. Add `publish: true` to B and confirm materialization blocks because B has no
   approved snapshot.
6. Prepare, review, and approve B.
7. Materialize the site again.
8. Confirm A's three approved hashes are unchanged.
9. Confirm A's RU and EN outputs now link to B's approved language routes.
10. Confirm no referrer translation job, review, approval, or snapshot write
    occurred.
11. Remove `publish: true` from B and confirm B disappears while A's references
    become text.
12. Restore `publish: true` and confirm B and A's links return from the existing
    approved snapshots.

## Acceptance Criteria

- Inline wikilink clickability is derived exclusively from the selected
  approved-target registry.
- Target approval or unpublication never changes a referrer's approved bytes.
- RU and EN semantic occurrence sequences are exactly equal.
- Multiple references to one target cannot be confused or reordered.
- A selected note with no approved snapshot blocks new materialization.
- Pending drafts never replace valid approved snapshots.
- Generated or unreviewed candidates never enter a new release.
- Unpublish and republish require no translation review.
- Existing approved content is migrated only through deterministic mapping or
  aggregate human confirmation.
- New release materialization and Astro build consume provenance-verified
  approved output.
- Public output contains no vault path, private reference identity, catalog
  data, or semantic `ref:` URI.
