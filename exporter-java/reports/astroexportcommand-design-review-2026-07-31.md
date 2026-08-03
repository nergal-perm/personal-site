# AstroExportCommand.java — Design Review

Reviewed: `src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java` (2234 lines)
Date: 2026-07-31
Method: two parallel subagent reviews — one applying `oo-design-heuristics` (Riel), one applying `applying-sbpp` (Smalltalk Best Practice Patterns) — findings merged below.

---

## Top 5 (combined, by severity)

### 1. God class wrapping a 530-line "do everything" method (`markReviewed`)
**Where:** whole file (2234 lines), worst concentration at lines 794–1326
Both reviews independently flagged this as the top issue. `AstroExportCommand` owns CLI parsing, preflight orchestration, locking, three-phase commit verification, snapshot staging, and JSON response assembly. `markReviewed` alone threads a dozen interdependent locals (`candidateRu`, `semanticLease`, `sourceApproved`, `publishedApproved`, ...) through nested try/catch, so a bug in one exit path (e.g. a forgotten `semanticLease.close()`) is invisible in review — matching this repo's history of repeated "harden migration"/"exercise semantic gates" patch-ups.
**Fix:** Extract a `MarkReviewedOperation` method-object with the locals as fields and named phase methods (`validateFreshness()`, `commitPublishedSnapshot()`, `commitWorkflowState()`); extract sibling services `PublicationRefreshService` (1328–1461) and `ReviewPairValidator` (370–432) so `AstroExportCommand` shrinks to argument parsing + delegation + JSON emission.

### 2. Duplicated diagnostic/response-building and reconciliation logic across the file
**Where:** e.g. 1205–1219 vs 1252–1266, plus similar 6–7 line `bridge(...).note(...).identity(...).diagnostics(...).build()` blocks at 890–898, 903–913, 952–963, 1135–1148, 1282–1292; the "stable preflight + concurrent-update retry" shape is independently reimplemented in `markReviewed`, `candidateState()` (723–762), and the `refresh` loop (1343–1450)
**Why it matters:** the same failure-reporting shape and the same staleness-recheck protocol are hand-copied 3+ times. A wording change or a new field on `BridgeResponse` means hunting down every near-identical site; a protocol fix has to be applied redundantly and is easy to miss in one copy.
**Fix:** extract shared helpers like `emitStale(note, identity, workspaceHealth, field, message)` and a single `reconcileOnePublication(...) -> ReconciliationOutcome` used by both `markReviewed` and `refresh`.

### 3. Domain logic and raw data structures leak across layer boundaries
**Where:** CLI reads `Note.frontmatter()` map directly with hardcoded keys and validation rules (358–364); `target()` decodes `ManifestEntry`'s path convention via string-splitting in the CLI class (1783–1796, with unguarded `parts[4]`/`parts[2]` indices)
**Why it matters:** two different domain objects' internal representations (frontmatter schema, manifest path convention) are duplicated as knowledge inside an unrelated CLI class. A layout change in either silently breaks this file — the array-index version fails as an unhelpful `ArrayIndexOutOfBoundsException` with no context on which manifest entry was at fault.
**Fix:** move identity-candidate extraction onto `Note` (e.g. `Note.publicationIdentityCandidate()`) and path parsing onto `ManifestEntry` (e.g. `ManifestEntry.collectionAndPublicId()`), each owning its own format knowledge with a proper guard/validation error.

### 4. Nested types, static utilities, and subcommands form a hidden "package inside a class"
**Where:** 10 nested records/exceptions (1999–2067) plus ~15 stateless static helpers, none touching instance state; subcommand inner classes (2069–2232) call the parent's *private* methods (`parent.identityFromPreflight`, `parent.emitJson`, etc.)
**Why it matters:** these types/helpers are a self-contained value-object library that can't be reused or unit-tested independently because they're private to the command class; subcommands are fully welded to the outer class's private surface rather than a narrow contract, so neither can be tested or reasoned about in isolation.
**Fix:** promote the records/exceptions to top-level types in the relevant domain packages; move path/lock helpers into `PathContainment`/`FileLocking` utility classes; have subcommands depend on `CommandServices` plus the extracted workflow classes directly instead of `parent`.

### 5. Commit-protocol state tracked with ad-hoc booleans and same-typed positional parameters
**Where:** `sourceApproved`/`publishedApproved` flags set deep inside a try block, read in unrelated catch blocks hundreds of lines later (1007–1267); `replaceCandidateWithReviewed` takes 9 parameters, six of them same-typed `byte[]`/`Path` (480–506, called at 1027–1036); boolean-heavy call sites like `prepareBilingualManifest(selection, reviewRoot, false, vaultRoot, false)` (182) and `runExport(..., !dryRun)` (2081); two unrelated methods both named `candidateState` (723 vs 751)
**Why it matters:** flags-as-state-machine and same-typed positional args are both classic transposition/omission bugs waiting to happen — the compiler can't catch a swapped `candidateEn`/`reviewedEn` byte array, and a missed flag-check on a new failure path silently reports the wrong recovery message to the operator. The overloaded `candidateState` name makes call sites read as recursion when they aren't.
**Fix:** once extracted per #1, turn the two booleans into a small explicit enum (`NOT_STARTED`/`SOURCE_COMMITTED`/`PUBLISHED_COMMITTED`); bundle the candidate triple into a `CandidateSnapshot(ru, en, references)` record; replace positional booleans with named factory methods (`ManifestOptions.forDryRun()`); rename one `candidateState` overload to disambiguate (e.g. `candidateStateFromPair`).

---

## Full findings — OO Design Heuristics review (Riel)

### 1. God class controlling the entire application (3.1, 3.2)
**File:** whole file, esp. 156–1326, 1328–1461, 1889–1997

The class does CLI argument parsing, export orchestration, semantic-migration mode dispatch, review-pair freshness validation, byte-level snapshot comparison, temp-directory-based translation re-validation, three-phase commit verification for published snapshots, file locking, path-containment security, refresh-queue reconciliation, and JSON bridge-response assembly — all in one type. Every other domain concept in the system (`ReviewWorkspace`, `WorkflowStateService`, `PublishedSnapshotStore`, `SemanticOperationLock`, `PageReferenceMapCodec`, `TranslationValidator`) is a satellite that this class pulls into itself and re-orchestrates by hand rather than delegating to a workflow object that owns the orchestration.

**Why it matters:** any change to the mark-reviewed protocol, the migration-mode gating, or the locking scheme touches this one file, and reasoning about correctness requires holding the entire 2234-line class in your head. The commit history in this repo (multiple "harden migration"/"exercise semantic gates" fixes) is exactly the failure mode this produces — narrow protocol fixes keep landing in the same monolith because there's no smaller unit to change.

**Fix:** extract a `MarkReviewedWorkflow` (or `PublicationApprovalWorkflow`) service that owns the whole approve-and-commit protocol (steps currently at 794–1326), a `PublicationRefreshService` for lines 1328–1461, and a `ReviewPairValidator` for lines 370–432. `AstroExportCommand` should be reduced to argument parsing + one delegating call per subcommand + JSON emission.

### 2. `markReviewed` is a ~530-line method doing five jobs at once (4.2, 4.4, 2.3)
**File:** 794–1326

Single method: acquires a publication lock, re-validates staleness twice, branches on semantic vs. legacy schema mode, reads/validates a three-file candidate snapshot, stages a published snapshot, runs a two-phase "verify nothing changed at each commit boundary" protocol (three separate re-preflight checks at 1010, 1051, 1067), commits workflow state, and has ~10 distinct catch blocks each hand-building a different `BridgeResponse`.

**Why it matters:** the commit-boundary re-validation logic (three near-duplicate "did anything change since we started" checks) needs to be independently testable. Buried in a 530-line method with a dozen early returns, a missed edge case (e.g., forgetting to close `semanticLease` on one exit path) is invisible in review — and this file's own commit history shows that's exactly what keeps happening.

**Fix:** extract an `ApprovalCommitProtocol` class with named steps (`validateStillFresh()`, `stageCandidate()`, `commitPublishedSnapshot()`, `commitWorkflowState()`) that can be unit tested against fakes for `WorkflowStateService`/`PublishedSnapshotStore` independent of CLI/JSON concerns.

### 3. Ten nested types + a dozen static utility methods turn the class into a package-in-a-class (2.8, 2.9)
**File:** 1999–2067 (records/exceptions), plus statics at 1751, 1755, 1763, 1771, 1802, 1810, 1829, 1835, 1839, 1846, 1854, 1871, 1904, 1974, 1985

`PublicationIdentity`, `Target`, `ReviewPairState`, `StablePreflight`, `CurrentPairState`, `ReleaseInspection`, `DerivedRefreshState`, `CliValidationException`, `LockBusyException`, `LockHandle` are all nested inside the command, alongside stateless helpers like `text()`, `decode()`, `target()`, `bounded()`, `snapshotsCurrent()`, `concurrentWorkflowRecovery()`. None of these types or helpers reference `AstroExportCommand`'s own fields (`vault`, `out`, `dryRun`, `report`, `review`) — they're a self-contained domain-value-object library that happens to live inside the CLI class only because that's where the first method that needed them was written.

**Why it matters:** this is the textbook sign a class doesn't capture "one and only one key abstraction." Anyone wanting to reuse `bounded()`'s path-containment logic, or write a unit test for `Target` parsing, has to reach through `AstroExportCommand`'s private surface.

**Fix:** move the record/exception types to `dev.eugene.astroexport.review`/`.migration` packages as top-level types; move the static helpers into a `PathContainment` utility class (`bounded`, `boundedReviewDirectory`, `nearestExisting`, `validatedAstroRoot`) and a `FileLocking` utility (`acquireLock`, `LockHandle`, `LockBusyException`).

### 4. Domain validation logic (review-pair freshness) implemented in the CLI layer (3.5, 2.9)
**File:** 370–432

`reviewPairState()` reads a file with hard-link/regular-file guards, decodes UTF-8 with strict error actions, creates a temp directory, re-invokes `services.buildEnglishManifest` against it purely to re-derive `translationStatus`, then re-compares byte snapshots to detect a race. This is model-level business logic (what makes a translation review "fresh") wearing a controller's clothes.

**Why it matters:** this logic is duplicated in spirit by `candidateState()` (723–762) and `currentPairStateAfterConflict()` (1950–1972), which independently reconstruct freshness/candidate state through slightly different paths — a classic sign the abstraction (`ReviewPairState`, a valid one) needs its *behavior* moved next to it instead of being recomputed ad hoc from the command.

**Fix:** extract a `ReviewPairValidator` class owning `reviewPairState()`, `candidateState()`, and `candidateTripleMatches()`; have it depend on `TranslationValidator` and `ReviewWorkspace` directly so the CLI class just calls `validator.stateFor(entry, reviewRoot)`.

### 5. CLI reaches directly into `Note`'s frontmatter map instead of using a typed accessor (2.1, 2.2)
**File:** 358–364

```java
String collection = text(note.frontmatter().get("publicCollection"));
String publicId = text(note.frontmatter().get("publicId"));
if (collection == null || publicId == null
    || !PublicationKind.allowedCollections().contains(collection)
    || PublicationKind.allowedContentTypes(collection).isEmpty()
    || !PUBLIC_ID.matcher(publicId).matches())
```

`Note.frontmatter()` is exposed as a raw `Map`, and the CLI class knows the literal frontmatter key strings and the validation rules for what makes a valid publication identity. That's `Note`'s encapsulated state leaking its internal representation and business rules into a caller two layers away.

**Why it matters:** if the frontmatter key for collection/id changes, or a new identity-validity rule needs to be added, both `Note` (wherever frontmatter is populated) and this unrelated CLI class must change together — classic shotgun-surgery coupling from broken encapsulation.

**Fix:** add `Note.publicationIdentityCandidate()` (or similar) returning an `Optional<PublicationIdentity>`/raw pair, with the key names and `PUBLIC_ID` pattern owned by `Note` or `PublicationKind`.

### 6. `target()` decodes `ManifestEntry`'s path structure via string-splitting in the CLI class (2.9, 2.1)
**File:** 1783–1796

```java
if (targetPath.startsWith("src/content/")) {
  String[] parts = targetPath.split("/");
  String publicId = parts[4].replaceFirst("\\.md$", "");
  return new Target(parts[2], publicId);
}
if (targetPath.startsWith("src/data/pages/ru/")) { ... }
```

This hardcodes `ManifestEntry.targetPath()`'s directory-layout convention (`src/content/{kind}/{collection}/{publicId}.md` vs `src/data/pages/ru/{id}.json`) as index-based string parsing, entirely outside `ManifestEntry`.

**Why it matters:** `ManifestEntry` is the class that should know its own path format. If the target-path layout changes (new collection type, renamed content directory), this brittle index-based parser in an unrelated CLI file breaks silently (e.g., `parts[4]` throwing `ArrayIndexOutOfBoundsException`) rather than the change being localized to `ManifestEntry`.

**Fix:** move this parsing into `ManifestEntry.collectionAndPublicId()` (returning the `Target`-equivalent pair), keeping the path convention as `ManifestEntry`'s private knowledge.

### 7. Excessive collaborator count (4.1)
**File:** imports at 1–58, used throughout

The class collaborates with 25+ distinct types spanning six different packages (`assets`, `discovery`, `fs`, `manifest`, `migration`, `model`, `prepare`, `references`, `release`, `report`, `review`, `translation`, `validation`, `workflow`) plus picocli's framework types. Riel's guidance is to keep this small; this class is at the opposite extreme — it is the one place every subsystem in the exporter meets.

**Why it matters:** this is both cause and symptom of finding #1 — a class this widely coupled cannot be changed in isolation, and every subsystem's public interface change is a candidate for breaking this file.

**Fix:** a direct consequence of extracting `MarkReviewedWorkflow`, `PublicationRefreshService`, `ReviewPairValidator`, and the utility classes above — each extracted class would collaborate with a small, coherent subset (e.g., `ReviewPairValidator` only needs `TranslationValidator`, `ReviewWorkspace`, `ManifestResult`), and `AstroExportCommand` itself would shrink to collaborating mainly with `CommandServices` and the newly extracted workflow classes.

### 8. Nested subcommand classes reach through the parent's private internals (4.13)
**File:** 2069–2232 (e.g., 2111–2124, 2160–2166)

`PrepareCommand.call()` calls `parent.identityFromPreflight(...)`, `parent.identityFromEntry(...)`, `parent.emitJson(...)`, `parent.bridge(...)` — private methods on the enclosing instance. Every subcommand is wired directly to `AstroExportCommand`'s private implementation surface via `@ParentCommand`.

**Why it matters:** none of these subcommands can be constructed, tested, or reasoned about without the full outer class; a "contained" object (the subcommand) fully depends on its container's internals rather than a narrow public contract. This is somewhat forced by picocli's subcommand idiom, but the command class compounds it by exposing orchestration methods as instance methods on itself rather than on an injectable service the subcommands could hold directly.

**Fix:** have subcommands depend on `CommandServices` plus the (extracted) workflow/response-building classes directly, rather than routing through `parent`'s private methods — `parent` should only be needed for shared `@Option` values like `--review`/`--vault` defaults.

### 9. Duplicate/overloaded `candidateState` naming adds interface clutter (2.3, 2.6)
**File:** 723 (instance, 5-arg) and 751 (static, 1-arg)

Two methods with the same name but different signatures and different responsibilities (one resolves candidate files from disk and schema mode, the other purely maps a `ReviewPairState` to a string) sit next to each other. It reads as one operation but is really "resolve candidate state" plus "classify a pair state," bundled under one overloaded name.

**Why it matters:** minor on its own, but it's a symptom of the same file hosting logic at very different levels of abstraction (I/O-performing resolution vs. pure classification) under a name that hides the distinction — makes the class's protocol harder to scan.

**Fix:** rename the pure classifier to `classify(ReviewPairState)` and keep `candidateState(...)` for the disk-resolving version, or move both into the `ReviewPairValidator` from finding #4 where the naming collision disappears naturally.

---

## Full findings — SBPP review

### 1. `markReviewed` is a ~530-line method that needs Method Object, not just Composed Method
**File:line:** 794-1326
**Pattern violated:** SBPP-BEH-01 (Composed Method), SBPP-BEH-10 (Method Object)
**Why it matters:** The method threads a large cluster of interdependent local state through nested try/catch/finally blocks: `candidateRu`, `candidateEn`, `candidateReferences`, `semanticPageRef`, `semanticLease`, `pair`, `reviewed`, `reviewedBytes`, `stagedRussian`, `pendingSnapshot`, `sourceApproved`, `publishedApproved`. This is exactly the situation SBPP-BEH-10 describes: complexity that plain method extraction can't remove because every "step" needs to read and mutate shared state. As written, understanding any one branch (e.g. what happens on a stale candidate) requires holding the whole method's control flow in your head, and a bug in variable lifetime (e.g. `candidateEn`/`candidateReferences` reassigned mid-method at lines 1037-1038) is easy to miss.
**Fix:** Extract a `MarkReviewedOperation` (or `MarkReviewedAttempt`) class scoped to one invocation, with the above locals as fields and named methods per phase: `acquireSemanticCandidate()`, `validateFreshness()`, `markContentReviewed()`, `commitPublishedSnapshot()`, `commitWorkflowState()`. `markReviewed(...)` on the command then becomes a short Composed Method that constructs the operation and calls `run()`.

### 2. Duplicated diagnostic-emission blocks instead of a shared failure-reporting method
**File:line:** e.g. 1205-1219 vs 1252-1266; also 890-898, 903-913, 952-963, 1135-1148, 1282-1292
**Pattern violated:** SBPP-BEH-01 (Composed Method), SBPP-BEH-18 (Intention Revealing Selector)
**Why it matters:** The "approved baseline saved but source queue could not be updated; run Refresh publication queue" diagnostic (lines 1206-1217 and 1253-1264) is duplicated near-verbatim, and many other `bridge(...).note(...).identity(...).diagnostics(...).workspaceHealth(...).build()` sequences repeat the same 6-7 line shape with only the status string/diagnostic field/message changed. A future change to the wording or to what fields get attached (e.g. adding `pairFreshness`) has to be hunted down across a dozen near-identical sites — a classic sign the block needs a name.
**Fix:** Extract helpers such as `emitQueueUpdateFailed(note, identity, workspaceHealth, error)` and `emitStale(note, identity, workspaceHealth, field, message)` that build and emit the response in one call.

### 2b. `runExport` mixes multiple abstraction levels in one method
**File:line:** 164-288
**Pattern violated:** SBPP-BEH-01 (Composed Method)
**Why it matters:** In one method body you have: path-separation validation, dry-run branch with its own manifest-building/error-reporting sub-flow, semantic-lock acquisition, schema-mode branching between "semantic release" and "bilingual manifest" paths, site writing, and report writing/error-fallback logic. Reading it top to bottom mixes "what are we trying to do" (build a release) with "how do we recover if report-writing itself fails" (lines 273-285) — very different levels of abstraction sitting side by side.
**Fix:** Extract composed methods like `runDryRun(...)`, `buildManifestForWrite(...)`, `writeSiteOrReport(error paths)`, `finalizeReport(...)`, leaving `runExport` as a short sequence of named steps.

### 3. `stablePreflight`/triple-commit protocol relies on scattered boolean flags instead of encapsulated state
**File:line:** 1007-1008 (`sourceApproved`, `publishedApproved`), used at 1159, 1107, 1205, 1252, 1267
**Pattern violated:** SBPP-STA-16 (Collecting Temporary Variable misuse), SBPP-STA-14 (Role Suggesting Names — borderline)
**Why it matters:** `sourceApproved`/`publishedApproved` are mutated deep inside a try block and then read in unrelated catch blocks several hundred lines later to decide which partial-failure message to show. This is using local variables to simulate an object's state machine. It's easy to add a new failure path and forget to check/set one of these flags, silently reporting the wrong recovery guidance to the operator.
**Fix:** Once extracted into a Method Object (finding #1), these become fields with an explicit small state enum (`NOT_STARTED`, `SOURCE_COMMITTED`, `PUBLISHED_COMMITTED`) rather than two independent booleans.

### 4. Boolean-parameter call sites hide intent (violates Intention Revealing Selector / Type Suggesting Parameter Name)
**File:line:** call site 182: `prepareBilingualManifest(selection, reviewRoot, false, vaultRoot, false)`; declaration 312-317; also `runExport(vault, out, dryRun, report, review, !dryRun)` at 2081
**Pattern violated:** SBPP-FMT-02 (Type Suggesting Parameter Name), SBPP-BEH-18 (Intention Revealing Selector)
**Why it matters:** `prepareBilingualManifest(selection, reviewRoot, false, vaultRoot, false)` gives no clue at the call site what the two `false`s mean (`writeRuReview`, `resolveAssets`); a reader must jump to the declaration every time. `runExport(..., !dryRun)` for the `refreshRuReview` parameter is even worse — it silently encodes "refresh iff not a dry run" as a boolean negation at the call site instead of a named concept.
**Fix:** Replace positional booleans with small enums or named factory methods, e.g. `ManifestOptions.forDryRun()` / `ManifestOptions.forWrite(vaultRoot)`, or split into `prepareBilingualManifestForDryRun(...)` / `prepareBilingualManifestForWrite(...)`.

### 5. Two methods named `candidateState` with unrelated behavior (overload masking distinct intents)
**File:line:** 723-749 (instance, dispatches on `SemanticSchemaState.Mode`, does file I/O) vs 751-762 (static, pure function of `ReviewPairState`)
**Pattern violated:** SBPP-BEH-18 (Intention Revealing Selector)
**Why it matters:** Same selector name for two conceptually different operations — one is "compute the candidate state of a publication by reading the review filesystem," the other is "map a `ReviewPairState` to a candidate label." A reader skimming `return candidateState(pair);` inside the instance method has to notice they're calling the *other* overload, not recursing.
**Fix:** Rename to something like `candidateStateForEntry(...)` and `candidateStateFromPair(ReviewPairState pair)`.

### 6. `replaceCandidateWithReviewed` — 9-parameter method is a Method Object candidate
**File:line:** 480-506
**Pattern violated:** SBPP-BEH-10 (Method Object), SBPP-FMT-02 (Type Suggesting Parameter Name)
**Why it matters:** Nine parameters, six of them `byte[]`/`Path` with similar shapes (`candidateRu`, `candidateEn`, `reviewedEn`, `candidateReferences`, `sourceSnapshot`, `sourcePath`, `candidate`). At the call site (1027-1036) it's easy to transpose two `byte[]` arguments (e.g. swap `candidateEn`/`reviewedEn`) and the compiler won't catch it, since they're all `byte[]`.
**Fix:** Bundle the candidate triple into a small record (`CandidateSnapshot(ru, en, references)`) so the signature becomes `replaceCandidateWithReviewed(reviewRoot, entry, current, reviewedEn, sourceSnapshot, sourcePath, candidateDir)`, cutting same-typed positional params.

### 7. Manual lease-close duplicated instead of consistent Execute Around
**File:line:** 900-902, 920-922, 949-951, 978-979, 988-989, contrasted with 1009 (`try (pendingSnapshot; SemanticOperationLock.Lease ignoredLease = semanticLease)`)
**Pattern violated:** SBPP-BEH-11 (Execute Around Method)
**Why it matters:** `semanticLease.close()` is invoked manually and defensively (`if (semanticLease != null) { semanticLease.close(); }`) at five different early-return points, but a few dozen lines later the same object is closed via try-with-resources instead. Two different idioms for the same resource lifetime inside one method make it hard to verify the lease is *always* released — a missed early-return branch would leak the lock.
**Fix:** Wrap lease acquisition in a small helper that returns an `AutoCloseable`-aware structure so every exit path (including the ones before line 1009) uses try-with-resources uniformly, or restructure so the lease is acquired inside the try-with-resources block that already exists.

### 8. `target()` uses unexplained magic array indices with no guard clause
**File:line:** 1783-1796
**Pattern violated:** SBPP-STA-18 (Explaining Temporary Variable), SBPP-FMT-05 (Guard Clause)
**Why it matters:** `parts[4]`, `parts[2]` on a `targetPath.split("/")` result, with no bounds check and no local variable naming what each index means. If `targetPath` doesn't have at least 5 segments (e.g. a malformed manifest entry), this throws an unhelpful `ArrayIndexOutOfBoundsException` deep in a helper with no context about which entry or field was at fault.
**Fix:** Name the segments explicitly (`String collection = parts[2];`) and guard with a clear validation error, e.g. `if (parts.length < 5) throw new IllegalStateException("Unexpected content path: " + targetPath);`.

### 9. `refresh` loop body mixes reconciliation levels and inline error bookkeeping
**File:line:** 1328-1461
**Pattern violated:** SBPP-BEH-01 (Composed Method)
**Why it matters:** The `for (String path : paths)` loop (lines 1343-1450) interleaves lock acquisition, preflight stabilization, identity comparison, state derivation, and workflow update — each with its own try/catch that increments one of `updated`/`unchanged`/`uncertain` and appends to `errors`. The loop body is ~110 lines and reads as "how" rather than "what" (reconcile-one-publication). It's also the third near-duplicate occurrence of the "stable preflight + concurrent-update retry" shape seen in `markReviewed`.
**Fix:** Extract `reconcileOnePublication(vaultRoot, reviewRoot, jobsRoot, path, initial) -> ReconciliationOutcome` returning a small record with `(status, delta, diagnostics)`, and let the loop just accumulate outcomes.

### 10. `text()` helper name is too generic for its actual behavior
**File:line:** 1846-1852
**Pattern violated:** SBPP-BEH-18 (Intention Revealing Selector)
**Why it matters:** `text(Object value)` doesn't just cast — it also strips whitespace and converts empty-after-strip to `null`. Called throughout as `text(note.frontmatter().get("publicCollection"))`, the name gives no hint that blank strings become `null`, which matters for correctness at call sites like line 360 (`collection == null` check).
**Fix:** Rename to something like `nonBlankStringValue(Object value)` or `trimmedOrNull(Object value)`.
