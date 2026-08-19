## Context

S21 supplies read-only legacy inventory and a minimal activation marker; S22 supplies strict human-decision parsing and freshness validation. The operator selected in-place migration to preserve existing translations and approvals. The current review-workspace adapters already provide complete candidate and approved snapshots, but no global operation lock, migration journal, catalog generation, or write-capable activation path.

S23 is limited to one complete, blocker-free inventory. Normal greenfield preparation, approval, release materialization, build, deployment, and real-vault rehearsal remain outside the migration path.

## Goals / Non-Goals

**Goals:**

- Apply every identity from one fresh, complete human decision set as one explicit migration generation.
- Preserve immutable pre-apply snapshots until recovery reaches a terminal state.
- Make each persistent transition durable, inspectable, and deterministic after an injected interruption.
- Exclude competing semantic mutation using one operation-lock abstraction.
- Admit semantic mode only when marker, catalog, sealed journal, and approved triples agree.
- Prove the state machine with null/in-memory collaborators before testing the filesystem adapter implementing the same ports.

**Non-Goals:**

- Automatic migration, generated-draft authorization, partial selection, or changing the S22 decision schema.
- Rewriting source notes, conducting a real-vault migration, approving migration output, building the Astro site, or deployment.
- Generalizing the existing snapshot workspaces or adding a framework-wide transaction abstraction.

## Decisions

### One inventory is one generation

`MigrationApplyHandler` validates S22 decisions, performs a fresh inventory, rejects blockers and ambiguities, and captures the resulting ordered identity set and inventory fingerprint before it opens the journal. It never rereads the decision file during recovery. This fits the selected all-identities scope and prevents an activated marker from describing only a subset of legacy content.

### Immutable journal manifest with atomic replacement

`MigrationJournal` stores one strict JSON manifest below `.migration/`. It contains the schema edition, inventory fingerprint, ordered identities, pre-apply candidate/approved snapshot copies, per-identity cursor, and terminal state (`RUNNING`, `SEALED`, or `ROLLED_BACK`). The filesystem adapter writes a complete replacement manifest to a sibling staging file and atomically moves it into place; strict duplicate-field parsing rejects a malformed manifest.

An append-only JSONL log was considered, but would require replay and corrupt-tail policy that do not improve S23’s one-generation boundary. Per-step directories would enlarge the safe-path and collision surface. The chosen manifest reuses the repository’s strict JSON and atomic-replacement conventions.

### Explicit terminal recovery

`rollForward()` consumes the recorded generation and cursor, completing only the unfinished identities before creating catalog, marker, and sealed journal. `rollBack()` restores the immutable preimages and records `ROLLED_BACK`; it never infers an outcome from the current mutable workspace. Both operations acquire the same lock and reject absent, sealed, or inconsistent journals as appropriate.

### Small ports with state-based null implementations

The migration handler receives narrow collaborators: inventory/decision validation, a migration workspace for candidate-and-approved snapshot replacement, `MigrationJournalStore`, `MigrationCatalogStore`, `ActivationMarkerStore`, and `SemanticOperationLock`. Null collaborators retain observable state for direct tests rather than mocks. The filesystem versions own confinement, symlink avoidance, strict parsing, and atomic write behavior. Domain values are immutable and validate themselves; constructors remain side-effect free.

### Activation verifies a complete generation, not only marker syntax

`SchemaActivationGuard` is extended with a generation verifier. A valid marker alone remains insufficient when migration artefacts exist. Semantic admission checks the same schema version, inventory fingerprint, identity set, sealed journal, catalog integrity, and complete approved triples. Any disagreement returns a recovery-oriented block. Legacy workspaces without migration artefacts retain their current migration-required outcome.

### Operation lock is global to semantic mutation

`SemanticOperationLock` exposes a single scoped operation. The migration command uses it across validation, journal creation, writes, sealing, and recovery. Existing approval flow can later use this same seam; S23 proves the competing-operation behavior through the lock port without broadening unrelated CLI workflows.

## Risks / Trade-offs

- [Interrupted filesystem replacement] → Each manifest transition is fully staged and atomically replaced; recovery accepts only strict complete manifests.
- [Rollback loses pre-existing translations or approvals] → Preimages are captured before the first mutation and retained until the explicit terminal recovery state.
- [Mutable workspace changes after interruption] → Recovery relies on the recorded generation and does not reread decisions; incompatible current state blocks with recovery evidence rather than guessing.
- [Lock cannot coordinate another process] → The filesystem lock uses non-blocking OS file locking; collision fails before mutation.
- [Existing marker-only callers become falsely current] → Missing catalog or journal alongside a marker is incomplete activation and fails closed; a workspace with no migration artefacts keeps legacy guidance.
- [Scope expansion] → S23 intentionally supports one complete inventory generation only; per-identity selection is deferred.

## Migration Plan

1. Establish in-memory value objects, journal state transitions, snapshot workspace, and lock; test apply, interruption, roll-forward, roll-back, and lock collision.
2. Add filesystem journal/catalog/lock adapters with strict parsing, confinement, and atomic replacement tests.
3. Wire the handler and extend activation verification; add one end-to-end filesystem fixture.
4. Verify that an incomplete journal blocks semantic admission. No production vault is migrated by this change.

If an apply is interrupted, the operator explicitly selects recovery: roll-forward completes the recorded generation; roll-back restores its preimages. No automatic recovery or deployment action occurs.

## Open Questions

None for S23. The operator selected all-inventory scope and atomic JSON manifest representation.
