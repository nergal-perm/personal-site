## Context

S02 left `InspectPublicationHandler` able to admit a valid `blog/essay` (via `EssayAdmission`, now reading
its source identity from frontmatter key `id`, not `sourceId` — see the pre-slice rename, `note-20260804-469b8022`)
and report four permanently-absent state dimensions. S03 replaces "always absent" with a real `prepare`
command: given the same admitted essay, it produces exactly one first-publication candidate triple — RU
(frontmatter-stripped body, verbatim, per `dec-20260804-9f43c17f`/G4), EN (worker output, per
`dec-20260804-cd0c1597`/G3), and `references.json` (a schema-valid empty map, per `specs/semantic-references/spec.md`'s
new SEM-03 scenario) — and returns a lean schema-v2 response.

All code in this slice is governed by the same standing conventions as S01/S02 (memory
`feedback-exporter-design-testing-conventions`): `/nullables` as the default testing technique,
`/applying-sbpp` for class/method structure, and `/oo-design-guide` for encapsulation/cohesion/coupling.

## Goals / Non-Goals

**Goals**
- `prepare` for the S02-admitted essay installs one candidate triple and returns `ok: true`,
  `status: "ready_for_review"` with the essay's identity and no diagnostics (TRP-01, PCM-01, PCM-02).
- Preparing an unrelated invalid publication creates no job or candidate for it (ADM-05, realized via
  TRP-01's own "Another publication is invalid" scenario).
- `references.json` is a real `ReferenceMap` bound to the candidate's identity and exact RU/EN hashes,
  with an empty occurrence list, accepted as schema-valid rather than treated as missing (SEM-03).
- G3 (worker protocol) and G4 (RU normalization depth) are both closed with recorded Haft decisions
  before this document is finalized — done: `dec-20260804-cd0c1597`, `dec-20260804-9f43c17f`.

**Non-Goals** (deferred to later slices, per this change's `scope-pins.md`)
- Diffing against an approved baseline, candidate replacement, and preserving a known-good English
  candidate on failure (TRP-02/03, S08) — there is no prior candidate to preserve in this slice.
- Job isolation, unique job workspaces, and concurrent-job handling (TRP-04, S08).
- Semantic occurrence IDs (TRP-05/SEM-02, S19) — `references.json`'s `occurrences` array is always empty.
- Links, transclusions, assets, protected-Markdown/Obsidian-comment removal (PCM-03/04/05, S12-S14) — RU
  is the source body passed through verbatim.
- Structural translation-alignment validation beyond "non-empty" (PCM-06, S08/S17).
- Reporting the four inspect-style state dimensions from `prepare` itself (operator's explicit choice
  during the functional collaborative-design pass) — the plugin calls `inspect-publication` separately.

## Decisions

### D1 — Two new production adapters this slice: `TranslationWorker` and `CandidateWorkspace`, not one merged port

```java
public interface TranslationWorker {
    TranslationResult translate(String ruBody);
}

public interface CandidateWorkspace {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);
}
```

**Alternatives considered:** collapsing both into one port (e.g. `CandidateWorkspace.installFromTranslation(...)`
that internally invokes the worker), reasoning that the plan's slice discipline says "at most one new
production boundary adapter" per slice.

**Why not merged:** the plan's own "Outside-in implementation discipline" section separately names
`translation-worker` and `review-workspace` as two of the project's four standing adapter categories
(alongside `vault` and `site`) — S03 is simply the first slice to introduce either one, not a slice
inventing two ports for architectural symmetry (the exact thing condition 4 guards against). They are
genuinely different I/O concerns (spawning an external process vs. durable atomic file writes) that
later slices reuse independently: `translation-worker` again at S08 (reprepare), `review-workspace` at
S05 (approval reads the candidate), S08/S09 (replace it), and S06 (release reads the approved snapshot
built the same way). Forcing them into one port would make the worker responsible for filesystem
bookkeeping it has no business owning, or the candidate store responsible for process management — worse
coupling than two small ports. The plan's own S03 entry says exactly this: "one real worker adapter and
one real candidate-workspace adapter, each verified against its fake" — read as the plan's own explicit
exception to its general rule for this specific slice, not a silent violation of it.

### D2 — `TranslationWorker`'s real adapter delegates argv construction to an injected `TranslationCommand` (G3)

```java
public interface TranslationCommand {
    List<String> argsFor(Path workdir, String prompt);
}

public final class CodexTranslationCommand implements TranslationCommand {
    public List<String> argsFor(Path workdir, String prompt) {
        return List.of("codex", "exec", "--ephemeral", "--sandbox", "workspace-write",
                "--skip-git-repo-check", "-C", workdir.toString(),
                "--output-last-message", workdir.resolve("agent-message.txt").toString(),
                prompt);
    }
}

public final class ProcessTranslationWorker implements TranslationWorker {
    ProcessTranslationWorker(TranslationCommand command, Duration timeout) { ... }
    // translate(ruBody): creates a scratch workdir, builds the prompt (see D2a below),
    // spawns command.argsFor(workdir, prompt) with input redirected from /dev/null (no stdin,
    // matching CodexRunner), waits up to timeout with forced termination on expiry, then reads
    // workdir/candidate.en.md. Missing file, non-zero exit, or timeout => TranslationResult.failure(...).
}
```

Per `dec-20260804-cd0c1597`: this argv, the no-stdin invocation, the 900-second timeout-with-forced-termination
behaviour, and the `candidate.en.md`-in-workdir result convention are each evidenced 1:1 from
`exporter-java`'s `CodexRunner`/`PrepareWorkflow` (a compatibility oracle, not a code donor — the *shape*
is reused, not any Java source). `CodexTranslationCommand` is the only `TranslationCommand` wired in this
slice; a future `gemini`/`agy` tool is a new implementation of the same interface, not a
`ProcessTranslationWorker` rewrite.

**D2a — the prompt is deliberately much smaller than exporter-java's.** Legacy's prompt instructs the
model to preserve YAML frontmatter, a structural template, and semantic occurrence IDs (`ref:ref-NNNN`)
— none of which exist yet in this slice (PCM-06, SEM-02 are both later). S03's prompt is: translate this
plain-prose Russian body to English and write the complete result, and only the result, to
`candidate.en.md` in the current directory. Growing the prompt to match legacy's full contract happens
incrementally as PCM-06/SEM-02 land, not speculatively here.

`TranslationResult` follows the same Whole-Value shape as `EssayAdmission.Result` (private constructor,
named factories `success(String enBody)`/`failure(String reason)`, no bare `new`).

### D3 — `CandidateWorkspace`'s real adapter stages then atomically renames; directory layout reuses `--review`

```java
public final class FilesystemCandidateWorkspace implements CandidateWorkspace {
    FilesystemCandidateWorkspace(Path reviewRoot) { ... }
    // install(...): writes ru.md, en.md, references.json into a temp staging directory under
    // reviewRoot, then Files.move(staging, reviewRoot/<publicCollection>/<publicId>/candidate,
    // ATOMIC_MOVE) so the triple lands as one coherent unit or the install visibly fails before
    // any of the three files are observable at their final path.
}
```

Directory naming (`candidate/{ru.md,en.md,references.json}`) matches `exporter-java`'s
`CandidateSnapshotStore`'s own `Set.of("ru.md", "en.md", "references.json")` evidence. `reviewRoot` is
`InspectPublicationCommand`'s already-existing, previously-unused `--review` option, now finally wired to
something — `PrepareCommand` reuses the same option.

**Alternatives considered:** writing the three files directly to their final path with no staging step.

**Why not:** violates TRP-01's "one coherent unit" requirement — a crash between writing `ru.md` and
`en.md` would leave a partial, invalid candidate directory. Staging + atomic rename is the same pattern
S05 will need for approved-snapshot installs (RVA-05) and S09 for replacement; S03 establishes it once
for candidates, at create-only scope (no existing candidate to replace yet, per this slice's exclusions).

### D4 — `ReferenceMap`: a real Whole Value with an always-empty `occurrences` list, no JSON-Schema file yet

```java
public final class ReferenceMap {
    public static ReferenceMap empty(PublicationIdentity identity, String ruHash, String enHash) { ... }
    // schemaVersion, identity, ruHash, enHash accessors; occurrences() returns List.of() always in this slice
}

public final class ReferenceMapCodec {
    public static String write(ReferenceMap map) { ... } // Jackson serialization
}
```

SEM-03 requires the map to "bind publication identity, exact RU and EN hashes... without duplicate,
unknown, or unused references" even when `occurrences` is empty — so this slice cannot get away with
writing a literal `{}`. `ruHash`/`enHash` are content hashes of the just-installed RU/EN bodies (SHA-256,
matching REL-03's later hash-based provenance language so the same hash function is reused, not
reinvented, when release provenance is built in S06).

**Alternatives considered:** a formal JSON-Schema file for `references.json`, mirroring
`bridge-contract/schema-v2.json`.

**Why not yet:** unlike `BridgeResponse`, nothing outside this Java process reads `references.json` yet
— S05, S06, and S19 (the only consumers so far) are all Java, and all reuse `ReferenceMapCodec` directly
rather than re-parsing JSON against an external contract. A cross-language conformance need is exactly
what justified `bridge-contract/schema-v2.json`; it doesn't exist here yet. Revisit if a non-Java consumer
of `references.json` ever appears.

### D5 — `Frontmatter` gains a `body()` accessor; no new parser class

```java
public final class Frontmatter {
    // existing: parse(String), string(String), flag(String)
    public String body() { ... } // everything after the closing --- delimiter, verbatim; the
                                   // whole source text if no frontmatter block was found (same
                                   // "absent means unchanged" precedent as string()/flag())
}
```

**Why not a separate class:** `Frontmatter.parse` already scans the source line-by-line to find the
closing delimiter; capturing "everything after that point" is the same scan, not a second responsibility.
Splitting it into a second parser would duplicate the delimiter-scanning logic S02 already built and
tested.

### D6 — `PrepareHandler`: same orchestration shape as `InspectPublicationHandler`, one new collaborator role each

```java
public final class PrepareHandler {
    public BridgeResponse prepare(
            VaultRelativePath notePath, VaultReader vaultReader,
            TranslationWorker worker, CandidateWorkspace workspace) {
        // 1. vault/path safety + read + Frontmatter.parse + EssayAdmission.admit — identical to
        //    InspectPublicationHandler's existing sequence, reused, not duplicated (extract the
        //    shared "admit this note" step during implementation if duplication proves real, per
        //    the plan's own refactor-inside-the-cycle discipline — not pre-emptively here)
        // 2. worker.translate(frontmatter.body()) — on failure, return BridgeResponse.prepareFailed(...)
        // 3. workspace.install(identity, ru, en, ReferenceMap.empty(identity, hash(ru), hash(en)))
        // 4. return BridgeResponse.prepared(identity) — ok:true, status: ready_for_review
    }
}
```

Same deliberate `oo-design-guide` 3.9 departure S01/S02 already established for `InspectPublicationHandler`
and `EssayAdmission` — noted once, not re-litigated per class.

### D7 — `BridgeResponse.prepared(...)`: a new, lean factory — no state-field reuse from `essayInspected`

```java
public static BridgeResponse prepared(String command, PublicationIdentity identity) {
    // ok: true, status: "ready_for_review", identity set, candidateState/approvedSnapshotState/
    // semanticReferenceState/releaseState all left null (omitted from JSON via existing
    // @JsonInclude(NON_NULL)) — per the operator's explicit choice: prepare confirms identity +
    // status only; a follow-up inspect-publication call is how the plugin observes full state.
}
```

**Correction found during low-level planning:** `blocked(...)` hardcodes `status: "metadata_blocked"`
inside its own factory body, so it cannot be reused as-is for a `translation_failed` outcome. The
worker-failure path instead gets its own factory, `BridgeResponse.translationFailed(String command,
List<Diagnostic> diagnostics)`, with the exact same shape as `blocked(...)` (`ok: false`, same
`@JsonInclude(NON_NULL)`-omitted state fields) but the `"translation_failed"` status literal — one more
named Constructor Method (SBPP-BEH-02) for the private constructor, not a constructor change.

## Risks / Trade-offs

- **[Risk]** The Codex-specific prompt/result convention (D2/D2a) is unverified against a live `codex`
  binary in this design pass. **Mitigation, refined during low-level planning:** `ProcessTranslationWorker`'s
  own contract test does not need a live `codex` — it's injected with a small portable test
  `TranslationCommand` (`sh -c ...`) to prove the adapter's own mechanics (workdir lifecycle, timeout
  enforcement, result-file reading, non-zero-exit/missing-result-file failure) without any external
  dependency; a separate, dependency-free unit test proves `CodexTranslationCommand.argsFor(...)` produces
  the exact evidenced argv. No test in this slice requires `codex` to actually be installed. A full
  CLI-wired "prepare succeeds against a live `codex`" run stays an unautomated manual smoke check, the
  same category as S07's Astro smoke test.
- **[Risk]** `ReferenceMap`'s hash function choice (D4) is provisional — SHA-256 is a reasonable default
  but REL-03 (S06) may have its own established hashing convention by the time it's built.
  **Mitigation:** flagged here as a concrete revisit trigger for S06, not silently assumed compatible.
- **[Risk]** `references.json`'s lack of a JSON-Schema file (D4) means a malformed map written by a bug
  in `ReferenceMapCodec` itself wouldn't be caught by an external contract test, only by
  `ReferenceMapCodec`'s own unit tests. **Mitigation:** accepted per the plan's own anti-premature-
  abstraction stance; `ReferenceMapCodec` gets targeted unit tests for the empty-map shape now, and full
  round-trip tests once SEM-02/S19 introduces non-empty occurrences.
- **[Risk]** Two new production adapters in one slice (D1) is more surface than most slices introduce.
  **Mitigation:** explicitly justified above against the plan's own S03 text and named standing adapter
  categories, not asserted without reasoning.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit(s); S01/S02's
`inspect-publication` path, CLI option surface, and every existing test remain unchanged and green
throughout.

## Open Questions

None outstanding. D1-D7 close every fork raised during design; the Codex-prompt-verification risk above
is a flagged mitigation for the implementation task sequence, not a blocking open question.
