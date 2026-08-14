# Semantic references Specification

## Purpose

Preserve stable semantic note identity across moves and activate localized public links only from approved target state. Evidence: E-REF, E-REL, E-GOV, and `e2e/run-synthetic.sh`.
## Requirements
### Requirement: SEM-01 Require stable source-owned semantic identities

Every selected source note and every directly referenced private target SHALL have a stable, unique, human-assigned source ID before semantic preparation or release; the exporter SHALL NOT derive identity from a path, title, public ID, or route.

#### Scenario: All required source IDs are valid
- **GIVEN** the selected source and each direct private target have unique valid source IDs
- **WHEN** semantic references are planned
- **THEN** identities are resolved from those source IDs

#### Scenario: Required source ID is absent or duplicated
- **GIVEN** the selected source or a direct private target lacks a source ID or shares one with another note
- **WHEN** semantic preparation is requested
- **THEN** processing is blocked as `metadata_blocked` before a translation job is requested or a candidate is mutated
- **AND** no path-derived identity is allocated

### Requirement: SEM-02 Assign stable occurrence references in source order

For each eligible semantic link occurrence in publishable prose, the exporter SHALL assign a stable occurrence ID, preserve source order, and encode the same ordered IDs in RU, EN, and the reference map.

#### Scenario: Occurrences are unchanged
- **GIVEN** a previously mapped source whose eligible link occurrences still correspond exactly
- **WHEN** a candidate is prepared
- **THEN** prior occurrence IDs are reused in source order

#### Scenario: Link-like syntax is not eligible
- **GIVEN** a transclusion, protected-code token, malformed link, or non-semantic construct
- **WHEN** semantic occurrences are scanned
- **THEN** no semantic occurrence ID is assigned to it

### Requirement: SEM-03 Validate the reference map as a bound snapshot member

The reference map SHALL use the declared schema edition and bind publication identity, exact RU and EN hashes, strict occurrence order, target source IDs, source paths, and localized labels without duplicate, unknown, or unused references.

#### Scenario: Reference map matches candidate
- **GIVEN** one complete RU/EN candidate and a map whose hashes, identity, order, and occurrences match exactly
- **WHEN** semantic candidate validation runs
- **THEN** the map is accepted as the third candidate member

#### Scenario: Reference map is inconsistent
- **GIVEN** duplicate JSON keys, unsafe source paths, wrong hashes, reordered IDs, unknown IDs, or unused entries
- **WHEN** semantic candidate validation runs
- **THEN** the candidate is blocked before approval

#### Scenario: First-publication candidate has no semantic references
- **GIVEN** a first-publication RU/EN candidate whose body contains no eligible semantic link occurrences
- **WHEN** semantic candidate validation runs
- **THEN** the reference map is accepted as a schema-valid empty map bound to the candidate's publication identity and exact RU/EN hashes
- **AND** it is not treated as missing, malformed, or a validation failure

### Requirement: SEM-04 Resolve links late from approved target state

Release SHALL render an occurrence as a localized public link only when its target has a currently selected, complete approved snapshot; otherwise release SHALL render the approved visible label as plain text.

#### Scenario: Target is private at referrer approval
- **GIVEN** an approved referrer occurrence whose target lacks an approved selected snapshot
- **WHEN** the referrer is released
- **THEN** RU and EN contain their approved labels as plain text with no target path or semantic marker leak

#### Scenario: Target is approved later
- **GIVEN** the same unchanged approved referrer and the target later gains a selected approved snapshot
- **WHEN** release is materialized again
- **THEN** the occurrence becomes a localized route in RU and EN
- **AND** the referrer's approved files and hashes do not change

#### Scenario: Target becomes unpublished
- **GIVEN** a linked approved target ceases to be selected
- **WHEN** release is materialized again
- **THEN** the referrer occurrence returns to approved plain-label rendering without referrer reapproval

### Requirement: SEM-05 Keep target changes out of referrer approval scope

Changing a target's path, title, localization, publication route, or approval state SHALL affect release projection through source identity and target approval, not by automatically rewriting or reapproving approved referrers.

#### Scenario: Target note moves
- **GIVEN** an approved referrer points to a target by stable source ID and the target path changes without identity change
- **WHEN** the reference index is resolved
- **THEN** the occurrence continues to identify the same target

#### Scenario: New target becomes eligible
- **GIVEN** a referrer already contains an approved plain-label occurrence and its target completes ordinary prepare, review, and approval
- **WHEN** release is rebuilt
- **THEN** the link activates without creating a referrer candidate or approval request

