## Context

The exporter's approval-to-site path has two independent stages that both consume the same `CandidateSnapshot`, and neither is currently kind-aware in a way that helps `editorial/curated_page`:

1. **`build-from-review`** (`BuildFromReviewHandler` + `ReleaseOutputStore`) writes `approved.ruBody()`/`approved.enBody()` plus `ReleaseProvenance` to a staging directory (`<outputRoot>/<collection>/<id>/release/{ru.md,en.md,release-provenance.json}`). This stage is already fully kind-neutral (`String ruBody, String enBody` in, two files out) and needs no change.
2. **`install-to-site`** (`InstallToSiteHandler` + `FilesystemManagedSiteInstaller`) re-reads the approved snapshot and, today, unconditionally renders it as `---\n<frontmatter from ruFields()/enFields()/structuredData()>\n---\n<ruBody()/enBody()>` into `src/content/{collection}/{locale}/{publicId}.md`. This is the one place that must become kind-aware, because `editorial/curated_page`'s target is `src/data/pages/{locale}/{publicId}.json` — a JSON document, not Markdown+frontmatter — and the site already reserves that payload root (`FilesystemManagedSiteInstaller.PAYLOAD_ROOTS` has carried `"src/data/pages"` since an earlier slice, unused until now).

A second, independent problem: every existing kind's translatable "body" is genuinely free markdown prose — `PrepareHandler` reads `note.body()` once, generically, for every kind (not just essay), and sends it to the translation worker as `TranslationJob.forSource(ruBody, ruFields)` alongside the kind's `PublicField`s. `editorial/curated_page` (`about`)'s body is not free prose; it is the strict `## Кратко` / `## Eyebrow` / `## Лид` / `## Принципы` / `## Колофон` grammar that `exporter-java`'s `EditorialParser` already parses. If that raw grammar were sent through the free-prose translation path, the worker would translate the Russian heading text itself (`## Лид` → some English phrase), and no fixed-string parser could then re-read structure from the English candidate. `about`'s fields must instead be parsed once, at RU admission time, directly into individually-translated `PublicField`s (exactly how `context`/`association`/`listenFor` already work for `music/album`). **Correction found during Task 5 implementation:** `PrepareHandler` sources `ruBody` from `intake.body()` (the note's real, normalized body) for every kind uniformly, independent of what any kind's `admit()` parsed — so `about`'s `ruBody` is NOT actually empty; it is the same raw `## Кратко`/.../`## Колофон` grammar text, translated wholesale by the worker alongside the fields. That translated body is simply never read by `CuratedPageJson`/`CuratedPagePublicationKind.projectManagedArtifact` (which only consume `ruFields()`/`enFields()`), so the (correctly) parsed-and-individually-translated fields are what actually reaches the JSON artifact — the wholesale body translation is harmless, unread work, not a correctness issue. D3's `EnglishCandidateValidator` fix remains valid and safe as a generalization (proven by its own tests), just not strictly required by this particular kind's evidence as originally reasoned below.

That surfaces one real shared-code gap: `EnglishCandidateValidator.blankFieldDiagnostics` currently treats *any* blank `enBody` as a translation-worker failure (`"Translation worker produced a blank body."`). Every kind implemented so far always has non-blank prose, so this has never fired incorrectly. `editorial/curated_page` is genuinely the first kind with no body at all, so the check needs one line of new evidence-driven nuance, not a redesign.

## Goals / Non-Goals

**Goals:**

- Add `CuratedPagePublicationKind` for `editorial/curated_page`, admitting only `editorialPage: about` this slice, through the existing `PublicationKind` seam.
- Parse the `about` body grammar once, at admission time, directly from `MarkdownNote.body()` (already available to every `PublicationKind.admit()`, not essay-specific), into `PublicField` entries — no free-form body reaches translation for this kind.
- Extract exactly the release-materialization seam the plan pre-authorized: `PublicationKind` gains a `default` artifact-projection method (today's Markdown+frontmatter rendering, moved out of `FilesystemManagedSiteInstaller` verbatim, so every existing kind is unaffected), and `CuratedPagePublicationKind` overrides it to emit JSON.
- Give `FilesystemManagedSiteInstaller` a `PublicationKinds` dependency so it can look the kind up by `(collection, contentType)` and ask it to project, replacing the hardcoded `src/content/.../.md` path logic — for every kind, not just this one, since the path is now kind-owned rather than hardcoded.
- Fix `EnglishCandidateValidator.blankFieldDiagnostics` to only flag a blank `enBody` as a worker failure when the matching `ruBody` was non-blank in the first place.
- Register `editorial/curated_page` in `PublicationKinds.installed()` and extend `write-publication-contract` coverage.

**Non-Goals:**

- No support for the other eight legacy `editorialPage` values (`home`, `essays`, `claims`, `notes`, `music`, `library`, `concepts`, `now`) — each has a materially different body grammar (per G7, `dec-20260813-88dd478e`) and is out of scope until its own future work.
- No generic "structured body parser framework" — `about`'s grammar is a small, purpose-built parser local to `CuratedPagePublicationKind`, following the plan's explicit ban on speculative generalization. A shared parser is warranted only once a second page-type grammar proves the shape is reusable.
- No change to `build-from-review`/`ReleaseOutputStore` — already kind-neutral; `ruBody`/`enBody` flow through as empty strings for this kind with zero code change there.
- No change to `exporter-java` or `site/`; both remain evidence sources only. `site/src/lib/registry.ts`'s `fromPage()` and the existing `src/data/pages` payload-root reservation are read as fixed consumer contracts, not modified.

## Decisions

### D1 — `CuratedPagePublicationKind` owns `editorial/curated_page`; admits only `editorialPage: about`

```java
public final class CuratedPagePublicationKind implements PublicationKind {
    private static final Set<String> KNOWN_PAGE_KEYS = Set.of(
        "about", "home", "essays", "claims", "notes", "music", "library", "concepts", "now");
    private static final String SUPPORTED_PAGE_KEY = "about";

    public String collection() { return "editorial"; }
    public String contentType() { return "curated_page"; }
    public String routePrefix() { return null; } // see D4 — this kind does not use the shared route-prefix convention

    public AdmittedPublication admit(MarkdownNote note) {
        // 1. editorialPage must be present, one of the nine known keys, and equal to publicId (mirrors
        //    exporter-java's own self-check redundancy).
        // 2. editorialPage must equal "about" — any other known key is blocked with a distinct
        //    "not yet supported" diagnostic, not silently admitted.
        // 3. id, title required (no description — see D2's about-grammar summary/eyebrow findings).
        // 4. AboutPageBody.parse(note.body()) — see D2.
    }
}
```

`editorialPage` is validated as a *closed* set (`KNOWN_PAGE_KEYS`) so an unrecognized value gets the same "unsupported collection/content-type pair"-style diagnostic as any other malformed identity, while a *known but unimplemented* value (`home`, `now`, ...) gets its own clearer diagnostic naming the field and stating only `about` is supported today — this is the ADM-03 "unsupported page key is blocked" scenario's actual mechanism.

Alternative considered: admit all nine page keys now with a generic "structured body TBD" placeholder that always blocks. Rejected — this would still require modeling each future grammar's admission shape to write meaningful tests, defeating the purpose of scoping to one fixture; a flat "not yet supported" diagnostic is honest and equally useful to an author.

### D2 — `about`'s body grammar is parsed once, at admission, into `PublicField`s; `ruBody` is empty

A small, kind-local `AboutPageBody` parser (ported behaviourally from `exporter-java`'s `EditorialParser`, not copied verbatim — that class handles all nine grammars in one file; this one handles exactly `about`) reads `note.body()` and requires, in order: one `## Кратко` section (→ `summary`), one `## Eyebrow` section (→ `eyebrow`), one `## Лид` section (→ `lead`), one `## Принципы` section containing at least one `### <title>` subsection with non-empty prose (→ `principles[i].title` / `principles[i].text`, bracket-indexed exactly like `music/album`'s `listenFor[i]` and `concepts/concept`'s `relations[i].name`), and one `## Колофон` section (→ `colophon`).

All parsed values become translated `PublicField` entries (`summary`, `eyebrow`, `lead`, `principles[0].title`, `principles[0].text`, ..., `colophon`) alongside the shared `title`. `AdmittedPublication`'s `ruBody`-equivalent input to `PrepareHandler` — which reads `note.body()` directly, independent of the kind — is therefore never parsed as free prose for this kind; `PrepareHandler` still calls `note.body()` for every kind uniformly (no `PrepareHandler` change), but for `editorial/curated_page` fixtures that body is expected to be re-derived as empty once parsed, so **`CuratedPagePublicationKind`'s own contract requires no additional body content beyond the five required sections**, and the acceptance fixture's vault note simply has no prose outside those sections (matching every real `about.md`-shaped source note).

Invariant fields (`searchable` from `publicSearchable`, and the literal `type: "about"` marker) go into `structuredData`, mirroring every prior kind's invariant channel — except this kind's `structuredData` holds a JSON object fragment, not a YAML fragment (see D5). `topics` and `links` are deliberately not populated: no kind implemented so far carries them (`FilesystemManagedSiteInstaller.frontmatter()` never writes either field for any existing kind), so adding them only for `editorial/curated_page` would be scope creep unsupported by cross-kind evidence; `registry.ts`'s `fromPage()` already defaults both to `[]` when absent.

Alternative considered: keep the raw section-headed grammar as `ruBody` and translate it wholesale, re-parsing the English result with a second, English-keyed grammar (`## Summary`, `## Lead`, ...). Rejected — doubles the parser surface, and nothing anywhere else in this project's translated-content model expects a kind to define two different source grammars for two locales; every other kind translates individual field values, never structural markup.

### D3 — `EnglishCandidateValidator`'s blank-body check becomes conditional on the source body

```java
private static List<String> blankFieldDiagnostics(String ruBody, String enBody, List<PublicField> enFields) {
    List<String> diagnostics = new ArrayList<>();
    if (!ruBody.isBlank() && enBody.isBlank()) {
        diagnostics.add("Translation worker produced a blank body.");
    }
    ...
}
```

`blankFieldDiagnostics` gains a `ruBody` parameter (it already has it in scope at both call sites — `validate(ruBody, enBody, enFields)` and the 4-arg overload already receive `ruBody`) and only flags a blank `enBody` when the RU source body was itself non-blank, i.e. when a translation was actually expected. A kind whose RU source has an empty body (this slice's only such kind) correctly produces an empty EN body with zero diagnostics; a kind with real prose whose worker returns garbage/empty output is still caught exactly as before.

Alternative considered: give `editorial/curated_page` a placeholder non-blank `ruBody` (e.g. a single space or a synthetic marker string) purely to dodge this check. Rejected — a fake body value would leak into `release/{ru.md,en.md}` (still written by the untouched `build-from-review` stage) as meaningless content, and silently defeats the check's actual purpose (catching a broken worker) for a kind that could still have a genuinely broken worker on its *fields*.

### D4 — `PublicationKind` gains a default artifact-projection method; `FilesystemManagedSiteInstaller` becomes kind-aware

```java
public interface PublicationKind {
    String collection();
    String contentType();
    String routePrefix();
    AdmittedPublication admit(MarkdownNote note);
    KindContract contract();

    // NEW
    default ManagedArtifact projectManagedArtifact(
            PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        // Moved verbatim from FilesystemManagedSiteInstaller.frontmatter() + markdownFile():
        // relativePath = "src/content/" + identity.publicCollection() + "/" + locale + "/" + identity.publicId() + ".md"
        // content = "---\n" + <yaml frontmatter from ruFields()/enFields()/structuredData()> + "---\n" + <ruBody()/enBody()>
        // collisionMarkerLine = "contentType: " + YamlScalar.doubleQuoted(identity.publicContentType())
    }
}
```

`ManagedArtifact` is a new tiny immutable value type (`relativePath`, `content`, `collisionMarkerLine`) in the `site` package. `CuratedPagePublicationKind` overrides `projectManagedArtifact` to return `relativePath = "src/data/pages/" + locale + "/" + identity.publicId() + ".json"`, a JSON document built from `approved.ruFields()`/`enFields()` (translated) plus `approved.structuredData()` (invariant JSON fragment, parsed and merged — see D5), and `collisionMarkerLine = "\"contentType\":\"curated_page\""`.

`FilesystemManagedSiteInstaller` gains a `PublicationKinds` constructor dependency (matching the precedent already set for `PrepareHandler`/contract writing/link indexing per the S17a decision), and its `markdownFile()`/`frontmatter()`/`requireNoKindCollision()` methods become: look up `publicationKinds.forIdentity(identity)`, call `kind.projectManagedArtifact(...)` to get the `ManagedArtifact`, resolve `stagedInstall.canonicalRoot().resolve(artifact.relativePath())`, and scan existing-file lines for `artifact.collisionMarkerLine()` instead of the hardcoded `"contentType: "` YAML-line prefix check. Every existing kind's *default* `projectManagedArtifact` reproduces today's exact output byte-for-byte (proven by keeping every existing acceptance fixture green with zero fixture changes), so this is a behaviour-preserving extraction for six kinds and new behaviour for exactly one.

Alternative considered: give `ManagedSiteInstaller`/`FilesystemManagedSiteInstaller` an `if (identity.publicCollection().equals("editorial"))` branch instead of a kind-owned method. Rejected outright — this is precisely the collection/content-type conditional in generic orchestration the plan's kind-neutral lifecycle rule forbids, and it would not scale to a second non-Markdown kind without another branch.

### D5 — `structuredData` holds a JSON fragment for this kind; format is opaque everywhere except the render site

`structuredData` is a plain `String` carried opaquely through `AdmittedPublication`, `CandidateSnapshot`, workspace persistence, and staleness/invariant-change comparison (confirmed: nothing outside `FilesystemManagedSiteInstaller` parses its syntax — every other consumer does byte-equality or straight pass-through). Only the render site (D4's `projectManagedArtifact`) interprets its contents, and that interpretation is now kind-owned. `CuratedPagePublicationKind.structuredDataFrom(...)` therefore emits a JSON object fragment (`{"searchable":true,"type":"about"}`) instead of YAML lines; every other kind's `structuredDataFrom` is untouched and keeps emitting YAML.

Alternative considered: keep `structuredData` YAML-only and have `CuratedPagePublicationKind` re-derive its own invariant values a different way (e.g. a dedicated field on `AdmittedPublication`). Rejected — `structuredData` already exists exactly to carry kind-owned invariant data opaquely from admission through to release; adding a second, parallel carrier for one kind duplicates a solved problem instead of using the existing seam as designed.

### D6 — `editorial/curated_page` has no `routePrefix()`-shaped route; site URL construction stays kind-owned

Every existing kind's `routePrefix()` feeds the shared `/{routePrefix}/{publicId}/` route-construction convention. `about`'s real route is `/{locale}/about/` (no collection segment at all — `pageUrl()` in `registry.ts` special-cases `id === 'home'` to `/` and otherwise emits `/{language}/{id}/`), which does not fit that convention. Rather than stretch `routePrefix()`'s contract to express "no prefix" ambiguously, `CuratedPagePublicationKind.routePrefix()` returns `null` and `PCM-02`'s route-policy scenario is satisfied by documenting the route directly in this kind's own manifest-projection logic, not by generalizing the shared convention for one caller. This is flagged as an open question below since it is the one place this slice's design is least settled.

## Risks / Trade-offs

- [Risk] `FilesystemManagedSiteInstaller` gaining a `PublicationKinds` dependency touches every existing kind's release path, even though their output is meant to be byte-identical. → Mitigation: the full existing acceptance suite (763 tests, including one full end-to-end fixture per kind) re-runs unchanged as the regression gate; any byte-level drift fails immediately and loudly rather than silently.
- [Risk] `EnglishCandidateValidator`'s blank-body check change is shared code touched by a single new kind's evidence. → Mitigation: the change is additive-only (an existing check now requires one more condition to fire; it never fires in a new situation it didn't fire in before), and every existing kind's fixtures have non-blank `ruBody`, so no existing test's expected outcome changes.
- [Risk] `about`'s hand-parsed grammar could silently diverge from `exporter-java`'s `EditorialParser` in an edge case (e.g. whitespace handling, multiple `### ` principles). → Mitigation: the acceptance fixture is drawn directly from `EditorialParserTest`'s own `about` fixture text (already read during this design pass), and G7's frozen-edition decision makes `exporter-java` the explicit compatibility oracle for this grammar.
- [Risk] The `routePrefix() -> null` decision (D6) is the least evidence-backed part of this design — only one fixture exists, so there is no second data point proving `null` is the right shape for a future no-collection-segment kind. → Mitigation: flagged explicitly as an open question; revisit if `home` (the other collection-less legacy page) is ever implemented.

## Migration Plan

1. Add `ManagedArtifact` value type; extract `projectManagedArtifact` as a `default` method on `PublicationKind` from `FilesystemManagedSiteInstaller`'s existing rendering logic, with zero behavior change for existing kinds (verified by the unchanged existing acceptance suite).
2. Thread `PublicationKinds` into `FilesystemManagedSiteInstaller`'s constructor and its composition root; replace the hardcoded Markdown path/frontmatter/collision logic with calls through `PublicationKind.projectManagedArtifact`.
3. Fix `EnglishCandidateValidator.blankFieldDiagnostics` to accept the source `ruBody` and only flag a blank `enBody` when `ruBody` was non-blank.
4. Add `AboutPageBody` (the local `about` grammar parser) and `CuratedPagePublicationKind` (admission, translated `PublicField` construction including bracket-indexed `principles`, invariant JSON `structuredData`, and the overridden `projectManagedArtifact`); register it in `PublicationKinds.installed()`.
5. Add one full `editorial/curated_page` (`about`) acceptance fixture (admit → prepare → approve → build-from-review → install-to-site) proving the JSON artifact lands at `src/data/pages/{locale}/about.json` with the expected shape, and that the eight unsupported `editorialPage` values are blocked with the documented diagnostic.
6. Extend `write-publication-contract` CLI coverage so the emitted contract includes `editorial/curated_page`'s required fields and structured-body requirement.

Rollback is a plain revert. Steps 1-3 are behavior-preserving extractions with their own regression gate (the existing suite); step 4 onward adds one new kind with no destructive change to existing state.

## Open Questions

- D6's `routePrefix() -> null` shape is a placeholder for "this kind's route doesn't fit the shared convention," decided with only one fixture as evidence. If a second collection-less curated page (`home`) is ever implemented, this may need a real typed representation instead of `null`. Deferred rather than resolved now, since generalizing from one data point would guess at a shape neither fixture yet proves.
