## Why

The operator selected in-place migration to retain existing translations and approvals. S21 and S22 can diagnose legacy state and validate a human decision set, but they cannot safely apply it or recover from an interrupted apply.

## What Changes

- Add an explicitly authorized migration apply path that validates fresh human decisions and safe paths before any mutation.
- Add an exclusive semantic-operation lock and a durable migration journal with explicit roll-forward and roll-back recovery.
- Complete semantic activation verification so the marker, catalog, journal, and migrated approved triples must agree before normal semantic release is admitted.
- Provide an in-memory implementation that proves the state machine, then filesystem adapters that run the same contract.
- Keep drafts non-executable and exclude automatic application, real-vault rehearsal, approval, build, and deployment.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `legacy-transition`: Add conditional migration apply, deterministic recovery, exclusive semantic mutation control, and complete activation integrity checks required by MIG-04 and MIG-05.

## Impact

The change affects only `publication-exporter` legacy migration adapters, activation admission, and their tests. Normal greenfield publication workflows remain outside the migration path. The filesystem implementation will write migration state only after explicit authorized apply; no external dependencies or public plugin-schema changes are introduced.
