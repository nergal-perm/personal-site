## Why

S18 and S19 closed stable source identity and a stable, ordered semantic occurrence map (`ReferenceMap.occurrences()`, each `Occurrence(id, order, targetSourceId, ruLabel, enLabel)`). S19's own `design.md` named the remaining gap explicitly: today, `LinkResolver.resolve()` finalizes every occurrence's route or plain-label rendering *eagerly, at prepare time*, based on whether the target is currently *admitted* in the vault (`PublicNoteIndex.routeFor()` — `publish: true` and valid) — not on whether the target currently has a *selected, complete approved snapshot*. Once a referrer is approved, its baked-in `[label](route)` or bare `label` text is frozen forever in `CandidateSnapshot.ruBody()`/`enBody()`, and `BuildFromReviewHandler`/`ReleaseOutputStore`/`InstallToSiteHandler` all pass that frozen text straight through to release with zero re-evaluation. `ReferenceMap.occurrences()` is written and read back for translation-preservation only (`PrepareHandler.previousOccurrencesFor()`); nothing downstream ever consults it.

This means a link to a not-yet-approved target can never activate without editing and reapproving the referrer, and a link to a target that later gets unpublished never deactivates — both directly contradict SEM-04/SEM-05 (already normatively specified in `openspec/specs/semantic-references/spec.md`) and REL-02/REL-03 (`openspec/specs/release-materialization/spec.md`), which require release to resolve each occurrence against the target's *current* approved-and-selected state, independently of when the referrer itself was last approved. `ReleaseProvenance.activationCount()`/`deactivationCount()` already exist as fields but are hardcoded to `0` — a reserved, unimplemented placeholder for exactly this slice. (`SiteReleaseManifest.activationCount()`/`deactivationCount()` are a separate, whole-site-tree-hash concept unrelated to per-publication occurrence resolution — out of scope here; see Impact.)

## What Changes

- **`LinkResolver` stops baking a route (or a permanent bare label) into the body for an admitted target.** Instead, for any link whose target resolves to a currently-admitted note, it emits a durable, release-resolvable marker — `[label](ref:<targetSourceId>)` — reusing the `](ref:...)` convention already validated as prior art in the legacy `exporter-java` oracle (read-only reference, not a code donor). Links to private, unresolved, or ambiguous targets are unaffected: they still become a permanent bare label at prepare time, per PCM-03's existing safety scenario — this slice narrows PCM-03's scope to *admitted* targets only, it does not touch the private/unresolved/ambiguous path.
- **A new release-time occurrence-resolution step** runs between `ApprovedSnapshotWorkspace.read()` and `ReleaseOutputStore.install()` inside `BuildFromReviewHandler`. It scans the approved body for `ref:<targetSourceId>` markers, looks each one up against a live, this-release's approved-target registry (built from every currently selected+approved snapshot being released together), and substitutes a genuine locale-prefixed route (`/ru/...`/`/en/...`, synthesized from the existing locale-neutral `routePrefix()`/`publicId` shape plus a language segment) and the occurrence's stored `ruLabel`/`enLabel` when the target is currently approved and selected — or the stored label as plain text, with the marker fully stripped, when it is not. The approved snapshot's own stored bytes and hashes never change; only the *materialized release output* differs.
- **`ReleaseProvenance` activation/deactivation counts become real**, computed by comparing this release's resolved occurrence states against the prior release's (from provenance), closing REL-03.
- **`InstallToSiteHandler` applies the same resolution before handing a snapshot to `ManagedSiteInstaller`**, so the actually-published site content never contains a literal, unresolved `ref:` marker — this is a correctness requirement (REL-02's "no semantic token... leak"), not just a provenance-counting concern.
- **`ReferenceMap.occurrences()` becomes load-bearing at release time**, not just a translation-preservation artifact.

## Capabilities

### New Capabilities

(none — SEM-04, SEM-05, REL-02, and REL-03 are already specified in the baseline; see design.md for the concrete mechanism)

### Modified Capabilities

- `semantic-references`: SEM-04 and SEM-05 are implemented for the first time — occurrences resolve against live target-approval state at release time, independent of referrer approval timing.
- `release-materialization`: REL-02 (bilingual semantic projection without private leaks) and REL-03 (activation-count provenance) are implemented for the first time.
- `public-content-model`: PCM-03's scope is narrowed — for a link to a currently-*admitted* target, prepare-time output SHALL contain a durable release-resolvable marker (not a baked route); the previously-specified locale-neutral route text is now what release materializes from that marker, not what prepare emits directly. The private/unresolved/ambiguous-target scenario is unchanged.

## Impact

- `LinkResolver`/`LinkOccurrence` (prepare-time body construction) — marker format changes for the admitted-target case only.
- New release-time component(s): an approved-target registry (built from the snapshots being released together) and an occurrence-resolution/body-rewrite step, invoked from `BuildFromReviewHandler` before `ReleaseOutputStore.install()`.
- `ReleaseProvenance`: real activation/deactivation counting instead of a stub. `SiteReleaseManifest`'s activation stub is untouched (separate, whole-site-tree-hash concept, out of scope).
- `InstallToSiteHandler`: applies the same marker resolution as `BuildFromReviewHandler` before installing to the managed site tree.
- No changes to `ApprovedSnapshotWorkspace`/`CandidateSnapshot`'s stored shape, `MarkReviewedHandler`, or any candidate/approval workflow surface — approved bytes are never rewritten; only release-materialized output differs per release.
- No migration or automatic rewriting of any approved referrer (explicitly excluded, per `openspec/implementation-plan.md`'s S20 boundary; belongs to the later, conditional S21-S23 milestone).
- Governed by Haft problem `prob-20260818-bc96093f`, sub-problem of `prob-20260803-fe9b3011` (greenfield exporter slice sequencing).
