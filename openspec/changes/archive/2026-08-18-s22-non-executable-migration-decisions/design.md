## Context

S21 introduced `LegacyWorkspaceInventoryHandler` and the `legacy-inventory` CLI command in `publication-exporter`. The handler returns an immutable `LegacyWorkspaceInventory` whose SHA-256 fingerprint represents the diagnosed approved/candidate pairs, ambiguities, and blockers. S22 must turn that read-only result into a human-reviewable decision workflow without altering the review workspace or exposing an apply operation; S23 alone owns apply, catalog mutation, activation, journaling, and recovery.

The operator selected in-place migration to retain existing translations and approvals. Generated output must therefore be useful to a reviewer while being structurally incapable of standing in for that reviewer's decision.

## Goals / Non-Goals

**Goals:**

- Emit deterministic JSON drafts outside the review root, marked as non-executable and bound to one inventory fingerprint.
- Parse a separate human decision file with strict JSON semantics and validate that it is eligible for later apply and bound to the current inventory fingerprint.
- Expose draft generation and validation through the existing legacy CLI surface without mutating review state.
- Prove the behavior first with in-memory workspaces; use the same value/codec contracts from the CLI path.

**Non-Goals:**

- Editing the review workspace, candidate snapshots, approved snapshots, catalog, or activation marker.
- Applying migration decisions, recovery, approval, release, build, deployment, or a real-vault rehearsal.
- A generic workflow engine, decision framework, or speculative S23 abstraction.

## Decisions

### Separate draft and human-decision carriers

The draft writer will produce a dedicated JSON file containing the inventory fingerprint, a `draftOnly` marker, a human-readable status, the diagnosed inventory, and the minimal separate-file template. Validation will accept only a distinct decision-file shape that omits all draft markers and carries the same fingerprint. S22 deliberately defines no migration-resolution vocabulary; S23 will extend the decision schema when it owns applying those resolutions. This makes a generated carrier permanently non-executable rather than relying on a reviewer to remove a safety flag correctly.

The rejected alternative—editing a generated draft into an executable file—has one fewer file but weakens provenance and can blur machine suggestion with human approval.

### Keep the inventory as fingerprint authority

`LegacyWorkspaceInventory` remains the sole source for the fingerprint. A focused immutable decision-draft value, human-decision value, and codec will consume its public data rather than duplicating inventory scans or persisting an inventory cache. Validation will re-inspect before comparing fingerprints, so a changed workspace fails closed.

The rejected alternative—trusting an earlier report file—would accept stale external state and makes the report a hidden source of authority.

### Strict codec at the filesystem boundary

One legacy decision codec will use Jackson duplicate-field detection and explicit shape checks. It will reject missing fields, wrong JSON types, unknown top-level fields, draft markers in a decision file, and malformed fingerprints before returning an immutable validated value. The codec is the only component that reads or writes decision JSON; domain values remain free of I/O.

The rejected alternative—deserializing directly into permissive records in the command—would scatter validation and make CLI behavior diverge from in-memory acceptance tests.

### Read-only CLI modes

The existing `legacy-inventory` command will grow mutually exclusive read-only modes: inventory-only remains the default; `--draft <path>` writes only the draft carrier outside the review root; `--validate <path>` validates a distinct human decision file against a fresh inventory. CLI failures use a non-zero exit code and must not create or alter review files.

The rejected alternative—a new apply-like command—would blur S22 and S23 and violate the slice boundary.

### Object and test shape

Implementation will use small immutable records/value objects, constructor-injected collaborators, guard clauses, and intention-revealing methods. Tests use existing null workspaces as sociable in-memory fakes; no mocks or test-local I/O substitutes are introduced. Filesystem tests cover only the real JSON boundary and prove review-workspace bytes remain unchanged.

## Risks / Trade-offs

- [A valid decision becomes stale between draft and validation] → Re-inspect and compare the fingerprint on every validation; reject without mutation.
- [A reviewer mistakes a draft for an approved decision] → Keep a separate file shape and reject any draft marker in executable input.
- [JSON parsing accepts ambiguous input] → Enable duplicate detection and validate fields and decision keys explicitly.
- [A CLI output path targets the review root] → Reuse S21-style path confinement and reject before writing the draft.
- [S22 grows into migration apply] → Keep the public result to draft/validated/rejected only; no writer receives a review workspace.

## Migration Plan

1. Add in-memory acceptance tests for draft, draft rejection, fresh decision validation, and stale decision validation.
2. Introduce immutable decision carrier values and strict codec, then wire the handler and CLI read-only modes.
3. Run focused legacy and CLI tests followed by the full `publication-exporter` suite.
4. Roll back by reverting the additive S22 files and CLI options; existing S21 inventory and fail-closed guards are unaffected.

## Open Questions

None for S22. The detailed decision vocabulary that S23 will consume remains deliberately deferred; this slice validates carrier safety and freshness only.
