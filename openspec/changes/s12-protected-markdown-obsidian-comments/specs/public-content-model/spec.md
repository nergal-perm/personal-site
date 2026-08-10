## MODIFIED Requirements

### Requirement: PCM-04 Preserve protected Markdown and remove Obsidian-only syntax

The exporter SHALL leave code, inline code, and other declared protected regions semantically unchanged while removing Obsidian comments and transforming only eligible Markdown constructs. Protected regions for this requirement are fenced code blocks and inline code spans; other constructs are added to the protected-region set only when a future slice's requirements need them. An Obsidian comment with no matching closing marker SHALL block preparation with a diagnostic rather than being treated as extending to the end of the source, so an author's forgotten closing marker cannot silently drop the remainder of an article from public content.

#### Scenario: Link-like text appears in protected content
- **GIVEN** code or another protected region containing wiki-link, embed, or reference-like text
- **WHEN** Markdown normalization runs
- **THEN** the protected text remains unchanged

#### Scenario: Obsidian comment appears in publishable prose
- **GIVEN** an admitted source body containing an Obsidian comment
- **WHEN** Markdown normalization runs
- **THEN** the comment is absent from public content

#### Scenario: Obsidian comment is left unclosed
- **GIVEN** an admitted source body containing an opening Obsidian comment marker with no matching closing marker
- **WHEN** Markdown normalization runs
- **THEN** preparation blocks with a diagnostic identifying the unclosed comment
- **AND** no candidate, approved snapshot, or workflow state is created or changed
