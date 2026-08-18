## MODIFIED Requirements

### Requirement: PCM-03 Resolve public links without leaking private topology

The exporter SHALL convert unambiguous links to selected public notes into a durable, release-resolvable reference marker, convert links to private, unresolved, or ambiguous notes into visible plain labels, and block private transclusions. This slice recognizes `[[Target]]`, `[[Target|Alias]]`, and `[[Target#Heading]]` wikilink syntax (a heading fragment, if present, is dropped — resolution and labeling apply to `Target` only) and `![[Target]]` embed syntax. For an unambiguous admitted (non-embed) target, prepare-time output is a marker binding the occurrence to the target's stable source ID — not a baked public route — so that whether it renders as a route or a plain label is decided at release time against the target's *current* approval state (SEM-04), independently of the referrer's own approval timing; determining which kind-correct route (e.g. `/essays/{publicId}/` vs `/notes/{publicId}/`) that marker resolves to, and applying the final locale prefix, is release-materialization's responsibility (REL-02), not this requirement's. Embed (`![[Target]]`) transclusion resolution is unchanged by this slice: a routable embed target is still resolved and rendered immediately at prepare time, and an embed to a private, unresolved, or ambiguous target still blocks prepare with a transclusion diagnostic — embeds carry rendered content inline and have no release-time activation state to defer. An ambiguous target — one matching more than one known note — receives the same safe-label treatment as a private or unresolved target rather than a distinct diagnostic; disambiguating colliding note names is the author's responsibility, not a case the exporter blocks on. An embed target whose name has a recognized publishable-asset extension is not evaluated as a note transclusion — asset resolution is a separate requirement (PCM-05).

#### Scenario: Public target is unambiguous
- **GIVEN** a source link (`[[Target]]` or `[[Target|Alias]]`, with an optional `#Heading` fragment ignored) whose target text resolves uniquely to one selected public note among the known notes
- **WHEN** the source body is normalized
- **THEN** the output contains a durable reference marker binding the occurrence to the target's stable source ID, not a baked public route
- **AND** it contains the authored alias, or the target text if no alias was given, as the display label

#### Scenario: Private, unresolved, or ambiguous target is linked
- **GIVEN** a source link whose target text is private, matches no known note, or matches more than one known note
- **WHEN** the source body is normalized
- **THEN** the output retains a human-readable label — the authored alias, or the target text if no alias was given
- **AND** it contains no vault path, private route, source identifier, or Obsidian link token, or reference marker

#### Scenario: Private target is transcluded
- **GIVEN** a source note transcludes (`![[Target]]`) content from a note that is private, unresolved, or ambiguous among the known notes
- **WHEN** the source body is normalized
- **THEN** normalization is blocked with a transclusion diagnostic
- **AND** no candidate is installed or replaced

#### Scenario: Embed target is a publishable asset, not a note
- **GIVEN** a source note embeds (`![[Target]]`) a target whose name has a recognized publishable-asset extension
- **WHEN** the source body is normalized
- **THEN** the target is resolved as an asset embed, not a note transclusion, and is unaffected by this slice's marker change

#### Scenario: Routable embed target is unaffected by this slice
- **GIVEN** a source note embeds (`![[Target]]`) a target that resolves uniquely to one selected public note
- **WHEN** the source body is normalized
- **THEN** the output contains the target's resolved route, baked in immediately, exactly as before this slice — no reference marker, no deferred resolution
