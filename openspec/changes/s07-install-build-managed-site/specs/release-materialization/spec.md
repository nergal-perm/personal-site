## MODIFIED Requirements

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

## Why this is a real delta, not a scope pin

REL-05's existing "Staged site content is valid" scenario reads as general-purpose but its own wording ("replaced," "code-owned templates ... remain byte-identical") and its sibling scenarios ("Staged content or filesystem is unsafe" protects "the prior complete generation"; "Installation is interrupted" recovers to "one complete old or new generation") all presuppose a generation already exists to replace, protect, or recover to. S07 is the first slice that ever writes into the managed roots at all — there is no prior generation, so "replaced" needs an explicit degenerate case: creating managed trees from nothing. Without this scenario, REL-05's atomicity guarantee has no scenario asserting it holds for the one case S07 can actually reach (an install into absent roots), leaving the guarantee untested rather than trivially-but-explicitly satisfied by construction. This mirrors S03's SEM-03 empty-map scenario, S05's RVA-05 second-approval scenario, and S06's own REL-02 zero-occurrence scenario: the plan's "(restricted case)" pattern signals a new scenario is expected, not that the requirement is out of scope. "Staged content or filesystem is unsafe" and "Installation is interrupted" both stay unreachable in this slice by construction (no prior generation exists to protect or recover to) and remain S10's job — see `scope-pins.md`.

## Not touched by this change

REL-04 ("Guard release inputs during materialization") and REL-06 ("Gate Astro builds on content ownership and provenance") remain fully specified in the baseline and are realized, not modified, by this slice: REL-04's "Inputs remain stable" scenario and both REL-06 scenarios already read correctly for a first install without presupposing any prior generation. REL-01, REL-02, and REL-03 are unaffected — this slice reads the same S06 release output unchanged and adds no new provenance concept. See `scope-pins.md` for the full scope-pin record, including REL-04's "Input changes concurrently" scenario and REL-05's replace/recover scenarios, both of which stay unreachable until S10.
