## Why

The exporter's admission and contract code only knows one publication kind: `EssayAdmission` hardcodes `blog`/`essay` as class constants, `NoteIntake.admit()` instantiates `new EssayAdmission()` directly, and `PublicationContractWriter` wraps a single hardcoded `EssayPublicationContract.kind()`. The Astro site already defines a second real `blog` content type — `blog/note` (`site/src/content.config.ts`'s `blogNote`: shared public/translation/editorial fields, `contentType: 'note'`, optional `observation`/`model`/`boundary`/`experiment`, no required structured body) — that the exporter cannot admit, prepare, approve, or release. `openspec/implementation-plan.md`'s S17a entry sequences `blog/note` as the first rung of the content-kind ladder (Milestone C) and requires the shared `PublicationKind`/`PublicationKinds` seam to be extracted inside this slice's red-green-refactor cycle, once a second real kind's fixture forces the seam — not before.

## What Changes

- Add a `PublicationKind` role (owns its `(collection, contentType)` key, admission rules, published `KindContract`) and a `PublicationKinds` collection (deterministic lookup, unsupported-kind diagnostics, sorted contract enumeration).
- Migrate the existing essay policy from `EssayAdmission`/`EssayPublicationContract` into an `EssayPublicationKind` implementation of that role, behaviour-preserving.
- Add a `NotePublicationKind` implementation for `blog/note`: identity fields identical to essay's (`publicCollection=blog`, `publicContentType=note`, `publicId` slug, `id`, `title`, `description`), no required structured body — matching `blogNote`'s all-optional `observation`/`model`/`boundary`/`experiment` fields.
- Change `NoteIntake.admit()` to return an `AdmittedPublication` (kind-neutral) instead of the concrete `EssayAdmission.Result`, resolving the note's kind through the shared `PublicationKinds` instance.
- Change `PublicationContractWriter` to enumerate `PublicationKinds` instead of a hardcoded single-kind list, so `write-publication-contract` emits both `blog/essay` and `blog/note` entries.
- Add one acceptance fixture proving a `blog/note` note completes prepare → approve → release through the same handlers already proven for `blog/essay`.
- No new CLI command, no new production boundary adapter, no bridge schema-v2 change.

## Capabilities

### New Capabilities
(none — `blog/note` extends existing admission/content-model capabilities; it does not introduce a new bounded capability)

### Modified Capabilities
- `publication-admission`: ADM-03 and ADM-04 gain `blog/note` as a second supported kind with its own field contract, proven by fixture and no longer only illustrated by essay/book/album/concept/editorial-page examples.
- `public-content-model`: PCM-02 gains a `blog/note` manifest-projection scenario (kind-specific allowed fields); PCM-06 gains an English-candidate structural-alignment scenario for `blog/note`.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `PublicationKind` interface, new `PublicationKinds` registry, `EssayAdmission` migrates into `EssayPublicationKind`, new `NotePublicationKind`.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`: returns `AdmittedPublication`; takes a `PublicationKinds` collaborator.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `EssayPublicationContract` folds into `EssayPublicationKind`; `PublicationContractWriter` composes `PublicationKinds`.
- New `blog/note` acceptance fixture(s) under `publication-exporter/src/test/`.
- No change to `PrepareHandler`, approval, release, or site CLI wiring beyond consuming `AdmittedPublication`; no change to `bridge-contract/`; the existing `blog/essay` acceptance suite must stay green unchanged.
