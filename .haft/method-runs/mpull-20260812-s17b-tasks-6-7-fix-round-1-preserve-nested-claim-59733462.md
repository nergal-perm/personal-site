---
id: mpull-20260812-s17b-tasks-6-7-fix-round-1-preserve-nested-claim-59733462
kind: MethodRun
version: 2
status: addressed
title: Method pull: S17b Tasks 6-7 fix round 1: preserve nested claim sources as opaque YAML, reject
mode: tactical
created_at: 2026-08-12T02:53:14Z
updated_at: 2026-08-12T03:10:13Z
---

# Method Run

- Status: closed
- Catalog: swe-core@1.0.0
- Task kind: bugfix_refactor
- Ceremony: medium — non-trivial code work
- Task: S17b Tasks 6-7 fix round 1: preserve nested claim sources as opaque YAML, reject malformed structured metadata, validate relationship labels, and refactor MarkdownNote parsing into composed methods

## Methods

### Verification before completion `verification-before-completion`

Source posture: source_kind=methodpack_card · source_edition=swe-core@1.0.0 · normativity=support_carrier_non_normative_fpf · authority_boundary=method_cards_route_work_and_closeout_gates; they do not define normative FPF source material, binding decisions, evidence truth, or gate passage

Source pattern refs: fpf:A.10, fpf:B.3, fpf:A.15

Why applies: fallback for unmatched non-trivial code work

Intent: Do not claim work is done until the relevant check has actually run or an explicit waiver is recorded.

Hard gates:
- `fresh_verification_before_completion` (test_evidence/deterministic): A relevant test, build, runtime check, or diff inspection is recorded before completion.

## Closeout

Changed files:
- publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java
- publication-exporter/src/main/java/dev/eugene/publicationexporter/note/MarkdownNote.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java
- publication-exporter/src/test/java/dev/eugene/publicationexporter/note/MarkdownNoteTest.java
- .superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md

Gate results:
- `fresh_verification_before_completion`: satisfied

Verification: pass


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "closed_at": "2026-08-12T03:10:13Z",
  "closeout": {
    "changed_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/note/MarkdownNote.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/note/MarkdownNoteTest.java",
      ".superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md"
    ],
    "closed_at": "2026-08-12T03:10:13Z",
    "gate_results": [
      {
        "evidence_refs": [
          "command:mvn -q test -Dtest=MarkdownNoteTest,ClaimPublicationKindTest,BlogClaimAcceptanceTest exit=0",
          "command:mvn -q test exit=0 tests=615 failures=0 errors=0 skipped=0",
          "command:git diff --check exit=0"
        ],
        "gate_id": "fresh_verification_before_completion",
        "status": "satisfied"
      }
    ],
    "verification": {
      "commands": [
        "cd publication-exporter \u0026\u0026 mvn -q test -Dtest=MarkdownNoteTest,ClaimPublicationKindTest,BlogClaimAcceptanceTest",
        "cd publication-exporter \u0026\u0026 mvn -q test",
        "git diff --check"
      ],
      "output_ref": ".superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md#fix-round-1--nested-claim-metadata-and-parser-composition",
      "result": "pass"
    }
  },
  "deterministic_context": {},
  "id": "mpull-20260812-s17b-tasks-6-7-fix-round-1-preserve-nested-claim-59733462",
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
  "opened_at": "2026-08-12T02:53:14Z",
  "status": "closed",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "address_only_reviewer_findings_for_s17b_tasks_6_7_without_task_8_contract_conformance,_exporter_java,_semantic_resolution,_translation,_or_production_release_package_changes",
    "intended_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/note/MarkdownNote.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/note/MarkdownNoteTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java",
      ".superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md"
    ],
    "normalized_task_kind": "bugfix_refactor",
    "risk_signals": [
      {
        "evidence": "Nested source mappings and sequences are currently flattened or rejected",
        "id": "yaml_structure_loss",
        "source": "review finding"
      },
      {
        "evidence": "Non-list relationship/sources metadata may be treated as empty",
        "id": "silent_malformed_acceptance",
        "source": "review finding"
      },
      {
        "evidence": "Relationship entries can currently emit arbitrary raw YAML keys",
        "id": "yaml_key_injection",
        "source": "review finding"
      }
    ],
    "task": "S17b Tasks 6-7 fix round 1: preserve nested claim sources as opaque YAML, reject malformed structured metadata, validate relationship labels, and refactor MarkdownNote parsing into composed methods",
    "user_scope_constraints": [
      "No Task 8 contract-conformance work",
      "No exporter-java changes",
      "No production release package changes",
      "Do not alter semantic resolution or translation",
      "Commit after focused tests, full mvn -q test, and git diff --check"
    ]
  }
}
haft:end -->
