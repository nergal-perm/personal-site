# Release materialization Specification

## Purpose

Materialize deterministic, provenance-bound Astro input from approved publication state without advancing approval. Evidence: E-REL, E-REF, E-CONTENT, `e2e/README.md`, and `site/scripts/check-content.mjs`.
## Requirements
### Requirement: REL-01 Read approved snapshots only

Release materialization SHALL derive publishable RU and EN pages exclusively from complete approved snapshots and current approved-target projection state; candidate and job artefacts SHALL have no release authority.

#### Scenario: Candidate differs from approved snapshot
- **GIVEN** a pending candidate newer than the approved snapshot
- **WHEN** release is materialized
- **THEN** public content reflects the approved snapshot and ignores candidate bytes

#### Scenario: Selected publication lacks a safe approved snapshot
- **GIVEN** a selected publication with absent, partial, unsafe, or inconsistent approved state
- **WHEN** a complete release is requested
- **THEN** release is blocked before live site trees change

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

### Requirement: REL-04 Guard release inputs during materialization

The exporter SHALL detect changes to source selection, approved snapshot leaves, semantic schema state, or other declared release inputs between planning and commit.

#### Scenario: Inputs remain stable
- **GIVEN** every planned release input retains its validated fingerprint
- **WHEN** staged output is committed
- **THEN** the release may replace managed trees

#### Scenario: Input changes concurrently
- **GIVEN** a declared release input changes after planning
- **WHEN** staged output is about to be committed
- **THEN** release is blocked and existing live site trees remain unchanged

### Requirement: REL-05 Replace only exporter-managed site trees atomically

The exporter SHALL stage and validate complete managed trees before replacing them, SHALL leave all non-managed site paths untouched, and SHALL recover to one complete old or new generation after interruption.

#### Scenario: Staged site content is valid
- **GIVEN** complete staged managed trees passing path, content, and provenance gates
- **WHEN** site installation commits
- **THEN** only declared managed roots are replaced as one release generation
- **AND** code-owned templates and other site files remain byte-identical

#### Scenario: Empty-destination install
- **GIVEN** no managed trees and no provenance manifest exist yet for any publication
- **WHEN** site installation commits complete staged managed trees passing path, content, and provenance gates
- **THEN** the declared managed roots are created and populated as the first release generation
- **AND** code-owned templates and other site files remain byte-identical

#### Scenario: Staged content or filesystem is unsafe
- **GIVEN** validation fails or a managed path contains a symlink, device, unexpected entry, or race-induced substitution
- **WHEN** installation is attempted
- **THEN** live managed trees remain at the prior complete generation

#### Scenario: Installation is interrupted
- **GIVEN** a failure occurs during managed-tree replacement
- **WHEN** recovery runs
- **THEN** it deterministically restores or completes one validated generation and reports the outcome

### Requirement: REL-06 Gate Astro builds on content ownership and provenance

The generated site input SHALL pass checks for managed-root ownership, expected page/registry parity, allowed collection and content-type combinations, semantic-marker absence, and release provenance before Astro build success.

#### Scenario: Generated content is coherent
- **GIVEN** managed trees and provenance correspond exactly and all content checks pass
- **WHEN** the site build runs with provenance required
- **THEN** the content gate permits Astro compilation

#### Scenario: Generated content violates a gate
- **GIVEN** a missing or extra page, invalid collection relation, marker leak, unsafe path, or provenance mismatch
- **WHEN** the site build runs
- **THEN** it fails before publication deployment can be considered successful
