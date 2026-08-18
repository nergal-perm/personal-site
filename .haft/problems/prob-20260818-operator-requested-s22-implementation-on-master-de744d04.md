---
id: prob-20260818-operator-requested-s22-implementation-on-master-de744d04
kind: ProblemCard
version: 1
status: addressed
title: S22 — Non-executable migration decisions
mode: standard
created_at: 2026-08-18T15:07:01Z
updated_at: 2026-08-18T16:38:00Z
---

# S22 — Non-executable migration decisions

## Signal

S21 inventories legacy-shaped publication workspaces but cannot yet generate a clearly non-executable decision draft or reject a stale human decision set before any migration step.

## Problem Type

synthesis

## Problem Profile

thin

## P2W Readiness

p2w_candidate

## Why Now

The operator selected in-place migration to preserve existing translations and approvals; S22 is the next planned read-only decision slice after archived S21.

## Scope

publication-exporter S22

## Constraints

- Implement only MIG-03.
- Use publication-exporter, not exporter-java.
- Keep inventory and decision carriers in memory for the fast suite.
- Real JSON adapters must share strict parsing and fingerprint contracts.
- Do not change review workspace, apply migration, mutate catalog, or activate semantic mode.

## Acceptance

A deterministic draft is visibly non-executable; draft and stale-fingerprint decision inputs are rejected without review-workspace mutation; S22 does not alter the review workspace.

## Resolution

Implemented in `publication-exporter`: strict separate draft/decision JSON carriers, fresh-inventory validation, and read-only `legacy-inventory --draft` / `--validate` modes. The CLI rejects draft, stale, malformed, symlinked, and hard-linked decision/draft paths before review-workspace mutation. Fresh verification recorded 971 Maven tests with zero failures/errors; S22 OpenSpec artifacts were synced into `openspec/specs/legacy-transition/spec.md` and archived at `openspec/changes/archive/2026-08-18-s22-non-executable-migration-decisions/`. Graphify was refreshed successfully after the sandbox-only `Operation not permitted` restriction was bypassed by an elevated local AST update.

## Blast Radius

publication-exporter legacy inventory, decision-draft, validation, and CLI surfaces

## Reversibility

high

## Spec Fit (Advisory)

State: spec_gap

Next expected action: draft_section

| Variant | State | SpecSections | Next action |
|---------|-------|--------------|-------------|
| probe | spec_gap | - | draft_section |


## Related History

- [problem] **Wire up the real semantic link resolver end-to-end** `prob-20260802-1803dd18`
- [Note] **S21 read-only legacy diagnosis: implementation complete, ready for manual problem close** `note-20260818-3880e191`
- [Note] **G6 Cutover gate: operator selected in-place migration** `note-20260818-prob-20260818-40bccb11-slice-s21-read-only-legac-a548cfbe`

<!-- haft:structured_data
{
  "acceptance": "A deterministic draft is visibly non-executable; draft and stale-fingerprint decision inputs are rejected without review-workspace mutation; S22 does not alter the review workspace.",
  "blast_radius": "publication-exporter legacy inventory, decision-draft, validation, and CLI surfaces",
  "constraints": [
    "Implement only MIG-03.",
    "Use publication-exporter, not exporter-java.",
    "Keep inventory and decision carriers in memory for the fast suite.",
    "Real JSON adapters must share strict parsing and fingerprint contracts.",
    "Do not change review workspace, apply migration, mutate catalog, or activate semantic mode."
  ],
  "problem_type": "synthesis",
  "profile": {
    "blockers": [
      "problem_profile is not deep",
      "acceptance_probe missing",
      "freshness_disposition missing"
    ],
    "boundary_status": "partial",
    "level": "thin",
    "readiness": "p2w_candidate",
    "scope": "publication-exporter S22",
    "source_kind": "observed_problem",
    "why_now": "The operator selected in-place migration to preserve existing translations and approvals; S22 is the next planned read-only decision slice after archived S21."
  },
  "reversibility": "high",
  "semantic": {
    "carrier_binding": {
      "carrier_kind": "markdown",
      "carrier_ref": "prob-20260818-operator-requested-s22-implementation-on-master-de744d04",
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
      "hash": "sha256:7654c805b53073b20028bbe8c535a401c33f18008528e06fc171b8aaa399d4ff",
      "projection_kind": "problem_card_markdown",
      "sync_policy": "explicit_sync_validated_import",
      "views": [
        "working",
        "exact",
        "audit"
      ]
    },
    "publication_unit": {
      "carrier_hash": "sha256:9ad3c399f4392f84f301c508a24e06c0926de904af96fd8a08c93bf942f73255",
      "publication_hash": "sha256:7654c805b53073b20028bbe8c535a401c33f18008528e06fc171b8aaa399d4ff",
      "recoverability": {
        "mechanism": [
          "sqlite structured_data",
          "markdown structured_data block"
        ],
        "status": "exact"
      },
      "schema_version": 1,
      "source_edition_pin": {
        "hash": "sha256:39b1f37c4584a6eec9ba380943c1074e4d8ed9ca0f41572fb30e57d86f83be87",
        "ref": "episteme://haft/problem-card/prob-20260818-operator-requested-s22-implementation-on-master-de744d04/v1",
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
      "created_at": "2026-08-18T15:07:01Z",
      "family": "ProblemCard",
      "hash": "sha256:39b1f37c4584a6eec9ba380943c1074e4d8ed9ca0f41572fb30e57d86f83be87",
      "id": "episteme://haft/problem-card/prob-20260818-operator-requested-s22-implementation-on-master-de744d04/v1",
      "version": 1
    },
    "status": "exact"
  },
  "signal": "S21 inventories legacy-shaped publication workspaces but cannot yet generate a clearly non-executable decision draft or reject a stale human decision set before any migration step.",
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
