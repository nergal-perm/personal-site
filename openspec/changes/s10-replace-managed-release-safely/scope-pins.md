# S10 scope pins

These notes record the functional collaborative-design pass over S10's requirement set. That pass found **no
genuine requirement-text gap** — every scenario S10 needs is already fully worded in the baseline, and S07's
own scope-pins already flagged exactly these scenarios as "not yet applicable"/"reachable once S10 exists."
This mirrors S06's and S08's pure-realization pattern.

## Release materialization

`openspec/specs/release-materialization/spec.md` already fully specifies REL-01 through REL-06.

### Requirement: REL-03 Bind output to deterministic release provenance

Fully in scope for S10, no gap. Both existing scenarios are worded generically over "release," not scoped to
a first release:

- **In scope** — Scenario: Same approved state is built twice. S07 already proved this for the
  first-generation case (`CheckContentGateContractTest`); S10 must prove it still holds when the second build
  is a genuine *replacement* of a first generation, not merely a repeat of the same one. No new scenario text
  is needed — "release is materialized twice" already covers this without distinguishing "twice into empty
  roots" from "twice with a real change between them."
- **In scope** — Scenario: Provenance or output is tampered with. Already realized structurally by S07's
  `SiteReleaseManifest`/`check-content.mjs` gate; S10 must prove the same gate still catches tampering against
  a *replaced* generation's provenance, not just a first one. Same wording, no new scenario.

### Requirement: REL-04 Guard release inputs during materialization

Fully in scope for S10, no gap — this is precisely the requirement S07's scope-pins named S10 as the slice
that reaches it:

- **In scope** — Scenario: Inputs remain stable. Already realized by S07 (trivially, since nothing existed to
  drift yet); remains realized for S10's replace path by construction — the guard already runs unconditionally.
- **In scope, newly reachable** — Scenario: Input changes concurrently. S07's scope-pins recorded this as
  "not yet applicable... reachable once S10 (replace an existing generation) exists." S10 is exactly that
  slice: a second release attempt for a publication that already has a managed generation, racing a change to
  the declared input (the selected approved snapshot's hashes) between planning and commit. The existing
  scenario text ("a declared release input changes after planning... release is blocked and existing live
  site trees remain unchanged") already says exactly what S10 must do — no new scenario, no wording change.

### Requirement: REL-05 Replace only exporter-managed site trees atomically

Fully in scope for S10, no gap — again, exactly the two scenarios S07's scope-pins named as S10's job:

- **In scope** — Scenario: Staged site content is valid. Already realized by S07 for the first-generation
  case; S10 proves the same "only declared managed roots are replaced... code-owned templates remain
  byte-identical" guarantee holds when replacing an existing generation, which is what "replaced" already
  implies literally (S07's own test necessarily exercised the create case; this scenario's wording was never
  restricted to it).
- **In scope, newly reachable** — Scenario: Staged content or filesystem is unsafe. S07's scope-pins recorded
  this as not yet applicable ("'Live managed trees remain at the prior complete generation' presupposes a
  prior generation. S07... has no 'prior generation' state to remain at"). S10 has one, making this scenario
  reachable for the first time with no wording change needed.
- **In scope, newly reachable** — Scenario: Installation is interrupted. S07's scope-pins recorded this
  identically: "Recovery 'to one complete old or new generation' requires an old generation to be a valid
  recovery target. S07 has none; interrupted-install recovery is S10's job." No new scenario, no wording
  change — S10 makes the existing text reachable.
- **Empty-destination install** (S07's own delta) remains untouched and unaffected — it stays the
  first-generation case, which S10 does not change.

### Requirement: REL-06 Gate Astro builds on content ownership and provenance

Not touched. Both scenarios ("Generated content is coherent" / "Generated content violates a gate") are
already fully realized by S07 for the first-generation case and, by the same "no first/second distinction in
the wording" reasoning as REL-03 above, continue to hold for a replaced generation without any change to
`check-content.mjs` or the gate mechanism itself — S10 only needs `SiteReleaseManifest`'s provenance to stay
accurate across a replace, which is REL-03's job, not REL-06's.

### Requirements REL-01, REL-02

Not touched. REL-01 (approved-snapshots-only authority) and REL-02 (semantic projection) are realized by
S06/S07 and unaffected by replace mechanics — S10 changes *how* a generation is installed/replaced, not
*what* it's built from or projects.

## Workflow bridge, review-and-approval, translation-preparation, semantic-references

Not touched. `install-to-site` remains outside `bridge-contract/schema-v2.json`'s command enum (confirmed by
S07's technical design, unchanged here) and produces no `BridgeResponse`. S10 introduces no new approval,
translation, or semantic-occurrence concept.

## Conclusion

No file is written under `specs/` for this change: the functional collaborative-design pass found that
REL-03, REL-04, and REL-05 are all realized as-worded by this slice, with zero requirement text or scenario
changes required. All gaps found are implementation gaps, tracked in `design.md` and `tasks.md`.
