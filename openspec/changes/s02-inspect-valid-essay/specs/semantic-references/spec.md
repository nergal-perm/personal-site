# Semantic references — S02 scope pin (no delta)

This change adds no new requirement text and modifies none. `openspec/specs/semantic-references/spec.md`
already fully specifies SEM-01 through SEM-05 as the target end state, derived directly from
`openspec/requirements-baseline.md` ahead of any implementation. This file exists only to pin,
scenario by scenario, exactly which already-baselined behaviour S02 realizes — per
`openspec/implementation-plan.md`'s S02 boundary and Haft problem `prob-20260804-60dfda6c`.

**Tooling note:** this file intentionally carries no `## ADDED/MODIFIED/REMOVED/RENAMED Requirements`
section, so `openspec validate` will report "no delta sections found" for it — that is correct, not
an omission. This file itself carries no delta, but the change also contains real `review-and-approval`
and `workflow-bridge` deltas. Archive the whole change with `openspec archive s02-inspect-valid-essay`,
per Task 10; do not use `--skip-specs` for this change.

## Requirement: SEM-01 Require stable source-owned semantic identities

In scope for S02: the selected source note's own source ID only. S02 excludes links entirely (S13
introduces link resolution, S18 introduces direct-target source-ID admission for referenced notes), so
"each directly referenced private target" in the requirement text has no target to evaluate yet.

- **In scope** — Scenario: All required source IDs are valid, restricted to the single inspected essay
  having a stable, unique, human-assigned source ID (no direct private target exists to check).
- **In scope** — Scenario: Required source ID is absent or duplicated, restricted to the inspected
  essay itself lacking a source ID or sharing one with another already-known note; blocked as
  `metadata_blocked` before any candidate mutation (none exists to mutate in this slice regardless).
- **Deferred** — The "direct private target" half of both scenarios waits for S18, once notes can
  directly reference other notes at all.

## Not touched by this change

SEM-02, SEM-03, SEM-04, and SEM-05 remain fully specified in the baseline and are unimplemented until
S19 and S20 respectively. Their requirement text is unaffected here.
