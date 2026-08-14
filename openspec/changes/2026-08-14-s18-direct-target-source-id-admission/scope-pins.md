# S18 scope pins

These notes record requirement scope that S18 realizes but does not modify, following the same convention
established in S02's `scope-pins.md`. They are stored outside `specs/` so OpenSpec archives only the real
delta, while this change retains its scope evidence.

## Semantic references

`openspec/specs/semantic-references/spec.md` already fully specifies SEM-01 through SEM-05 as the target
end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### SEM-01 — Require stable source-owned semantic identities

S02 realized the source note's own identity only, explicitly deferring "the direct-private-target part of
both scenarios" to S18 (see `openspec/changes/archive/2026-08-04-s02-inspect-valid-essay/scope-pins.md`).
S18 realizes that deferred half, with the following scope narrowing carried forward as a pin rather than a
spec-wording change:

- **In scope** — A direct private target is a *plain* wikilink (`[[...]]`, not `![[...]]`) in the prepared
  body whose target resolves to an existing vault file that is not itself an admitted public note (i.e. not
  present in `PublicNoteIndex` — the same set S13's `LinkResolver` already renders as a safe label). That
  file's `id` frontmatter is read and checked for presence and uniqueness. An *embed* (`![[...]]`) of a
  non-public target is unaffected by this slice: `LinkResolver` already unconditionally blocks it as a
  transclusion before this check could run, so it already fails closed today, just via S13's own
  "not a public note" diagnostic rather than a SEM-01 identity diagnostic — both are `prepare` failing
  closed with no job or candidate mutation, so no behavior gap exists for embeds.
- **In scope** — "Shares one with another note" (the existing scenario's duplicate wording) is evaluated
  only among the notes actually touched by this prepare operation: the source note and its own direct
  private targets. This mirrors S02's identical narrowing of ADM-03's duplicate-identity scenario:
  "Duplicate-identity detection across two selected notes requires whole-vault discovery (S16); S02
  exercises this scenario only for the single inspected note against no other known notes." S18 makes the
  same call for SEM-01: true whole-vault duplicate detection (any two arbitrary vault notes, neither of
  which the operator selected or linked) waits for S16.
- **In scope** — Public link targets are excluded from this check. A public target was already admitted
  through its own `PublicationKind`, which already requires a valid, unique `id` (ADM-03/ADM-04); rechecking
  it here would be redundant, not a new guarantee.
- **Deferred / not applicable** — A wikilink target that does not resolve to any existing vault file at all
  (a broken link or typo) is not evaluated by this check; it keeps S13's existing "safe label" rendering
  unchanged. SEM-01 governs the identity of real referenced notes, not the validity of link syntax — that
  remains S13's concern.
- **Deferred / not applicable** — A self-referencing link (the source linking to itself) is not evaluated as
  a second note sharing the source's ID; the source's own identity is already validated once by the
  existing source-side SEM-01 check.

SEM-02, SEM-03, SEM-04, and SEM-05 remain fully specified in the baseline and are unimplemented until S19
and S20 respectively.
