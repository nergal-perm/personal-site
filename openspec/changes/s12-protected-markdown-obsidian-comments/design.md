## Context

`PrepareHandler.prepare()` currently threads `intake.body()` (the frontmatter-stripped RU source, unmodified) through three places: `matchingApprovedBaseline(...)` (the approved-unchanged check via `RussianDiff.between(...)`), the `TranslationJob` built for the worker, and the installed candidate body via `installCandidate(...)`. PCM-04 requires that Obsidian comments (`%%...%%`) be stripped from public content while fenced code and inline code survive byte-for-byte — including any comment-like or link-like text they contain. Two prior functional design decisions bound this slice tightly:

- Protected-region scope is fenced code + inline code only (not raw HTML `<pre>`, not HTML comments) — matches PCM-04's literal wording, no speculative breadth.
- An unclosed Obsidian comment blocks preparation with a diagnostic rather than silently consuming the rest of the body (exporter-java's legacy behavior) — consistent with this project's fail-closed convention.
- A comment-only edit must count as "unchanged" for the approved-baseline skip-optimization, which means normalization must run once, before `matchingApprovedBaseline`, and the same normalized text must be reused for the diff check, the translation job, and the installed candidate — not normalized independently (or forgotten) at each call site.

`RussianDiff.between(...)`'s own `normalize()` (line-ending unification, trailing-whitespace trim) is orthogonal and unaffected — it runs on whatever body text it's handed, before or after Markdown normalization, and composes without change.

`dec-20260804-9f43c17f` (G4) already chose semantic/site-acceptance RU-normalization depth over byte-for-byte legacy compatibility; this design stays inside that boundary. `exporter-java`'s `MarkdownScanner` and `MarkdownNormalizationTest` were read as behavioural evidence only — confirming that fenced-code/inline-code detection for arbitrary Markdown has known, real edge cases (CRLF line endings, closing-fence length/character matching, backtick-run-length matching for inline code, scanning resuming correctly after a protected region). The regex shapes below implement CommonMark's own fenced-code-block and code-span rules (a public specification, not `exporter-java`-specific design); they are independently authored for this narrower two-kind scope, cross-checked against the legacy suite's documented edge cases as validation evidence — not ported from `exporter-java`'s `MarkdownScanner` class or package structure.

## Goals / Non-Goals

**Goals:**
- Strip Obsidian comments (`%%...%%`) from RU body text outside protected regions.
- Leave fenced code blocks and inline code spans byte-for-byte unchanged, including any comment-like or link-like text inside them.
- Block preparation with a diagnostic when an Obsidian comment is opened but never closed.
- Normalize once per `prepare()` call and reuse the result for the approved-baseline-unchanged check, the translation job, and the installed candidate body.
- Zero new production boundary adapters — pure in-process text transform.

**Non-Goals:**
- Resolving, validating, or rewriting real wiki-links, embeds, or transclusions (S13).
- Resolving or content-addressing assets (S14).
- Protecting raw HTML `<pre>` blocks or HTML comments (`<!--...-->`) — deferred until a real requirement needs them.
- Normalizing EN (translated) content — the translation worker's output is validated separately by `EnglishCandidateValidator` (TRP-03/PCM-06 territory), unaffected by this slice.
- Normalizing title/description frontmatter scalars — they are plain strings, not Markdown prose; PCM-04 concerns body content.

## Decisions

### D1. New collaborator: `MarkdownNormalizer` in the `prepare` package

`dev.eugene.publicationexporter.prepare.MarkdownNormalizer` — a stateless final utility class (private constructor, matching `EnglishCandidateValidator`'s and `RussianDiff`'s existing shape in this package) with one entry point:

```java
public static MarkdownNormalizationOutcome normalize(String body)
```

**Why this package, not a new `markdown` package:** `RussianDiff` and `EnglishCandidateValidator` already establish `prepare` as where this project's prepare-pipeline text transforms live. A single new class does not yet justify a new package; if S13/S14 later add enough Markdown-processing collaborators that `prepare` gets crowded, extracting a `markdown` package then has real, evidence-based justification instead of speculative up-front structure — matching this project's stated bias against inventing generic frameworks before repeated behaviour exists.

**Rejected:** a new `dev.eugene.publicationexporter.markdown` package now. Correct in the limit, premature today — one class does not need its own package, and nothing else currently lives there.

### D2. Outcome representation: sealed interface + `resolve()`, mirroring `TranslationOutcome`

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;

public sealed interface MarkdownNormalizationOutcome permits NormalizedMarkdown, UnclosedObsidianComment {

    static MarkdownNormalizationOutcome normalized(String body) {
        return new NormalizedMarkdown(body);
    }

    static MarkdownNormalizationOutcome unclosedComment(int position) {
        return new UnclosedObsidianComment(position);
    }

    <T> T resolve(
            Function<String, T> onNormalized,
            Function<Integer, T> onUnclosedComment);
}

final class NormalizedMarkdown implements MarkdownNormalizationOutcome {
    private final String body;

    NormalizedMarkdown(String body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onNormalized.apply(body);
    }
}

final class UnclosedObsidianComment implements MarkdownNormalizationOutcome {
    private final int position;

    UnclosedObsidianComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onUnclosedComment.apply(position);
    }
}
```

**Why:** scanning necessarily stops at the first unclosed comment — there is nothing further to collect, unlike `EnglishCandidateValidator`'s independent, all-evaluated-regardless checks (blank fields, internal routes, dropped URLs). This is a single terminal success/failure distinction, exactly the shape `TranslationOutcome` already models in this codebase (`SuccessfulTranslation`/`FailedTranslation` + `resolve(onSuccess, onFailure)`), from a recent deliberate refactor toward explicit outcome modeling. Using the same shape here is consistency with precedent, not novelty for its own sake.

**Rejected:** `EnglishCandidateValidator`-style `Result{valid, List<String> diagnostics}`. Would carry a list that only ever holds zero or one entry for this check — technically works, but models a collect-many problem shape for a single-terminal-outcome problem.

### D3. Scanning algorithm: leftmost-match-among-candidates, copy-through, repeat

Two candidate-span generators run from the current cursor position on each iteration:

1. **Fenced code**: `^ {0,3}(`{3,}|~{3,})([^\r\n]*)$` opening line (info string after a backtick fence must not itself contain a backtick, per CommonMark — such a line is not a valid fence-open and scanning continues from the next line); closing fence must use the same character and be at least as long as the opening fence, matched line-by-line; an unmatched opening fence protects through end-of-body (self-evident in output — a large trailing code-styled block — not silent data loss, so no block-with-diagnostic here, unlike the comment case).
2. **Inline code**: a backtick-delimited span where the closing run of backticks has the same length as the opening run (`(?s)(?<!\\)(`+)(?!`).*?\1(?!`)`), non-greedy, so a shorter/longer backtick run inside does not falsely close it.

Independently, `%%` occurrences are located via plain `indexOf` scanning (not a third pattern candidate in the same regex family, since Obsidian comments are the one construct being *stripped* rather than *protected* — see D4 for why this distinction still composes correctly).

At each cursor position, the algorithm:
1. Finds the earliest-starting span among {next fenced-code span, next inline-code span, next `%%` occurrence} at or after the cursor.
2. Copies all body text between the cursor and that span's start through to the output unchanged.
3. If the span is a protected region (fenced/inline code): copies the span itself through unchanged.
   If the span is a `%%` occurrence: looks for a matching closing `%%` *after* this point. If found, the whole comment span is dropped from the output (not copied). If not found, the scan terminates immediately with `MarkdownNormalizationOutcome.unclosedComment(position)` — no partial output is produced or used.
4. Advances the cursor to the end of the consumed span and repeats until the cursor reaches the end of the body, at which point the accumulated output becomes `NormalizedMarkdown`.

This is a standard tokenizer scan (leftmost-match among mutually-exclusive alternatives) — the correct shape whenever one construct (a fenced code block) can contain text that would otherwise match another construct (`%%...%%`) and must win. It generalizes cleanly if S13/S14 or a future slice needs to add a third protected-region kind (raw HTML `<pre>`, HTML comments): one more candidate generator in the same `min(candidates, by start)` step.

### D4. Why a fenced/inline-code span "protects" `%%` inside it, without a shared `Kind` enum

Because the algorithm always evaluates *all* candidate generators (fenced, inline, `%%`) at the current cursor and picks whichever starts earliest, a `%%` sitting inside a code fence is never reached as its own candidate — the fenced-code span's start position is earlier (or equal, in which case the code fence wins by construction since it is checked as a real span with defined start/end, and the cursor jumps straight past the whole fence). This makes the "protects" relationship implicit in the scan order rather than needing an explicit `Kind.FENCED_CODE` vs `Kind.OBSIDIAN_COMMENT` enum comparison — simpler than `exporter-java`'s six-kind `EnumSet`-parameterized `process(...)`, which needed the enum because it had three independent output modes (strip / mask / leave) driven by which kinds were in scope for a given call. `MarkdownNormalizer` has exactly one mode (strip comments, protect code), so the enum-driven generality is unneeded machinery for this slice's scope.

### D5. `PrepareHandler` integration point

Normalize once, immediately after `NoteIntake` accepts the note, before the approved-baseline check:

```java
public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
    NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
    if (!intake.accepted()) {
        return BridgeResponse.blocked(COMMAND, intake.diagnostics());
    }
    return MarkdownNormalizer.normalize(intake.body()).resolve(
            normalizedBody -> prepareNormalizedEssay(notePath, vaultReader, intake, normalizedBody),
            position -> unclosedCommentFailure(position));
}

private BridgeResponse prepareNormalizedEssay(
        VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake, String normalizedBody) {
    // exactly today's prepare() body from "Optional<CandidateSnapshot> unchangedApproved" onward,
    // reading normalizedBody wherever intake.body() was read before (matchingApprovedBaseline,
    // prepareAdmittedEssay's ruBody parameter). intake.title()/intake.description() are untouched —
    // they are frontmatter scalars, not Markdown prose, and are out of PCM-04's scope.
}

private static BridgeResponse unclosedCommentFailure(int position) {
    return BridgeResponse.translationFailed(COMMAND,
            Diagnostic.blocking("candidate", "Obsidian comment starting at position " + position + " is never closed."));
}
```

This guarantees `matchingApprovedBaseline`'s `RussianDiff.between(...)` call, the `TranslationJob` built in `prepareAdmittedEssay`, and `installCandidate`'s installed RU body all see the exact same normalized text — a comment-only edit produces identical `normalizedBody` before and after, so `RussianDiff` (which already ignores line-ending/trailing-whitespace noise) reports no change, and the existing skip-optimization fires exactly as it does today. No change to `RussianDiff` itself is needed.

**Response shape for the unclosed-comment block:** `translation_failed` status, `"candidate"` diagnostic field — the same status/field `EnglishCandidateValidator` failures and candidate-install failures already use. This is a "cannot produce a valid candidate from this input" failure, the same class already surfaced through that field; introducing a new field name for one additional case was considered and rejected as unwarranted vocabulary growth for this slice.

## Risks / Trade-offs

- **[Risk]** The leftmost-match scan re-scans candidate patterns from the cursor on every iteration (no incremental state carried between iterations) → **Mitigation**: acceptable at essay-body scale (the plan's own performance envelope is sub-1-second in-memory acceptance suites); `Matcher.find(cursor)` on compiled patterns is linear per call, and essay bodies are not large enough for repeated-scan overhead to matter. Not optimized further absent evidence it needs to be.
- **[Risk]** Backtick-run-length regex backreferences (`(`+)(?!`).*?\1(?!`)`) are easy to get subtly wrong (e.g. a 2-backtick run falsely closing on a later single backtick) → **Mitigation**: this is exactly the kind of "genuinely combinatorial protected-region case" the plan's slice discipline allows narrow unit tests for; `tasks.md` includes unit-test coverage for backtick-run-length matching and fence-character/length matching directly, not deferred to acceptance-only coverage.
- **[Risk]** `dec-20260804-9f43c17f`'s refresh trigger ("S12-S14 land and this decision's scope boundary needs re-examination") fires once this slice lands → **Mitigation**: verify that decision post-implementation (attach evidence, re-baseline) as part of closing this change, not silently supersede it — D5 above shows the plain-essay (no comments, no protected regions) pass-through byte-identity guarantee that decision's post-condition names is unaffected: `MarkdownNormalizer.normalize` on a body with no `%%` and no code returns the body unchanged (empty scan, output equals input).
