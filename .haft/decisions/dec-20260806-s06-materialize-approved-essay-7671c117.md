---
id: dec-20260806-s06-materialize-approved-essay-7671c117
kind: DecisionRecord
version: 1
status: active
title: build-from-review: ReleaseOutputStore port + FilesystemReleaseOutputStore adapter + BuildFromReviewHandler/Command
mode: standard
created_at: 2026-08-06T05:42:50Z
updated_at: 2026-08-06T05:42:50Z
links:
  - ref: prob-20260806-e107746a
    type: based_on
---

# build-from-review: ReleaseOutputStore port + FilesystemReleaseOutputStore adapter + BuildFromReviewHandler/Command

## 1. Problem Frame

**Signal:** S05 (approve-first-candidate) is archived: an approved RU/EN/references.json triple can be durably installed via mark-reviewed, but nothing yet turns an approved snapshot into deterministic Astro-ready output. REL-01, REL-02 (no-semantic-occurrences slice), REL-03 (minimum provenance), and the release-boundary halves of PCM-01/PCM-02 are unimplemented. The exporter still cannot produce a single coherent release artifact for the one plain essay proven through S01-S05.

**Constraints:**
- Read from approved snapshots only; candidate/job artefacts have no release authority (REL-01)
- Emit no semantic token, source ID, private vault path, or internal private route (REL-02, trivially satisfied since no semantic occurrences exist yet)
- Bind output to deterministic provenance: contract edition + snapshot hashes + output-tree/file hashes (REL-03)
- Exactly one new production boundary adapter: the release-output (new-directory) adapter
- In-memory acceptance subset stays below one second
- No live-site-tree replacement, no assets, no links, no multiple publications, no recovery-from-prior-generation logic in this slice

**Acceptance:** A new `build-from-review` command reads the approved RU/EN triple for one essay (in-memory approved-store fake first, then the real filesystem approved-store contract) and writes one RU essay file, one EN essay file, plus deterministic minimum release provenance (exporter contract edition, approved-snapshot hashes, output-file hashes; zero activation/semantic-projection inputs since none exist yet) into a brand-new, previously empty output root. Any existing candidate is ignored. A missing/partial/unsafe approved snapshot blocks release before any output write. The full acceptance suite (228+ tests) stays green, the in-memory acceptance subset stays under one second, and the change ships as one independently reviewable commit.

## 2. Decision

**Selected:** build-from-review: ReleaseOutputStore port + FilesystemReleaseOutputStore adapter + BuildFromReviewHandler/Command

**Selection policy:** Follow the proven S05 shape (in-memory fake first, real create-only filesystem adapter proven against the same contract) unless the technical collaborative-design pass surfaces concrete evidence to deviate; extract shared mechanics only on a third evidenced occurrence, per the plan's own no-speculative-reuse discipline.

**Why selected:** Materializes the S05 approved snapshot into deterministic Astro-input files plus minimum release provenance, following the exact proven shape S05 established for ApprovedSnapshotWorkspace (in-memory fake first, real create-only filesystem adapter second, stage-then-ATOMIC_MOVE with confinement checks). This is the evidenced third occurrence of that staging/confinement shape, so a shared StagedDirectoryInstall helper was finally extracted from the two existing Filesystem*Workspace adapters and reused by the new one, per the trigger condition S05's own design doc flagged in advance.


**Invariants:**
- Release materialization reads approved snapshots only; candidate/job artefacts have no release authority
- No semantic token, source ID, private vault path, or internal private route ever appears in release output
- build-from-review writes into a fresh output root, isolated from the review workspace (approved/candidate directories reject a shared root)
- Every release binds deterministic provenance: contract edition, approved-snapshot hashes, and freshly-computed output-file hashes

**Post-conditions:**
- [ ] publication-exporter test suite green at 261 tests
- [ ] obsidian-plugin conformance suite green (69 pass, 1 pre-existing unrelated skip)
- [ ] openspec validate --changes s06-materialize-approved-essay --strict passes

## 3. Rationale

**Counterargument:** Committing to the identity-scoped `<outputRoot>/<collection>/<id>/release/...` layout now, rather than the site's real `<collection>/<locale>/<id>.md` shape, risks looking like premature convention-setting if S07 ends up wanting something closer to the final site tree from the start.

**Selected variant weakest link:** The release-output layout intentionally does not match the live site's locale-keyed shape, so S07's install step must perform a real re-layout, not a trivial tree copy -- if S07's design doesn't budget for that transformation, this choice will need revisiting.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| build-from-review: ReleaseOutputStore port + FilesystemReleaseOutputStore adapter + BuildFromReviewHandler/Command | **Selected** | Materializes the S05 approved snapshot into deterministic... |
| Mirror the site's <collection>/<locale>/<id>.md content-tree layout directly in the release output | Rejected | would borrow a multi-publication directory-sharing decision this single-publication slice has no evidence to make well, and breaks the proven create-only atomic-whole-directory-move trick once a second publication ever shares a locale directory; deferred to S07/S16 with real evidence. |
| Reuse BridgeResponse's schemaVersion/status vocabulary for build-from-review's result | Rejected | build-from-review is absent from bridge-contract/schema-v2.json's command enum and is never consumed by the Obsidian plugin; reusing BridgeResponse would imply a plugin contract that does not exist and cannot be validated. |

**Evidence requirements:**
- Full whole-branch review (gpt-5.6-sol, xhigh) found 2 Important findings, both fixed and confirmed addressed by scoped re-review
- Independent controller-run test verification: 261/261 publication-exporter tests, 69/70 obsidian-plugin tests (1 pre-existing unrelated skip)

## 4. Consequences

**Rollback plan:**
Triggers:
- S07's design finds the release-output layout unworkable for site installation
- the output-root isolation guard proves too narrow or too broad in practice
Steps:
1. revert commits fee96f5..4c98892 (the S06 slice) and cc81a0f (checkbox reconciliation)
2. no other slice depends on build-from-review yet, so rollback is a clean revert with no cascading changes
Blast radius: publication-exporter module only; no site, plugin, or bridge-contract changes to unwind

**Refresh triggers:**
- S07's design pass decides the site-install re-layout strategy
- S10 designs release-generation replacement/recovery semantics

