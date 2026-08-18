---
id: mpull-20260818-apply-the-six-s21-final-review-fixes-from-final-74f9417b
kind: MethodRun
version: 2
status: addressed
title: Method pull: Apply the six S21 final-review fixes from final-fix-brief.md using TDD and commi
mode: tactical
created_at: 2026-08-18T14:01:50Z
updated_at: 2026-08-18T14:14:58Z
---

# Method Run

- Status: closed
- Catalog: swe-core@1.0.0
- Task kind: bugfix
- Ceremony: medium — non-trivial code work
- Task: Apply the six S21 final-review fixes from final-fix-brief.md using TDD and commit them on master

## Methods

### Systematic debugging before fix `systematic-debugging-before-fix`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:B.5.2, fpf:A.10

Why applies: task_kind=bugfix

Intent: Avoid shotgun edits: reproduce or explain the failure, rank hypotheses, then patch the evidenced cause.

Hard gates:
- `root_cause_named_before_fix` (debug_evidence/human_review): The closeout names the root cause or the best evidenced hypothesis before presenting the patch as a fix.

### Verification before completion `verification-before-completion`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:A.10, fpf:B.3, fpf:A.15

Why applies: task_kind=bugfix

Intent: Do not claim work is done until the relevant check has actually run or an explicit waiver is recorded.

Hard gates:
- `fresh_verification_before_completion` (test_evidence/deterministic): A relevant test, build, runtime check, or diff inspection is recorded before completion.

## Closeout

Changed files:
- .superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-report.md
- openspec/changes/s21-read-only-legacy-diagnosis/design.md
- openspec/changes/s21-read-only-legacy-diagnosis/proposal.md
- openspec/changes/s21-read-only-legacy-diagnosis/specs/legacy-transition/spec.md
- openspec/specs/legacy-transition/spec.md
- publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java
- publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java
- publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java
- publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java
- publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/AlbumAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/BibliographyBookAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/ConceptAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/CuratedPageAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStoreTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/BlogNoteAcceptanceTest.java

Gate results:
- `root_cause_named_before_fix`: satisfied
- `fresh_verification_before_completion`: satisfied

Verification: pass


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "closed_at": "2026-08-18T14:14:58Z",
  "closeout": {
    "changed_files": [
      ".superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-report.md",
      "openspec/changes/s21-read-only-legacy-diagnosis/design.md",
      "openspec/changes/s21-read-only-legacy-diagnosis/proposal.md",
      "openspec/changes/s21-read-only-legacy-diagnosis/specs/legacy-transition/spec.md",
      "openspec/specs/legacy-transition/spec.md",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/AlbumAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/BibliographyBookAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/ConceptAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/CuratedPageAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStoreTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/BlogNoteAcceptanceTest.java"
    ],
    "closed_at": "2026-08-18T14:14:58Z",
    "gate_results": [
      {
        "evidence_refs": [
          ".superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-brief.md",
          ".superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-report.md"
        ],
        "gate_id": "root_cause_named_before_fix",
        "status": "satisfied"
      },
      {
        "evidence_refs": [
          "publication-exporter/target/surefire-reports",
          "final mvn -q test exit code 0 with 959 fresh tests"
        ],
        "gate_id": "fresh_verification_before_completion",
        "status": "satisfied"
      }
    ],
    "verification": {
      "commands": [
        "cd publication-exporter \u0026\u0026 mvn -q test",
        "git diff --check"
      ],
      "output_ref": ".superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-report.md",
      "result": "pass"
    }
  },
  "deterministic_context": {},
  "id": "mpull-20260818-apply-the-six-s21-final-review-fixes-from-final-74f9417b",
  "methods": [
    {
      "anti_patterns": [
        "Trying plausible fixes without reproducing or explaining the failure.",
        "Editing unrelated files during a narrow bugfix."
      ],
      "hard_gates": [
        {
          "check_level": "human_review",
          "gate_id": "root_cause_named_before_fix",
          "gate_kind": "debug_evidence",
          "pass_condition": "The closeout names the root cause or the best evidenced hypothesis before presenting the patch as a fix.",
          "required_evidence": [
            "reproduction",
            "test_ref",
            "log_ref",
            "trace_ref"
          ],
          "waiver": {
            "allowed": true,
            "requires_reason": true
          }
        }
      ],
      "id": "systematic-debugging-before-fix",
      "intent": "Avoid shotgun edits: reproduce or explain the failure, rank hypotheses, then patch the evidenced cause.",
      "lifecycle": {
        "status": "current",
        "valid_from": "2026-06-25"
      },
      "procedure": [
        "State the observed failure.",
        "Name the strongest root-cause hypothesis.",
        "Patch only after evidence supports the hypothesis."
      ],
      "required_closeout": true,
      "required_evidence": [
        "reproduction",
        "test_ref",
        "log_ref"
      ],
      "source_pattern_refs": [
        "fpf:B.5.2",
        "fpf:A.10"
      ],
      "source_posture": {
        "authority_boundary": "method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage",
        "normativity": "support_carrier_non_normative_fpf",
        "source_edition": "swe-core@1.0.0",
        "source_kind": "methodpack_card"
      },
      "title": "Systematic debugging before fix",
      "version": "1.0.0",
      "waiver": {
        "allowed": true,
        "requires_reason": true
      },
      "why_applies": "task_kind=bugfix"
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
      "why_applies": "task_kind=bugfix"
    }
  ],
  "opened_at": "2026-08-18T14:01:50Z",
  "status": "closed",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "add_legacy_inventory_cli,_guard_mark_reviewed,_correct_s21_docs/spec_scope,_prove_non_mutating_ordinary_inventory,_and_enforce_strict_activation_marker_json_node_types",
    "intended_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/cli",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy",
      "openspec/changes/s21-read-only-legacy-diagnosis/proposal.md",
      "openspec/changes/s21-read-only-legacy-diagnosis/design.md",
      "openspec/changes/s21-read-only-legacy-diagnosis/specs/legacy-transition/spec.md",
      "openspec/specs/legacy-transition/spec.md",
      ".superpowers/sdd/s21-read-only-legacy-diagnosis/final-fix-report.md"
    ],
    "normalized_task_kind": "bugfix",
    "task": "Apply the six S21 final-review fixes from final-fix-brief.md using TDD and commit them on master",
    "user_scope_constraints": [
      "Do not touch exporter-java",
      "Do not run graphify update",
      "Work on master",
      "Use additive constructors delegating to createNull",
      "Run mvn -q test after each change",
      "Commit via normal git hooks"
    ]
  }
}
haft:end -->
