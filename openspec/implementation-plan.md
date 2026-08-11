# Greenfield exporter slice-based implementation plan

## Status and scope

This is a proposed high-level sequence for implementing the 47 requirements in `openspec/specs/`. It is not an implementation decision or authorization to change production code. The governing Haft problem is `prob-20260803-fe9b3011`.

The plan is technology-neutral and refers to the new sibling project as `<new-exporter>`. The old `exporter-java` implementation remains an evidence source and compatibility oracle, not a design template or code donor.

## What counts as a slice

Every implementation slice must satisfy all of these conditions:

1. It begins with one failing system-boundary acceptance test written in Given-When-Then terms.
2. It ends with exactly one coherent result visible through a CLI response, plugin-consumable response, candidate/approved artifact, release artifact, or site build.
3. It normally targets one to three new OpenSpec scenarios. More requires a written reason and should trigger an attempted split.
4. It introduces at most one new production boundary adapter. Pure in-process behaviour uses no port merely for architectural symmetry.
5. Its in-process acceptance subset remains below one second.
6. It contains no foundation-only, abstraction-only, or refactoring-only milestone. Necessary refactoring happens inside the red-green-refactor cycle of a behavioural slice.
7. It ends as one independently reviewable commit with the whole prior acceptance suite green.

“Minimal” therefore means fewer newly targeted scenarios, fewer new production adapters, and zero requirements pulled in only for speculative reuse. It does not mean fewer assertions, weaker safety, or incomplete observable behaviour.

## Outside-in implementation discipline

For each slice:

1. Add a failing acceptance test at the executable boundary, wiring the system with in-memory vault, review-workspace, translation-worker, and site adapters as needed.
2. Implement the smallest behaviour inline in application/domain code.
3. Refactor repeated stateful behaviour into an in-memory adapter only after the acceptance test passes.
4. Extract a port from the proven in-memory API only when the dependency performs I/O or runs out of process.
5. Add the real adapter last and run the same behavioural contract against the fake and real adapter.
6. Add a component or unit test only for genuinely combinatorial parsing, diffing, hashing, or recovery logic that is unclear at acceptance-test scope.

Do not create eight modules to mirror the eight specifications. Begin with one small application hexagon plus CLI and test composition roots. Split a Bounded Context only when acceptance-test setup or change coupling provides concrete evidence that the boundary is needed.

## Decision gates before implementation

These are prerequisites, not implementation slices, because a decision alone adds no exporter behaviour.

| Gate | Needed before | Decision required |
| --- | --- | --- |
| G1 Project identity | S01 | Runtime/language, sibling directory name, build tool, and executable name. |
| G2 Bridge carrier | S01 | Single source-of-truth representation for bridge schema v2 and how exporter/plugin conformance tests consume it. |
| G3 Translation process | S03 | Production worker protocol, cancellation, timeout, and result carrier. Tests remain worker-neutral. |
| G4 Compatibility depth | S03 | Semantic/site acceptance versus byte-for-byte compatibility for legacy YAML and Markdown quirks. |
| G5 Distribution | First real-plugin trial after S07 | Supported operating systems and packaged/JVM/native delivery. |
| G6 Cutover | S21 | Whether legacy pairs must be migrated or the new exporter starts only from already-current triples. |
| G7 Editorial grammar | S17f | Freeze current editorial grammar as one edition or version it independently. |

No implementation slice should quietly decide one of these through incidental code.

## Slice sequence

### S01 — Plugin-readable blocked inspection

**Visible result:** invoking `inspect-publication --json` for an unsafe or absent note produces exactly one plugin-accepted schema-v2 blocked response with a non-zero exit code.

**Requirements introduced:** BRG-01 (inspect only), BRG-02, BRG-03.

**Acceptance boundary:** the real CLI adapter calls the smallest application entry point; the plugin contract harness consumes its JSON. Path input is treated as data, not shell syntax.

**Explicitly excluded:** Markdown parsing, valid-note success, review workspace, workflow states beyond `metadata_blocked`, and every mutating command.

### S02 — Inspect one valid plain essay

**Visible result:** inspection of one valid `blog/essay` reports its publication identity and independent absent candidate/approved/reference/release states; malformed identity or missing source ID reports `metadata_blocked`.

**Requirements introduced:** ADM-02, ADM-03 (essay only), ADM-04 (essay only), SEM-01 (current source only), RVA-01 (absent state), BRG-04.

**Acceptance boundary:** one in-memory vault note and the CLI response. Add real vault-reading/path confinement behind the behaviour-proven API.

**Explicitly excluded:** whole-vault discovery, other kinds, links, assets, candidates, translation, and review files.

### S03 — Prepare the first essay candidate

**Visible result:** `prepare` for the S02 essay creates exactly one coherent first-publication candidate triple—RU, worker-produced EN, and an empty valid `references.json`—and returns schema-v2 `ready_for_review` without touching approved or site state.

**Requirements introduced:** ADM-05 (bounded request), PCM-01 (single entry), PCM-02 (essay), TRP-01, SEM-03 (empty map), BRG-01 (prepare).

**Acceptance boundary:** in-memory vault, translation worker, and candidate workspace first; then one real worker adapter and one real candidate-workspace adapter, each verified against its fake.

**Explicitly excluded:** an existing candidate, approved baseline, incremental diff, links, assets, source workflow edits, and concurrent jobs. Those conditions fail closed as unsupported state.

### S04 — Inspect and open first-publication review

**Visible result:** `inspect-publication` returns an exact first-publication review plan that the existing plugin can use to open RU and EN candidates in separate editor windows.

**Requirements introduced:** RVA-01 (complete candidate), RVA-02 (absent baseline), BRG-04 (candidate ready), BRG-07.

**Acceptance boundary:** candidate data is supplied by the in-memory review adapter; a bridge contract test proves the response is accepted by the plugin.

**Explicitly excluded:** approved-to-proposed diffs, approval, candidate replacement, and editor implementation details already owned by the plugin.

### S05 — Approve the first candidate

**Visible result:** explicit `mark-reviewed` installs the exact candidate as the first durable approved triple and returns success only after it is readable as a coherent snapshot.

**Requirements introduced:** RVA-03, RVA-04 (exact first candidate), RVA-05 (create-only atomic install), BRG-01 (mark-reviewed), SEM-03 (approved empty map).

**Acceptance boundary:** an in-memory approved-snapshot store proves authority and exactness first; the real create-only store runs the same contract.

**Explicitly excluded:** replacing an existing approved snapshot, crash recovery after replacement starts, and release generation. A second approval fails closed until S09.

### S06 — Materialize one approved essay

**Visible result:** `build-from-review` writes one RU and one EN essay plus deterministic minimum release provenance into a new empty output root, ignoring any candidate.

**Requirements introduced:** REL-01, REL-02 (no semantic occurrences), REL-03 (snapshot/output hashes), PCM-01 and PCM-02 at release boundary.

**Acceptance boundary:** in-memory approved store and release output first, followed by a real new-directory output adapter contract.

**Explicitly excluded:** replacing live site trees, assets, links, multiple publications, and recovery from a prior generation.

### S07 — Install and build the first managed site generation

**Visible result:** the S06 generation is installed into previously absent managed site roots, passes the existing site content gate, and completes an Astro build without changing code-owned site files.

**Requirements introduced:** REL-04 (stable first-generation inputs), REL-05 (empty-destination install), REL-06.

**Acceptance boundary:** the fast acceptance test uses an in-memory managed-tree adapter; the real filesystem contract and one slow Astro smoke test verify integration.

**Explicitly excluded:** replacing an existing generation and interrupted replacement recovery.

**Milestone A:** one plain `blog/essay` can now travel through plugin-compatible inspection, preparation, review, explicit approval, release materialization, and site build. Only after this milestone should breadth be added.

### S08 — Reprepare a changed approved essay

**Visible result:** preparing a changed approved essay produces the complete normalized Russian diff and a new validated English candidate; stale or failed worker output preserves the previous valid English candidate.

**Requirements introduced:** TRP-02, TRP-03, TRP-04, PCM-06, RVA-02 (approved-to-proposed diff).

**Acceptance boundary:** in-memory job and candidate adapters simulate success, failure, stale result, and competing completion without sleeps or processes; the real worker adapter receives a focused contract.

**Explicitly excluded:** approving the replacement and updating the live release.

### S09 — Replace an approved snapshot safely

**Visible result:** approving the S08 candidate replaces the prior approved triple atomically; stale source/candidate evidence blocks, and an injected interruption recovers to exactly the old or new triple.

**Requirements introduced:** RVA-04 (replacement), RVA-05 (replacement/recovery), RVA-06.

**Acceptance boundary:** failure injection is a behaviour of the in-memory approved store, not a mock interaction; the real adapter must pass the same recovery contract.

**Explicitly excluded:** release-tree replacement and queue refresh.

### S10 — Replace a managed release safely

**Visible result:** materializing the newly approved snapshot replaces the prior managed generation; input drift, output tampering, or injected interruption leaves or recovers one complete verified generation.

**Requirements introduced:** REL-03 (tamper detection), REL-04 (concurrent input guard), REL-05 (replacement/recovery).

**Acceptance boundary:** exercise two release generations through the in-memory adapter, then run the shared contract against the filesystem adapter.

**Explicitly excluded:** semantic target activation, additional content kinds, and migration.

### S11 — Truthful workflow state and queue refresh

**Visible result:** `refresh-publication-queue` reports the six-state summary and updates only stale exporter-owned workflow scalars while leaving an actively translating note untouched.

**Requirements introduced:** TRP-06, BRG-01 (refresh), BRG-05, BRG-06.

**Acceptance boundary:** one small in-memory vault with notes representing decisive, uncertain, and translating cases; the real frontmatter editor receives a byte/permission-preservation contract.

**Explicitly excluded:** unrelated frontmatter normalization and whole-file YAML rewriting.

### S12 — Protected Markdown and Obsidian comments

**Visible result:** preparing an essay removes Obsidian comments while preserving link-like syntax inside code and other protected regions byte-for-byte.

**Requirements introduced:** PCM-04.

**Acceptance boundary:** one acceptance fixture through `prepare`; add narrow unit tests only if the scanner has genuinely combinatorial protected-region cases.

**Explicitly excluded:** resolving actual links, transclusions, and assets.

### S13 — Public/private links and transclusion safety

**Visible result:** preparing an essay converts one unambiguous public link to a route, renders one private/unresolved link as a safe label, and blocks one private transclusion without leaking vault topology.

**Requirements introduced:** PCM-03.

**Acceptance boundary:** a three-note in-memory vault and candidate result. Link resolution remains in-process until real vault lookup behaviour has emerged.

**Explicitly excluded:** stable semantic occurrence IDs and late-bound target activation.

### S14 — Content-addressed assets

**Visible result:** preparing an essay with one image emits one content-addressed asset and rewrites the candidate reference; ambiguous or escaping lookup blocks before candidate replacement.

**Requirements introduced:** PCM-05.

**Acceptance boundary:** in-memory asset bytes first; the real vault/file adapter passes exact-path, ambiguous-basename, deduplication, and escape contracts.

**Explicitly excluded:** asset variants, optimization, remote assets, and unused media types beyond the existing contract.

### S15 — Machine-readable publication contract

**Visible result:** `write-publication-contract` emits a deterministic contract for every content kind implemented so far, and the same fixtures drive runtime validation and contract conformance.

**Requirements introduced:** ADM-06.

**Acceptance boundary:** one command response and one shared fixture table. Do not generate internal OOP design from the external contract.

**Explicitly excluded:** discovering notes or adding a new publication kind in this slice.

### S16 — Whole-vault discovery and aggregate admission

**Visible result:** a read-only whole-vault command lists every Boolean-selected note exactly once, excludes lookalikes, and reports all invalid selected notes without silently publishing a partial manifest.

**Requirements introduced:** ADM-01 and ADM-05 (aggregate path).

**Acceptance boundary:** an in-memory vault with selected, lookalike, ignored-path, valid, and invalid fixtures; the real discovery adapter receives an ordering and ignored-path contract.

**Explicitly excluded:** queue-state mutation and release of a partially valid vault.

### S17a–S17f — Content-kind ladder

Each entry below is a separate slice and separate commit. Its visible result is that one fixture of the new kind can complete the already-working prepare → approve → release path, and `write-publication-contract` includes exactly that kind's rules.

**Extension direction:** the first essay path deliberately proved one concrete shape. It is not yet an extensible content-kind model: admission, contract publication, link routing, localized content, translation, snapshots, diffs, and site projection must not accumulate collection/content-type switches as the ladder proceeds. Preserve the existing kind-neutral identity and filesystem addressing; make kind policy explicit only where a second real kind demonstrates shared behaviour.

**Shared refactoring rule:** do not create a standalone framework slice. In the red-green-refactor cycle of the first slice that proves each seam, extract a small immutable compiled-edition `PublicationKinds` collection and a `PublicationKind` role. A kind owns its `(collection, contentType)` key, admission rules, published `KindContract`, and public route policy; `PublicationKinds` owns deterministic lookup, unsupported-kind diagnostics, and sorted contract enumeration. The runtime kind objects derive the external contract; the JSON contract is not an internal schema engine. The S17a migration may move the existing essay policy into that seam. Afterward, adding a kind may change only the explicit composition list, its acceptance fixture, and genuinely new site presentation code; it must not require edits to old kinds or new collection/type conditionals in generic orchestration.

**Kind-neutral lifecycle rule:** `NoteIntake` returns an `AdmittedPublication`, not a concrete kind result, and receives the same `PublicationKinds` instance used by contract writing and public-link indexing. Stop constructing intake or kind dependencies inside handlers. When a second fixture demonstrates heterogeneous public metadata, replace the fixed body/title/description parameter trains with immutable localized-publication values containing canonical body and validated ordered public fields. Translation, candidate/approved snapshots, freshness hashes, and diffs then operate on those whole values. Do not generalize fields or body rules before a real kind requires the new behaviour.

**Release rule:** kinds own their projection to managed content. Keep filesystem installation responsible only for confinement, atomic installation, and provenance. Extract a generic safe-artifact projection only when a kind actually needs a different managed artifact shape; do not pre-emptively redesign ordinary Markdown release for editorial content before S17f and G7.

| Slice | Kind added | Requirements increment |
| --- | --- | --- |
| S17a | `blog/note` | ADM-03, ADM-04, PCM-02, PCM-06 for note |
| S17b | `blog/claim` | ADM-03, ADM-04, PCM-02, PCM-06 for claim |
| S17c | `bibliography/book` | ADM-03, ADM-04, PCM-02, PCM-06 for book |
| S17d | `concepts/concept` | ADM-03, ADM-04, PCM-02, PCM-06 for concept |
| S17e | `music/album` | ADM-03, ADM-04, PCM-02, PCM-06 for album |
| S17f | `editorial/curated_page` | ADM-03, ADM-04, PCM-02, PCM-06 for editorial page; gated by G7 |

**Per-slice refactoring triggers:**

- **S17a:** make `blog/note` pass the full boundary acceptance path first. Once essay and note are both proven, extract only the shared kind-selection seam: `PublicationKind`, `PublicationKinds`, `AdmittedPublication`, shared contract enumeration, and kind-owned routing. This is the first justified refactor, not a separate milestone.
- **S17b:** let the claim fixture decide whether the essay-shaped localized-content, translation, snapshot, hash, and diff carriers must become whole publication values. If it does, migrate every dependent carrier as one coherent behaviour-preserving refactor; do not leave dual essay/generic paths.
- **S17c–S17e:** let book, concept, and album requirements drive only the specific field or structured-body policies they need. Extract a reusable rule object only after at least two implemented kinds share that exact validation or projection behaviour; prefer kind polymorphism and composition over enums, type switches, or cross-kind inheritance.
- **S17f:** after G7 freezes or versions editorial grammar, give the editorial kind its own validated projection. Generalize release artifacts only if that projection proves the ordinary Markdown artifact insufficient.

**Explicitly excluded from every kind slice:** a generic schema-framework, reflective/plugin discovery, speculative cross-kind inheritance, and refactoring not justified by the new kind's acceptance behaviour. After the S17a migration, the practical extension check is that the next kind needs its own kind object, fixtures, one explicit composition registration, and any genuinely new site schema/view—not edits to intake, generic preparation, contract aggregation, snapshot persistence, or filesystem installation.

### S18 — Direct-target source-ID admission

**Visible result:** preparing a note with a direct private target succeeds only when the source and target have unique human-assigned source IDs; missing or duplicate target identity returns `metadata_blocked` before job or candidate mutation.

**Requirements introduced:** SEM-01 (direct targets).

**Acceptance boundary:** a small in-memory vault identity index. No path fallback or automatic allocation exists in either fake or production adapter.

**Explicitly excluded:** occurrence IDs, `references.json` entries, and target link activation.

### S19 — Stable semantic occurrence map

**Visible result:** preparing a linked publication emits RU, EN, and `references.json` with identical stable occurrence IDs and order; a translation that reorders or invents occurrences is rejected.

**Requirements introduced:** SEM-02, SEM-03 (non-empty map), TRP-05.

**Acceptance boundary:** one referrer and one target in memory, with a previous map for the reuse scenario. Codec unit tests are justified for duplicate keys, hashes, and strict number/order parsing.

**Explicitly excluded:** resolving the occurrence into a public route at release time.

### S20 — Late-bound target activation

**Visible result:** the same unchanged approved referrer releases as a plain label while its target is private, becomes a localized link after ordinary target approval, and returns to a label when the target is unpublished—without referrer candidate or reapproval.

**Requirements introduced:** SEM-04, SEM-05, REL-02 (semantic projection), REL-03 (activation provenance).

**Acceptance boundary:** a two-publication in-memory acceptance test covers the complete state sequence and asserts unchanged referrer approved hashes.

**Explicitly excluded:** migration and automatic rewriting of any approved referrer.

### S21 — Read-only legacy diagnosis

**Visible result:** an explicitly invoked migration inventory emits a deterministic report for a legacy workspace, while normal prepare/release fails closed with migration-required evidence and no mutation.

**Requirements introduced:** MIG-01, MIG-02, MIG-05 (incomplete-state gate).

**Acceptance boundary:** in-memory legacy pairs and semantic-state markers first; the real read-only inventory adapter receives a no-mutation contract.

**Explicitly excluded:** decision draft generation, apply, catalog mutation, and activation.

### S22 — Non-executable migration decisions

**Visible result:** a generated decision draft is visibly marked non-executable, and apply rejects both the draft and any human decision set whose inventory fingerprint is stale.

**Requirements introduced:** MIG-03.

**Acceptance boundary:** inventory and decision carriers remain in memory for the fast suite; real JSON adapters share strict parsing and fingerprint contracts.

**Explicitly excluded:** changing the review workspace.

### S23 — Conditional migration apply and recovery

**Visible result:** when G6 selects in-place migration, an explicitly authorized apply transforms one legacy fixture into a coherent current generation; injected interruption can be rolled forward or back, and competing semantic work is blocked.

**Requirements introduced:** MIG-04 and MIG-05 (complete activation).

**Acceptance boundary:** a journalled in-memory workspace and operation lock prove the state machine first; real filesystem journaling and recovery then run the same contract.

**Explicitly excluded:** automatic application, using drafts as approval, real-vault rehearsal, approval, build, or deployment. If G6 selects clean cutover instead, this slice is removed rather than implemented speculatively.

## Requirement coverage matrix

| Capability | Requirement coverage |
| --- | --- |
| publication-admission | ADM-01 S16; ADM-02 S02; ADM-03 S02/S17; ADM-04 S02/S17; ADM-05 S03/S16; ADM-06 S15/S17 |
| public-content-model | PCM-01 S03/S06; PCM-02 S03/S06/S17; PCM-03 S13; PCM-04 S12; PCM-05 S14; PCM-06 S08/S17 |
| translation-preparation | TRP-01 S03; TRP-02 S08; TRP-03 S08; TRP-04 S08; TRP-05 S19; TRP-06 S11 |
| review-and-approval | RVA-01 S02/S04; RVA-02 S04/S08; RVA-03 S05; RVA-04 S05/S09; RVA-05 S05/S09; RVA-06 S09 |
| semantic-references | SEM-01 S02/S18; SEM-02 S19; SEM-03 S03/S05/S19; SEM-04 S20; SEM-05 S20 |
| release-materialization | REL-01 S06; REL-02 S06/S20; REL-03 S06/S10/S20; REL-04 S07/S10; REL-05 S07/S10; REL-06 S07 |
| workflow-bridge | BRG-01 S01/S03/S05/S11; BRG-02 S01 and every later command response; BRG-03 S01; BRG-04 S02/S04; BRG-05 S11; BRG-06 S11; BRG-07 S04 |
| legacy-transition | MIG-01 S21; MIG-02 S21; MIG-03 S22; MIG-04 S23 conditional; MIG-05 S21/S23 conditional |

## Milestones and stopping rules

### Milestone A — first usable publication path

S01–S07 complete. One plain essay works end to end. Stop and test the real plugin plus site before implementing updates, breadth, or semantic links.

### Milestone B — safe repeated publication

S08–S11 complete. The same note can be changed, re-reviewed, re-approved, re-released, and reconciled safely.

### Milestone C — current content breadth

S12–S17f complete. Markdown safety, assets, whole-vault admission, contract export, and every current content kind work through the same path.

### Milestone D — semantic publication frontier

S18–S20 complete. Stable source identity and occurrence maps support late-bound activation without referrer reapproval.

### Milestone E — legacy transition, only if selected

S21–S23 complete or G6 explicitly removes apply from scope.

Stop and split a slice before coding when any of these holds:

- More than three new acceptance scenarios are required for its visible result.
- More than one new real adapter is necessary.
- The in-memory acceptance subset exceeds one second.
- A requirement is added only to make a guessed abstraction reusable.
- The proposed commit cannot be described as one new user-observable sentence.
- The slice would change candidate, approved, and release state in one unreviewable step.

## Deliberate ordering choices

- Bridge v2 comes first because it restores a real system boundary and catches contract drift immediately.
- One essay reaches the site before whole-vault discovery, all kinds, rich Markdown, or semantic links.
- Create-only candidate, approval, and release paths precede replacement/recovery paths; unsupported repeated-state cases fail closed until the strengthening slice lands.
- Content kinds are separate micro-slices, preventing a generic schema framework from being invented before repeated behaviour exists.
- Semantic identity enters the simple path only where the active Haft decision requires it; occurrence machinery waits until linked content is actually introduced.
- Legacy migration is last and conditional because it must not shape the normal greenfield architecture.
