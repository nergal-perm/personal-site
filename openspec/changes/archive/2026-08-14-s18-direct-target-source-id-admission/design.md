## Context

`prepare` already resolves direct wikilink targets during S13's `LinkResolver.resolve(normalizedBody, knownNotes)` step, but `knownNotes` (`PublicNoteIndex`) only maps already-admitted **public** notes to routes — a private target is invisible to it and always falls through to S13's deliberate "safe label" / "blocked transclusion" treatment, with no read of the target's own identity. `VaultReader` (the one existing vault-reading port) can only enumerate `publish: true` files (`listPublishCandidates()`) and read/check-existence of a note given its *full* vault-relative path — there is no capability today to find an arbitrary **private** note by the filename stem a wikilink actually names, which is what SEM-01's target-identity check needs to read.

## Pipeline insertion point

```
PrepareHandler.prepare()
  noteIntake.admit(source)                     // SEM-01, own identity (S02)
  PublicNoteIndex.from(vaultReader, noteIntake) // public routing table (S13)
  MarkdownNormalizer.normalize(body)            // PCM-04
  LinkResolver.resolve(body, knownNotes)        // <- extend: also report direct target stems seen
    └─(NEW) DirectTargetIdentityCheck           // <- insert here: before ANY branch that can dispatch
         .verify(sourceId, targetStems, index)  //    a translation job or mutate a candidate
    ├─ blocked  -> BridgeResponse.blocked(metadata_blocked)     [NEW exit]
    └─ passed   -> AssetResolver.resolve(...)                   [existing S14 path]
                     -> prepareNormalizedEssay(...)
                          ├─ approved baseline unchanged -> mirrorApprovedCandidate  [no job dispatched]
                          └─ prepareWithInstallLock -> prepareAdmittedEssay
                                -> translationWorker.translate(job, ...)  [job dispatch — must not
                                                                            be reachable if blocked above]
```

Placing the check right after `LinkResolver.resolve` succeeds — before `prepareAfterAssetResolution` is even called — means it runs unconditionally on every `prepare()` call, including the approved-baseline-mirror replay. That is a deliberate choice, not an oversight: SEM-01 says identity is required "before semantic preparation **or release**," and the simplest, single insertion point (no branching duplication between the fresh-prepare and mirror-replay paths) is also the most conservative reading — it revalidates target identity on every call rather than only on calls that happen to produce a new candidate. The alternative (skip the check on the mirror-replay branch, since no new job or candidate write happens there) was considered and rejected: it would require two insertion points instead of one for a distinction SEM-01's wording does not draw, for a small slice whose explicit acceptance boundary is "no path fallback or automatic allocation," not "check only when convenient."

## New/changed types

### `VaultReader` (existing port, widened by one *default* method)

`VaultReader` has 14 additional ad hoc anonymous implementations scattered across
`PrepareHandlerTest`, `InspectPublicationHandlerTest`, `MarkReviewedHandlerTest`, and
`RefreshPublicationQueueHandlerTest` (`new VaultReader() { ... }`), none of which have anything to do with
link targets or identity — they exist to simulate freshness races, I/O failures, or malformed frontmatter
for entirely different commands. Adding a plain new abstract method would force a compile-time change to
all 14, which is real, unrelated blast radius this slice's Haft problem does not budget for. Adding a
*default* method keeps every one of them compiling unchanged, and is safe precisely because none of their
fixture bodies contain a direct wikilink target — `DirectTargetIdentityCheck` never queries the index
for a stem `LinkResolver` did not report, so an unrepresentative default answer for those 14 is never
observed:

```java
public interface VaultReader {
    ...
    List<VaultRelativePath> listPublishCandidates();   // existing: publish:true only

    // NEW. Default delegates to the publish-only listing, which is a safe (if narrower) answer for the
    // many test doubles that only ever exist to simulate a single already-admitted note and never model a
    // second, private note at all. Only the two real collaborators — FilesystemVaultReader and
    // NullVaultReader — override it with the true unfiltered listing SEM-01's target-identity check needs.
    default List<VaultRelativePath> listAllNotePaths() {
        return listPublishCandidates();
    }
}
```

No new adapter is introduced. `FilesystemVaultReader` refactors its existing `Files.walk` + confinement +
`.md`-filter + sort pipeline into a shared private `listMarkdownFiles()` helper, then `listPublishCandidates()`
adds the `hasPublishTrueFlag` filter on top and the overridden `listAllNotePaths()` does not — this is the
one refactor this slice's red-green-refactor cycle justifies (duplicating the walk instead would drift the
two listings' confinement/sort behavior apart over time). `NullVaultReader` overrides it with the symmetric
unfiltered variant of its existing stream pipeline. Both overrides are small, mechanical, and covered by the
existing adapter contract test pattern (same fake/real pair, one shared contract).

### `PrivateNoteIdentityIndex` (new, in-process, `prepare` package — sibling to `PublicNoteIndex`)

```java
final class PrivateNoteIdentityIndex {
    static PrivateNoteIdentityIndex from(VaultReader vaultReader) { ... }
    Optional<TargetIdentity> identityFor(String filenameStem) { ... }
}

record TargetIdentity(Optional<String> sourceId) { }
```

Built once per `prepare()` call (same lifecycle as `PublicNoteIndex.from(...)`) by walking `vaultReader.listAllNotePaths()`, grouping by filename stem (identical stem-extraction helper to `PublicNoteIndex`'s), and reading each file's `id` frontmatter via the existing `MarkdownNote.parse(vaultReader.readSource(path)).string("id")` — no dependency on `NoteIntake`/`PublicationKinds`; private targets are never kind-admitted, only identity-checked.

- `identityFor(stem)` returns **empty** when the stem does not resolve to exactly one vault file (not found *or* ambiguous — two private files sharing a stem). This mirrors `PublicNoteIndex.registerIfAdmitted`'s existing silent-drop-on-ambiguity precedent exactly, and both cases defer to S13's existing "safe label" / "blocked transclusion" treatment rather than introducing a new blocking case this slice's scope-pins don't name.
- `identityFor(stem)` returns **present** `TargetIdentity(Optional<String> sourceId)` when exactly one file matches; `sourceId` itself is empty when that file's `id` frontmatter is blank or absent — this is the "missing" half of the blocking scenario.

### `LinkResolver` / `LinkResolutionOutcome` (existing S13 type, extended)

`LinkResolver.resolve` already parses every wikilink once. Rather than re-scanning the body with a second regex pass (which would drift from the resolver's own link-eligibility rules — protected regions, asset embeds — over time), it is extended to also collect the filename stems of every *plain* (non-embed) link that fell through to "not in `knownNotes`" (today's safe-label branch), and expose them on success. An unresolved *embed* is not collected here: `appendLink` already returns it as a blocked transclusion unconditionally (S13), so `prepare` already fails closed for it before this check could ever run — collecting it too would be dead code for a case that never reaches the "resolved" success path in the first place.

```java
public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {
    static LinkResolutionOutcome resolved(String body, Set<String> privateTargetStems) { ... }
    static LinkResolutionOutcome blockedTransclusion(String target) { ... }   // unchanged

    <T> T resolve(
            BiFunction<String, Set<String>, T> onResolved,   // widened: body, plus the stems it collected
            Function<String, T> onBlockedTransclusion);       // unchanged
}
```

This is the one real touch to S13 code this slice makes, and it is contained entirely to the `prepare` package: `LinkResolver`'s own walk, `LinkResolutionOutcome`'s two implementations, and `LinkResolutionOutcome`'s two existing callers (`PrepareHandler.prepare()` and `PrepareHandler.sourceFreshness()`, both of which already destructure the resolved body via a lambda and simply gain one more lambda parameter) — plus `LinkResolverTest`'s resolved-body test helper and one direct-call test. It does not touch `VaultReader`, `PublicNoteIndex`, or any test outside this package, and it is additive to routing/labeling behavior, not a change to it — every existing S13 assertion on the *body* `LinkResolver` produces is unaffected.

### `DirectTargetIdentityCheck` (new, in-process, `prepare` package)

```java
final class DirectTargetIdentityCheck {
    static Result verify(String sourceId, Set<String> targetStems, PrivateNoteIdentityIndex index) { ... }
}
```

Pure function, no I/O of its own (the index is already built). For each stem in `targetStems`:
1. Look up `index.identityFor(stem)`; empty → skip (defers to S13, per scope-pins).
2. Present but `sourceId()` empty → block: target is missing a source ID.
3. Present with a `sourceId` equal to the source's own `sourceId`, or equal to another target's `sourceId` already seen in this same call → block: shared identity.
4. Otherwise: contributes to the "seen" set and continues.

A target stem that resolves to the source note itself (self-link) is excluded from step 3's comparison — the source's own identity is already validated once by S02's existing check; comparing it against itself would manufacture a false duplicate.

Returns a `BridgeResponse.blocked(COMMAND, diagnostic)` on the first failure (fail-closed on the whole `prepare()` call, not partial), or signals "passed" to let `prepareAfterAssetResolution` proceed — matching the existing `resolve(...)`-callback style used throughout `PrepareHandler`.

## Not in this design

- Occurrence IDs, `references.json` population, late-bound activation — S19/S20, untouched.
- Whole-vault duplicate detection between two notes neither the operator selected nor linked — S16.
- Any change to what `LinkResolver` renders (routes, labels, blocked-transclusion messages) — unchanged; this design only adds a read of stems it already computes internally.
