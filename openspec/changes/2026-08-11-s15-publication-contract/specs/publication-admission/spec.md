## MODIFIED Requirements

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements. The contract is a standalone JSON document (`contractVersion` plus one entry per supported kind) returned by the `write-publication-contract` command; it is not wrapped in the `BridgeResponse` schema-v2 envelope used by note-scoped commands, since a contract has no operation outcome (no `ok`/`status`/`diagnostics`/`identity`) — it is a declarative description of what a valid publication looks like. For each kind, the contract states: its `collection`/`contentType` pair; each required frontmatter field with its expected type, and where applicable an explicit allowed-value list (e.g. `publicCollection` must be `"blog"`) or a documented pattern (e.g. `publicId` must match the lowercase route-slug pattern); and its structured-body requirements, which is an empty list for every kind implemented so far (only `blog/essay` exists; essay has no required body sections beyond its frontmatter fields).

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization
- **AND** the normalization is: stable per-kind field order, kinds sorted by `(collection, contentType)`, no timestamp or environment-dependent value in the document

#### Scenario: Contract describes the essay kind
- **GIVEN** the exporter edition implements exactly one kind, `blog/essay`
- **WHEN** the publication contract is requested
- **THEN** the contract lists exactly one kind entry for `blog`/`essay`
- **AND** that entry's required fields match `EssayAdmission`'s enforced fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`) with their actual allowed values or pattern
- **AND** that entry's structured-body requirements are empty

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance
- **AND** the harness draws every fixture from one shared fixture table also exercised by `EssayAdmission`'s own validation tests, so the two suites cannot silently diverge in what fixtures they cover
