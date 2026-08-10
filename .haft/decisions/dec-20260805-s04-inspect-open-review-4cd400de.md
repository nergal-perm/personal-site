---
id: dec-20260805-s04-inspect-open-review-4cd400de
kind: DecisionRecord
version: 3
status: active
title: Extend CandidateWorkspace with find(...), report candidate-ready state and a first-publication reviewPlan through inspect-publication
mode: standard
created_at: 2026-08-05T08:34:01Z
updated_at: 2026-08-10T06:09:37Z
links:
  - ref: prob-20260805-d9f3aef2
    type: based_on
  - ref: review-and-approval
    type: governs
---

# Extend CandidateWorkspace with find(...), report candidate-ready state and a first-publication reviewPlan through inspect-publication

## 1. Problem Frame

**Signal:** S03 gave `prepare` a working candidate triple (RU/EN/references.json via `PrepareHandler` + `CandidateWorkspace`), but `InspectPublicationHandler` still unconditionally reports candidate, approved-snapshot, semantic-reference, and release state as `"absent"` (InspectPublicationHandler.java:19-21) — it has no dependency capable of reading back a prepared candidate. Separately, `BridgeResponse` has no `reviewPlan` concept and `bridge-contract/schema-v2.json` declares no shape for one. Meanwhile the obsidian-plugin (main.js) already contains fully-built, currently-orphaned consumer logic — `inspectAndOpenReview`, `validateReviewPlan`, `launchReviewPlan`, `runZedTarget` — that reads `result.reviewPlan` off the inspect response and expects exactly `{baselineState: "absent"|"complete", targets: [{language:"ru"|"en", proposedPath, publishedPath}]}` to open RU/EN candidates as separate Zed windows (added 2026-07-29, predating this rebuild). Milestone A (S01-S07) cannot progress to S05 approval without a working review step.

**Constraints:**
- Candidate data for the acceptance test is supplied by an in-memory review-capable fake first; a real filesystem-backed read path is proven against the same contract only after the fake API is settled
- At most one new production boundary adapter is introduced (a read-capable extension of the candidate-workspace boundary, or a new review-read port) - no adapter added merely for architectural symmetry
- In-memory acceptance subset stays under one second
- No approved-snapshot diff logic, no mark-reviewed/approval behaviour, and no change to editor-launch code in obsidian-plugin
- The emitted reviewPlan shape must match the plugin's already-hardcoded validateReviewPlan/launchReviewPlan expectations exactly (baselineState absent|complete; targets order ru then en; publishedPath null when baseline absent)
- schema-v2.json remains the single source of truth consumed by both Java and JS conformance tests

**Acceptance:** `inspect-publication` on the S02/S03 essay, once a candidate exists, returns an exact first-publication review plan — RU and EN candidate paths, `baselineState: "absent"` (no approved baseline yet) — that the plugin's existing `validateReviewPlan`/`launchReviewPlan` accepts without modification, and a bridge contract test on both the Java and JS sides proves the emitted JSON is schema-v2 conformant and plugin-accepted. Explicitly excluded: approved-to-proposed diffs (`baselineState: "complete"`, deferred to when S05/S08 exist), approval itself, candidate replacement, and any editor-launch implementation detail (owned entirely by the plugin).

## 2. Decision

**Selected:** Extend CandidateWorkspace with find(...), report candidate-ready state and a first-publication reviewPlan through inspect-publication

**Selection policy:** Minimize new production adapter surface (plan's slice discipline caps this slice at zero-to-one new adapters) while making the emitted JSON match the plugin's already-built consumer exactly; any completeness/security hardening beyond what the existing write path can actually produce is deferred to the slice that introduces the reachable unsafe state, not built speculatively now.

**Why selected:** Reuses the existing CandidateWorkspace/FilesystemCandidateWorkspace boundary (a read method added to the port already proven in S03) rather than a parallel port, keeping this slice at zero new production adapters. The emitted reviewPlan shape matches obsidian-plugin's already-built, previously-orphaned validateReviewPlan/launchReviewPlan consumer exactly, so no plugin runtime code changed. Top-level status becomes ready_for_review (matching prepare's existing value for the same condition) ahead of BRG-05/BRG-06's formal introduction at S11.


**Invariants:**
- semanticReferenceState remains absent for a first-publication candidate in this slice
- reviewPlan.baselineState is always absent (never complete) until S08/S09
- existing not_prepared/all-absent response shape is byte-for-byte unchanged when no candidate exists

**Spec sections:**
- review-and-approval

**Admissibility:**
- NOT: approved-to-proposed diff / baselineState: complete (S08/S09)
- NOT: approval or candidate replacement
- NOT: any editor-launch implementation detail (owned by obsidian-plugin)
- NOT: semanticReferenceState reporting anything but absent

## 3. Rationale

**Counterargument:** The final whole-branch review argued candidate completeness should be verified more strongly now (symlink-safe, references.json-inclusive, tri-state) rather than deferred, since a corrupted or tampered candidate directory could otherwise be silently reported ready.

**Selected variant weakest link:** If S08/S09's replacement/recovery work ever introduces an interruption window that can leave a partial candidate directory visible, find()'s ru.md+en.md-existence check would need to become fail-closed at that point — exactly the revisit trigger D4 already names.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Extend CandidateWorkspace with find(...), report candidate-ready state and a first-publication reviewPlan through inspect-publication | **Selected** | Reuses the existing CandidateWorkspace/FilesystemCandidat... |
| Split CandidateWorkspace into a separate read-only port (CandidateLookup) alongside the existing write-only CandidateWorkspace | Rejected | Would cost this slice's one-new-production-adapter budget for a CQS purity gain the project accepted departing from at the interface-cohesion level; find() itself remains a pure query. |
| Model candidate lookup as a fail-closed absent\|ready\|blocked tri-state with NOFOLLOW_LINKS and references.json presence checking | Rejected | Raised by the final whole-branch review; declined for this slice because no writer in this codebase can produce a partial candidate directory (install()'s atomic stage-then-move guarantees all three files land together or none do) — this is exactly the case design.md's D4 already reasoned through, with its own stated revisit trigger being S09's replacement/recovery work, not S04. Only the narrower, non-conflicting gap (uncaught confinement/IO exceptions escaping without a schema-v2 response) was fixed. |

**Evidence requirements:**
- publication-exporter test suite stays green (181 tests at time of decision)
- obsidian-plugin conformance suite stays green aside from the pre-existing unrelated community-plugins.json fixture gap

## 4. Consequences

**Rollback plan:**
Triggers:
- S08/S09 replacement/recovery work demonstrates a reachable partial-candidate-directory state
Steps:
1. Harden CandidateWorkspace#find(...) into the fail-closed tri-state model the final review proposed, adding NOFOLLOW_LINKS and references.json presence checks
Blast radius: publication-exporter candidate package only; no schema-v2 contract change needed since candidateState's string values are already free-form

**Refresh triggers:**
- S08/S09 (replacement/recovery) lands
- S11 (BRG-05/BRG-06 six-state vocabulary) lands and status/candidateState need to be reconciled with the formal vocabulary

**Affected files:** publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewTarget.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidatePaths.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java, bridge-contract/schema-v2.json

## Impact Measurement (2026-08-10)

**Verdict:** accepted

**Findings:**
S04's flagged drift (8 modified, 6 added since baseline) is accounted for by S05-S11 landing on top of the same files; none of it contradicts S04's invariants (first-publication reviewPlan shape, absent-only baselineState/semanticReferenceState, unchanged not_prepared shape). Both declared evidence requirements still hold on a live re-run.

**Criteria met:**
- [x] publication-exporter test suite stays green
- [x] obsidian-plugin conformance suite stays green aside from the pre-existing unrelated gap

**Measurements:**
- publication-exporter suite: 482/482 (baseline 181)
- obsidian-plugin conformance: 73/74, 1 pre-existing skip (baseline 69/70 ratio at S05, same skip)
