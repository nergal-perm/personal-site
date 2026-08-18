## MODIFIED Requirements

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
