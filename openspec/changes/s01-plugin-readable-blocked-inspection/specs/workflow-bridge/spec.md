# Workflow bridge — S01 scope pin (no delta)

This change adds no new requirement text and modifies none. `openspec/specs/workflow-bridge/spec.md`
already fully specifies BRG-01 through BRG-07 as the target end state, derived directly from
`openspec/requirements-baseline.md` ahead of any implementation. This file exists only to pin,
scenario by scenario, exactly which already-baselined behaviour S01 realizes — per
`openspec/implementation-plan.md`'s S01 boundary and Haft problem `prob-20260803-a75ab1d8`.

**Tooling note:** this file intentionally carries no `## ADDED/MODIFIED/REMOVED/RENAMED Requirements`
section, so `openspec validate` will report "no delta sections found" for it — that is correct, not
an omission. Archive this change with `openspec archive --skip-specs`; the baseline spec must not be
rewritten from this file.

## Requirement: BRG-01 Support the plugin command set without shell interpretation

In scope for S01: the `inspect-publication` command only, exercised as a note-scoped command.

- **In scope** — Scenario: Note-scoped command is invoked (restricted to `inspect-publication`; an
  unsafe or absent note path is treated as data, not shell syntax).
- **Deferred** — `prepare` (S03), `mark-reviewed` (S05), `refresh-publication-queue` (S11), and the
  "Refresh is invoked" scenario, which needs `refresh-publication-queue` to exist.

## Requirement: BRG-02 Emit bridge schema v2 for the initial replacement release

In scope for S01: the blocked/failure path only. There is no valid-note success path yet.

- **In scope** — Scenario: Domain operation is blocked.
- **In scope** — Scenario: Schema major differs (defensive framing only; S01's own emitted response
  is schema-v2 by construction and is checked against `bridge-contract/schema-v2.json`).
- **Deferred** — Scenario: Successful bridge command returns (needs a valid-note success response,
  which S02 introduces).

## Requirement: BRG-03 Keep the bridge contract single-sourced and conformance-tested

Fully in scope for S01, realized concretely via `bridge-contract/schema-v2.json`
(gate decision `dec-20260803-4834d689`), the format and location choice BRG-03 leaves open.

- **In scope** — Scenario: Either side changes the contract (exercised by both the Java-side and
  JS-side conformance tests introduced in this change).
- **In scope** — Scenario: Optional field is added compatibly (the schema must declare unknown
  optional fields ignorable).

## Not touched by this change

BRG-04, BRG-05, BRG-06, and BRG-07 remain fully specified in the baseline and are unimplemented
until S02, S11, S11, and S04 respectively. Their requirement text is unaffected here.
