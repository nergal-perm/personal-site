## MODIFIED Requirements

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

The inventory phase SHALL produce a deterministic report of legacy approved pairs, candidate pairs, existing identities, semantic occurrences, ambiguities, unsafe paths, and migration blockers without changing source, review, or site files.

#### Scenario: Inventory is repeated
- **GIVEN** an unchanged legacy vault and review workspace
- **WHEN** inventory runs twice
- **THEN** the normalized inventory and fingerprints are identical
- **AND** workspace bytes are unchanged

#### Scenario: Ambiguity is found
- **GIVEN** a link target, identity, or pair correspondence is ambiguous
- **WHEN** inventory runs
- **THEN** the report records the alternatives and blocks automatic resolution

### Requirement: MIG-05 Fail closed on incomplete activation state

An activation marker, catalog, migration journal, and all migrated approved triples SHALL agree on schema edition and integrity before semantic-mode release is admissible.

#### Scenario: Activation is complete
- **GIVEN** all migration artefacts agree and every selected approved publication has a complete valid triple
- **WHEN** semantic schema state is checked
- **THEN** normal semantic release is admissible

#### Scenario: Activation is partial or inconsistent
- **GIVEN** a missing marker, incomplete triple, conflicting journal, catalog inconsistency, or hash mismatch
- **WHEN** prepare, approval, or release checks semantic state
- **THEN** the operation fails closed with recovery guidance
