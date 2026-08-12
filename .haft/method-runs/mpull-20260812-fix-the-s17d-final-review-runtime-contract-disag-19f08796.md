---
id: mpull-20260812-fix-the-s17d-final-review-runtime-contract-disag-19f08796
kind: MethodRun
version: 2
status: addressed
title: Method pull: Fix the S17d final-review runtime/contract disagreement for explicit empty optio
mode: tactical
created_at: 2026-08-12T17:19:26Z
updated_at: 2026-08-12T17:25:19Z
---

# Method Run

- Status: closed
- Catalog: swe-core@1.0.0
- Task kind: bugfix
- Ceremony: medium — non-trivial code work
- Task: Fix the S17d final-review runtime/contract disagreement for explicit empty optional concept list fields and add a shared regression fixture

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
- publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ConceptPublicationKindFixtures.java
- .superpowers/sdd/tasks/final-review-fix.md

Gate results:
- `root_cause_named_before_fix`: satisfied
- `fresh_verification_before_completion`: satisfied

Verification: pass


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "closed_at": "2026-08-12T17:25:19Z",
  "closeout": {
    "changed_files": [
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ConceptPublicationKindFixtures.java",
      ".superpowers/sdd/tasks/final-review-fix.md"
    ],
    "closed_at": "2026-08-12T17:25:19Z",
    "gate_results": [
      {
        "evidence_refs": [
          ".superpowers/sdd/tasks/final-review-fix.md#root-cause",
          "RED: PublicationContractConformanceTest rejected conceptWithExplicitEmptyRelationsAndExamples while runtime baseline accepted explicit empty lists"
        ],
        "gate_id": "root_cause_named_before_fix",
        "status": "satisfied"
      },
      {
        "evidence_refs": [
          "mvn -f publication-exporter/pom.xml test -Dtest=PublicationContractConformanceTest,ConceptPublicationKindTest,FieldContractTest: 64 passed",
          "mvn -f publication-exporter/pom.xml test: 726 passed"
        ],
        "gate_id": "fresh_verification_before_completion",
        "status": "satisfied"
      }
    ],
    "verification": {
      "commands": [
        "mvn -f publication-exporter/pom.xml test -Dtest=PublicationContractConformanceTest,ConceptPublicationKindTest,FieldContractTest",
        "mvn -f publication-exporter/pom.xml test",
        "GRAPHIFY_MAX_WORKERS=1 graphify update .",
        "git diff --check"
      ],
      "output_ref": ".superpowers/sdd/tasks/final-review-fix.md",
      "result": "pass"
    }
  },
  "deterministic_context": {},
  "id": "mpull-20260812-fix-the-s17d-final-review-runtime-contract-disag-19f08796",
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
  "opened_at": "2026-08-12T17:19:26Z",
  "status": "closed",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "treat_empty_list_as_absent_only_in_optional_list_field_presence_checks_while_preserving_required_list_rejection",
    "intended_files": [
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ConceptPublicationKindFixtures.java",
      ".superpowers/sdd/tasks/final-review-fix.md"
    ],
    "normalized_task_kind": "bugfix",
    "risk_signals": [
      {
        "evidence": "STRING_LIST and STRUCTURED_LIST satisfiers are shared with required fields such as bibliography/book authors",
        "id": "required_list_regression",
        "source": "operator"
      }
    ],
    "task": "Fix the S17d final-review runtime/contract disagreement for explicit empty optional concept list fields and add a shared regression fixture",
    "user_scope_constraints": [
      "Exactly one final-review fix dispatch and one scoped re-review; no second fix wave",
      "Do not weaken required list validation",
      "Run focused Maven suite and full Maven suite",
      "Commit on master when green"
    ]
  }
}
haft:end -->
