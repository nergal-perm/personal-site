---
id: prob-20260818-operator-authorized-s23-implementation-following-05ae6357
kind: ProblemCard
version: 1
status: closed
title: S23 conditional migration apply and recovery
mode: standard
created_at: 2026-08-18T16:48:12Z
updated_at: 2026-08-18T22:36:32Z
---

# S23 conditional migration apply and recovery

## Signal

The selected in-place legacy migration path has a validated human decision file but no implementation that can transform legacy review state atomically enough to preserve approved translations, recover an interruption deterministically, or exclude a concurrent semantic mutation.

## Problem Type

synthesis

## Problem Profile

thin

## P2W Readiness

p2w_candidate

## Why Now

G6 selected in-place migration specifically to retain existing translations and approvals; S21 and S22 now provide activation and decision-validation prerequisites.

## Scope

publication-exporter legacy migration adapters, state machine, operation lock, journal, activation integrity gate, and their in-memory and filesystem tests.

## Acceptance Probe

A fresh, complete human decision set can migrate one fixture to a coherent current generation; injected interruption recovers by explicitly chosen roll-forward or rollback; a competing semantic operation is rejected before mutation; incomplete activation fails closed.

## Constraints

- Work directly on master in publication-exporter.
- Preserve existing translations and approvals through the selected in-place migration path.
- Do not automate apply or accept generated drafts as approval.
- Do not rehearse a real vault, approve content, build, or deploy.

## Acceptance

MIG-04 and MIG-05 complete activation are implemented with an in-memory state-machine proof and filesystem implementation of the same contract.

## Blast Radius

Legacy migration workflow and semantic-state admission only; normal greenfield publication paths remain independent.

## Reversibility

medium

## Spec Fit (Advisory)

State: spec_gap

Next expected action: draft_section

| Variant | State | SpecSections | Next action |
|---------|-------|--------------|-------------|
| probe | spec_gap | - | draft_section |


## Related History

- [decision] **Narrow REL-05's atomicity guarantee: atomic with respect to other installers only, not to all external readers** `dec-20260807-s10-managed-site-reader-atomicity-2a2526ed`
- [problem] **Wire up the real semantic link resolver end-to-end** `prob-20260802-1803dd18`

<!-- haft:structured_data
{
  "acceptance": "MIG-04 and MIG-05 complete activation are implemented with an in-memory state-machine proof and filesystem implementation of the same contract.",
  "blast_radius": "Legacy migration workflow and semantic-state admission only; normal greenfield publication paths remain independent.",
  "constraints": [
    "Work directly on master in publication-exporter.",
    "Preserve existing translations and approvals through the selected in-place migration path.",
    "Do not automate apply or accept generated drafts as approval.",
    "Do not rehearse a real vault, approve content, build, or deploy."
  ],
  "problem_type": "synthesis",
  "profile": {
    "acceptance_probe": "A fresh, complete human decision set can migrate one fixture to a coherent current generation; injected interruption recovers by explicitly chosen roll-forward or rollback; a competing semantic operation is rejected before mutation; incomplete activation fails closed.",
    "blockers": [
      "problem_profile is not deep",
      "freshness_disposition missing"
    ],
    "boundary_status": "explicit",
    "level": "thin",
    "readiness": "p2w_candidate",
    "scope": "publication-exporter legacy migration adapters, state machine, operation lock, journal, activation integrity gate, and their in-memory and filesystem tests.",
    "source_kind": "observed_problem",
    "why_now": "G6 selected in-place migration specifically to retain existing translations and approvals; S21 and S22 now provide activation and decision-validation prerequisites."
  },
  "reversibility": "medium",
  "semantic": {
    "carrier_binding": {
      "carrier_kind": "markdown",
      "carrier_ref": "prob-20260818-operator-authorized-s23-implementation-following-05ae6357",
      "source_of_truth": "sqlite",
      "storage_kind": "ProblemCard"
    },
    "profile": {
      "hash": "sha256:25225273a1f77d5c356fa7eb68d9a6e3748f8d40acb747a3a0188314b5329abf",
      "id": "haft-semantic-spine-v3.problem-card.v1",
      "source_kind": "embedded-profile",
      "source_ref": "embedded:haft-semantic-spine-v3/problem-card-v1",
      "valid_until": "2026-09-18T00:00:00Z"
    },
    "publication_projection": {
      "hash": "sha256:f2cf7fa13a00250eac900a7a7c67fc737130eeb185cedc99128befb33d3f19bd",
      "projection_kind": "problem_card_markdown",
      "sync_policy": "explicit_sync_validated_import",
      "views": [
        "working",
        "exact",
        "audit"
      ]
    },
    "publication_unit": {
      "carrier_hash": "sha256:3af401bd04c5ba5b1d0b0155825134264c145025c393e790fd8ecc7fc6bc9647",
      "publication_hash": "sha256:f2cf7fa13a00250eac900a7a7c67fc737130eeb185cedc99128befb33d3f19bd",
      "recoverability": {
        "mechanism": [
          "sqlite structured_data",
          "markdown structured_data block"
        ],
        "status": "exact"
      },
      "schema_version": 1,
      "source_edition_pin": {
        "hash": "sha256:0003c69201e60f291bcc2e7854ff8ced54a37b1a8561d3ddb57a67dbd31814ca",
        "ref": "episteme://haft/problem-card/prob-20260818-operator-authorized-s23-implementation-following-05ae6357/v1",
        "status": "pinned_by_source_hash"
      }
    },
    "reference_scheme": {
      "anchors": [
        "frontmatter.id",
        "structured_data.semantic.semantic_edition.id"
      ],
      "primary": "artifact_id"
    },
    "schema_version": 1,
    "semantic_edition": {
      "created_at": "2026-08-18T16:48:12Z",
      "family": "ProblemCard",
      "hash": "sha256:0003c69201e60f291bcc2e7854ff8ced54a37b1a8561d3ddb57a67dbd31814ca",
      "id": "episteme://haft/problem-card/prob-20260818-operator-authorized-s23-implementation-following-05ae6357/v1",
      "version": 1
    },
    "status": "exact"
  },
  "signal": "The selected in-place legacy migration path has a validated human decision file but no implementation that can transform legacy review state atomically enough to preserve approved translations, recover an interruption deterministically, or exclude a concurrent semantic mutation.",
  "spec_fit": {
    "authority": "read_only_spec_fit_probe",
    "authority_boundary": "spec_fit_probe_is_advisory_not_approval_not_baseline_not_evidence_not_gate_decision_not_claim_truth_not_publication",
    "next_expected_action": "draft_section",
    "record_kind": "spec_fit_probe",
    "schema_version": 1,
    "state": "spec_gap",
    "variant_spec_fit": [
      {
        "expected_action": "draft_section",
        "proposed_delta": "no active SpecSection matched the decision draft with high confidence",
        "state": "spec_gap",
        "variant_ref": "probe"
      }
    ]
  }
}
haft:end -->
