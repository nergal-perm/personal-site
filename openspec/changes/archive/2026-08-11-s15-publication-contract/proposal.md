## Why

`EssayAdmission` (`publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java`) hardcodes the only content kind's admission rules — `publish` must be Boolean `true`, `publicCollection` must equal `"blog"`, `publicContentType` must equal `"essay"`, `publicId` must match a lowercase route-slug pattern, and `id`/`title`/`description` must be non-blank — as Java conditionals with no externally readable description. ADM-06 (`openspec/specs/publication-admission/spec.md`) requires the exporter to expose that shape as a deterministic, machine-readable publication contract so an authoring tool (the Obsidian plugin, an editor) can validate a draft before ever invoking the exporter. Today no such surface exists: `EssayAdmission`'s rules are legible only by reading Java source, and nothing proves the rules a human reads out of a published contract are the same rules `EssayAdmission.admit(...)` actually enforces at runtime — the two could silently drift the next time either changes.

This is `openspec/implementation-plan.md`'s S15 slice, governed by Haft problem `prob-20260811-9d6b934d` under the slice-sequence decision `dec-20260803-76166a5e`. It is the first slice after Milestone C's content-safety trio (S12 `dec-20260810-a568f461`, S13 `dec-20260810-cc3f02ed`, S14 `dec-20260811-57d50375`) and comes immediately before S16's whole-vault discovery — ADM-06 is scoped to the kinds implemented so far (essay only; S17a–f add the rest later), not to discovering which notes exist.

`exporter-java`'s `PublicationValidator`/`PublicationKind`/`ManifestBuilder` (`astroexport/validation/`, `astroexport/model/`) were read as behavioural evidence only (per this project's standing rule that the legacy implementation is a compatibility oracle, never a code donor) to see what shape a multi-kind contract has taken before: an enum of kinds, per-kind allowed collection/content-type pairs, and per-kind required-field lists. That evidence is informative, not binding — this slice's exact contract shape, serialization normalization, and fixture-table design are settled in the functional and technical collaborative-design passes, not decided here.

## What Changes

- Add a `write-publication-contract` CLI command that emits a deterministic, machine-readable publication contract for every content kind the exporter currently implements (today: `blog/essay` only), describing required fields, allowed values, and structured-body requirements.
- Requesting the contract twice for the same exporter edition, with no contract changes, yields byte-equivalent output after the contract's declared serialization normalization (stable key order, no timestamps or environment-dependent values).
- Introduce one shared fixture table (valid and invalid `blog/essay` frontmatter/body fixtures) that drives both `EssayAdmission`'s existing runtime validation tests and a new contract-conformance harness, so a fixture the published contract would accept but runtime validation rejects — or the reverse — fails the exporter edition's acceptance suite.
- The contract is a read-only projection of `EssayAdmission`'s existing rules; it does not introduce a new admission code path, and `EssayAdmission` itself is not restructured to fit the contract's external shape (no internal OOP design is generated from the external contract, per the plan's explicit exclusion).
- Exactly how the contract is modeled (data shape, where kind/field/allowed-value metadata is sourced from `EssayAdmission` without duplicating its rule logic, and how the fixture table is shared between the two test suites) is resolved in the functional and technical collaborative-design passes, not decided here.

**Explicitly excluded from this slice** (per the S15 boundary in the implementation plan): discovering notes (whole-vault discovery is S16), adding any publication kind beyond essay (S17a–f), and any change to `prepare`/`inspect-publication`/`mark-reviewed`/`build-from-review`/`install-to-site`/`refresh-publication-queue` behaviour.

## Capabilities

### New Capabilities

None — this slice realizes a requirement (ADM-06) already fully specified in the baseline; it does not introduce a new capability area.

### Modified Capabilities

- `publication-admission`: ADM-06 gains implementation. Its two existing baselined scenarios ("Contract is requested twice", "Validator and published contract disagree") are the acceptance target; whether either needs sharper scenario text for this slice's single-kind boundary is a question for the functional collaborative-design pass, not decided here.

## Impact

- **Modified:** `publication-exporter/` — a new CLI command (`write-publication-contract`) plus its supporting application-layer collaborator(s) (name and shape settled in `design.md`), reading `EssayAdmission`'s rules without changing its behaviour; a new shared fixture table consumed by both `EssayAdmissionTest` and the new contract-conformance test.
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/`, `bridge-contract/schema-v2.json`, every other CLI command's behaviour, `site/`, and every content kind beyond essay (essay remains the only kind through S17).
- **Governance:** implements Haft problem `prob-20260811-9d6b934d`, under decision `dec-20260803-76166a5e` (slice sequence).
