---
id: dec-20260806-s05-approve-first-candidate-7554247c
kind: DecisionRecord
version: 3
status: active
title: New ApprovedSnapshotWorkspace port + CandidateWorkspace#read + MarkReviewedHandler for the first approved snapshot
mode: standard
created_at: 2026-08-06T03:18:56Z
updated_at: 2026-08-10T06:09:41Z
links:
  - ref: prob-20260805-3d747bed
    type: based_on
---

# New ApprovedSnapshotWorkspace port + CandidateWorkspace#read + MarkReviewedHandler for the first approved snapshot

## 1. Problem Frame

**Signal:** S04 gave `inspect-publication` a real review plan once `prepare` (S03) installs a candidate, but there is still no way to actually approve anything: `mark-reviewed` is declared in `bridge-contract/schema-v2.json`'s command enum but has zero implementation anywhere in the codebase (no handler, no CLI command, no Java reference to the string "mark-reviewed" outside the schema). `BridgeResponse.approvedSnapshotState` is hard-coded to the literal "absent" in every response `InspectPublicationHandler` produces. No approved-snapshot store concept exists at all — no port, no adapter, no test — this is greenfield. Per `openspec/implementation-plan.md`'s S05 entry, an explicit `mark-reviewed` command must install the exact reviewed candidate as the first durable approved triple (RU, EN, reference map) and return success only after it is readable back as one coherent snapshot. Milestone A (S01-S07) cannot progress to release materialization (S06) without a durable approved snapshot to build from.

**Constraints:**
- Acceptance boundary: an in-memory approved-snapshot store proves authority and exactness first; the real create-only filesystem store runs the same contract second, per this project's outside-in discipline
- At most one new production boundary adapter (the approved-snapshot store) - reuse existing conventions (Constructor Method, stage-then-ATOMIC_MOVE, requireWithinReviewRoot-style confinement) already proven by CandidateWorkspace rather than inventing new patterns
- In-memory acceptance subset stays under one second
- Explicitly excluded from this slice: replacing an existing approved snapshot, crash recovery after a replacement starts, release generation, and any competing/concurrent-approval lock contention - those fail closed as unsupported state until S09
- A second approval attempt (one approved snapshot already exists) fails closed in this slice, not silently ignored or silently replacing
- governed by /nullables, /applying-sbpp, /oo-design-guide per the standing project convention

**Acceptance:** `mark-reviewed`, invoked against the S04-reviewed candidate, installs that exact candidate (RU, EN, reference map) as the first approved snapshot and returns success only once it is durable and readable back as one coherent triple (RVA-03, RVA-05). Approval revalidates the candidate is still exact before installing (RVA-04, scoped to this slice's first-approval case — no competing/concurrent approval or replacement exists yet to revalidate against, per the plan's own "(exact first candidate)" qualifier; full contention/lock semantics are S09's). The approved reference map is schema-valid and bound to the exact candidate hashes, accepted as an empty map for a first-publication candidate with no semantic links (SEM-03, approved-boundary realization — already realized at the candidate boundary by S03). Every non-approval command (prepare, inspect, refresh) leaves approved bytes untouched.

## 2. Decision

**Selected:** New ApprovedSnapshotWorkspace port + CandidateWorkspace#read + MarkReviewedHandler for the first approved snapshot

**Selection policy:** Reuse CandidateWorkspace's proven conventions (Constructor Method, stage-then-ATOMIC_MOVE, requireWithinReviewRoot confinement) for a new, separate ApprovedSnapshotWorkspace port rather than extending CandidateWorkspace itself, since candidate and approved lifecycles diverge further at S09 (replacement/recovery) and RVA-06 (tamper detection), not converge.

**Why selected:** mark-reviewed now revalidates the S04-reviewed candidate against its own recorded reference-map hashes (catching both source drift and candidate-file tampering with one mechanism, D4) and installs RU/EN/reference-map as the first approved snapshot via a create-only, stage-then-atomic-move adapter, returning ready_to_publish only once the atomic install completes. A second approval attempt fails closed (RVA-05's new scenario) rather than silently replacing or no-oping. references.json is legible again via ReferenceMapCodec#read, reused by both CandidateWorkspace#read and the new ApprovedSnapshotWorkspace.


**Invariants:**
- CandidateWorkspace#read only ever returns content whose reference map identity matches the requested identity, on every adapter including the in-memory fake
- ApprovedSnapshotWorkspace#install is create-only — a second install for the same identity always throws ApprovedSnapshotAlreadyExistsException rather than replacing or silently ignoring
- MarkReviewedHandler translates every CandidateWorkspace/ApprovedSnapshotWorkspace lookup failure (UncheckedIOException, *ConfinementException) into a schema-v2 blocked response — nothing propagates as a raw exception past the handler
- FilesystemCandidateWorkspace#read confinement-checks each of ru.md/en.md/references.json individually via requireWithinReviewRoot, not only the candidate directory as a whole
- approvedSnapshotState stays hard-coded absent in inspect-publication until a future slice wires it up

**Spec sections:**
- review-and-approval

**Admissibility:**
- NOT: NOT: replacing an existing approved snapshot, or crash recovery after a replacement starts (S09)
- NOT: NOT: crash-survival durability (fsync) for the approved-snapshot install — deferred; RVA-03/RVA-05's requirement text was corrected to match what stage-then-ATOMIC_MOVE actually guarantees instead of adding fsync now
- NOT: NOT: inspect-publication reporting the new approved-snapshot state (approvedSnapshotState stays absent)
- NOT: NOT: non-empty reference-map occurrence validation (SEM-02/PCM-03, S13/S19)
- NOT: NOT: per-publication exclusion locking under real contention (S09)

## 3. Rationale

**Counterargument:** The final whole-branch review (gpt-5.6-sol) argued FilesystemApprovedSnapshotWorkspace's stage-then-ATOMIC_MOVE does not actually guarantee durability the way RVA-05's and RVA-03's inherited requirement text claimed ("...are durable before success is reported" / "...is durable") — rename(2) is atomic (never a half-renamed state) but not durable (no fsync of the staged files or the destination directory means a concurrent crash could still lose data). The review also flagged: MarkReviewedHandler was missing exception translation for candidate/approved-snapshot lookup failures (fixed); FilesystemCandidateWorkspace#read only confinement-checked the candidate directory, not the individual ru.md/en.md/references.json member files, an incomplete symlink-escape guard (fixed); NullCandidateWorkspace#read didn't verify the stored candidate's referenceMap carries the matching identity, unlike the real adapter (fixed); and a handful of findings ruled out of scope or pre-existing (adapter identity-keying inconsistency inherited unchanged from CandidateWorkspace; coordinated candidate-file-plus-hash forgery, which belongs to RVA-06/S09's tamper detection, not this slice; schema-v2.json's pre-existing cross-cutting permissiveness for status-specific shapes).

**Selected variant weakest link:** The approved snapshot's durability guarantee is now honestly scoped to atomic, all-or-nothing visibility, not crash survival — if a later slice (S06's release generation reading from approved/, or S09's replacement/recovery work) needs genuine crash-survival durability, FilesystemApprovedSnapshotWorkspace (and FilesystemCandidateWorkspace, for consistency) will need real fsync work then, not an implicit reading of "durable" now.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| New ApprovedSnapshotWorkspace port + CandidateWorkspace#read + MarkReviewedHandler for the first approved snapshot | **Selected** | mark-reviewed now revalidates the S04-reviewed candidate ... |
| Extend CandidateWorkspace with installApproved(...)/findApproved(...) instead of a new port | Rejected | Candidate and approved lifecycles are genuinely different and will diverge further, not converge — S09 adds replacement/atomic-recovery semantics and RVA-06 adds tamper detection, neither of which CandidateWorkspace needs. Folding both into one interface trades a small amount of near-term duplication for keeping each interface's single responsibility clean. |
| Derive references.json's path from CandidatePaths and read files directly in MarkReviewedHandler, avoiding any CandidateWorkspace change | Rejected | Works for the real filesystem adapter but not NullCandidateWorkspace, which has no real files backing its synthetic paths — read(...) gives both adapters an honest, symmetric answer to 'what content is installed for this identity'. |

**Evidence requirements:**
- publication-exporter test suite stays green (227 tests at time of decision)
- obsidian-plugin conformance suite stays green (69 passed, 1 pre-existing unrelated skip)
- openspec validate --strict clean for the archived change

## 4. Consequences

**Rollback plan:**
Triggers:
- S09's replacement/recovery work, or S06's release generation reading from approved/, demonstrates a genuine need for crash-survival durability of the approved snapshot
Steps:
1. Add fsync on each staged file plus the destination parent directory in FilesystemApprovedSnapshotWorkspace and FilesystemCandidateWorkspace before reporting success
2. Restore RVA-03/RVA-05's stronger "durable" wording once the implementation actually provides it
Blast radius: publication-exporter candidate and approved packages; openspec/specs/review-and-approval/spec.md wording only, no schema-v2 contract change needed

**Refresh triggers:**
- S09 (replacement/recovery, RVA-06 tamper detection) lands
- S06 (release generation) starts reading from the approved/ snapshot and may need stronger durability guarantees
- S11 (BRG-05/BRG-06 six-state vocabulary) lands and ready_to_publish/stale need reconciling with the formal vocabulary

**Affected files:** publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotAlreadyExistsException.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspaceConfinementException.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java, bridge-contract/schema-v2.json, openspec/specs/review-and-approval/spec.md

## Impact Measurement (2026-08-10)

**Verdict:** accepted

**Findings:**
S05's flagged drift (15 modified, 11 added) is downstream S06-S11 work landing on the same approved/candidate packages; the create-only install invariant and the explicitly-scoped non-durability rollback trigger are both still intact — S09 and S06 landed without tripping the fsync escalation this decision predicted as its own weakest link.

**Criteria met:**
- [x] publication-exporter test suite stays green
- [x] obsidian-plugin conformance suite stays green
- [x] openspec validate --strict clean

**Measurements:**
- publication-exporter suite: 482/482 (baseline 227)
- obsidian-plugin conformance: 73/74 (baseline 69/70)
- openspec validate --all --strict: 8/8 passed
