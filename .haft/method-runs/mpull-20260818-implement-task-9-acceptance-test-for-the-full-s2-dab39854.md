---
id: mpull-20260818-implement-task-9-acceptance-test-for-the-full-s2-dab39854
kind: MethodRun
version: 1
status: active
title: Method pull: Implement Task 9 acceptance test for the full S20 late-bound target activation s
mode: tactical
created_at: 2026-08-18T09:52:46Z
updated_at: 2026-08-18T09:52:46Z
---

# Method Run

- Status: open
- Catalog: swe-core@1.0.0
- Task kind: test_only_acceptance_implementation
- Ceremony: medium — non-trivial code work
- Task: Implement Task 9 acceptance test for the full S20 late-bound target activation state sequence through real PrepareHandler, MarkReviewedHandler, and BuildFromReviewHandler

## Methods

### Verification before completion `verification-before-completion`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:A.10, fpf:B.3, fpf:A.15

Why applies: fallback for unmatched non-trivial code work

Intent: Do not claim work is done until the relevant check has actually run or an explicit waiver is recorded.

Hard gates:
- `fresh_verification_before_completion` (test_evidence/deterministic): A relevant test, build, runtime check, or diff inspection is recorded before completion.


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "deterministic_context": {},
  "id": "mpull-20260818-implement-task-9-acceptance-test-for-the-full-s2-dab39854",
  "methods": [
    {
      "anti_patterns": [
        "Completion claim with no fresh evidence.",
        "Assuming a check passed because the change is small."
      ],
      "hard_gates": [
        {
          "check_level": "deterministic",
          "gate_id": "fresh_verification_before_completion",
          "gate_kind": "test_evidence",
          "pass_condition": "A relevant test, build, runtime check, or diff inspection is recorded before completion.",
          "required_evidence": [
            "command_output",
            "runtime_check",
            "diff_inspection"
          ],
          "waiver": {
            "allowed": true,
            "requires_reason": true
          }
        }
      ],
      "id": "verification-before-completion",
      "intent": "Do not claim work is done until the relevant check has actually run or an explicit waiver is recorded.",
      "lifecycle": {
        "status": "current",
        "valid_from": "2026-06-25"
      },
      "procedure": [
        "Identify the narrowest relevant verification before editing.",
        "Run that verification after the change.",
        "Record the command/result or explicit waiver before claiming completion."
      ],
      "required_closeout": true,
      "required_evidence": [
        "command_output",
        "runtime_check",
        "diff_inspection"
      ],
      "source_pattern_refs": [
        "fpf:A.10",
        "fpf:B.3",
        "fpf:A.15"
      ],
      "source_posture": {
        "authority_boundary": "method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage",
        "normativity": "support_carrier_non_normative_fpf",
        "source_edition": "swe-core@1.0.0",
        "source_kind": "methodpack_card"
      },
      "title": "Verification before completion",
      "version": "1.0.0",
      "waiver": {
        "allowed": true,
        "requires_reason": true
      },
      "why_applies": "fallback for unmatched non-trivial code work"
    }
  ],
  "opened_at": "2026-08-18T09:52:46Z",
  "status": "open",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "add_one_system_boundary_acceptance_test_without_production_changes;_verify_focused_and_full_maven_suites;_preserve_approved_referrer_snapshot_across_target_activation_and_deactivation",
    "intended_files": [
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java",
      "openspec/changes/s20-late-bound-target-activation/tasks.md",
      ".superpowers/sdd/tasks/task-9-report.md"
    ],
    "normalized_task_kind": "test_only_acceptance_implementation",
    "risk_signals": [
      {
        "evidence": "Real PrepareHandler to MarkReviewedHandler to BuildFromReviewHandler flow",
        "id": "system_boundary_acceptance",
        "source": "task-9-brief"
      },
      {
        "evidence": "Referrer approved ReferenceMap hash must remain unchanged across target approval changes",
        "id": "approved_immutability",
        "source": "SEM-04"
      }
    ],
    "task": "Implement Task 9 acceptance test for the full S20 late-bound target activation state sequence through real PrepareHandler, MarkReviewedHandler, and BuildFromReviewHandler",
    "user_scope_constraints": [
      "Do not modify exporter-java",
      "No production changes; stop and report if a real wiring gap requires one",
      "Reuse existing nullable-adapter wiring style",
      "Use Optional instead of null in helper code",
      "Run focused test and full mvn -q test",
      "Attempt git commit and report exact permission errors"
    ]
  }
}
haft:end -->
