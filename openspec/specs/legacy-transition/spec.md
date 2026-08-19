# Legacy transition Specification

## Purpose

Provide an explicitly conditional path for inspecting and recovering legacy review workspaces while keeping migration authority separate from the greenfield exporter's normal publication path. Evidence: E-MIG, E-GOV, and `exporter-java/README.md`.

## Requirements

### Requirement: MIG-01 Keep legacy migration outside the core publication path

Normal admission, preparation, approval, and release SHALL use source-owned IDs and approved triples; legacy inventory, allocation, and migration commands SHALL run only when explicitly requested for a workspace not yet activated for the current semantic schema.

#### Scenario: Current semantic workspace is used
- **GIVEN** the semantic schema activation marker is valid
- **WHEN** normal publication work runs
- **THEN** no legacy inventory, draft decision, or automatic ID allocation is invoked

#### Scenario: Migration is requested implicitly
- **GIVEN** a legacy workspace and an ordinary prepare or release request
- **WHEN** the exporter observes missing migration state
- **THEN** it blocks with migration-required evidence rather than mutating the workspace

### Requirement: MIG-02 Inventory legacy state without mutation

The inventory phase SHALL produce a deterministic report of legacy approved pairs, candidate pairs, cross-pair ambiguities (mismatched source IDs between an approved and candidate pair sharing an identity), and migration blockers (pairs missing a recorded source ID) without changing source, review, or site files. Reporting semantic occurrences and unsafe paths individually is deferred to a follow-up slice; this slice's fingerprint covers only the fields above, so it will not detect occurrence-only changes.

#### Scenario: Inventory is repeated
- **GIVEN** an unchanged legacy vault and review workspace
- **WHEN** inventory runs twice
- **THEN** the normalized inventory and fingerprints are identical
- **AND** workspace bytes are unchanged

#### Scenario: Ambiguity is found
- **GIVEN** a link target, identity, or pair correspondence is ambiguous
- **WHEN** inventory runs
- **THEN** the report records the alternatives and blocks automatic resolution

### Requirement: MIG-03 Separate decision drafts from executable decisions

Generated decision drafts SHALL be deterministic, visibly non-executable carriers that remain separate from the human decision file. A human decision file SHALL become eligible for later apply only after it conforms to the declared decision schema and matches a freshly generated inventory fingerprint. Presence of a generated-draft marker, a stale fingerprint, or malformed decision JSON SHALL reject the decision file before any review-workspace mutation.

#### Scenario: Draft exists without human decision file
- **GIVEN** an inventory and a generated decision draft marked non-executable
- **WHEN** validation or a later apply attempts to use that draft as the decision file
- **THEN** the operation is rejected without review-workspace mutation

#### Scenario: Human decision file is fresh
- **GIVEN** an unchanged workspace, a current inventory, and a separate human decision file matching its fingerprint and schema
- **WHEN** the decision file is validated
- **THEN** validation succeeds without review-workspace mutation

#### Scenario: Decisions are stale
- **GIVEN** a human decision file bound to an older inventory fingerprint and the workspace has since changed
- **WHEN** validation or a later apply attempts to use that decision file
- **THEN** the operation is rejected without review-workspace mutation and requires a fresh inventory

### Requirement: MIG-04 Apply migration under exclusive, recoverable control

Migration apply SHALL run only after an explicit authorized request, shall validate the complete fresh human decision set, inventory, and all target paths before mutation, and shall migrate every identity in the validated inventory as one generation. It SHALL reject inventories with blockers or ambiguities, hold an exclusive semantic-operation lock for the full apply or recovery operation, journal each durable state transition, and retain sufficient state for an explicitly requested deterministic roll-forward or roll-back after interruption. Roll-back SHALL restore the coherent pre-apply legacy generation; roll-forward SHALL complete the one validated generation. Generated drafts SHALL NOT be accepted as authorization or as decision input.

#### Scenario: Valid complete migration applies
- **GIVEN** fresh complete human decisions, a blocker-free and ambiguity-free inventory, safe paths, no competing semantic operation, and explicit authorization
- **WHEN** apply runs
- **THEN** every inventoried candidate and approved pair is transformed into one coherent current generation
- **AND** catalog state, sealed migration journal, and activation marker identify that generation

#### Scenario: Apply rejects an incomplete inventory before mutation
- **GIVEN** fresh human decisions whose current inventory has a blocker or ambiguity
- **WHEN** apply runs
- **THEN** it rejects the request before candidate, approved, catalog, journal, or activation-marker mutation

#### Scenario: Apply is interrupted
- **GIVEN** an interruption after one or more journalled steps
- **WHEN** roll-forward or roll-back is explicitly requested
- **THEN** recovery reaches the corresponding coherent terminal generation without guessing or rereading a changed decision file

#### Scenario: Another semantic operation is active
- **GIVEN** migration, approval, or another semantic mutation holds the operation lock
- **WHEN** a competing apply begins
- **THEN** it is blocked before mutation

### Requirement: MIG-05 Fail closed on incomplete activation state

An activation marker, catalog, sealed migration journal, and all migrated approved triples SHALL agree on schema edition, selected inventory fingerprint, identity set, and integrity before semantic-mode release is admissible. A partial, unsealed, inconsistent, missing, or hash-mismatched migration state SHALL be treated as incomplete and SHALL block semantic prepare, approval, and release with recovery guidance. A legacy workspace with no migration state remains outside semantic mode and retains the existing migration-required guidance.

#### Scenario: Activation is complete
- **GIVEN** all migration artefacts agree on the one completed generation and every selected approved publication has a complete valid triple
- **WHEN** semantic schema state is checked
- **THEN** normal semantic release is admissible

#### Scenario: Activation is partial or inconsistent
- **GIVEN** a missing marker, incomplete triple, conflicting journal, catalog inconsistency, or hash mismatch
- **WHEN** prepare, approval, or release checks semantic state
- **THEN** the operation fails closed with recovery guidance
