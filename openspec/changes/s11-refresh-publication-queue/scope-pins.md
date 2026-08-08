# S11 scope pins

These notes record the functional and technical collaborative-design passes over S11's requirement set. The
one genuine delta (BRG-04's missing "approved-only" scenario) is in `specs/workflow-bridge/spec.md`. Everything
else below is realized, not modified, or deliberately narrowed — recorded here so the scope evidence survives
archival even though `specs/` only carries the real delta.

## Workflow bridge

`openspec/specs/workflow-bridge/spec.md` already fully specifies BRG-01 through BRG-07.

### Requirement: BRG-01 Support the plugin command set without shell interpretation

Realized for its refresh clause: `refresh-publication-queue` is the missing fourth command, taking no
current-note argument, wired through picocli argument boundaries exactly like the existing four commands — no
requirement-text gap.

### Requirement: BRG-05 Use the six-state workflow vocabulary consistently

Realized as specified. The one thing worth recording: `not_prepared` is not one of BRG-05's six enumerated
values, and is not meant to be — BRG-04's own "No publication work has started" scenario already frames it as
a distinct, deliberate zeroth state ("a workflow status that reflects 'admitted, nothing prepared yet' rather
than collapsing to `metadata_blocked`"), not a contradiction of BRG-05's enum. `WorkflowStateClassifier`
produces it as a legitimate seventh response value alongside the six; see `design.md` D2.

### Requirement: BRG-06 Refresh queue state without disturbing active work

Realized as specified, but its "Translation lock is active" scenario is satisfied **by construction, not by
detection**. `prepare` is fully synchronous end-to-end and never writes to the source note while translation is
running — only after it completes (successfully, on failure, or on staleness) — so there is no window in which
a concurrently-invoked `refresh-publication-queue` could observe or disturb in-flight translation state through
the source note itself. Building active detection would require a new identity-scoped, cross-process job marker
that nothing today provides: `TranslationJob.id()` is an anonymous random UUID with no link to
`PublicationIdentity`, and `JobWorkspace` cleans up its directory in the same process before returning, so
nothing durable and identity-addressable survives for a second process to read. This slice proceeds on the
documented operational assumption that `refresh-publication-queue` is not invoked mid-translation.
`WorkflowState.TRANSLATING` remains a valid BRG-05 vocabulary value; no code path in this slice produces it. If
that operational assumption changes, revisit with a real identity-scoped job marker rather than retrofitting one
under time pressure.

### Requirement: TRP-06 (translation-preparation) Update only exporter-owned workflow scalars

Realized exactly as already written — no requirement-text change. Its "duplicate, aliased, or malformed workflow
keys" scenario is not implemented as a distinct detection path: `Frontmatter.parseHeader` is all-or-nothing (any
malformed/duplicate key anywhere in the block collapses the whole frontmatter map to empty), so such a note
already fails `EssayAdmission` upstream as `metadata_blocked` before either the classifier or the new
`WorkflowStatusEditor` is reached. "Aliased" has no concrete target in this codebase — no second/legacy workflow
key exists to alias `workflowStatus` against. The genuinely reachable half of this scenario — "the source note
changes after validation" — is fully implemented via `FilesystemWorkflowStatusEditor`'s hash-compare-before-write
guard, reusing `PrepareHandler.sourceStillMatches()`'s proven shape from S08. See `design.md` D2 and D4.

### Requirements BRG-02, BRG-03, BRG-04 (except the added scenario), BRG-07

Not touched beyond the one BRG-04 addition recorded in `specs/workflow-bridge/spec.md`. BRG-02/BRG-03 (schema v2,
single-sourced contract) are realized by S01 and unaffected — `refresh-publication-queue` emits the same
`BridgeResponse` shape every other command already does. BRG-07 (editor-launch integration) is realized by S04
and unaffected — this slice adds no review-plan or editor-launch behavior.

## Public content model, translation preparation (except TRP-06), review and approval, release materialization,
semantic references

Not touched. This slice adds no new candidate/approved/release semantics, no new content kind, no semantic
occurrence concept, and no change to `mark-reviewed`, `build-from-review`, or `install-to-site`.

## Conclusion

Only `workflow-bridge` BRG-04 is a real requirement-text delta. BRG-01, BRG-05, BRG-06, and TRP-06 are realized
as-is, with two of their scenarios (BRG-06's translation-lock scenario, TRP-06's duplicate/aliased-key scenario)
satisfied by construction rather than by dedicated detection logic — both recorded above with the reasoning for
why that's sound now and what would need to change to revisit it. Every other requirement and capability is
untouched by this slice.
