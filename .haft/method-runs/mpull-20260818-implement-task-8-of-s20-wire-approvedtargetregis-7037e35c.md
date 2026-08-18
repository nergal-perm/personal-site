---
id: mpull-20260818-implement-task-8-of-s20-wire-approvedtargetregis-7037e35c
kind: MethodRun
version: 2
status: addressed
title: Method pull: Implement Task 8 of S20: wire ApprovedTargetRegistry and OccurrenceMarkerResolve
mode: tactical
created_at: 2026-08-18T09:39:23Z
updated_at: 2026-08-18T09:42:29Z
---

# Method Run

- Status: closed
- Catalog: swe-core@1.0.0
- Task kind: feature
- Ceremony: medium — non-trivial code work
- Task: Implement Task 8 of S20: wire ApprovedTargetRegistry and OccurrenceMarkerResolver into InstallToSiteHandler with outside-in TDD

## Methods

### Behavior-first testing `behavior-first-testing`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:A.10, fpf:B.3

Why applies: task_kind=feature

Intent: Define the behavior being changed and verify it through the highest practical public boundary.

Hard gates:
- `public_behavior_evidence_recorded` (test_evidence/human_review): The closeout records a public behavior, integration, or API-level check for the changed behavior.

### Verification before completion `verification-before-completion`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:A.10, fpf:B.3, fpf:A.15

Why applies: task_kind=feature

Intent: Do not claim work is done until the relevant check has actually run or an explicit waiver is recorded.

Hard gates:
- `fresh_verification_before_completion` (test_evidence/deterministic): A relevant test, build, runtime check, or diff inspection is recorded before completion.

## Closeout

Changed files:
- publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java
- .superpowers/sdd/tasks/task-8-report.md

Gate results:
- `public_behavior_evidence_recorded`: satisfied
- `fresh_verification_before_completion`: satisfied

Verification: pass


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "closed_at": "2026-08-18T09:42:29Z",
  "closeout": {
    "changed_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java",
      ".superpowers/sdd/tasks/task-8-report.md"
    ],
    "closed_at": "2026-08-18T09:42:29Z",
    "gate_results": [
      {
        "evidence_refs": [
          ".superpowers/sdd/tasks/task-8-report.md#red",
          ".superpowers/sdd/tasks/task-8-report.md#green-focused"
        ],
        "gate_id": "public_behavior_evidence_recorded",
        "status": "satisfied"
      },
      {
        "evidence_refs": [
          ".superpowers/sdd/tasks/task-8-report.md#green-full-suite",
          "commit:1e413ca"
        ],
        "gate_id": "fresh_verification_before_completion",
        "status": "satisfied"
      }
    ],
    "verification": {
      "commands": [
        "cd publication-exporter \u0026\u0026 mvn -q -Dtest=InstallToSiteHandlerTest test",
        "cd publication-exporter \u0026\u0026 mvn -q test",
        "git diff --check"
      ],
      "output_ref": ".superpowers/sdd/tasks/task-8-report.md",
      "result": "pass"
    }
  },
  "deterministic_context": {},
  "id": "mpull-20260818-implement-task-8-of-s20-wire-approvedtargetregis-7037e35c",
  "methods": [
    {
      "anti_patterns": [
        "Only testing private helpers for a user-visible behavior change.",
        "Changing behavior under a refactor label."
      ],
      "hard_gates": [
        {
          "check_level": "human_review",
          "gate_id": "public_behavior_evidence_recorded",
          "gate_kind": "test_evidence",
          "pass_condition": "The closeout records a public behavior, integration, or API-level check for the changed behavior.",
          "required_evidence": [
            "e2e_test",
            "integration_test",
            "api_test",
            "runtime_check"
          ],
          "waiver": {
            "allowed": true,
            "requires_reason": true
          }
        }
      ],
      "id": "behavior-first-testing",
      "intent": "Define the behavior being changed and verify it through the highest practical public boundary.",
      "lifecycle": {
        "status": "current",
        "valid_from": "2026-06-25"
      },
      "procedure": [
        "Name the public behavior that changes.",
        "Add or identify a behavior-level check.",
        "Run it after implementation."
      ],
      "required_closeout": true,
      "required_evidence": [
        "e2e_test",
        "integration_test",
        "api_test"
      ],
      "soft_gates": [
        "Prefer E2E/API/integration evidence over unit-only proof when behavior crosses a boundary."
      ],
      "source_pattern_refs": [
        "fpf:A.10",
        "fpf:B.3"
      ],
      "source_posture": {
        "authority_boundary": "method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage",
        "normativity": "support_carrier_non_normative_fpf",
        "source_edition": "swe-core@1.0.0",
        "source_kind": "methodpack_card"
      },
      "title": "Behavior-first testing",
      "version": "1.0.0",
      "waiver": {
        "allowed": true,
        "requires_reason": true
      },
      "why_applies": "task_kind=feature"
    },
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
      "why_applies": "task_kind=feature"
    }
  ],
  "opened_at": "2026-08-18T09:39:23Z",
  "status": "closed",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "resolve_ref_markers_only_in_the_candidatesnapshot_passed_to_managedsiteinstaller_while_preserving_optimistic_concurrency_against_the_original_approved_planned_snapshot",
    "intended_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java"
    ],
    "normalized_task_kind": "feature",
    "task": "Implement Task 8 of S20: wire ApprovedTargetRegistry and OccurrenceMarkerResolver into InstallToSiteHandler with outside-in TDD",
    "user_scope_constraints": [
      "Do not modify exporter-java",
      "Optional over null everywhere",
      "Failing test before production code",
      "Do not delegate",
      "Run focused and full Maven tests",
      "Attempt git commit"
    ]
  }
}
haft:end -->
