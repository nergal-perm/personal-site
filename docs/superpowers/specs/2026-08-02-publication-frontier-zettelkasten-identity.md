# Publication-Frontier Zettelkasten Identity

## Authority

This is the durable implementation-facing projection of the binding Haft
decision
[`dec-20260802-bind-the-vanilla-publication-frontier-zettelkast-b77c183c`](../../../.haft/decisions/dec-20260802-bind-the-vanilla-publication-frontier-zettelkast-b77c183c.md),
selected from the focused portfolio
[`sol-20260802-2618001e`](../../../.haft/solutions/sol-20260802-2618001e.md)
for problem
[`prob-20260802-1803dd18`](../../../.haft/problems/prob-20260802-1803dd18.md).
The operator acceptance of human-assigned pre-publication IDs is recorded in
[`note-20260802-ed0ebb76`](../../../.haft/notes/note-20260802-ed0ebb76.md).

If this document conflicts with the DecisionRecord, the DecisionRecord wins.
The graph-bearing and lazy-allocation recommendations in older semantic-link
documents are historical and are not implementation authority.

Governance baseline: Git commit `063cb11`.

## Decided architecture

Every note entering semantic publication has a human-assigned source
frontmatter `id`. The same requirement applies to every uniquely resolved
direct vault-note target referenced by the note being prepared. The source ID
is durable semantic identity; `publicId`, routes, titles, aliases, and paths are
not fallback identity.

The exporter is authoritative. It reads current vault bytes, admits the
publication frontier, builds route-independent target references, and blocks
ambiguity or invalid identity before candidate generation, translation,
approval, or publication can advance. Any catalog or index is derived from
source notes and can be rebuilt.

The Obsidian command remains a thin note-path bridge. No graph cache, backlink
view, or plugin-supplied identity participates in admission or approval. A graph
lens may be reconsidered only when the DecisionRecord's runtime-friction trigger
is crossed.

## Terms

- **Source ID:** a nonblank YAML string in frontmatter field `id`. For the
  current compatibility floor, `/` and `\` are invalid. No numeric-only or
  timestamp-only format is imposed.
- **Current note:** the vault-relative Markdown note passed to Prepare.
- **Direct target:** a non-embedded inline Obsidian wikilink occurrence outside
  escaped or protected Markdown contexts.
- **Uniquely resolved target:** a direct target that the exporter maps to
  exactly one current vault note using authored path, filename stem, title, or
  alias. These are lookup clues, not semantic identity.
- **Publication frontier:** the current note plus the deduplicated uniquely
  resolved direct targets, ordered with the current note first and targets by
  their first authored occurrence.
- **Admission:** a read-only check that returns all source-ID defects in the
  frontier in one deterministic result.

## Global invariants

1. A human assigns or repairs source IDs; the exporter never invents or edits
   one.
2. A source ID is globally unique across readable Markdown notes in the vault.
3. Missing, blank, non-string, slash-containing, backslash-containing, copied,
   duplicate, unreadable, invalid-UTF-8, or invalid-frontmatter identity blocks
   semantic Prepare.
4. When an existing catalog entry records a non-null stable ID for the same
   current path, a different source ID blocks as an identity change. Complete
   rename-safe immutability belongs to the later identity-index slice.
5. Unresolved and ambiguous links retain their existing fail-closed planner
   behavior. The ID gate neither guesses their destination nor converts them
   into identities.
6. Admission cannot write the source note, catalog, candidate, translation job,
   approved snapshot, review baseline, or publication output.
7. `PageReferenceMap` remains authoritative for actual approved referrer impact.
8. New targets and legacy pages pass through ordinary Prepare, review, and
   `mark-reviewed`; no existing referrer is silently rewritten or reapproved.
9. `migrate-semantic-links --apply` remains separately approval-gated and is not
   part of this implementation slice.

## First implementation slice: fail-closed identity admission

The first slice implements only the read-only admission boundary. It does not
change persistent `targetRef`, catalog schema, reconciliation, migration, or
Obsidian integration.

### Required flow

1. Existing publication preflight loads the current note.
2. Semantic mode and the semantic-operation lease are established.
3. Before `resolveEntry`, publication-lock creation, job-directory creation,
   translation, workflow-state mutation, or candidate staging:
   - scan real, non-symlink Markdown notes beneath the vault root;
   - parse the current body with the same wikilink/protected-span rules used by
     `SemanticReferencePlanner`;
   - resolve direct targets against the live note descriptors;
   - build the deterministic frontier;
   - collect every identity defect.
4. If any defect exists, return `PrepareResult.status() == "metadata_blocked"`
   with blocking `PublicationDiagnostic` entries whose field is `semantic-id`.
5. If no defect exists, continue through the unchanged semantic Prepare flow.

The existing semantic-operation lock may create or touch its operational lock
leaf. That leaf is not publication or review content and should be excluded
from byte-for-byte no-mutation assertions.

### Required code boundaries

- `VaultNoteDescriptor` retains the declared string ID separately from the
  sanitized usable ID so diagnostics remain actionable.
- `SemanticReferencePlanner.directTargets(String body)` exposes its existing
  parser's ordered, non-embed authored targets; no second wikilink parser is
  introduced.
- `VaultNoteTargetResolver` resolves authored targets to live descriptors and
  reports resolved, ambiguous, or unresolved without manufacturing identity.
- `PublicationFrontierIdentityAdmission.inspect(...)` constructs and validates
  the frontier and returns all blocking diagnostics without writes.
- `PrepareWorkflow` invokes admission before `resolveEntry` and uses a pure
  `identityBlocked(...)` result helper. It must not reuse `metadataBlocked(...)`
  because that helper updates source workflow metadata.

## First-slice acceptance

The slice is accepted only when tests demonstrate all of the following:

- valid IDs on the current note and every direct target admit Prepare;
- a missing current-note ID blocks before the translation runner starts;
- one call reports every missing, malformed, duplicate, copied, or changed ID
  in deterministic frontier order;
- an otherwise unrelated vault note copying a frontier ID makes the frontier
  ID non-unique and blocks;
- repeated occurrences of one target produce one repair item;
- embeds, escaped wikilinks, protected-context wikilinks, unresolved targets,
  and ambiguous targets do not get guessed into the identity frontier;
- source, catalog, jobs, candidate, approved snapshot, and publication bytes
  remain unchanged on identity failure;
- existing semantic Prepare tests use explicit source IDs and still reach their
  prior expected outcomes;
- focused reference and Prepare tests pass, followed by the complete Maven test
  suite.

## Explicitly deferred slices

The following remain necessary for the complete DecisionRecord but are not part
of the first commission:

1. Derive route-independent `pageRef`/`targetRef` from source IDs, rebuild the
   catalog/index, remove numeric allocation, and prohibit path-first identity
   reconciliation.
2. Revalidate source IDs at candidate commit and approval boundaries so a
   concurrent ID edit cannot pass.
3. Implement fresh semantic-site initialization around the source-owned index.
4. Exercise the ordinary Prepare/review legacy-upgrade route in a populated
   review-workspace rehearsal without real apply.
5. Collect the first 20 real Prepare observations and reconsider the optional
   graph lens only if more than four avoidable ID-discovery retries occur.

Completing the first slice must therefore be measured as partial progress
toward the binding decision, never as verification of the entire decision.
