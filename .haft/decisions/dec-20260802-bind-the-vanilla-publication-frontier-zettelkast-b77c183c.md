---
id: dec-20260802-bind-the-vanilla-publication-frontier-zettelkast-b77c183c
kind: DecisionRecord
version: 1
status: active
title: Publication-frontier Zettelkasten identity (base)
context: semantic-links
mode: deep
valid_until: 2026-11-02T00:00:00Z
created_at: 2026-08-02T10:30:59Z
updated_at: 2026-08-02T10:30:59Z
links:
  - ref: prob-20260802-1803dd18
    type: based_on
  - ref: sol-20260802-2618001e
    type: based_on
---

# Publication-frontier Zettelkasten identity (base)

## 1. Problem Frame

**Signal:** The semantic-link data model, validation, and public-projection mechanism already exist in code (PageReferenceMap/Codec, SemanticReferenceMarkdown, ApprovedTargetRegistry, ApprovedReleaseMaterializer), but nothing connects them into an operable pipeline: there is no semantic-site initializer, Prepare loads the target catalog but never allocates/reconciles new target identities into it, the Obsidian bridge never passes target/backlink graph data, and the 20 already-published legacy pages have no semantic bundle or upgrade path. The late-binding capability the operator flagged as most-wanted (TS.environment-change.003) is therefore not actually reachable end-to-end yet.

**Constraints:**
- Approved (published/) snapshots are never mutated by Prepare or catalog/inventory operations — only an explicit approval action can advance them
- RU and EN semantic occurrence IDs must be identical in count and order; ambiguous identity changes block preparation rather than guessing
- Target public IDs/routes never become part of the referrer's semantic identity (targetRef stays route-independent and stable across renames)
- Publishing or un-publishing a target must never silently rewrite or reapprove existing referrers
- Public output must never leak ref:/vault-ref-* tokens or otherwise-private semantic destinations
- Concurrent Prepare, approval, and build operations remain lock-guarded

**Acceptance:** (1) init-semantic-site creates a fresh, empty, valid semantic workspace without pretending any page is approved; (2) a new note goes publish:true -> Prepare -> review -> mark-reviewed -> build and produces a valid semantic bundle with no separate link-maintenance step; (3) each of the 20 legacy published pages can pass through the same Prepare/review boundary and receive a valid semantic bundle without hand-authored decisions.json and without rewriting unrelated approved pages; (4) publishing a previously-unpublished target changes only that target's own approved triple and the next public projection — every existing referrer keeps its approved RU/EN bytes and semantic map hash unchanged. All four verified first against fixtures/a test workspace, per the doc's explicit non-authorization of touching the real review workspace.

## 2. Decision

**Selected:** Publication-frontier Zettelkasten identity (base)

**Selection policy:** First eliminate any option that fails core semantic preservation. Then apply the project's pre-existing bias toward small, reversible changes: do not add a second advisory mechanism when its claimed effect is unmeasured and the comparison kernel has flagged that effect score as subjective. Among the remaining Pareto-valid options, choose the one with lower implementation blast radius and drift exposure. Reconsider the deferred enhancement only against collected runtime evidence under an explicit threshold.

**Why selected:** It establishes the required durable semantic substrate with the smaller mechanism: source-owned stable IDs, exporter-authoritative frontier admission, route-independent target references, and a rebuildable index. Both compared variants preserve semantics and remain Pareto-valid, but the graph lens adds plugin cache lifecycle, duplicated preview rules, and cross-environment drift while its incremental operator benefit is currently supported only by design-stage ordinal judgment, not runtime evidence. The operator explicitly chooses to incur that cost only if measured Prepare behavior shows a concrete need.


**Invariants:**
- Before semantic Prepare or approval proceeds, the current note and every uniquely resolved direct private link target have a valid, globally unique, stable source ID assigned by a human operator.
- The source ID is semantic identity; publicId or route slug is routing only, and no filesystem path, title, alias, or slug may serve as fallback semantic identity.
- The exporter independently reads current source bytes and remains authoritative for frontier membership, identity admission, ambiguity, occurrence order, candidate construction, atomic approval, and publication blocking.
- The identity catalog/index is derived and rebuildable; it is never the sole owner of an ID.
- CLI, CI fixtures, and the Obsidian Prepare command share identical semantic validation and artifact contracts.
- No graph cache or backlink view participates in semantic admission, target-reference creation, approval, or public output under this decision.
- Current-vault backlinks, if inspected manually, are only potential referrers; approved PageReferenceMaps remain authoritative for actual activation impact.
- New targets do not silently rewrite or reapprove existing referrers; legacy upgrades and newly public linked targets use the ordinary Prepare, review, and mark-reviewed workflow.
- No source note, candidate, approved snapshot, review baseline, or publication state is automatically rewritten merely to repair identity metadata.

## 3. Rationale

**Counterargument:** The deferred graph lens is read-only and removable, and it could cheaply expose missing IDs and potential referrers before the first Prepare attempt. Postponing it may impose avoidable operator retries and may conceal activation impact until later. This argument is plausible but its magnitude is unknown; the decision is therefore only justified if runtime friction is instrumented rather than left anecdotal.

**Selected variant weakest link:** Correctness depends on the exporter computing the complete direct publication frontier and enforcing stable-ID uniqueness and immutability without gaps. If a directly referenced target is omitted from admission, or a copied/changed ID passes revalidation, two notes can silently acquire aliased or unstable semantic identity.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Publication-frontier Zettelkasten identity (base) | **Selected** | It establishes the required durable semantic substrate wi... |
| Publication-frontier Zettelkasten identity with Frontier Lens | Rejected | It adds MetadataCache lifecycle handling, preview-rule duplication, and another environment in which behavior can drift, while no observed runtime Prepare history yet demonstrates that its early feedback produces enough benefit to repay that cost. It remains a later augmentation if the recorded friction threshold is crossed. |

**Evidence requirements:**
- Tests proving complete current-note and direct-target admission for missing, malformed, duplicate, copied, and changed IDs, including a complete actionable repair list.
- Rename, move, catalog-loss, and derived-index-rebuild tests proving target-reference stability and the absence of path fallback.
- Concurrency or revalidation evidence showing that an ID cannot be copied or changed between frontier discovery and artifact commit without an authoritative block.
- Cross-entry-point parity evidence for CLI, CI fixtures, and the thin Obsidian bridge using identical source bytes and hashes.
- A populated review-workspace rehearsal over the known legacy semantic-link inventory, bound to fresh source bytes/hashes, with no apply or publication advancement without explicit human approval.
- Migration evidence that every note entering publication and every uniquely resolved direct private target receives a human-assigned unique ID before Prepare proceeds; no bulk automatic ID rewrite is admissible.
- A runtime friction log for the first 20 real Prepare attempts, distinguishing ID-discovery retries from ambiguity, translation, review, or unrelated failures.
- Verification that approved PageReferenceMaps, not current vault backlinks, determine actual activation impact and stale-referrer handling.

**Predictions:**
| Claim | Observable | Threshold |
|-------|------------|-----------|
| Prepare blocks every current note and every uniquely resolved direct private target that lacks a valid, unique stable source ID before candidate generation, translation scheduling, approval, or publication can advance. | Authoritative unit, integration, and real-workspace rehearsal results covering missing, malformed, duplicate, and copied IDs on both the current note and direct targets. | 100% of covered admission cases block before any downstream artifact or publication-state mutation. |
| Source identity remains stable across file moves, renames, catalog deletion, and derived-index rebuilds. | Relocation and rebuild fixtures comparing the semantic target reference before and after each operation. | The target reference is identical in every covered relocation/rebuild case and resolution succeeds without path fallback. |
| The vanilla design preserves one authoritative semantic path across entry points. | Byte/hash comparison of semantic candidates, approved reference maps, and blocking diagnostics produced from identical source fixtures through CLI, CI fixtures, and the thin Obsidian note-path bridge. | 100% semantic artifact and blocking parity for the shared fixture corpus. |
| Deferring the graph lens does not create enough avoidable ID-discovery friction to justify its additional mechanism. | For the first 20 real publication Prepare attempts, count attempts that require an extra invocation solely because missing, malformed, or duplicate IDs were first discovered by Prepare. | No more than 4 of the first 20 attempts incur such an avoidable extra invocation; crossing this threshold triggers a fresh comparison of the graph lens. |

## 4. Consequences

**Rollback plan:**
Triggers:
- A rename or move changes a note's semantic target reference while its source ID is unchanged.
- A duplicate, changed, or missing source ID passes Prepare or approval without an authoritative block.
- CLI, fixture, and Obsidian entry points produce different semantic artifacts from identical source state.
- The migration cannot be completed without unsafe automatic rewrites or loss of an approved reference-map baseline.
Steps:
1. Pause semantic-link approval and publication advancement for affected notes.
2. Revert the new admission/resolution path to the last verified pre-implementation code state; do not automatically remove human-assigned source IDs.
3. Discard and rebuild all derived identity indexes from source notes.
4. Restore the last explicitly approved reference maps and review baseline, then rerun the authoritative fixture and rehearsal suites before resuming.
Blast radius: The exporter Prepare/resolution path, derived identity index, and source-note ID metadata. Rollback does not require deleting assigned IDs and must not rewrite approved snapshots or published state automatically.

**Refresh triggers:**
- More than 4 of the first 20 real Prepare attempts need an avoidable extra invocation solely to discover ID problems.
- Operators cannot receive a complete actionable ID repair list from a single authoritative Prepare attempt.
- A rename, move, catalog loss, or index rebuild changes semantic identity or requires path fallback.
- Duplicate or changed IDs evade admission or revalidation.
- CLI, CI, fixture, and Obsidian entry-point semantics diverge.
- The target-reference, public-route, PageReferenceMap, approval, or publication-baseline contract changes.
- A proposal would send graph data across the Obsidian bridge or persist graph-derived semantic state.
- Runtime timing or disagreement evidence suggests a graph preflight could materially reduce cost without becoming authoritative.
- The decision reaches its validity date.

**Affected files:** exporter-java/src/main/java, exporter-java/src/test/java, obsidian-plugin/main.js, obsidian-plugin/bridge-client.js, tools/astro-export/scripts
