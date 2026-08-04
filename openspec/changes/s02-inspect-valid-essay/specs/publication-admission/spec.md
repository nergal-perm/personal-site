# Publication admission — S02 scope pin (no delta)

This change adds no new requirement text and modifies none. `openspec/specs/publication-admission/spec.md`
already fully specifies ADM-01 through ADM-06 as the target end state, derived directly from
`openspec/requirements-baseline.md` ahead of any implementation. This file exists only to pin,
scenario by scenario, exactly which already-baselined behaviour S02 realizes — per
`openspec/implementation-plan.md`'s S02 boundary and Haft problem `prob-20260804-60dfda6c`.

**Tooling note:** this file intentionally carries no `## ADDED/MODIFIED/REMOVED/RENAMED Requirements`
section, so `openspec validate` will report "no delta sections found" for it — that is correct, not
an omission. This file itself carries no delta, but the change also contains real `review-and-approval`
and `workflow-bridge` deltas. Archive the whole change with `openspec archive s02-inspect-valid-essay`,
per Task 10; do not use `--skip-specs` for this change.

## Requirement: ADM-02 Confine note requests to the vault

Fully in scope for S02, though the mechanism (`VaultRelativePath.isWithinVault()` and
`FilesystemVaultReader`'s symlink-safe existence check) was already built in S01 to satisfy S01's own
`inspect-publication` blocked path. S02 is the first slice that formally claims this requirement, since
S01's proposal scoped itself to BRG-01/02/03 only.

- **In scope** — Scenario: Safe relative path is admitted (a valid `blog/essay` note reached by a
  vault-relative path is admitted for inspection).
- **In scope** — Scenario: Escaping path is blocked (already exercised by S01's blocked-response tests;
  reconfirmed here as the entry gate before identity evaluation).

## Requirement: ADM-03 Require a unique publication identity and supported kind

In scope for S02: `blog/essay` only. Other collection/content-type pairs are not evaluated as
supported kinds until their own S17 slice.

- **In scope** — Scenario: Supported kind is accepted, restricted to `essay` selecting exactly one
  supported publication kind (`publish: true`, a lowercase-slug `publicId`, `publicCollection: "blog"`,
  `publicContentType: "essay"`).
- **In scope** — Scenario: Ambiguous or incomplete identity is blocked, restricted to essay-relevant
  causes: missing/false/non-boolean `publish`, missing or invalid-slug `publicId`, an unsupported or
  mismatched `publicCollection`/`publicContentType` pair, or a duplicate publication identity.
- **Deferred** — Duplicate-identity detection across two selected notes requires whole-vault discovery
  (S16); S02 exercises this scenario only for the single inspected note against no other known notes.

## Requirement: ADM-04 Enforce kind-specific source contracts

In scope for S02: `blog/essay` only, which — per the compatibility oracle's `PublicationKind` table —
carries no kind-specific metadata or body-section requirement beyond the identity fields ADM-03 already
covers. For this one kind, the requirement is satisfied vacuously: every note that passes ADM-03 as an
essay also passes ADM-04, because essay adds no further obligation.

- **In scope** — Scenario: Kind-specific contract is complete, restricted to essay (trivially true once
  ADM-03 passes; no additional field or body section is checked).
- **Not applicable to essay** — Scenario: Kind-specific contract is incomplete never fires for `essay` in
  this slice, since essay has no kind-specific requirement capable of being incomplete. It becomes
  reachable starting with the first kind that does carry one (`bibliography/book`, S17c).
- **Deferred** — Every other kind's contract (claim, book, album, concept, curated page) waits for its
  own S17 slice; no generic kind-requirement framework is introduced here for a single observed kind.

## Not touched by this change

ADM-01, ADM-05, and ADM-06 remain fully specified in the baseline and are unimplemented until S16 and
S15/S17 respectively. Their requirement text is unaffected here.
