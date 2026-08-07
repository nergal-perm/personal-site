---
id: dec-20260807-s09-approved-snapshot-integrity-anchor-c04a83ac
kind: DecisionRecord
version: 1
status: active
title: Narrow RVA-06's tamper-detection scope: no trusted anchor outside the mutable approved snapshot itself
mode: standard
created_at: 2026-08-07T10:56:05Z
updated_at: 2026-08-07T10:56:05Z
links:
  - ref: prob-20260807-034395de
    type: based_on
---

# Narrow RVA-06's tamper-detection scope: no trusted anchor outside the mutable approved snapshot itself

## 1. Problem Frame

**Signal:** MarkReviewedHandler (S05) unconditionally blocks any second `mark-reviewed` call once an approved snapshot exists for a publication identity — `alreadyApprovedResponse()` always returns "replacing it is not yet supported," regardless of whether the new candidate is a legitimate, fully-revalidated re-review of changed content (S08's reprepare path). Slice S09 in openspec/implementation-plan.md requires: approving the S08 candidate replaces the prior approved triple atomically; stale source/candidate evidence blocks; an injected interruption recovers to exactly the old or new triple. This also surfaces a real tension in the baseline requirement text: RVA-05's existing "A second approval is attempted" scenario currently says the request "is blocked rather than silently replacing," which reads as blocking ALL second approvals — but S09's own visible result is exactly a (non-silent, fully revalidated, atomic) replacement. Resolving whether this is a genuine requirement-text change or a misreading is this problem's first job, via the functional collaborative-design pass.

**Constraints:**
- No new production adapter beyond what atomic replace/recovery genuinely requires (implementation-plan.md slice rule: at most one new boundary adapter)
- Failure injection is a behavior of the in-memory ApprovedSnapshotWorkspace fake, not a mock interaction; the real filesystem adapter must pass the same recovery contract
- Do not touch release-tree replacement (build-from-review, install-to-site) or queue refresh (S10/S11's job)
- Preserve RVA-04's existing revalidation/staleness-blocking behavior for a genuinely stale second approval attempt
- Reuse the per-publication serialization approach S08 already introduced in PrepareHandler for competing-completion safety rather than inventing a second locking mechanism, if applicable to mark-reviewed's own concurrency needs

**Acceptance:** Given an approved snapshot and a new candidate that has passed RVA-04's full revalidation (current source bytes, candidate completeness, English structure/freshness, semantic-reference map, safe workspace paths, per-publication exclusion lock), `mark-reviewed` replaces the prior approved triple with the new one as one atomic, coherent unit — never exposing a mixed old/new snapshot. A second approval whose evidence has gone stale (source or candidate changed since the review the operator is approving) still blocks, exactly as RVA-04 already requires. An injected interruption during install deterministically recovers to exactly the old complete snapshot or the new complete snapshot, reported rather than silently guessed. Concurrent replacement attempts for the same publication are serialized (per-publication exclusion lock) so no interleaved/partial write is ever visible. All existing acceptance/unit tests remain green plus new S09 acceptance coverage; in-memory acceptance subset stays under 1 second.

## 2. Decision

**Selected:** Narrow RVA-06's tamper-detection scope: no trusted anchor outside the mutable approved snapshot itself

**Selection policy:** Prefer the option that (a) keeps S09 shippable now with its real, substantial integrity improvement intact (single-file corruption and partial-write tampering ARE now caught), (b) does not silently overstate what RVA-06 actually guarantees, and (c) matches this project's own established governance pattern for scope questions a single implementation slice cannot resolve unilaterally (mirrors dec-20260807-s08-translation-worker-trust-boundary-8bab0bc6). Scope-narrowing-with-a-recorded-decision satisfies all three; blocking for a full trusted-anchor build is disproportionate architecture work for this slice, and silent parking would overstate RVA-06's realized guarantee.

**Why selected:** S09's final whole-branch review (gpt-5.6-sol, xhigh) and its scoped re-review both confirmed: FilesystemApprovedSnapshotWorkspace now validates every approved snapshot's six content hashes against references.json before treating it as complete, catching single-file corruption, partial writes, and the original reproduced probe (a canonical directory with one tampered file next to an untouched, still-valid backup). What remains undetectable by construction: an edit that changes both a file's bytes AND its corresponding recorded hash in references.json consistently, since references.json is stored in the same mutable directory as the bytes it authenticates — there is no anchor outside that directory (no append-only ledger, no OS-level immutability, no external checksum store) to catch a fully self-consistent rewrite. This mirrors S08's same-UID translation-worker trust boundary exactly: both are same-UID, single-operator-deployment gaps with no adversarial-process isolation, surfaced by a review that reproduced the concrete mechanism rather than a theoretical concern.


**Invariants:**
- Single-file corruption, partial writes, and any tampering that does NOT also correctly update references.json's corresponding hash remain fully detected and blocked (already implemented and tested).
- The approved-snapshot validation logic (six-hash comparison against references.json) remains in force for every read/find/install call — this decision narrows what counts as 'tampering' for RVA-06 purposes, it does not weaken the validation that already exists.
- Only same-UID, single-operator, trusted-write-access deployment is covered by this narrowing.

**Pre-conditions:**
- [ ] Single-file/partial-write tamper detection is implemented and independently verified (six-hash validation in FilesystemApprovedSnapshotWorkspace).
- [ ] The final whole-branch review and its scoped re-review have explicitly identified and reproduced the coordinated-tampering gap this decision narrows.

**Post-conditions:**
- [ ] openspec/changes/s09-replace-approved-snapshot-safely/scope-pins.md is corrected to state RVA-06 is realized for single-file/partial tampering only, with this decision recorded for the coordinated-tampering exclusion.
- [ ] S09 can be archived and its Haft problem closed without building a trusted anchor now.
- [ ] A future slice/gate is the designated place to add a trusted integrity anchor if the deployment model changes.

**Admissibility:**
- NOT: Treating this decision as covering multi-operator or shared/less-trusted filesystem access to the review root.
- NOT: Using this decision to justify skipping or weakening the existing six-hash validation, backup/restore, or cross-process locking mechanisms.
- NOT: Silently expanding this narrowing to other requirements (e.g. candidate-workspace integrity, release-tree integrity) without a separate decision.

## 3. Rationale

**Counterargument:** RVA-06 was written as an unconditional SHALL specifically to catch tampering with approved files, and this decision concedes the exporter cannot actually detect a coordinated, internally-consistent edit — the exact kind of tampering a deliberate attacker (not just accidental corruption) would produce. Narrowing the requirement after the fact to fit what got built is the same rationalization pattern DEC-08's self-deception check exists to catch. If the single-operator trust assumption is ever wrong, a maliciously and consistently rewritten approved snapshot could reach a public release completely undetected.

**Selected variant weakest link:** Nothing enforces this narrowing at runtime — there is no check that refuses to proceed if the deployment model changes (multi-operator, shared filesystem, less-trusted write access to the review root). The refresh triggers depend entirely on someone remembering to revisit this decision before such a change ships, exactly like S08's analogous weakest link.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Narrow RVA-06's tamper-detection scope: no trusted anchor outside the mutable approved snapshot itself | **Selected** | S09's final whole-branch review (gpt-5.6-sol, xhigh) and ... |
| Block S09 and build a trusted integrity anchor now | Rejected | A real anchor (append-only hash ledger, OS-level read-only enforcement after install, or an external checksum store) is substantial new infrastructure disproportionate to this slice's scope, and would need its own design pass (where does the anchor live, what enforces write-once, how does recovery interact with it) rather than being bolted onto S09's fix wave. No such decision gate exists yet in openspec/implementation-plan.md. |
| Park silently without a decision record | Rejected | scope-pins.md currently claims RVA-06 is 'fully realized' — silently parking this gap would leave that claim standing, letting a later slice or reviewer reasonably believe coordinated tampering is already caught when it is not. Haft's governance model exists precisely to make this kind of scope narrowing auditable rather than implicit, matching why S08 recorded its analogous decision instead of a silent note. |

**Evidence requirements:**
- If this project ever moves to a multi-operator or shared/less-trusted-filesystem deployment, this decision must be revisited before that change ships.
- No incident or near-miss involving coordinated approved-snapshot tampering has occurred; if one does, refresh this decision immediately.

## 4. Consequences

**Rollback plan:**
Triggers:
- Multi-operator or shared/less-trusted deployment becomes a real requirement.
- An actual coordinated-tampering incident occurs.
Steps:
1. Design and implement a trusted integrity anchor external to the mutable approved directory (append-only ledger, OS-level write-once enforcement after install, or an external checksum store).
2. Add adversarial tests for coordinated content+hash tampering.
3. Update this decision's status and RVA-06's scope-pins entry accordingly.
Blast radius: publication-exporter's approved package only (FilesystemApprovedSnapshotWorkspace's validation logic); no change to candidate/translation/release adapters.

**Refresh triggers:**
- The deployment model changes from single-operator/same-UID to multi-operator or shared/less-trusted filesystem access.
- A future slice adds any external anchor (ledger, OS immutability, checksum service) for approved snapshots.
- Any real incident involving coordinated approved-snapshot content+hash tampering.

