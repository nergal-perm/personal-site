---
id: mpull-20260812-s17b-final-review-fix-wave-make-structureddata-p-2c58a97c
kind: MethodRun
version: 1
status: active
title: Method pull: S17b final review fix wave: make structuredData participate in approved-baseline
mode: tactical
created_at: 2026-08-12T04:04:27Z
updated_at: 2026-08-12T04:04:27Z
---

# Method Run

- Status: open
- Catalog: swe-core@1.0.0
- Task kind: bugfix
- Ceremony: medium — non-trivial code work
- Task: S17b final review fix wave: make structuredData participate in approved-baseline freshness, validate claim sources against the site's transport schema shape, correct stale OpenSpec carrier wording, add regressions, verify, report, and commit.

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


<!-- haft:structured_data
{
  "catalog_id": "swe-core",
  "catalog_version": "1.0.0",
  "deterministic_context": {},
  "id": "mpull-20260812-s17b-final-review-fix-wave-make-structureddata-p-2c58a97c",
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
  "opened_at": "2026-08-12T04:04:27Z",
  "status": "open",
  "task_signature": {
    "ceremony": "medium",
    "ceremony_reason": "non-trivial code work",
    "change_intent": "narrow_corrective_implementation_against_base_1bbc1df;_no_exporter_java_or_release_package_changes.",
    "intended_files": [
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java",
      "publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java",
      "publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java",
      "openspec/changes/s17b-blog-claim-kind/design.md",
      "openspec/changes/s17b-blog-claim-kind/tasks.md",
      ".superpowers/sdd/s17b-blog-claim-kind/final-fix-report.md"
    ],
    "normalized_task_kind": "bugfix",
    "risk_signals": [
      {
        "evidence": "structuredData-only claim metadata edits currently reuse or mirror an approved snapshot",
        "id": "candidate_freshness",
        "source": "final review"
      },
      {
        "evidence": "claim sources may contain malformed or undeclared shape before site install",
        "id": "site_schema_transport",
        "source": "final review"
      }
    ],
    "task": "S17b final review fix wave: make structuredData participate in approved-baseline freshness, validate claim sources against the site's transport schema shape, correct stale OpenSpec carrier wording, add regressions, verify, report, and commit.",
    "user_scope_constraints": [
      "Make only changes needed for the supplied final-review findings.",
      "Do not alter exporter-java.",
      "Do not alter the release package.",
      "Run focused and full Maven tests plus git diff --check.",
      "Commit the fix wave and append the requested report."
    ]
  }
}
haft:end -->
