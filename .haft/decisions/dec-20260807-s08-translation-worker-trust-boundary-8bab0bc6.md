---
id: dec-20260807-s08-translation-worker-trust-boundary-8bab0bc6
kind: DecisionRecord
version: 1
status: active
title: Narrow TRP-04's threat model: trust the same-UID translation worker process for now
mode: standard
created_at: 2026-08-07T09:00:02Z
updated_at: 2026-08-07T09:00:02Z
links:
  - ref: prob-20260807-eb1209e1
    type: based_on
---

# Narrow TRP-04's threat model: trust the same-UID translation worker process for now

## 1. Problem Frame

**Signal:** PrepareHandler (S03) always translates the whole source body unconditionally and installs the result without validation, without diffing against an approved Russian baseline, and without preserving the prior candidate on failure. It has no concept of job isolation/authentication. Slice S08 in openspec/implementation-plan.md requires: preparing a changed approved essay produces the complete normalized Russian diff (RVA-02) and a new validated English candidate (PCM-06, TRP-03); stale, failed, or cross-job worker output must preserve the previous valid English candidate (TRP-03, TRP-04) rather than corrupting it.

**Constraints:**
- No new production adapter beyond what job isolation/authentication genuinely requires (implementation-plan.md slice rule: at most one new boundary adapter)
- Real worker adapter change gets a focused contract test, not a rewrite
- Do not touch approval (mark-reviewed) or release/site generation behavior
- Preserve existing S03 first-publication candidate behavior (no approved baseline yet) unchanged
- In-memory job and candidate adapters must simulate success/failure/stale/competing-completion without sleeps or real processes

**Acceptance:** Given an approved essay snapshot and a changed source, preparing it (1) computes the complete normalized diff against the exact approved RU snapshot and surfaces it in the review plan, (2) validates the new English candidate for structural/identity/route-safety invariants (PCM-06) before installing it, (3) only installs the new candidate as one coherent RU+EN+references triple after validation succeeds, and (4) on translation failure, staleness, or a result belonging to a different job/source fingerprint, leaves the prior valid English candidate bytes unchanged and reports translation_failed/stale with diagnostics. Concurrent/competing job results are rejected before candidate installation. Approving the replacement and updating the live release are explicitly out of scope (still gated behind mark-reviewed / S09+). All 324 existing acceptance/unit tests remain green plus new S08 acceptance coverage; in-memory acceptance subset stays under 1 second.

## 2. Decision

**Selected:** Narrow TRP-04's threat model: trust the same-UID translation worker process for now

**Selection policy:** Prefer the option that (a) keeps S08 shippable now, (b) does not silently weaken a written SHALL requirement, and (c) matches this project's own established governance pattern (G1-G7 gates) for scope questions that a single implementation slice cannot and should not resolve unilaterally. Scope-narrowing-with-a-recorded-decision satisfies all three; the other two options each fail one.

**Why selected:** S08's final whole-branch review (gpt-5.6-sol, xhigh effort) found that ProcessTranslationWorker's job-directory confinement (path traversal, symlink/hard-link substitution, fingerprint authentication, bounded output-drain) is real and independently verified, but cannot close a residual same-UID TOCTOU gap: a worker process (or a surviving descendant) running as the same OS user as the exporter can mutate a same-length result file in place during the brief window between the exporter's identity check and its read, or a sibling job across process boundaries the in-process PublicationIdentity lock does not cover (separate CLI invocations, as the real Obsidian plugin performs one process per prepare call). TRP-04's requirement text is unqualified as written, so a strict reading blocks S08 indefinitely without either (a) real OS-level process isolation or (b) a cryptographically authenticated result-transfer protocol. Neither exists in this codebase and neither has an implementation-plan.md decision gate. The operator selected explicitly recording a scope-narrowing decision over blocking S08 indefinitely or silently parking the gap.


**Invariants:**
- The exporter process and the translation worker process always run as the same OS user (no privilege separation) until a future decision changes this.
- Job-directory path confinement (traversal, symlink, hard-link substitution, wrong job ID, wrong fingerprint) remains fully enforced regardless of this narrowing - only the same-UID in-place-mutation/TOCTOU sub-case is excluded from TRP-04's guarantee.
- The bounded output-drain fix (ProcessTranslationWorker no longer hangs indefinitely on a stdout-holding descendant) remains in force.

**Pre-conditions:**
- [ ] S08's path/identity confinement checks (TranslationJob fingerprint, JobWorkspace real-path/fileKey/link-count validation) are implemented and independently verified green.
- [ ] The final whole-branch review has explicitly identified and characterized the residual gap this decision narrows.

**Post-conditions:**
- [ ] openspec/changes/s08-reprepare-changed-essay/scope-pins.md records this narrowing against TRP-04.
- [ ] S08 can be archived and its Haft problem closed without further isolation work.
- [ ] A future slice/gate is the designated place to revisit full process isolation if the threat model changes.

**Admissibility:**
- NOT: Treating this decision as covering cross-tenant or multi-user deployments - it is scoped to a single-operator, single-UID deployment model only.
- NOT: Using this decision to justify skipping the path/identity confinement checks already implemented - those remain mandatory.
- NOT: Silently expanding this narrowing to other requirements (e.g. candidate-workspace atomicity) without a separate decision.

## 3. Rationale

**Counterargument:** TRP-04 was written as an unconditional SHALL specifically to reject concurrent stale/adversarial writers, and this decision concedes the exporter cannot actually meet that guarantee against a same-UID adversary — a security requirement is being weakened post hoc to let a slice ship, which is exactly the kind of rationalization Haft's evidence discipline exists to catch. If the "trusted worker" assumption is ever wrong (e.g. the Codex CLI binary itself is compromised, or a future contributor swaps in a less-trusted worker without revisiting this decision), a corrupted translation could reach an approved publication candidate undetected.

**Selected variant weakest link:** The refresh triggers rely on someone remembering to revisit this decision when the worker or deployment model changes - there is no automated enforcement (e.g. a runtime check that refuses to run if the configured worker is anything other than the known-trusted Codex invocation). If a future slice swaps `TranslationCommand` for a third-party worker without reading this decision, the narrowing silently persists past the point it was meant to cover.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| Narrow TRP-04's threat model: trust the same-UID translation worker process for now | **Selected** | S08's final whole-branch review (gpt-5.6-sol, xhigh effor... |
| Block S08 and implement full process isolation now | Rejected | Real OS-level isolation or a secret-bound result-transfer protocol is a substantial new infrastructure investment disproportionate to this slice's scope (implementation-plan.md's slice-splitting rule: at most one new production boundary adapter per slice, already used by TranslationJob/JobWorkspace). It would also preempt G5 (distribution/packaging) and G3 (worker protocol) without those gates having been revisited for this exact threat model. |
| Park silently in scope-pins.md without a decision record | Rejected | TRP-04's guarantee is directly and materially weakened by this gap - silently parking it would let a later slice or reviewer reasonably believe the requirement is fully met when it is not; Haft's governance model exists precisely to make this kind of scope narrowing auditable rather than implicit. |

**Evidence requirements:**
- If this project ever moves to a multi-user/multi-tenant deployment, or swaps the trusted Codex worker for a third-party/untrusted translation backend, this decision must be revisited before that change ships.
- No incident or near-miss involving same-UID result tampering has occurred; if one does, refresh this decision immediately.

## 4. Consequences

**Rollback plan:**
Triggers:
- Multi-tenant or untrusted-worker deployment becomes a real requirement.
- An actual same-UID tampering incident occurs.
Steps:
1. Design and implement OS-level process isolation (separate UID/sandbox/container per translation job) or a secret-bound authenticated result-transfer protocol.
2. Add adversarial tests for in-place same-length mutation and sibling-job mutation across separate CLI process invocations.
3. Update this decision's status and TRP-04's scope-pins entry accordingly.
Blast radius: publication-exporter's translation package only (ProcessTranslationWorker, JobWorkspace); no change to candidate/approved/release adapters.

**Refresh triggers:**
- G5 (distribution) is decided and changes the single-operator deployment assumption.
- The translation worker is changed from the trusted exporter-java-derived Codex invocation to a third-party or less-trusted backend.
- Any real incident involving a compromised or malicious translation-worker process.

