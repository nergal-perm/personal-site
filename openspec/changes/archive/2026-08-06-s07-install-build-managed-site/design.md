## Context

S06 left `build-from-review`/`ReleaseOutputStore` writing one RU essay file, one EN essay file, and deterministic minimum provenance into a review-root-scoped `release/` directory — an intermediate artifact, not a site input. Nothing yet writes into the real Astro-managed content roots or proves a real build succeeds. S07 adds that: a new production adapter that installs an approved publication into `src/content`, `src/data/pages`, and `public/assets/vault` (this slice only ever writes the first), plus a site-level `.astro-export/release-provenance.json` manifest matching a contract the site already enforces (`site/scripts/check-content.mjs`, proven compatible in JS by `site/tests/release-provenance.test.mjs`).

All code in this slice is governed by the same standing conventions as S01-S06 (memory `feedback-exporter-design-testing-conventions`): `/nullables`, `/applying-sbpp`, `/oo-design-guide`, and the interface-change discipline from `feedback-java-interface-change-task-planning`.

The functional collaborative-design pass found one real requirement gap this slice must close before its own new adapter can produce anything valid: `site/src/content.config.ts`'s Zod schema requires non-empty `title`/`description` on every essay entry, and no requirement or domain type in S02-S06 carries either field (confirmed: `EssayAdmission` admits only `publish`/`publicId`/`publicCollection`/`publicContentType`/`sourceId`; grep of `openspec/requirements-baseline.md` finds no mention of "title" or "description" anywhere). The operator confirmed these are vault-author-provided fields — the Obsidian plugin gates on their presence before "Prepare to publication" — and directed that threading them through the existing pipeline (not synthesizing placeholders) is part of this slice (`ADM-04`'s new scenario; `PCM-01`/`PCM-02`/`TRP-01`/`REL-01`/`REL-03` realized by construction, see `scope-pins.md`).

This technical pass resolves the remaining forks by direct evidence, plus three operator decisions already made during the technical collaborative-design session:

1. **Operator decision — EN title/description are machine-translated, not reused verbatim.** The same translation worker that already translates the body translates title/description in the same invocation.
2. **Operator decision — `selectedPages` is `[]`.** Reading `verifyReleaseProvenance()` in `check-content.mjs` line by line: `recomputed.selectedPages = actual.selectedPages ?? []` copies the field under test verbatim into the value being compared against itself — no independent recomputation from real files, unlike `managedTrees`/`managedFiles`, which are recomputed from disk and compared. `selectedPages` only ever participates in the `payloadDigest` hash, self-consistently either way. The exporter's release boundary has no vault `sourcePath` (that's S02 admission-time knowledge `PublicationIdentity` never stored), so `[]` avoids inventing data.
3. **Operator decision — the exporter never shells out to Node/npm/Astro.** Production code writes files and the manifest only. The one slow smoke test (test code) subprocess-invokes `check-content.mjs` and `astro build` directly.

Also resolved by direct evidence, not asked:

4. **The real site layout is fixed, unlike S06's deferred choice.** `site/src/content/blog/{ru,en}/<slug>.md` (evidence: existing files under that path) is not a decision this slice can defer the way S06 deferred it — S07's whole job is writing into that exact tree.
5. **S07 does not depend on `ReleaseOutputStore` or `build-from-review` at all.** REL-01 already establishes precedent: "Release materialization SHALL derive publishable RU and EN pages exclusively from complete approved snapshots." S07's new command reads `ApprovedSnapshotWorkspace#read(identity)` directly — the same source S06 reads — rather than chaining off S06's intermediate `release/` output. `ReleaseOutputStore`, `ReleaseProvenance`, and `BuildFromReviewHandler` are untouched by this slice; the site's `.astro-export/release-provenance.json` is a structurally unrelated value (`managedTrees`/`managedFiles`/`payloadDigest`/`selectedPages`, no relation to `ReleaseProvenance`'s four hash fields) computed fresh from the just-installed managed-tree bytes.

## Goals / Non-Goals

**Goals**
- `EssayAdmission` admits `title`/`description` from source frontmatter, blocking with a field-specific diagnostic when either is missing or blank (ADM-04's new scenario).
- The translation worker translates title and description alongside the body in one invocation; `CandidateSnapshot` (shared by `CandidateWorkspace` and `ApprovedSnapshotWorkspace`, S05's precedent) carries all three RU/EN pairs.
- A new `install-to-site` command, given a publication identity with a durable approved snapshot, writes `src/content/<collection>/<lang>/<publicId>.md` for both locales (full, schema-valid frontmatter plus the approved body, byte-for-byte) and `.astro-export/release-provenance.json`, into previously-absent managed roots (REL-05's new scenario).
- The written manifest passes `check-content.mjs`'s real verification, and `astro build` succeeds against the installed tree (REL-06, both scenarios) — proven by a real-adapter filesystem contract test and one slow smoke test, neither of which the production code depends on.
- No code-owned site file (config, templates, layouts, other collections' pre-existing content) is ever touched; only the three declared managed roots are written.

**Non-Goals** (deferred to later slices, per `scope-pins.md`)
- Replacing an existing site generation, or recovery from an interrupted replacement (S10).
- Assets (`public/assets/vault`) and curated pages (`src/data/pages`) as exporter-generated output — this slice's fixtures pre-seed curated pages as static test data; the adapter only ever writes `src/content`.
- Multiple publications in one invocation (S16), links/assets (S13/S14), semantic occurrence resolution (S20).
- Any editorial-metadata mechanism beyond the two fields (`title`, `description`) this slice's own gate requires. Tags, topics, cover images, and every other optional/defaulted `content.config.ts` field are simply omitted, relying on Zod's own `.default(...)`.

## Decisions

### D1 — `EssayAdmission` admits `title`/`description`

```java
public Result admit(Frontmatter frontmatter) {
    if (!isPublished(frontmatter)) { return Result.blocked(List.of(publishDiagnostic())); }
    List<Diagnostic> diagnostics = new ArrayList<>();
    String publicId = requireValidPublicId(frontmatter, diagnostics);
    String collection = requireCollection(frontmatter, diagnostics);
    String contentType = requireContentType(frontmatter, collection, diagnostics);
    String sourceId = requireSourceId(frontmatter, diagnostics);
    String title = requireNonBlank(frontmatter, "title", diagnostics);
    String description = requireNonBlank(frontmatter, "description", diagnostics);
    if (!diagnostics.isEmpty()) { return Result.blocked(diagnostics); }
    return Result.accepted(PublicationIdentity.of(collection, contentType, publicId), sourceId, title, description);
}

private String requireNonBlank(Frontmatter frontmatter, String key, List<Diagnostic> diagnostics) {
    String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
    if (value == null) {
        diagnostics.add(Diagnostic.blocking(key, "Note has no " + key + "."));
    }
    return value;
}
```

`Result` gains `title()`/`description()` accessors (only meaningful when `accepted()`), mirroring `sourceId()`'s existing doc convention. This directly satisfies ADM-04's new scenario: "an essay with title and description" passes; a note missing either blocks with a diagnostic naming the missing field, matching `requireSourceId`'s existing shape exactly — no new validation pattern invented.

**Alternatives considered:** validating title/description in `NoteIntake` instead of `EssayAdmission`.

**Why not:** `EssayAdmission` already owns every other kind-specific/identity field check (`publish`, `publicId`, `publicCollection`, `publicContentType`, `sourceId`); `NoteIntake` is a thin orchestrator that never inspects frontmatter values itself. Splitting validation across two classes for two more fields would be inconsistent with every existing field.

### D2 — `NoteIntake.Result` exposes `title()`/`description()`

```java
public String title() { return admission.title(); }
public String description() { return admission.description(); }
```

Mirrors the existing `body()`/`identity()` delegation shape exactly — `NoteIntake.Result` already delegates every admitted fact to `EssayAdmission.Result` plus the frontmatter-derived body.

### D3 — `CandidateSnapshot` widens to carry title/description pairs

```java
public final class CandidateSnapshot {
    private final String ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription;
    private final ReferenceMap referenceMap;

    public static CandidateSnapshot of(String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription,
            ReferenceMap referenceMap) { ... }

    // ruBody(), enBody(), ruTitle(), enTitle(), ruDescription(), enDescription(), referenceMap()
    // equals/hashCode/toString over all seven fields
}
```

`CandidateWorkspace#install` and `ApprovedSnapshotWorkspace#install` both widen to the same six-string-plus-map parameter list. **This is the largest interface-change ripple in the codebase to date — both interfaces at once, following the same discipline S05/S06 used per interface.** Every known implementor/test-double is enumerated and updated in the same task/commit per interface:

- `CandidateWorkspace`: `NullCandidateWorkspace`, `FilesystemCandidateWorkspace` (both `src/main`); any anonymous test doubles in `PrepareHandlerTest`/`MarkReviewedHandlerTest` that implement `install`.
- `ApprovedSnapshotWorkspace`: `NullApprovedSnapshotWorkspace`, `FilesystemApprovedSnapshotWorkspace`; `MarkReviewedHandlerTest#approvedSnapshotWorkspaceThrowing` (the anonymous class already known from S06's own D1).
- Every call site: `PrepareHandler#prepareAdmittedEssay` (passes translated title/description alongside bodies), `MarkReviewedHandler#installApprovedSnapshot` (passes the candidate's already-carried title/description through unchanged — no new lookup, no new failure mode).

**Alternatives considered:** a new sibling type (e.g. `CandidateMetadata`) carrying only title/description, composed alongside `CandidateSnapshot`.

**Why not:** `CandidateSnapshot` already is "everything about one candidate/approved triple, content-wise" (S05 D1's own framing when it unified candidate and approved shape). Splitting title/description into a second value type would mean every caller threads two objects instead of one for no behavioral gain — the six fields are equally "the content," not a separable concern.

### D4 — Translation worker contract widens to three fields, one invocation

```java
public interface TranslationWorker {
    TranslationResult translate(String ruBody, String ruTitle, String ruDescription);
    static TranslationWorker createNull(String enBody, String enTitle, String enDescription) { ... }
    static TranslationWorker createNullFailing(String reason) { ... }
}

public final class TranslationResult {
    // success(enBody, enTitle, enDescription) / failure(reason)
    // enBody(), enTitle(), enDescription(), succeeded(), failureReason()
}
```

`ProcessTranslationWorker` (the real, subprocess-based adapter) changes its prompt to request three named outputs instead of one, and reads three result files instead of one:

```java
private static final String BODY_FILE = "candidate.en.md";
private static final String TITLE_FILE = "candidate.en.title.txt";
private static final String DESCRIPTION_FILE = "candidate.en.description.txt";
```

The prompt asks the worker (Codex, via `CodexTranslationCommand`) to write all three files into the same scratch workdir; `collectResult` reads all three, failing closed (same `TranslationResult.failure(...)` path already used for a missing body file) if any is absent. `TranslationCommand#argsFor(workdir, prompt)` is unchanged — only the prompt text and the set of files `collectResult` expects change; the process-invocation contract itself does not.

`NullTranslationWorker` widens its recorded-requests list to `List<TranslatedInput>` (a small local record of the three RU strings) instead of `List<String> requestedBodies()`, and its single constructed `TranslationResult` now carries all three EN strings.

**Alternatives considered:** three separate `translate(String)` calls (title, description, body) using the unchanged single-string interface.

**Why not:** the operator's own comparison during collaborative design was explicit — one worker invocation, three strings in, three strings out — over three round trips, each a real external-process/LLM invocation in production. Three separate calls would also let title, description, and body diverge in translation register independently, which one coherent invocation avoids by construction.

**Risk this decision accepts:** the real subprocess prompt-and-file-collection protocol changes in production code that already has real-world Codex-invocation behavior behind it (decision `dec-20260804-cd0c1597`). Mitigated in Risks below.

### D5 — `install-to-site`: this slice's one new production boundary adapter

```java
public interface ManagedSiteInstaller {
    void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot);
    static ManagedSiteInstaller create(Path siteRoot) { return new FilesystemManagedSiteInstaller(siteRoot); }
    static ManagedSiteInstaller createNull() { return new NullManagedSiteInstaller(); }
}
```

Reuses `CandidateSnapshot` a third time (D3) — the approved snapshot already carries every field the site needs (both body pairs, both title pairs, both description pairs, and `referenceMap()` for `sourceHash`). No new value type is needed to carry input into this port, matching S06 D1's own "reuse, don't invent a parallel type" precedent.

Layout: `<siteRoot>/src/content/<publicCollection>/{ru,en}/<publicId>.md` plus `<siteRoot>/.astro-export/release-provenance.json`. Staging: create-only, stage-then-`ATOMIC_MOVE`, confined to `siteRoot`, reusing `StagedDirectoryInstall` (S06 D3) a fourth time — the destination directories (`src/content/blog/ru/`, `src/content/blog/en/`) are **not** per-identity fresh directories the way `candidate`/`approved`/`release` are (they're shared across every publication in a collection+locale), so this adapter stages one temp file per locale and moves each file individually rather than moving one directory — a variant of `StagedDirectoryInstall`'s pattern, not a verbatim reuse of `moveIntoPlace`'s whole-directory move. This is confirmed in the implementation task, not assumed here.

Handler: `InstallToSiteHandler#installToSite(identity)` reads `ApprovedSnapshotWorkspace#read(identity)`, blocks (no write) if absent — mirroring `BuildFromReviewHandler`'s own "no approved snapshot, block before any write" shape from S06 — then delegates to `ManagedSiteInstaller#install`.

**Alternatives considered:** extending `build-from-review` to also install into the site in the same invocation.

**Why not:** `build-from-review`'s already-shipped, already-tested behavior (write to the review-root `release/` directory) has no reason to change, and REL-01's "derive exclusively from complete approved snapshots" precedent means both commands can independently read the same source of truth without one depending on the other's output. Keeping them separate commands also matches the plan's "at most one new production boundary adapter" per slice — extending `build-from-review` would still need this slice's one new adapter (`ManagedSiteInstaller`), just invoked from a busier existing command instead of a clean new one.

### D6 — `SiteReleaseManifest`: a Java-side reimplementation of `check-content.mjs`'s exact hashing scheme

```java
public final class SiteReleaseManifest {
    // schemaVersion=1, selectedPages=[] (operator decision), managedTrees, managedFiles,
    // activationCount=0, deactivationCount=0, payloadDigest
    public static SiteReleaseManifest computeOver(Path siteRoot, List<String> payloadRoots) { ... }
}
```

Reimplements, field-for-field and byte-for-byte: `hashTree` (SHA-256 stream of `kind` byte + 8-byte big-endian length + relative-path UTF-8 bytes + 8-byte big-endian length + payload bytes, per sorted entry using Java's natural `String` ordering — `site/tests/release-provenance.test.mjs`'s own "uses Java-compatible natural ordering" test exists precisely because this Java side must match); `hashPayloadFiles` (sorted per-file `{path, sha256}` records); `payloadDigest` (SHA-256 over canonical JSON — `{schemaVersion, selectedPages, managedTrees, managedFiles, activationCount, deactivationCount}` in that exact key order, empty `payloadDigest` field appended last) — matching `check-content.mjs`'s `verifyReleaseProvenance`'s `recomputed`/`canonical` objects field-for-field. Field order is load-bearing: `JSON.stringify` on the JS side and Jackson serialization on the Java side must produce byte-identical output, so the Java DTO uses `@JsonPropertyOrder` pinned to the exact JS key order, not alphabetical or declaration order by accident.

**Why a from-scratch Java reimplementation, not a shared spec file or generated code:** no existing mechanism in this codebase generates one language's serializer from the other's; `site/tests/release-provenance.test.mjs`'s own JS implementation is itself hand-written to match `check-content.mjs`, not derived from a shared source. This slice's real-adapter filesystem contract test is what proves the Java side byte-compatible — by literally invoking `check-content.mjs` as a subprocess against Java-produced output, the same proof strategy the JS test suite already uses for itself.

### D7 — Frontmatter synthesis: only fields the domain now actually has

For each locale, the adapter writes:

```yaml
---
id: <publicId>
title: <ruTitle | enTitle>
description: <ruDescription | enDescription>
publish: true
contentType: <publicContentType>          # "essay"
language: <ru | en>
sourceLanguage: ru
sourceHash: <referenceMap.ruHash()>        # same value in both locale files — identifies the RU source
translationStatus: <source | generated>    # ru=source, en=generated
translationOf: <publicId>                  # EN file only
---
<approved body, byte-identical>
```

Every other `content.config.ts` field (`topics`, `tags`, `cover`, `aliases`, `status`, `foundational`, `readTime`, `links`, `date`, `updated`, `translatedAt`, `translationProfile`, and every essay-specific field: `abstract`, `why`, `sections`, `closing`, `sources`) is `.optional()` or `.default(...)` in the Zod schema and is simply omitted — the schema fills them in, and `check-content.mjs`'s own field checks never require them beyond what's listed above (confirmed by reading both files in full). `sourceHash` maps to `ReferenceMap#ruHash()` specifically (not `enHash()` for the EN file) because it identifies the RU source both locale files were derived from — the same pattern `site/tests/release-provenance.test.mjs`'s own fixture already uses (one shared `sourceHash` value across both locale files).

**Alternatives considered:** deterministic placeholder synthesis (title := publicId, description := a fixed string) — this thread's first, rejected direction; see Context.

### D8 — `ApprovedSnapshotWorkspace` gains no new method; `install-to-site` reads via the existing `read(...)`

S06 already added `ApprovedSnapshotWorkspace#read(identity): Optional<CandidateSnapshot>`. D3 widens what `CandidateSnapshot` carries, so `read(...)`'s return type automatically carries title/description once D3 lands — no new method is needed on `ApprovedSnapshotWorkspace` itself, only the widened value type it already returns.

### D9 — CLI: `install-to-site` takes the site root directly, alongside identity and review root

```java
@Command(name = "install-to-site")
public final class InstallToSiteCommand implements Callable<Integer> {
    @Option(names = "--review", required = true) Path reviewDirectory;
    @Option(names = "--site", required = true) Path siteRoot;
    @Option(names = "--collection", required = true) String collection;
    @Option(names = "--content-type", required = true) String contentType;
    @Option(names = "--id", required = true) String publicId;
    // reads ApprovedSnapshotWorkspace.create(reviewDirectory), ManagedSiteInstaller.create(siteRoot)
}
```

Mirrors `BuildFromReviewCommand`'s D6 shape exactly (S06) — explicit identity options, no vault note involved. Registered on `Main` as a fifth subcommand. Produces its own small `InstallToSiteResult` JSON (`ok`/`identity`/`message`), not a `BridgeResponse` — `install-to-site` is absent from `bridge-contract/schema-v2.json`'s command enum for the same reason `build-from-review` is (S06 D5's finding still holds; the plugin never invokes either).

## Risks / Trade-offs

- **[Risk]** This slice's interface changes touch two production interfaces at once (`CandidateWorkspace`, `ApprovedSnapshotWorkspace`) plus one more (`TranslationWorker`) — the widest single-slice ripple so far, spanning every implementor of all three. **Mitigation:** D3/D4 enumerate every known implementor and test double explicitly; each interface's implementors are updated in one dedicated commit (the same discipline S05/S06 used per interface, applied three times here instead of once).
- **[Risk]** `ProcessTranslationWorker`'s real subprocess protocol changes from one result file to three, on a class with real-world Codex-invocation behavior already behind it. **Mitigation:** the change is purely additive to the file-collection contract (same failure-closed shape for a missing file, now checked three times instead of once); `NullTranslationWorker`-backed tests cover every call site's contract shape without invoking a real process, and the real adapter's own existing test (if present) is extended, not rewritten, to assert the three-file round trip.
- **[Risk]** The Java-side `SiteReleaseManifest` hashing/canonicalization must byte-for-byte match `check-content.mjs`'s JS implementation — a single field-order or byte-encoding mismatch fails the real gate. **Mitigation:** the real-adapter filesystem contract test literally subprocess-invokes `check-content.mjs` against Java-produced output (not a Java-side reimplementation of the JS logic asserting against itself), the same proof strategy `site/tests/release-provenance.test.mjs` already uses for the JS side.
- **[Risk]** This slice exceeds the plan's normal one-to-three-scenario guidance in code surface, even though it stays within it in scenario count (REL-05 + ADM-04 = 2 new/modified scenarios). **Mitigation:** the wider surface is recorded as a deliberate, operator-confirmed scope decision in `proposal.md`, not a silent expansion — every touched requirement beyond ADM-04/REL-05 is realized by already-general text, not newly specified.
- **[Risk]** `src/content/<collection>/<lang>/` is a shared directory across every publication in a collection+locale, unlike `candidate`/`approved`/`release`'s per-identity fresh directories — `StagedDirectoryInstall`'s whole-directory atomic move doesn't directly apply. **Mitigation:** D5 flags this as a variant to confirm during implementation (per-file staging within a shared destination directory), not a silent assumption that the existing helper applies unchanged.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit(s); S01-S06's existing behavior and every existing test remain green throughout except where D3/D4's interface widening is proven behavior-preserving for existing call sites by each interface's own updated test suite.

## Open Questions

None outstanding for this slice's design. D1-D9 close every fork raised during the technical collaborative-design pass, including the two forks the operator resolved directly (EN title/description translation, `selectedPages` emptiness) and the one the operator redirected entirely (title/description are admitted, not synthesized). D5's per-file-staging variant is flagged as a confirm-during-implementation detail, not a silent assumption.
