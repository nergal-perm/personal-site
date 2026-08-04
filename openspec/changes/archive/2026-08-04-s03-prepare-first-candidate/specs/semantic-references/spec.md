## MODIFIED Requirements

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

## Why this is a real delta, not a scope pin

Both existing SEM-03 scenarios assume a non-empty reference map — "matches candidate" talks about occurrences that "match exactly" and "is inconsistent" talks about unused or unknown entries, both of which presuppose entries exist to compare or count. S03 is the first slice that produces a `references.json` at all, and every candidate it produces is a first-publication candidate with no semantic links resolved yet (SEM-02's occurrence assignment and PCM-03's link resolution are both later, S13/S19). Without this scenario, "matches exactly" is ambiguous for the zero-occurrence case: does an empty map count as a degenerate match, or as evidence something is missing? The new scenario resolves that ambiguity explicitly as a first-class, permanent addition to the baseline, following the same reasoning S02 used for RVA-01's and BRG-04's all-absent scenarios.

## Not touched by this change

SEM-01 is realized (not modified) by this slice, restricted to the source note's own identity per its existing scope pin from S02 — see `scope-pins.md`. SEM-02, SEM-04, and SEM-05 remain fully specified in the baseline and are unimplemented until S19 and S20 respectively. Their requirement text is unaffected here.
