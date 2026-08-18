## Why

S21 can diagnose a legacy review workspace without changing it, but an operator still has no safe carrier for resolving the reported migration choices. In-place migration was selected to preserve existing translations and approvals, so the next slice must make proposed resolutions reviewable while ensuring that neither a generated draft nor stale human decisions can trigger migration.

## What Changes

- Generate a deterministic, human-reviewable migration decision draft from an S21 inventory.
- Mark every generated draft as non-executable and reject it wherever executable decisions are required.
- Validate human-converted decision JSON against strict schema and the current inventory fingerprint before any later migration apply can consume it.
- Add read-only CLI responses and in-memory acceptance coverage proving draft and stale-decision rejection without review-workspace mutation.
- Keep review-workspace changes, catalog mutation, activation, migration apply, approval, build, and deployment out of scope.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `legacy-transition`: Clarify MIG-03's separate, non-executable draft carrier and the required fresh-fingerprint validation of a distinct human decision file.

## Impact

- `publication-exporter` legacy inventory, CLI composition, JSON codec, and in-memory test fixtures.
- The existing `openspec/specs/legacy-transition/spec.md` contract.
- No plugin API change and no migration apply path in this slice.
