## MODIFIED Requirements

### Requirement: PCM-03 Resolve public links without leaking private topology

The exporter SHALL convert unambiguous links to selected public notes into public routes, convert links to private, unresolved, or ambiguous notes into visible plain labels, and block private transclusions. This slice recognizes `[[Target]]`, `[[Target|Alias]]`, and `[[Target#Heading]]` wikilink syntax (a heading fragment, if present, is dropped — resolution and labeling apply to `Target` only) and `![[Target]]` embed syntax. A resolved public route is locale-neutral (e.g. `/essays/{publicId}/`, with no `/ru/` or `/en/` locale segment): resolution runs once on the Russian source ahead of translation, and the same route text is reused, untranslated, in the derived English candidate, so no locale segment can leak between candidates; final locale-prefixed site routing is a later concern outside this slice. An ambiguous target — one matching more than one known note — receives the same safe-label treatment as a private or unresolved target rather than a distinct diagnostic; disambiguating colliding note names is the author's responsibility, not a case the exporter blocks on. An embed target whose name has a recognized publishable-asset extension is not evaluated as a note transclusion — asset resolution is a separate requirement (PCM-05).

#### Scenario: Public target is unambiguous
- **GIVEN** a source link (`[[Target]]` or `[[Target|Alias]]`, with an optional `#Heading` fragment ignored) whose target text resolves uniquely to one selected public note among the known notes
- **WHEN** the source body is normalized
- **THEN** the output contains a locale-neutral public route for the target note (e.g. `/essays/{publicId}/`)
- **AND** it contains the authored alias, or the target text if no alias was given, as the display label

#### Scenario: Private, unresolved, or ambiguous target is linked
- **GIVEN** a source link whose target text is private, matches no known note, or matches more than one known note
- **WHEN** the source body is normalized
- **THEN** the output retains a human-readable label — the authored alias, or the target text if no alias was given
- **AND** it contains no vault path, private route, source identifier, or Obsidian link token

#### Scenario: Private target is transcluded
- **GIVEN** a source note transcludes (`![[Target]]`) content from a note that is private, unresolved, or ambiguous among the known notes
- **WHEN** the source body is normalized
- **THEN** normalization is blocked with a transclusion diagnostic
- **AND** no candidate is installed or replaced

#### Scenario: Embed target is a publishable asset, not a note
- **GIVEN** a source note embeds (`![[Target]]`) a target whose name has a recognized publishable-asset extension
- **WHEN** the source body is normalized
- **THEN** the embed is left untouched by this requirement — it is neither resolved to a route nor blocked as a transclusion
