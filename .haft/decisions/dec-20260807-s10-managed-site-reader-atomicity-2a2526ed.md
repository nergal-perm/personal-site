---
id: dec-20260807-s10-managed-site-reader-atomicity-2a2526ed
kind: DecisionRecord
version: 1
status: active
title: Narrow REL-05's atomicity guarantee: atomic with respect to other installers only, not to all external readers
mode: standard
created_at: 2026-08-07T14:06:00Z
updated_at: 2026-08-07T14:06:00Z
links:
  - ref: prob-20260807-6cc11949
    type: based_on
---

# Narrow REL-05's atomicity guarantee: atomic with respect to other installers only, not to all external readers

## 1. Problem Frame

**Signal:** FilesystemManagedSiteInstaller (S07) is create-only: install() calls rejectIfAlreadyInstalled(...) and throws SiteAlreadyInstalledException whenever the RU/EN markdown destinations already exist. It has no replace, no input-drift guard between planning and commit, and no provenance/output tamper detection beyond what the existing site-content gate checks structurally. Slice S10 in openspec/implementation-plan.md requires: materializing a newly approved snapshot replaces the prior managed generation; input drift, output tampering, or an injected interruption leaves or recovers one complete verified generation. REL-03 (tamper detection), REL-04 (concurrent input guard), and REL-05 (replace/recover) are already fully specified in openspec/specs/release-materialization/spec.md — including a "Same approved state is built twice" determinism scenario, a "Provenance or output is tampered with" scenario, an "Input changes concurrently" scenario, and an "Installation is interrupted" recovery scenario — all explicitly marked "not yet applicable"/"reachable once S10 exists" in S07's own scope-pins.md. This is evidence S10 is a realization slice (implementing already-written scenarios), not a requirement-text change, but that must be confirmed via the functional collaborative-design pass, not assumed.

**Constraints:**
- No new production adapter beyond what replace/recovery genuinely requires (implementation-plan.md slice rule: at most one new boundary adapter) — reuse ManagedSiteInstaller's existing port
- Reuse the exact backup/restore-with-durable-recovery pattern proven twice now (FilesystemCandidateWorkspace in S08, FilesystemApprovedSnapshotWorkspace in S09) rather than inventing a third variant
- Reuse the real OS advisory FileChannel.tryLock() cross-process locking pattern from S09's final fix (not the fragile createFile+PID approach the current FilesystemManagedSiteInstaller.acquireInstallationLock still uses) if this slice touches locking at all
- Do not touch semantic target activation (S20's job), additional content kinds (S17's job), or legacy migration (S21+'s job)
- Two-release-generation acceptance coverage must run through the in-memory adapter first, then the identical shared contract against the real filesystem adapter

**Acceptance:** Given an already-installed managed generation for a publication and a newly approved snapshot ready to release, build-from-review/install-to-site replaces the prior managed RU/EN markdown files and provenance record atomically as one coherent generation — never exposing a mixed old/new generation to a concurrent reader. If a declared release input (selected approved snapshot hashes, target-selection state) changes between planning and commit, the release is blocked and existing live site trees remain unchanged. If staged output or provenance is tampered with, the site-content gate blocks with a provenance mismatch. An interruption during replacement recovers deterministically to exactly the old complete generation or the new complete generation, reported rather than silently guessed, on the next inspection or retry. Concurrent replacement attempts for the same publication are serialized so no interleaved/partial write is ever visible. Building the same approved state twice produces identical managed content and normalized provenance. All existing acceptance/unit tests remain green plus new S10 acceptance coverage exercising two release generations through the in-memory adapter, then the same contract against the real filesystem adapter.

## 2. Decision

**Selected:** Narrow REL-05's atomicity guarantee: atomic with respect to other installers only, not to all external readers

**Selection policy:** Prefer the option that (a) keeps S10 shippable now with its real, substantial improvement intact (the multi-installer race, the mixed-generation recovery bug, and the recovery-strands-itself bug are all genuinely fixed), (b) does not silently overstate what REL-05's atomicity guarantee actually covers, and (c) matches this project's own established governance pattern for scope questions a single implementation slice cannot resolve unilaterally (mirrors dec-20260807-s08-translation-worker-trust-boundary-8bab0bc6 and dec-20260807-s09-approved-snapshot-integrity-anchor-c04a83ac). Scope-narrowing-with-a-recorded-decision satisfies all three; a symlink-indirection redesign is disproportionate architecture work for this slice and touches site content-loading conventions beyond the exporter's own boundary.

**Why selected:** S10's final whole-branch review (gpt-5.6-sol, xhigh) found that FilesystemManagedSiteInstaller commits a managed generation across three separately-visible filesystem paths (ru.md, en.md, release-provenance.json), each moved atomically on its own but with no coordination across the three beyond the installer's own FileChannel.tryLock() — which only excludes other installers, not external readers. An external reader (Astro's build process, site/scripts/check-content.mjs, or a plain filesystem walk) scheduled between any two of the three moves can observe a torn intermediate generation (e.g. new RU paired with old EN and old provenance). Closing this fully requires either a single-file/single-directory publication boundary (incompatible with Astro's fixed src/content/<collection>/<locale>/<id>.md path convention, which the site already depends on) or a symlink-swap indirection layer that repoints atomically (a redesign of how Astro and check-content.mjs locate content, spanning the site project's own conventions, not just the exporter). Neither is proportionate to this slice.


**Invariants:**
- The FileChannel.tryLock() cross-process lock continues to fully serialize concurrent install-to-site invocations against each other (already implemented and tested) — this decision narrows only the reader-atomicity guarantee, not installer-to-installer exclusion.
- Recovery continues to restore to one complete, self-consistent generation on the NEXT install-to-site call after an interruption (already implemented and tested) — this decision is about a live reader observing an in-progress commit, not about the post-recovery end state.
- Only a single-operator deployment where site builds are not triggered concurrently with install-to-site is covered by this narrowing.

**Pre-conditions:**
- [ ] Installer-to-installer serialization (FileChannel.tryLock()), per-file backup/restore, and the joint provenance-vs-tree recovery policy are implemented and independently verified.
- [ ] The final whole-branch review has explicitly identified and reproduced the reader-atomicity gap this decision narrows.

**Post-conditions:**
- [ ] openspec/changes/s10-replace-managed-release-safely/scope-pins.md records this narrowing against REL-05.
- [ ] S10 can be archived and its Haft problem closed without building reader-side indirection now.
- [ ] A future slice/gate is the designated place to add symlink-swap indirection or an equivalent mechanism if the deployment/CI model changes to run builds concurrently with installs.

**Admissibility:**
- NOT: Treating this decision as covering a CI/deploy pipeline that runs Astro builds concurrently with install-to-site.
- NOT: Using this decision to justify skipping or weakening the existing FileChannel lock, per-file backup/restore, or recovery mechanisms.
- NOT: Silently expanding this narrowing to cover a genuinely concurrent multi-operator deployment model.

## 3. Rationale

**Counterargument:** REL-05 was written as an unconditional guarantee specifically so that no workflow ever observes a mixed old/new generation, and this decision concedes the exporter cannot actually deliver that against an unlocked reader — exactly the scenario a real Astro build or the content gate running concurrently with an install-to-site call would hit. If an operator ever triggers a site build at the same moment as a replace (plausible in an automated CI/deploy pipeline, not just a manual double-click), the build could read torn content and either fail unpredictably or, worse, publish a torn generation if the gate doesn't catch the specific torn combination it happens to see.

**Selected variant weakest link:** Nothing enforces this narrowing at runtime — there is no check that warns or refuses to proceed if a build pipeline is ever wired to run concurrently with install-to-site. The refresh trigger relies entirely on someone remembering this decision when the deployment/CI pipeline changes to run builds and installs concurrently, exactly like S08 and S09's analogous decisions.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Narrow REL-05's atomicity guarantee: atomic with respect to other installers only, not to all external readers | **Selected** | S10's final whole-branch review (gpt-5.6-sol, xhigh) foun... |
| Block S10 and build symlink-swap indirection now | Rejected | This redesigns how Astro/check-content.mjs read managed content, a site-project-wide convention change, not an exporter-internal one. It has no design pass, no decision gate in openspec/implementation-plan.md, and would need its own collaborative-design cycle before implementation — disproportionate scope for this slice given the installer-vs-installer race, the mixed-generation bug, and the recovery-strands-itself bug are all now genuinely closed. |
| Park silently without a decision record | Rejected | REL-05's requirement text and this slice's own proposal.md both currently claim full 'one coherent generation, never observes a mixed old/new snapshot' atomicity. Silently parking this gap would leave that claim standing for a future reviewer or slice to reasonably (and incorrectly) rely on. Haft's governance model exists precisely to make this kind of scope narrowing auditable, matching why S08 and S09 both recorded analogous decisions instead of silent notes. |

**Evidence requirements:**
- If the deployment pipeline is ever changed to trigger Astro builds or check-content.mjs runs concurrently with install-to-site (rather than sequentially, as today), this decision must be revisited before that change ships.
- No incident or near-miss involving a torn-generation read has occurred; if one does, refresh this decision immediately.

## 4. Consequences

**Rollback plan:**
Triggers:
- Concurrent build/install pipeline becomes a real requirement.
- An actual torn-read incident occurs.
Steps:
1. Design and implement a reader-atomic publication mechanism (symlink-swap indirection repointed atomically at commit, or an equivalent single-boundary publication scheme) as its own collaborative-design cycle, since it spans site-project conventions beyond the exporter.
2. Add adversarial tests for a reader scheduled at each of the three commit-path boundaries.
3. Update this decision's status and REL-05's scope-pins entry accordingly.
Blast radius: publication-exporter's site package (FilesystemManagedSiteInstaller) plus potentially site/ content-loading conventions if indirection is chosen; no change to approved/candidate/translation adapters.

**Refresh triggers:**
- The deployment/CI pipeline changes to run site builds concurrently with install-to-site rather than sequentially.
- A future slice adds symlink-swap indirection or an equivalent reader-atomicity mechanism.
- Any real incident involving a build or gate check observing a torn managed generation.

