## Context

`LinkResolver.resolve(body, knownNotes)` already parses every wikilink once during `prepare`'s step 4, but it throws per-occurrence identity away: public targets are inlined directly as `[label](route)` and forgotten; private targets collapse into an unordered, deduplicated `Set<String>` used only by S18's `DirectTargetIdentityCheck`. By the time `resolvedBody` reaches `translationWorker.translate(job, ruBody, ruFields)` at step 9, wikilink syntax is gone entirely — public occurrences are markdown links, private occurrences are indistinguishable plain prose. This matters because TRP-05 requires detecting a translation that reorders or invents occurrences, and a private occurrence that has already degraded into bare text has nothing left to compare. `ReferenceMap.occurrences()` is a placeholder that always returns `List.of()`. S18's `PrivateNoteIdentityIndex` already scans the *whole* vault via `listAllNotePaths()` (not just private notes), so it can already resolve source IDs for both public and private targets — it is narrower in name than in actual capability.

## Pipeline insertion points

```
PrepareHandler.prepare()
  ...
  LinkResolver.resolve(normalizedBody, knownNotes)      // <- widen: report ordered List<LinkOccurrence>
    resolvedBody unchanged in content/shape              //    instead of/alongside Set<String> stems
    (private label) or (public "[label](route)") as today

  DirectTargetIdentityCheck.verify(...)                  // <- unchanged logic; Set<String> derived from
                                                           //    occurrences where route().isEmpty()

  VaultSourceIdentityIndex.from(vaultReader)              // <- rename of PrivateNoteIdentityIndex; build
     (renamed, same implementation)                       //    whenever occurrences is non-empty, not only
                                                           //    when private stems exist — public occurrences
                                                           //    need target source IDs too (SEM-03)

  AssetResolver.resolve(...)                              // unchanged
  lookupApprovedBaseline / diff                           // unchanged — still diffs plain resolvedBody

  prepareAdmittedEssay:
    OccurrenceAssignment.assign(                          // <- NEW: positional reuse against the
        ruOccurrences, targetSourceIdsByStem,              //    previous candidate's ReferenceMap
        previousOccurrences)  -> List<AssignedOccurrence>

    OccurrenceLabelMarkers.delimit(                        // <- NEW: transient body for translation only
        resolvedBody, ruOccurrences) -> delimitedRuBody

    TranslationJob.forSource(resolvedBody, ruFields)       // UNCHANGED: fingerprint hashes the plain body
    translationWorker.translate(job, delimitedRuBody, ...) // CHANGED ARG: delimited body, not resolvedBody

  prepareTranslatedEssay:
    OccurrenceLabelMarkers.scan(translation.body())        // <- NEW: recover EN's delimited-span sequence
      compare to assigned RU occurrence sequence           //    TRP-05: block before install on mismatch
      extract enLabel per occurrence

    OccurrenceLabelMarkers.strip(delimitedRuBody)  -> ruBody  (== today's resolvedBody, byte-identical)
    OccurrenceLabelMarkers.strip(translation.body()) -> enBody (== today's shape, byte-identical)

    buildReferenceMap(..., assignedOccurrences)             // <- CHANGED: real Occurrence list, not empty
    installCandidate(...)                                   // unchanged call shape
```

Two invariants drive every type below: (1) the **installed** RU/EN candidate bodies must stay byte-identical in shape to what `prepare` produces today when nothing about occurrences changes — no regression to hashing, diffing (TRP-02), or the ~30 existing link-rendering assertions in `PrepareHandlerTest`/`LinkResolverTest`; (2) `TranslationJob`, `EnglishTranslation`, and `TranslationWorker` are untouched — TRP-05 is satisfied entirely by what `PrepareHandler` does with the `ruBody` argument and the returned body, not by widening the port.

## New/changed types

### `LinkOccurrence` (new record, `prepare` package)

```java
record LinkOccurrence(String targetStem, String label, Optional<String> route, int spanStart, int spanEnd) {}
```

One per resolved plain wikilink (`[[...]]`, not `![[...]]`), in source order. `route` present means the target was already in `PublicNoteIndex` (public); empty means it fell through to the private/safe-label branch (today's exact eligibility line — protected regions, transclusions, and unresolved/broken links never reach `appendLink`'s occurrence-producing branches, so they are excluded exactly as scope-pins.md records). `spanStart`/`spanEnd` are the `[start, end)` offsets in `resolvedBody` where the rendered label text (for private targets) or the label-within-brackets text (for public targets, i.e. the character range between `[` and `]`) was written — `LinkResolver` already computes these positions while building `resolvedBody`; this only retains them instead of discarding them.

### `LinkResolver` / `LinkResolutionOutcome` (existing S13 type, widened again)

```java
public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {
    static LinkResolutionOutcome resolved(String body, List<LinkOccurrence> occurrences) { ... }
    static LinkResolutionOutcome blockedTransclusion(String target) { ... }   // unchanged

    <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved,   // widened: List, not Set
            Function<String, T> onBlockedTransclusion);                // unchanged
}
```

`resolvedBody`'s actual characters are unchanged — this widening only changes what accompanies it. Both existing callers (`PrepareHandler.prepare()`, `PrepareHandler.sourceFreshness()`) already destructure the outcome through a lambda; each gains one more piece of data in the same parameter, not a new call. `sourceFreshness`'s freshness re-check does not need occurrence identity (it only needs `resolvedBody` for fingerprint comparison), so it ignores the new list exactly as it ignores `privateTargetStems` today.

### `DirectTargetIdentityCheck` (existing S18 type, unchanged)

Still takes `Set<String> targetStems`. `PrepareHandler` derives that set from the new occurrence list immediately before calling it: `occurrences.stream().filter(o -> o.route().isEmpty()).map(LinkOccurrence::targetStem).collect(toCollection(LinkedHashSet::new))` — identical values to what `LinkResolver` used to hand it directly, so S18's behavior and tests are unaffected.

### `PrivateNoteIdentityIndex` → `VaultSourceIdentityIndex` (rename, `prepare` package)

No implementation change — `from(vaultReader)` already walks `listAllNotePaths()` (the whole vault) and indexes every note's `id` frontmatter by filename stem, public and private alike. Only the name was narrower than the capability. `PrepareHandler` builds it whenever `occurrences` is non-empty (today's condition is `!privateTargetStems.isEmpty()`; S19 widens that to `!occurrences.isEmpty()`, since public occurrences now also need a `targetSourceId` for their `references.json` entry). `DirectTargetIdentityCheck.verify(...)`'s own call site is unaffected beyond the rename.

### `dev.eugene.publicationexporter.reference.Occurrence` (new public record)

```java
public record Occurrence(String id, int order, String targetSourceId, String ruLabel, String enLabel) {}
```

The bound, JSON-serializable shape from scope-pins.md's SEM-03 decision (no `targetSourcePath` — see scope-pins.md for why). `order` is redundant with list position but kept explicit because SEM-03 lists "strict occurrence order" as something the map *binds*, not merely something the codec infers from array position — `ReferenceMapCodec`'s "inconsistent" scenario (reordered/duplicate/unknown IDs) validates `order` against actual position on read.

### `OccurrenceAssignment` (new, stateless, `prepare` package)

```java
final class OccurrenceAssignment {
    static List<AssignedOccurrence> assign(
            List<LinkOccurrence> ruOccurrences,
            Map<String, String> targetSourceIdsByStem,
            List<Occurrence> previousOccurrences) { ... }
}

record AssignedOccurrence(String id, int order, String targetSourceId, String ruLabel) {}
```

Per scope-pins.md's "same target source ID at the same index" rule: for index *i*, if `previousOccurrences.get(i)` exists and its `targetSourceId` equals the current occurrence's, reuse its `id`; otherwise generate a fresh one (`UUID.randomUUID().toString()`, matching `TranslationJob`'s existing ID style). A target stem absent from `targetSourceIdsByStem` (unresolvable identity) cannot occur here — `DirectTargetIdentityCheck` already blocked before this step runs for any private target missing a source ID, and public targets are validated by their own `PublicationKind` admission (ADM-03/04), so every occurrence reaching this step has a resolvable source ID by construction.

### `OccurrenceLabelMarkers` (new, stateless, `prepare` package)

```java
final class OccurrenceLabelMarkers {
    static String delimit(String resolvedBody, List<LinkOccurrence> occurrences) { ... }
    static ScanResult scan(String delimitedBody) { ... }   // ordered delimited-span contents
    static String strip(String delimitedBody) { ... }       // delimiters removed, content kept
}
```

`delimit` wraps each occurrence's `[spanStart, spanEnd)` label text with a paired delimiter (a Private Use Area or invisible-format Unicode pair chosen to never collide with legitimate Markdown/Cyrillic/Latin prose) — producing `delimitedRuBody`, used **only** as the `ruBody` argument to `translationWorker.translate(...)`. `TranslationJob.forSource(...)` keeps hashing the plain `resolvedBody`, so TRP-04 fingerprinting is untouched by this transient representation.

`scan` recovers the ordered list of delimited-span contents from a translated body (used on `translation.body()`), giving the EN occurrence sequence directly — no re-parsing of markdown/wikilinks, no ambiguity between public/private, since every occurrence (both kinds) was delimited uniformly by `delimit`.

TRP-05 validation, in `prepareTranslatedEssay` before `installCandidate`: `scan(translation.body())` must yield exactly as many spans as `ruOccurrences`, in the same order (span *i* corresponds to assigned occurrence *i* by construction — no separate identity comparison needed, since `delimit` establishes the correspondence positionally up front and a validation failure is simply "wrong count" or, if a future worker reorders surrounding prose without moving the delimited spans themselves, this scan-based check cannot detect intra-body reordering *within* the same span sequence — that residual case is a real translation-content-fidelity assumption on the worker, consistent with `EnglishCandidateValidator`'s existing assumption that URLs/asset paths survive translation, not a gap this slice leaves open on IDs/count/order). A count mismatch blocks with `translation_failed` (matching `EnglishCandidateValidator`'s existing failure path), before `installCandidate` writes anything.

`strip` removes the delimiter characters from both `delimitedRuBody` (recovering the exact `ruBody` installed today) and `translation.body()` (producing `enBody` in the exact shape installed today) — this is what keeps the two invariants from the "Pipeline insertion points" section true.

### `ReferenceMap` / `ReferenceMapCodec` (existing, `occurrences()` un-stubbed)

```java
@JsonProperty("occurrences")
public List<Occurrence> occurrences() { return occurrences; }   // was: always List.of()
```

Constructor gains an `List<Occurrence> occurrences` parameter (defaulted to `List.of()` from the existing `empty(...)` factories, so every call site that doesn't yet populate occurrences — including the two `@Deprecated` overloads — keeps compiling and keeps today's empty-map behavior). A new factory, e.g. `ReferenceMap.of(identity, ruHash, enHash, ..., occurrences)`, is used only by `PrepareHandler.buildReferenceMap` once occurrences exist. `ReferenceMapCodec.referenceMapFrom(root)` reads the `occurrences` array back into `List<Occurrence>` instead of dropping it, validating (SEM-03's "inconsistent" scenario, justifying codec-level unit tests per the proposal): no duplicate `id`, `order` matches actual array position with no gaps, and every field present/non-blank. `write`/`read` round-trip is the natural place for the duplicate-JSON-key and strict-number-parsing cases the proposal calls out.

### `PrepareHandler.buildReferenceMap` (existing private method, signature widened)

Gains one parameter, the assigned+labeled `List<Occurrence>`, and calls `ReferenceMap.of(...)` instead of `ReferenceMap.empty(...)` when the list is non-empty (an empty list still calls the existing empty-map path — SEM-03's first-publication scenario is untouched).

## Not in this design

- Resolving an occurrence into a public route dynamically at **release** time, based on live target approval state — S20 (SEM-04/SEM-05). This design keeps today's behavior of finalizing each occurrence's route/label at **prepare** time, using currently-known state; S20 will need its own release-time re-substitution step, for which this slice's `Occurrence` record (carrying `targetSourceId`, not a baked route) is deliberately the right shape to build on.
- Any change to `PublicNoteIndex`'s route-computation policy, `AssetResolver`, `EnglishCandidateValidator`'s existing URL/route diagnostics, or `WorkflowStatusEditor` — all unaffected.
- Any diffing/staleness behavior change (TRP-02, TRP-04) — `matchingApprovedBaseline` and `sourceFreshness` keep comparing/fingerprinting the plain `resolvedBody`/`ruBody`, never the transient delimited body.
- Non-positional (any-position) reuse matching, or reuse across insertion/reordering — explicitly deferred per scope-pins.md; this slice's acceptance boundary does not exercise it.
