# Semantic Link Decision Workflow Handoff

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the one-time semantic-link migration practically executable for approved legacy review snapshots that currently report `decisions-required`.

**Architecture:** The migration already has a read-only inventory and an apply/recovery engine. The missing piece is a safe human decision workflow for ambiguous translated link alignments, plus an apply-side contract that can consume those decisions without guessing spans or routes.

**Tech Stack:** Java 21 source target, Maven, picocli CLI, Jackson JSON, JUnit 6, PIT available through `org.pitest:pitest-maven`, native binary at `exporter-java/target/astro-export`.

## Global Constraints

- Do not mutate approved review snapshots during inventory or decision drafting.
- Do not run `migrate-semantic-links --apply` against the real review workspace until a valid decisions file has been generated and validated against a fresh inventory hash.
- Preserve approval-owned bytes unless the migration journal records enough recovery evidence for roll-forward and rollback.
- Current real vault: `/Users/eugene/Documents/personal-wiki/knowledge-base`.
- Current real legacy review workspace: `/Users/eugene/Documents/personal-wiki/tools/astro-export/review`.
- Current Astro site root: `/Users/eugene/Dev/personal-site/site`.
- The repo-local `exporter-java/review` is not the legacy review workspace; it was effectively empty during this investigation.
- Rerun inventory before implementation or apply. The recorded inventory hash below is evidence, not a durable current truth.

---

## Current State

The correct read-only inventory command is:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java

target/astro-export migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --astro /Users/eugene/Dev/personal-site/site \
  --report /Users/eugene/Dev/personal-site/exporter-java/reports/semantic-link-migration-inventory.json \
  --json
```

Observed bridge result from the correct review workspace:

```json
{
  "schemaVersion": 3,
  "command": "migrate-semantic-links",
  "ok": false,
  "status": "decisions-required",
  "summary": {
    "exact": 2,
    "confirmedNeeded": 20,
    "unresolved": 0,
    "orderMismatch": 0,
    "unsafe": 0,
    "occurrences": 140
  }
}
```

Observed inventory file:

```text
/Users/eugene/Dev/personal-site/exporter-java/reports/semantic-link-migration-inventory.json
```

Observed inventory digest:

```text
82c1c5dceb9738ce30ada26475aa59c4fda7daef8132396d9fcb4dcc8120fcc3
```

Observed page-level status:

```text
20 confirmed-needed pages
2 exact pages
22 total pages
```

Observed occurrence-level status:

```text
140 ambiguous-translation occurrences
0 occurrences with proposedEnSpan
0 occurrences with proposedEnDestination
0 occurrences with proposedReference
```

Observed ambiguity reasons:

```text
109 legacy approved span does not match current target route
31 duplicate raw target and label require confirmation
```

Largest confirmed-needed pages in the observed inventory:

```text
32  bibliography/2025/The Lean Startup.md
11  claims/Стартап существует, чтобы научиться строить устойчивый бизнес.md
10  claims/Культура — равновесие структуры, а не свойство людей.md
10  concepts/Структурные сдвиги от роста производительности.md
9   claims/Работающая практика без общего языка не передаётся.md
8   claims/Теория менеджмента неопределённости опровержима на уровне процесса, а не исходов.md
8   claims/Стартап отменяет не менеджмент, а менеджмент предсказуемого.md
```

## Root Blocker

The current inventory asks for decisions, but the current decision schema cannot be satisfied for the real ambiguous cases.

Relevant code:

- `ReferenceMigrationInventory.validateDecisions(...)` validates `schemaVersion`, `inventorySha256`, and the `decisions` object.
- `ReferenceMigrationInventory.validateConfirm(...)` requires a `confirm` decision to include `enSpan.start` and `enSpan.end`.
- It then compares that decision span to `occurrence.proposedEnSpan()`.
- In the real inventory, all 140 ambiguous occurrences have `proposedEnSpan: null`, so every hand-authored `confirm` decision would fail with `hash-mismatch`.

Additional apply-side blocker:

- `SemanticMigrationService.buildMigrated(...)` currently rewrites links by calling `occurrence.proposedEnDestination()`.
- In the real inventory, all 140 ambiguous occurrences have `proposedEnDestination: null`.
- Therefore accepting decisions is not enough; the migration plan needs executable replacement data for ambiguous occurrences.

Relevant code pointers:

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
  - `validateDecisions(...)`
  - `validateConfirm(...)`
  - `validateCorrectedOrder(...)`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationAligner.java`
  - `classify(...)`
  - `reason(...)`
  - `toOccurrence(...)`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
  - `stagePlan(...)`
  - `buildMigrated(...)`
  - `replaceFirstDestination(...)`
  - `requireDecisionCoverage(...)`
  - `references(...)`

## Practical User Guidance Until Fixed

The user can continue normal publication now:

1. In Obsidian, set or keep `publish: true` on notes intended for publication.
2. Run Prepare for publication.
3. Review the RU/EN pair.
4. Run Confirm translation / Mark current translation reviewed.
5. Build from approved review output.

This refreshes legacy approved snapshots. It does not complete semantic-link migration and does not create semantic `published/references.json` files.

The user should not hand-author `semantic-link-decisions.json` yet. The observed inventory does not contain the span or destination fields required by the current validator and apply path.

## Recommended Engineering Direction

Build a page-oriented decision workflow rather than only patching the existing occurrence `confirm` path.

Rationale:

- The real ambiguity is not only UX. It is executable-data absence.
- The migration must know exactly which approved RU and EN link spans to replace, or must accept fully corrected page snapshots with hashes.
- For 109 occurrences, the legacy approved span does not match the current target route, so a route-only decision is not sufficient.
- For 31 duplicate occurrences, occurrence identity must remain stable and ordered.

The safest design is one of these two shapes:

1. **Span-confirm decisions:** each ambiguous occurrence decision supplies exact RU and EN approved-link spans, expected destination/text evidence, target reference identity, and hashes. Apply validates the current approved bytes still match those spans, then replaces those exact spans with `ref:<id>` destinations.
2. **Page-corrected decisions:** each confirmed-needed page decision supplies corrected RU and EN files plus hashes. The corrected files already contain semantic `ref:<id>` destinations. Apply validates UTF-8, hash, reference order, and `PageReferenceMapCodec.validate(...)`, then installs the corrected triple.

Prefer page-corrected decisions if the UI/manual process is expected to be human-driven. Prefer span-confirm decisions if the tool can generate high-quality candidate spans automatically.

## Task 1: Characterize the Current Blocker

**Files:**
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
- Read: `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- Read: `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationAligner.java`

**Interfaces:**
- Consumes: existing `ReferenceMigrationInventory.inspect(...)` and `validateDecisions(...)`.
- Produces: regression tests proving why real ambiguous inventories cannot be confirmed today.

- [ ] Add a focused test where an inventory occurrence has `classification = ambiguous-translation` and `proposedEnSpan = null`.
- [ ] Write a decision file with `"decision": "confirm"` and an arbitrary `enSpan`.
- [ ] Assert validation fails with code/message equivalent to `hash-mismatch` and `confirmed English span does not match inventory`.
- [ ] Add a second test proving a missing `enSpan` fails with `missing-en-span`.
- [ ] Run:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest test
```

Expected: tests pass and document the current blocker.

## Task 2: Choose and Implement the Decision Contract

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- Modify or create tests under: `exporter-java/src/test/java/dev/eugene/astroexport/migration/`

**Interfaces:**
- Consumes: inventory pages and occurrence keys.
- Produces: a validated decision model that carries enough data for apply, not only accepted keys.

- [ ] Decide between span-confirm and page-corrected decisions.
- [ ] Update `DecisionSet` so it carries typed decision payloads, not only accepted keys.
- [ ] Preserve existing stale-inventory protection: decisions must include `inventorySha256`, and it must exactly match the fresh inventory.
- [ ] Preserve unknown-decision protection: every decision key must be either a known occurrence key or a known page-level key.
- [ ] Add byte/hash validation so decisions cannot silently apply to changed approved snapshots.
- [ ] Add tests for stale inventory, unknown key, malformed decision, and changed approved bytes.
- [ ] Run:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.migration.*Test' test
```

Expected: migration tests pass.

## Task 3: Generate a Human-Reviewable Decision Draft

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticDecisionDraftWriter.java`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`

**Interfaces:**
- Consumes: inventory JSON and approved snapshot files.
- Produces: a draft artifact the user can review/edit and convert into a valid decisions file.

- [ ] Add a CLI option for the inventory mode that writes a decision draft without applying migration.
- [ ] The draft must include page path, occurrence key, raw wikilink, targetRef, heading, reason, source context, and enough approved RU/EN context for a human to choose the intended link.
- [ ] If using span-confirm decisions, include candidate approved Markdown links and their spans/hashes.
- [ ] If using page-corrected decisions, write per-page corrected draft files and a decisions JSON containing their relative paths and hashes.
- [ ] Ensure the draft writer never mutates `review/*/published`.
- [ ] Run the read-only inventory command against a fixture and assert the draft is deterministic.
- [ ] Run:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.cli.AstroExportCommandTest test
```

Expected: command tests pass and prove the draft path is read-only with respect to approved snapshots.

## Task 4: Make Apply Consume Decisions Safely

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`

**Interfaces:**
- Consumes: typed `DecisionSet`.
- Produces: staged `published/{ru.md,en.md,references.json}` triples for exact and manually confirmed pages.

- [ ] Update `stagePlan(...)` to merge validated decisions into an executable migration plan.
- [ ] Update `buildMigrated(...)` or add a new renderer that does not rely on `occurrence.proposedEnDestination()` for manually confirmed ambiguous occurrences.
- [ ] Preserve exact-page automatic migration behavior.
- [ ] Preserve journal, staging, recovery, roll-forward, and rollback semantics.
- [ ] Add a fixture with at least one ambiguous occurrence where current code has null `proposedEnSpan` and null `proposedEnDestination`.
- [ ] Prove `--apply` fails without the decision and succeeds with the decision.
- [ ] Prove migrated `references.json` validates with `PageReferenceMapCodec.validate(...)`.
- [ ] Prove public materialization remains free of `ref:` and private `vault-ref-*` tokens.
- [ ] Run:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.SemanticMigrationServiceTest test
```

Expected: service tests pass.

## Task 5: Validate Against the Real Inventory Without Applying

**Files:**
- No production file changes expected.
- Generated local artifacts under `exporter-java/reports/` are acceptable for manual inspection but should not be committed unless the user asks.

**Interfaces:**
- Consumes: real vault, real legacy review workspace, current site root.
- Produces: a fresh inventory and a valid draft decisions artifact.

- [ ] Rerun the real inventory command.
- [ ] Generate the decision draft.
- [ ] Validate the generated draft or edited decisions file with a non-mutating validation path. If no validation-only CLI exists, add one before this task is considered complete.
- [ ] Do not run `--apply` against the real review workspace in this task.
- [ ] Run the full JVM suite:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q test
```

Expected: tests pass, fresh inventory exists, decision draft exists, no approved snapshots are changed.

## Task 6: Real Apply Readiness Gate

**Files:**
- No code changes expected after earlier tasks.
- Real review workspace mutation requires explicit user approval.

**Interfaces:**
- Consumes: validated decisions file whose `inventorySha256` matches a fresh inventory.
- Produces: semantic-mode review workspace with `review/.semantic-links/schema-v1.active.json`, complete migration journal, catalog, and semantic published triples.

- [ ] Before applying, record `git status --short` for `/Users/eugene/Dev/personal-site` and the vault/tooling repo that owns `/Users/eugene/Documents/personal-wiki/tools/astro-export/review`.
- [ ] Confirm with the user before real `--apply`.
- [ ] Run apply only with a fresh inventory and matching decisions file.
- [ ] After apply, run `build-from-review` or the approved release materialization command against the real selected set.
- [ ] Run the content/provenance gate and Astro build.
- [ ] If apply is interrupted, use only explicit recovery:

```bash
target/astro-export migrate-semantic-links \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --roll-forward \
  --json
```

or:

```bash
target/astro-export migrate-semantic-links \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --roll-back \
  --json
```

Expected: real migration completes or explicit recovery completes; semantic mode is valid; builds consume only approved semantic triples.

## Verification Checklist

- [ ] Current blocker is covered by tests.
- [ ] Decisions carry executable replacement data.
- [ ] Decisions are inventory-hash bound.
- [ ] Decisions are approved-byte/hash bound.
- [ ] Draft generation is read-only.
- [ ] Apply refuses missing, stale, malformed, and incomplete decisions.
- [ ] Apply writes `published/ru.md`, `published/en.md`, and `published/references.json`.
- [ ] Roll-forward and rollback still work.
- [ ] Public output contains no raw `ref:` destinations or private `vault-ref-*` identifiers.
- [ ] Normal Prepare for publication and Mark reviewed workflows still pass in legacy and semantic modes.

## Known Dirty Worktree Context At Handoff Creation

At the time this handoff was written, `/Users/eugene/Dev/personal-site` had unrelated uncommitted or untracked changes, including:

```text
exporter-java/pom.xml
exporter-java/src/main/java/dev/eugene/astroexport/validation/PreflightService.java
exporter-java/reports/
exporter-java/review/
exporter-java/src/main/java/dev/eugene/astroexport/fs/FileSystem.java
exporter-java/src/main/java/dev/eugene/astroexport/fs/NullFileSystem.java
exporter-java/src/main/java/dev/eugene/astroexport/fs/RealFileSystem.java
```

Do not revert or overwrite those changes without explicit user permission.
