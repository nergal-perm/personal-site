## MODIFIED Requirements

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

#### Scenario: Human decision file is stale

- **GIVEN** a human decision file bound to an older inventory fingerprint and the workspace has since changed
- **WHEN** validation or a later apply attempts to use that decision file
- **THEN** the operation is rejected without review-workspace mutation and requires a fresh inventory
