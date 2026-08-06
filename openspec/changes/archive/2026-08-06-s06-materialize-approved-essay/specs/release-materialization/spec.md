## MODIFIED Requirements

### Requirement: REL-02 Resolve bilingual semantic projection without private leaks

Release SHALL resolve approved semantic occurrences through the current approved-target registry, use locale-correct routes and labels, preserve headings and ordinary Markdown, and emit no semantic token, source ID, private vault path, or internal private route.

#### Scenario: Approved target is visible
- **GIVEN** an approved referrer and a selected approved target with RU and EN routes
- **WHEN** bilingual release pages are projected
- **THEN** RU uses the RU route and label and EN uses the EN route and label

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

## Why this is a real delta, not a scope pin

Both existing REL-02 scenarios assume at least one approved semantic occurrence exists — "Approved target is visible" and "Target is not visible" both open on "an approved referrer and a selected approved target" or "an approved occurrence," neither of which S06 can ever produce: `ReferenceMap#occurrences()` is unconditionally empty until SEM-02's occurrence assignment lands in S19, and the implementation plan's own coverage matrix marks REL-02 as first introduced at S06 (restricted to "no semantic occurrences") before being extended at S20. Without a scenario covering the zero-occurrence entry, REL-02's safety guarantee — "emit no semantic token, source ID, private vault path, or internal private route" — has no scenario asserting it holds for the one case S06 can actually reach; leaving it unstated would let a future change silently assume the guarantee is untested rather than trivially-but-explicitly satisfied by construction. This is the same gap-closing reasoning S03 used for SEM-03's "First-publication candidate has no semantic references" scenario, and S05 used for RVA-05's "A second approval is attempted" scenario: the plan's own "(restricted case)" qualifier on the requirement is a signal that a new scenario is expected, not that the requirement is out of scope.

## Not touched by this change

REL-01 ("Read approved snapshots only") is realized, not modified, by this slice: "Candidate differs from approved snapshot" already describes S06's candidate-ignoring behavior exactly, and "Selected publication lacks a safe approved snapshot" already describes S06's only reachable failure case (an absent approved snapshot — partial/unsafe/inconsistent approved state only becomes reachable once replacement/recovery exist in S09/S10) with its "before live site trees change" clause read as forward-looking to S07's live managed tree, not falsified by S06 writing into a fresh, previously-empty output root. REL-03 ("Bind output to deterministic provenance") is likewise realized, not modified: "Same approved state is built twice" is exactly S06's determinism obligation; "Provenance or output is tampered with" names an actor (the site content gate) that does not exist until REL-06/S07 and is not yet reachable. REL-04, REL-05, and REL-06 remain fully specified in the baseline and are unimplemented until S07/S10. See `scope-pins.md` for the full scope-pin record, including `public-content-model` (PCM-01, PCM-02).
