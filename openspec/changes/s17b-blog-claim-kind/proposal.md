## Why

`EssayPublicationKind` and `NotePublicationKind` (publication-exporter's admission package) are today structurally identical — same field rules (`publicId`/`id`/`title`/`description`), no structured body beyond raw Markdown, and `AdmittedPublication` carries only `(kind, identity, sourceId, title, description)`. The Astro site already defines a third real `blog` content type, `blogClaim` (`site/src/content.config.ts`), sharing `blogShared` with essay/note but additionally requiring a non-optional `statement` field plus five relationship arrays (`supports`/`opposes`/`assumes`/`refines`/`contradicts`, each `{label, target?}`) and a `sources` array — a genuinely different, richer required-field shape the exporter cannot yet admit, prepare, approve, or release. `openspec/implementation-plan.md`'s S17b entry sequences `blog/claim` as the second rung of the content-kind ladder (Milestone C) and is explicit that this is the first fixture that may force the essay-shaped localized-content/translation/snapshot/hash/diff carriers to become whole publication values — evidence, not a foregone generalization. Haft problem `prob-20260811-f60fe262` frames this; its own prerequisite, the cross-kind `(collection, publicId)` filesystem collision (`prob-20260811-0b8b9f2d`), is now closed (`dec-20260811-02b96a37`).

## What Changes

- Add a `ClaimPublicationKind` implementation of the existing `PublicationKind` role for `blog/claim`, admitting the shared identity fields plus a required non-blank `statement`.
- Extend whichever carrier the claim fixture proves necessary to hold `statement` and the relationship/source arrays end-to-end (admission → translation → candidate/approved snapshot → release → site frontmatter projection). If the fixture shows the current `AdmittedPublication` (title/description-only) shape is insufficient, replace it with a whole-publication-value carrier as one coherent, behaviour-preserving refactor — not a parallel claim-only path alongside the existing essay/note path.
- Project `statement` and the relationship/source arrays into the site-managed Markdown frontmatter for `blog/claim`, matching `blogClaim`'s Zod schema in `site/src/content.config.ts`.
- Register `ClaimPublicationKind` in `PublicationKinds.installed()` so `write-publication-contract` emits a complete `blog/claim` entry alongside the unchanged `blog/essay` and `blog/note` entries.
- Add one acceptance fixture proving a `blog/claim` note (non-blank `statement`, at least one populated relationship array) completes prepare → approve → release through the same handlers already proven for `blog/essay` and `blog/note`.
- Reconfirm the cross-kind `(collection, publicId)` collision guard (`CandidateWorkspaceKindCollisionException`, `ManagedSiteKindCollisionException`, and the pre-existing approved/release invariants) holds with three kinds sharing the `blog` collection.
- No new CLI command, no new production boundary adapter, no bridge schema-v2 change.

## Capabilities

### New Capabilities
(none — `blog/claim` extends existing admission/content-model capabilities; it does not introduce a new bounded capability)

### Modified Capabilities
- `publication-admission`: ADM-03 gains a `blog/claim` fixture demonstrating a third distinct kind resolved by the same `(collection, publicContentType)` lookup; ADM-04 gains a `blog/claim` scenario whose kind-specific contract requires `statement` (and rejects a note missing it); ADM-06 gains a `blog/claim` entry in the exported contract (its required fields including `statement`), alongside the unchanged `blog/essay` and `blog/note` entries.
- `public-content-model`: PCM-02 gains a `blog/claim` manifest-projection scenario (kind-specific allowed fields, including `statement` and the relationship/source arrays); PCM-06 gains an English-candidate structural-alignment scenario for `blog/claim`'s translated `statement` and relationship labels.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `ClaimPublicationKind`; possible changes to `AdmittedPublication` (or its successor) if the whole-publication-value refactor is triggered.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `PublicationKinds.installed()` gains the claim registration; claim's `KindContract` describes `statement` as required.
- Prepare/translation/candidate/site frontmatter projection code for the claim-specific fields — exact files depend on whether the whole-publication-value refactor is triggered (see design.md).
- New `blog/claim` acceptance fixture(s) under `publication-exporter/src/test/`.
- No change to the existing `blog/essay` and `blog/note` acceptance suites' observable behaviour; no change to `bridge-contract/`.
