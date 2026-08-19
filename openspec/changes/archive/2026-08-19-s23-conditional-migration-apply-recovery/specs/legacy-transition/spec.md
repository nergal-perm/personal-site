## MODIFIED Requirements

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
- **GIVEN** an interruption after one or more durable migration-journal transitions
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
- **GIVEN** a missing marker, incomplete triple, conflicting catalog, unsealed or conflicting journal, inconsistent identity set, or hash mismatch
- **WHEN** prepare, approval, or release checks semantic state
- **THEN** the operation fails closed with explicit recovery guidance
