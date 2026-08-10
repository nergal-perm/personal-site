## Context

`PrepareHandler.prepare()` composes `NoteIntake.admit(...)` → `MarkdownNormalizer.normalize(intake.body())` → (unchanged-baseline check / translate / install), and re-runs the same `NoteIntake.admit` + `MarkdownNormalizer.normalize` pair inside `sourceFreshness(...)` to detect concurrent edits before installing. `MarkdownNormalizer` (S12, `dec-20260810-a568f461`) strips `%%...%%` Obsidian comments while leaving fenced code blocks and inline code spans byte-for-byte unchanged — including any link-like text they contain, per PCM-04. It has no awareness of wikilink (`[[Target]]`) or embed (`![[Target]]`) syntax at all; confirmed by exhaustive search, nothing in `publication-exporter/src/main` parses either.

PCM-03 requires converting an unambiguous link to a selected public note into a public route, converting a link to a private/unresolved/ambiguous note into a safe plain-text label, and blocking a transclusion of a non-public note. The functional design pass fixed three things the baseline spec left open: routes are **locale-neutral** (`/essays/{publicId}/`, no `/ru/`/`/en/` segment — because resolution runs once on the RU body ahead of translation, and the same route text is reused untranslated in the EN candidate, so no locale segment can leak); an **ambiguous** target gets the same safe-label treatment as private/unresolved (no dedicated diagnostic — disambiguating colliding note names is the author's problem); and `[[Target]]`, `[[Target|Alias]]`, `[[Target#Heading]]` (heading dropped) plus `![[Target]]` are all in scope.

Two things this slice must NOT silently reopen: PCM-04's protected-region guarantee (a `[[...]]`-looking string inside a code span must stay untouched, exactly as it does today) and S12's normalize-once-and-reuse discipline (the RU body that reaches the approved-baseline diff, the translation job, and the installed candidate must all be identical — extending to "resolve once and reuse" for the same three places, plus the `sourceFreshness` re-check).

`exporter-java`'s `LinkProcessor`/`ManifestLink`/`TransclusionException` were read as behavioural evidence only (this project's standing rule: compatibility oracle, never a code donor). Its wikilink regex (`(!?)\[\[([^\]|#]+)(#[^\]|]*)?(?:\|([^\]]+))?\]\]`) and its whole-vault multi-key index (path/publicId/filename-stem/title/aliases) confirm what real Obsidian vaults need in the general case — but `publication-exporter` has no whole-vault discovery yet (ADM-01/S16 is four slices away), so this slice deliberately uses a narrower, single-key index built from data already available through the existing `VaultReader` port, not a new one.

## Goals / Non-Goals

**Goals:**
- Recognize `[[Target]]`, `[[Target|Alias]]`, `[[Target#Heading]]`, and `![[Target]]` in the RU body, after `MarkdownNormalizer` has run.
- Resolve an unambiguous target to a locale-neutral public route; render a private, unresolved, or ambiguous target as a plain label (alias, or target text if no alias); block a private/unresolved/ambiguous transclusion before any candidate is installed or replaced.
- Never touch link-like text inside a fenced code block or inline code span — reuse, not duplicate, the protected-region detection PCM-04 already established.
- Never misclassify an asset-extension embed (`![[image.png]]`) as a note transclusion — leave it untouched, deferred to PCM-05/S14.
- Resolve once per `prepare()` call (and once per `sourceFreshness` re-check) and reuse the result, exactly extending S12's discipline.
- Zero new production boundary adapters — reuse the existing `VaultReader` port as-is.

**Non-Goals:**
- Stable semantic occurrence IDs (`ReferenceMap.occurrences()` stays the empty stub) — S19.
- Late-bound target activation (a referrer's route changing when its target's publish state later changes without referrer reapproval) — S20.
- Resolving or content-addressing the asset itself behind an asset-extension embed — S14.
- A whole-vault discovery command, aggregate invalid-note reporting, or any new caller-visible response shape — ADM-01/S16. The per-`prepare()`-call note index this slice builds is purely internal; it is never returned to a caller and reports nothing about notes a link doesn't reference.
- Guaranteeing the translation worker preserves a baked-in internal route through RU→EN translation — noted as a risk below, not solved here (would touch PCM-06/`EnglishCandidateValidator`, outside this slice's declared capability).

## Decisions

### D1. Extract `ProtectedRegionScanner`; add `LinkResolver` and `PublicNoteIndex` — all in the `prepare` package

```
prepare/
  ProtectedRegionScanner.java   (extracted from MarkdownNormalizer: fenced+inline code detection)
  MarkdownNormalizer.java       (PCM-04, unchanged behavior — now drives the shared scanner)
  LinkResolver.java             (PCM-03, new — drives the same shared scanner)
  LinkResolutionOutcome.java    (new, sealed — mirrors MarkdownNormalizationOutcome's shape)
  PublicNoteIndex.java          (new — small value type: known public notes for one prepare() call)
```

**Why extract instead of duplicate or merge:** `MarkdownNormalizer`'s own design (S12) named this exact extension ("generalizes cleanly if S13... needs a third protected-region kind — one more candidate generator in the same scan"). Duplicating the fenced/inline-code regex pair into `LinkResolver` independently would leave two copies of CommonMark-derived fence/backtick matching that must stay in sync if protected-region rules ever change. Merging link-resolution into `MarkdownNormalizer` directly would make one class respondsible for two distinct requirements (PCM-04 and PCM-03) with independent evolution paths (PCM-03 gains occurrence-awareness in S19/S20; PCM-04 does not). Extraction keeps one source of truth for "what counts as protected" while keeping each requirement's own transform in its own class — matching this project's own precedent of extracting a collaborator only once concrete reuse evidence exists (not speculatively).

**Why still no new package:** three related classes in `prepare` is not yet enough to justify `prepare.markdown` or similar — same reasoning `MarkdownNormalizer`'s own D1 gave for staying in `prepare` rather than inventing a package for one class.

### D2. `ProtectedRegionScanner` — the shared primitive

```java
package dev.eugene.publicationexporter.prepare;

final class ProtectedRegionScanner {

    // Drives a leftmost-match scan across body: at each cursor position, finds
    // the earliest of {next fenced-code span, next inline-code span, next match
    // the caller's own candidate finder reports}, copies everything before it
    // through untouched, and lets the caller's transform decide what happens to
    // a protected span (always: copy through unchanged) versus its own
    // non-protected match (comment: drop; link: rewrite or block).
    static <T> T scan(String body, NonProtectedTransform<T> transform) { ... }

    interface NonProtectedTransform<T> {
        // called once, driving the whole scan; the implementation finds its own
        // candidate spans (== next occurrence of its construct) at each cursor
        // step and folds output the same way MarkdownNormalizer's D3 already does
    }
}
```

This is a direct extraction of `MarkdownNormalizer`'s existing D3 algorithm (leftmost-match-among-candidates, copy-through, repeat) with the fenced/inline-code candidate generators kept inside the scanner and the "what is the third candidate, and what happens when it wins" question left to the caller. `MarkdownNormalizer` becomes a thin `NonProtectedTransform` supplying the `%%` candidate and the strip-or-block behavior; `LinkResolver` supplies the wikilink/embed candidate and the rewrite-or-block behavior. Exact method shape (the transform interface's fold/emit contract) is a `tasks.md` concern, not pinned further here — the important boundary is that **only one class knows what a fenced or inline-code span looks like**.

### D3. `LinkResolutionOutcome` — sealed interface mirroring `MarkdownNormalizationOutcome`

```java
package dev.eugene.publicationexporter.prepare;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body) {
        return new ResolvedLinks(body);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            Function<String, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}
```

**Why:** a private/unresolved/ambiguous *link* is not a failure — it still produces a resolved body (with a plain label), same as `MarkdownNormalizer` treats an unclosed comment as the one true terminal failure. Only a blocked *transclusion* is terminal here, giving the same two-arm shape `MarkdownNormalizationOutcome` and `TranslationOutcome` already use in this codebase. `resolve()` passes the offending target text (not a position) — the diagnostic message names the link, not a body offset, since a transclusion block is reported per-link, not per-scan-position.

**Rejected:** reusing `MarkdownNormalizationOutcome` itself with a third variant (folds PCM-03 into PCM-04's type — rejected in the collaborative-design pass for the same reason D1 rejects merging the classes).

### D4. `PublicNoteIndex` — built once per call, from the existing `VaultReader` port only

```java
package dev.eugene.publicationexporter.prepare;

final class PublicNoteIndex {

    // vault-relative filename stem (basename without ".md") -> locale-neutral public route
    private final Map<String, String> routesByFilenameStem;

    static PublicNoteIndex from(VaultReader vaultReader) {
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            NoteIntake.Result intake = new NoteIntake().admit(candidate, vaultReader);
            if (!intake.accepted()) {
                continue; // invalid/private notes are simply absent from the index -> unresolved
            }
            String stem = candidate.filenameStem();
            String route = routeFor(intake.identity());
            if (routes.containsKey(stem)) {
                ambiguousStems.add(stem); // a second note claims a stem already seen -> ambiguous
            } else {
                routes.put(stem, route);
            }
        }
        ambiguousStems.forEach(routes::remove); // neither candidate wins; both become unresolved
        return new PublicNoteIndex(routes);
    }

    Optional<String> routeFor(String filenameStem) { ... }

    private static String routeFor(PublicationIdentity identity) {
        return "/essays/" + identity.publicId() + "/";
    }
}
```

**Why `routeFor(PublicationIdentity)` has no kind switch:** `NoteIntake.admit(...)` unconditionally delegates to `EssayAdmission` — no kind dispatch exists anywhere in `publication-exporter` yet, so every note reaching this point is already known to be an essay; a `switch` with a `default` arm for other kinds would be unreachable dead code today. This project's own content-kind ladder (S17a–f) is where a second kind, and the dispatch this method would then need, actually arrives — adding the switch now would be exactly the "requirement pulled in only for speculative reuse" the plan's slice discipline rules out.

**Matching key — vault-relative filename stem, not frontmatter title:** this is how Obsidian itself resolves `[[Target]]` by default, and mirrors PCM-05's own "exact vault-relative match" precedent for assets. It also means the index needs no frontmatter `title` read for matching — only for `routeFor`, which needs `identity()` (already returned by `NoteIntake.Result`), not title at all, since D5 below fixes the *display* label to the link's own text.

**Why this is not ADM-01/S16 whole-vault discovery in disguise:** `listPublishCandidates()` already exists and is already used elsewhere (e.g. S11's refresh-queue path) — this slice adds no new enumeration capability. The index is built, consumed, and discarded inside a single `prepare()` call; it is never a response, never reports which notes are invalid to any caller, and excludes lookalikes/duplicates by the same "just isn't in the map" mechanism that already makes ambiguous and private notes indistinguishable from each other (deliberately, per the functional design decision). ADM-01's job — a dedicated, caller-visible, exhaustively-diagnosed discovery command — remains entirely unbuilt.

**Two candidates sharing a filename stem (ambiguous):** a naive last-write-wins `Map.put` would silently resolve the link to whichever note happened to be enumerated last — that *contradicts* the functional spec, which requires an ambiguous target to get the same safe-label treatment as an unresolved one, not a route to an arbitrary winner. `from(...)` therefore tracks colliding stems separately and removes them from the map entirely once detected, so `routeFor(...)` correctly reports `Optional.empty()` for an ambiguous stem regardless of enumeration order.

### D5. Display label is always the link's own text, never the resolved note's title

`resolve()`'s output for a resolved link is `[alias-or-target-text](route)`; for a private/unresolved/ambiguous link it is just `alias-or-target-text` with no brackets. In both cases the label comes from the wikilink syntax itself (`Target`, or `Alias` if `[[Target|Alias]]` was used) — never from re-reading the target note's frontmatter title. This keeps `PublicNoteIndex` from needing title data at all (D4), and keeps behavior stable if a target note's title changes without a referrer edit (out of scope to actively support, since S20 owns that, but this choice at least doesn't make it worse).

### D6. `PrepareHandler` integration — resolve once, reuse, mirroring S12's D5

```java
public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
    NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
    if (!intake.accepted()) {
        return BridgeResponse.blocked(COMMAND, intake.diagnostics());
    }
    PublicNoteIndex knownNotes = PublicNoteIndex.from(vaultReader);
    return MarkdownNormalizer.normalize(intake.body()).resolve(
            normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                    resolvedBody -> prepareNormalizedEssay(notePath, vaultReader, intake, resolvedBody, knownNotes),
                    target -> transclusionBlockedFailure(target)),
            position -> unclosedCommentFailure(position));
}
```

`knownNotes` is threaded down into `prepareNormalizedEssay` → ... → `sourceFreshness`, which gains the identical composition after its own `MarkdownNormalizer.normalize(current.body())` call — so the freshness re-check's fingerprint comparison is computed from the fully resolved body, exactly as it is computed from the fully normalized body today. No behavior changes for `matchingApprovedBaseline`, the `TranslationJob`, or `installCandidate` beyond "the body they all already share is now link-resolved too," identical in shape to how S12 added Markdown normalization to the same three call sites without touching any of them individually.

**Response for a blocked transclusion:** `BridgeResponse.translationFailed(COMMAND, Diagnostic.blocking("candidate", "Transclusion target \"" + target + "\" is not a public note."))` — same status and field S12's `unclosedCommentFailure` uses, for the same reason D5 of S12's design gave: this is one more "cannot produce a valid candidate from this input" case, not a new vocabulary word.

## Risks / Trade-offs

- **[Risk]** `PublicNoteIndex.from(...)` re-admits every publish-candidate note on every single `prepare()` call (once for the initial pass, once again inside `sourceFreshness`) → **Mitigation**: acceptable at this project's current vault scale and the plan's sub-1-second in-memory acceptance envelope; `listPublishCandidates()` and `NoteIntake.admit` are already-shipped, already-linear operations. Not optimized into a cached/indexed port now — revisit if S16's real whole-vault discovery makes a shared index available that this slice could reuse instead of rebuilding its own.
- **[Risk]** A resolved internal route (`/essays/notes-on-time/`) is baked into the RU body *before* translation, but `EnglishCandidateValidator.droppedUrlDiagnostics` only checks that `https?://` external URLs survive translation unchanged — nothing currently verifies the translation worker preserves an internal route's markdown-link target instead of mangling or dropping it → **Mitigation**: none in this slice; documented as a known gap. Fixing it means extending PCM-06/`EnglishCandidateValidator`, a different requirement not declared in this slice's `proposal.md` Capabilities section — a candidate follow-up problem, not silently absorbed here.
- **[Risk]** Extracting `ProtectedRegionScanner` touches `MarkdownNormalizer`, governed by `dec-20260810-a568f461` → **Mitigation**: the refactor must preserve that decision's byte-for-byte protected-region guarantee and normalize-once-and-reuse composition exactly; `tasks.md` runs the full pre-existing `MarkdownNormalizer`/`PrepareHandler` test suite unchanged against the refactored code as a regression gate, and this decision gets a verify pass (evidence attached, not silently superseded) once this slice lands, matching how S12 itself treated `dec-20260804-9f43c17f`'s refresh trigger.
