## MODIFIED Requirements

### Requirement: RVA-05 Install approved snapshots atomically and recoverably

The approved RU, EN, and semantic-reference map SHALL become visible as one coherent snapshot; a crash or write failure SHALL expose either the prior complete snapshot or the new complete snapshot, never a mixed one.

#### Scenario: Approval completes
- **GIVEN** a valid candidate and safe review workspace
- **WHEN** approved installation succeeds
- **THEN** all approved files correspond to the candidate hashes and become visible as one atomic unit before success is reported

#### Scenario: Approval is interrupted
- **GIVEN** failure occurs during approved installation
- **WHEN** the workspace is next inspected or approval is retried
- **THEN** recovery deterministically restores or completes one coherent snapshot
- **AND** the outcome is reported rather than silently guessed

#### Scenario: A second approval is attempted
- **GIVEN** an approved snapshot already exists for the publication
- **WHEN** `mark-reviewed` is invoked again
- **THEN** the request is blocked rather than silently replacing or silently ignoring the existing snapshot
- **AND** the existing approved snapshot remains exactly as it was

## Why this is a real delta, not a scope pin

Neither existing RVA-05 scenario, nor RVA-03's or RVA-04's scenarios, describes what happens when `mark-reviewed` is invoked a second time for a publication that already has an approved snapshot. The implementation plan's own S05 entry explicitly excludes "replacing an existing approved snapshot" from this slice ("a second approval fails closed until S09") — this is genuinely new observable behavior a create-only install mechanism must produce, not a case any existing scenario's GIVEN clause already covers. The new scenario closes that gap as a permanent, first-class addition to the baseline, following the same reasoning S02 gave RVA-01's all-absent scenario, S03 gave SEM-03's empty-map scenario, and S04 gave RVA-01's first-publication scenario.

## Spec correction: "Approval completes" no longer claims crash-survival durability

S05's final whole-branch review found that the pre-existing "Approval completes" scenario text ("...are durable before success is reported") overstated what `StandardCopyOption.ATOMIC_MOVE` actually guarantees: atomic, all-or-nothing *visibility* of the rename, not survival of a concurrent power loss (`rename(2)` is atomic, not durable — durability requires `fsync` on the staged files and the destination directory, which `FilesystemApprovedSnapshotWorkspace` does not perform). The requirement's own first sentence ("a crash or write failure SHALL expose either the prior complete snapshot or the new complete snapshot, never a mixed one") was always the accurate claim and needed no change; only the scenario's restatement of it as "durable" was corrected to "become visible as one atomic unit," matching what the implementation actually does and has ever done since `FilesystemCandidateWorkspace#install`'s original, already-reviewed convention. Crash-survival durability (fsync-backed) remains unaddressed and is not required by any S01-S09 scenario; if it's ever wanted, it needs its own explicit requirement and scenario, not an implicit reading of "durable."

## Not touched by this change

RVA-03 ("Operator approves an exact candidate", "Non-approval command runs") and RVA-04 ("Candidate remains exact", "Candidate or source changed") already carry scenario text that exactly describes the rest of this slice's mechanism with no gap — see `scope-pins.md`. RVA-06 (post-approval immutability/tamper detection) remains fully specified in the baseline and is unimplemented until S09.
