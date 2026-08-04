# S02 scope pins

These notes record requirement scope that S02 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the two real deltas, while this change retains its scope evidence.

## Publication admission

`openspec/specs/publication-admission/spec.md` already fully specifies ADM-01 through ADM-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### ADM-02 — Confine note requests to the vault

Fully in scope for S02, though the mechanism (`VaultRelativePath.isWithinVault()` and
`FilesystemVaultReader`'s symlink-safe existence check) was already built in S01 to satisfy S01's own
`inspect-publication` blocked path. S02 is the first slice that formally claims this requirement, since
S01's proposal scoped itself to BRG-01/02/03 only.

- **In scope** — Safe relative path is admitted (a valid `blog/essay` note reached by a vault-relative
  path is admitted for inspection).
- **In scope** — Escaping path is blocked (already exercised by S01's blocked-response tests; reconfirmed
  here as the entry gate before identity evaluation).

### ADM-03 — Require a unique publication identity and supported kind

In scope for S02: `blog/essay` only. Other collection/content-type pairs are not evaluated as supported
kinds until their own S17 slice.

- **In scope** — A supported kind is accepted, restricted to `essay` selecting exactly one supported
  publication kind (`publish: true`, a lowercase-slug `publicId`, `publicCollection: "blog"`,
  `publicContentType: "essay"`).
- **In scope** — Ambiguous or incomplete identity is blocked, restricted to essay-relevant causes:
  missing/false/non-boolean `publish`, missing or invalid-slug `publicId`, an unsupported or mismatched
  `publicCollection`/`publicContentType` pair, or a duplicate publication identity.
- **Deferred** — Duplicate-identity detection across two selected notes requires whole-vault discovery
  (S16); S02 exercises this scenario only for the single inspected note against no other known notes.

### ADM-04 — Enforce kind-specific source contracts

In scope for S02: `blog/essay` only, which carries no kind-specific metadata or body-section requirement
beyond the identity fields ADM-03 already covers. Every note that passes ADM-03 as an essay also passes
ADM-04.

- **In scope** — Kind-specific contract is complete, restricted to essay.
- **Not applicable to essay** — The incomplete scenario becomes reachable with the first kind that has a
  kind-specific requirement (`bibliography/book`, S17c).
- **Deferred** — Every other kind's contract waits for its own S17 slice; no generic kind-requirement
  framework is introduced here for a single observed kind.

ADM-01, ADM-05, and ADM-06 remain fully specified in the baseline and are unimplemented until S16 and
S15/S17 respectively.

## Semantic references

`openspec/specs/semantic-references/spec.md` already fully specifies SEM-01 through SEM-05 as the target
end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### SEM-01 — Require stable source-owned semantic identities

In scope for S02: the selected source note's own source ID only. S02 excludes links entirely (S13
introduces link resolution, S18 introduces direct-target source-ID admission for referenced notes), so
there is no target to evaluate yet.

- **In scope** — All required source IDs are valid, restricted to the single inspected essay having a
  stable, unique, human-assigned source ID.
- **In scope** — A required source ID is absent or duplicated, restricted to the inspected essay itself;
  the result is `metadata_blocked` before any candidate mutation.
- **Deferred** — The direct-private-target part of both scenarios waits for S18.

SEM-02, SEM-03, SEM-04, and SEM-05 remain fully specified in the baseline and are unimplemented until
S19 and S20 respectively.
