---
id: dec-20260807-s10-provenance-attribution-scope-70bbc467
kind: DecisionRecord
version: 1
status: active
title: Narrow REL-03's tamper-detection scope: site-wide provenance recompute can be laundered by an unrelated recovery, inherited from S07's whole-tree design
mode: standard
created_at: 2026-08-07T14:07:39Z
updated_at: 2026-08-07T14:07:39Z
links:
  - ref: prob-20260807-0fc08447
    type: based_on
---

# Narrow REL-03's tamper-detection scope: site-wide provenance recompute can be laundered by an unrelated recovery, inherited from S07's whole-tree design

## 1. Problem Frame

**Signal:** S10's final whole-branch review found that FilesystemManagedSiteInstaller's recovery path recomputes SiteReleaseManifest fresh from the entire current payload tree (all publications' managed files, per S07's original whole-tree design) after restoring one publication's own interrupted swap. If a different publication's file was tampered with in the interval, that tampering is silently absorbed into the freshly-written provenance as validated, since the recompute has no way to attribute a mismatch specifically to the recovering publication versus unrelated content elsewhere in the tree. This pattern originates in S07 (SiteReleaseManifest.computeOver(...) has recomputed over the whole tree on every install since S07), but S10's recovery path makes it newly reachable and operationally relevant.

**Acceptance:** A decision is recorded narrowing REL-03's tamper-detection guarantee to exclude cross-publication tamper laundering during a single publication's recovery, with the S07 origin explicitly documented, so S10 can archive without overstating what REL-03 actually guarantees.

## 2. Decision

**Selected:** Narrow REL-03's tamper-detection scope: site-wide provenance recompute can be laundered by an unrelated recovery, inherited from S07's whole-tree design

**Selection policy:** Prefer the option that (a) keeps S10 shippable now, (b) does not silently overstate REL-03's actual guarantee, and (c) correctly attributes this to S07's original whole-tree provenance design rather than treating it as an S10-introduced defect requiring an S10-scoped fix. A per-publication attribution redesign is out of proportion for S10, which only replaces an existing single-generation install path.

**Why selected:** S10's final whole-branch review found that recovery for one publication's interrupted replace recomputes SiteReleaseManifest fresh from the ENTIRE current payload tree after restoring one publication's own interrupted swap. If a different publication's file was tampered with in the interval, that tampering is silently absorbed into the freshly-written provenance as validated. Investigation confirms this whole-tree-recompute-as-attestation pattern is not new to S10: SiteReleaseManifest.computeOver(...) has recomputed over the whole tree on every install since S07; S10 makes it newly reachable through the recovery path but does not introduce the underlying characteristic. A real fix requires per-publication provenance attribution, a REL-03/SiteReleaseManifest redesign spanning back to S06/S07, not scoped to S10.


**Invariants:**
- The per-file backup/restore and joint recovery-completion-marker mechanisms continue to correctly restore ONE coherent generation for the recovering publication's own files.
- check-content.mjs's existing gate continues to run and continues to catch a mismatch it can actually observe at build time.
- This narrowing applies to the single-operator, trusted-write-access deployment model already established by dec-20260807-s08-translation-worker-trust-boundary-8bab0bc6 and dec-20260807-s09-approved-snapshot-integrity-anchor-c04a83ac.

**Pre-conditions:**
- [ ] S10's per-file backup/restore and joint recovery-completion-marker fixes are implemented and independently verified.
- [ ] The final whole-branch review has explicitly identified and reproduced this gap, and confirmed it is inherited from S07's original SiteReleaseManifest design.

**Post-conditions:**
- [ ] openspec/changes/s10-replace-managed-release-safely/scope-pins.md records this narrowing against REL-03, explicitly noting the S07 origin.
- [ ] S10 can be archived and its Haft problem closed without a SiteReleaseManifest/provenance-attribution redesign.
- [ ] A future slice that revisits release-materialization's provenance model is the designated place to add per-publication attribution.

**Admissibility:**
- NOT: Treating this decision as covering a multi-operator or less-trusted-filesystem-access deployment model.
- NOT: Using this decision to justify skipping or weakening check-content.mjs's existing gate checks or SiteReleaseManifest's hashing.
- NOT: Silently expanding this narrowing to justify not attributing provenance per-publication in a future slice that specifically targets REL-03's provenance model.

## 3. Rationale

**Counterargument:** REL-03 was written as an unconditional SHALL specifically to make provenance/output tampering detectable, and this decision concedes that tampering to one publication can be silently laundered by a completely unrelated publication's routine recovery — a real security-relevant gap. An attacker or buggy process that corrupts one publication's managed file could have that corruption permanently absorbed into "validated" provenance the next time ANY publication is replaced or recovered, with no diagnostic ever surfaced.

**Selected variant weakest link:** This decision's framing ("S07-inherited, not S10-specific") could be used to indefinitely defer fixing a real gap by attributing it to prior slices each time it resurfaces, unless a future slice is explicitly designated to resolve it.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Narrow REL-03's tamper-detection scope: site-wide provenance recompute can be laundered by an unrelated recovery, inherited from S07's whole-tree design | **Selected** | S10's final whole-branch review found that recovery for o... |
| Block S10 and redesign provenance attribution now | Rejected | Per-publication provenance attribution is a genuine redesign of SiteReleaseManifest's whole-tree hashing model, which S06/S07 established and multiple already-archived slices depend on. Redesigning it as part of S10 would retroactively change already-shipped, already-reviewed S07 behavior for a gap S10 did not introduce. |
| Park silently without a decision record | Rejected | REL-03 and S10's own scope-pins currently describe tamper detection as fully realized for a replaced generation. Silently parking this gap would leave a materially incomplete guarantee standing without a record. |

**Evidence requirements:**
- If a real incident or credible near-miss involving cross-publication tamper laundering occurs, this decision must be revisited immediately.
- If a future slice touches SiteReleaseManifest for any other reason, it should re-evaluate whether per-publication attribution can be added at that point.

## 4. Consequences

**Rollback plan:**
Triggers:
- A real cross-publication tampering incident occurs.
- A future slice specifically targets REL-03's provenance model.
Steps:
1. Redesign SiteReleaseManifest/provenance to attribute hashes per-publication so recovery and gate checks can validate only the paths relevant to the operation in progress.
2. Add adversarial tests for cross-publication tamper laundering during recovery.
3. Update this decision's status and REL-03's scope-pins entry accordingly.
Blast radius: SiteReleaseManifest and both of its consumers (FilesystemManagedSiteInstaller from S07/S10, and check-content.mjs's site-side gate) — a genuine cross-cutting change.

**Refresh triggers:**
- A real incident or credible near-miss involving cross-publication tamper laundering.
- Any future slice that redesigns SiteReleaseManifest or REL-03's provenance model for other reasons.
- The deployment model changes from single-operator/trusted-write-access to something less trusted.

