## MODIFIED Requirements

### Requirement: RVA-05 Install approved snapshots atomically and recoverably

The approved RU, EN, and semantic-reference map SHALL become visible as one coherent snapshot; a crash or
write failure SHALL expose either the prior complete snapshot or the new complete snapshot, never a mixed
one.

#### Scenario: Approval completes
- **GIVEN** a valid candidate and safe review workspace
- **WHEN** approved installation succeeds
- **THEN** all approved files correspond to the candidate hashes and become visible as one atomic unit before
  success is reported

#### Scenario: Approval is interrupted
- **GIVEN** failure occurs during approved installation
- **WHEN** the workspace is next inspected or approval is retried
- **THEN** recovery deterministically restores or completes one coherent snapshot
- **AND** the outcome is reported rather than silently guessed

#### Scenario: A second approval replaces the prior snapshot
- **GIVEN** an approved snapshot already exists for the publication and the new candidate passes RVA-04's
  full revalidation
- **WHEN** `mark-reviewed` installs the new snapshot
- **THEN** the new snapshot atomically replaces the prior one as one coherent unit
- **AND** no other workflow ever observes a mixed old/new snapshot

## Why this is a real delta, not a scope pin

RVA-05's third scenario previously read "A second approval is attempted... THEN the request is blocked rather
than silently replacing or silently ignoring the existing snapshot" — this was S05's deliberate, explicitly
temporary boundary ("A second approval fails closed until S09," per `implementation-plan.md`'s S05 entry), not
a permanent design decision. S09 is exactly the slice that lifts that boundary: a *revalidated* second
approval must now replace, atomically and recoverably, the same coherent-snapshot guarantee the first two
scenarios already describe. The old wording is not merely under-specified (like S07's REL-05 empty-destination
gap) — it actively contradicts this slice's purpose, so it is corrected rather than extended with a new
scenario alongside it.

The *stale* second-approval case (source or candidate changed since the review being approved) is
deliberately **not** re-stated as a new RVA-05 scenario: RVA-04's existing "Candidate or source changed"
scenario already covers it generically ("source bytes, candidate bytes, reference map, or paths differ...
approval is blocked... the prior approved snapshot remains exact") without distinguishing first from second
approval. Duplicating that scenario under RVA-05 would restate the same guarantee under the wrong requirement
ID. See `scope-pins.md` for the full record, including why RVA-04 and RVA-06 are realized, not modified.
