## MODIFIED Requirements

### Requirement: REL-02 Resolve bilingual semantic projection without private leaks

Release SHALL resolve approved semantic occurrences through the current approved-target registry, use locale-correct routes and labels, preserve headings and ordinary Markdown, and emit no semantic token, source ID, private vault path, or internal private route. A resolved route is selected according to the target's own publication kind (e.g. `/{lang}/essays/{publicId}/` for `blog/essay`, `/{lang}/notes/{publicId}/` for `blog/note`), not the referrer's kind — this is the release-time counterpart of PCM-03's admission-time kind-routing, now evaluated against the target's current approval state instead of baked in at the referrer's prepare time.

#### Scenario: Approved target is visible
- **GIVEN** an approved referrer and a selected approved target with RU and EN routes
- **WHEN** bilingual release pages are projected
- **THEN** RU uses the RU route and label and EN uses the EN route and label

#### Scenario: Approved target's route matches its own publication kind
- **GIVEN** an approved referrer occurrence pointing to a `blog/note` target while the referrer itself is a `blog/essay`
- **WHEN** bilingual release pages are projected
- **THEN** the resolved route is the target's own `/{lang}/notes/{publicId}/` route, not the referrer's `/{lang}/essays/{publicId}/` shape

#### Scenario: Target is not visible
- **GIVEN** an approved occurrence whose target is not currently releasable
- **WHEN** release pages are projected
- **THEN** each locale contains only its approved plain label
- **AND** the output safety gate finds no semantic or vault-private marker

#### Scenario: Approved snapshot has no semantic occurrences
- **GIVEN** an approved snapshot whose reference map carries zero occurrences
- **WHEN** bilingual release pages are projected
- **THEN** each locale's release body is emitted exactly as recorded in the approved snapshot, with no occurrence resolution attempted
- **AND** the output safety gate finds no semantic token, source ID, private vault path, or internal private route

### Requirement: REL-03 Bind output to deterministic release provenance

Each release SHALL produce deterministic provenance that binds the exporter contract edition, selected approved snapshot hashes, semantic projection inputs, activation counts, and hashes of every managed output tree and file.

#### Scenario: Same approved state is built twice
- **GIVEN** identical approved snapshots, target-selection state, and exporter edition
- **WHEN** release is materialized twice
- **THEN** managed content and normalized provenance are identical

#### Scenario: Provenance or output is tampered with
- **GIVEN** a managed file, tree, selected snapshot, activation count, or provenance field differs from the recorded release
- **WHEN** the site content gate verifies it
- **THEN** the build is blocked with a provenance mismatch
