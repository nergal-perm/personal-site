## Context

S03 left `PrepareHandler`/`CandidateWorkspace` able to install a real first-publication candidate triple
(`ru.md`, `en.md`, `references.json`), but `InspectPublicationHandler` still unconditionally reports
candidate, approved-snapshot, semantic-reference, and release state as `"absent"` — it has no dependency
capable of reading back what `prepare` just installed. Separately, `obsidian-plugin/main.js` already
contains fully-built, currently-orphaned consumer logic (`inspectAndOpenReview`, `validateReviewPlan`,
`launchReviewPlan`, `runZedTarget`) that reads a `reviewPlan` field off the inspect response and expects
exactly `{baselineState: "absent"|"complete", targets: [{language, proposedPath, publishedPath}]}` — added
2026-07-29, predating this rebuild. S04's job is closing that gap on the exporter side only.

All code in this slice is governed by the same standing conventions as S01-S03 (memory
`feedback-exporter-design-testing-conventions`): `/nullables` as the default testing technique,
`/applying-sbpp` for class/method structure, and `/oo-design-guide` for encapsulation/cohesion/coupling.

Two functional decisions were made during the collaborative-design pass on requirements (see
`specs/review-and-approval/spec.md` and `scope-pins.md`): RVA-01 needed a genuine new scenario for
"complete candidate, no approved baseline" (the exact S04 case had no prior baseline text), while RVA-02,
BRG-04, and BRG-07 are pure scope pins — their existing scenario text already says exactly what this
slice does.

## Goals / Non-Goals

**Goals**
- `inspect-publication`, once a candidate exists for the inspected publication, reports `candidateState:
  "ready"` (not hard-coded `"absent"`) and a top-level `status: "ready_for_review"` (RVA-01's new
  scenario, BRG-04).
- The same response carries a `reviewPlan` with `baselineState: "absent"` and two ordered targets (`ru`
  then `en`) identifying the candidate's RU and EN paths as absolute paths (RVA-02, BRG-07) — matching
  the plugin's already-hardcoded `validateReviewPlan` exactly, so no plugin runtime code changes.
- The read path reuses the existing `CandidateWorkspace`/`FilesystemCandidateWorkspace` boundary — at
  most one new production adapter this slice, per the plan's slice discipline — proven first via an
  in-memory fake, then against the real filesystem adapter under the same contract.
- `bridge-contract/schema-v2.json` declares the `reviewPlan`/`reviewTarget` shape so both the Java and JS
  conformance tests validate it, not just tolerate it via `additionalProperties: true`.

**Non-Goals** (deferred to later slices, per `scope-pins.md`)
- `baselineState: "complete"` and the approved-versus-candidate Russian diff (RVA-02's "Existing
  publication changed" scenario) — no approved snapshot can exist until S05.
- Approval itself, candidate replacement, and any editor-launch implementation detail — the launch
  mechanics (`launchReviewPlan`, `runZedTarget`, Zed CLI preflight) are already built and tested in
  `obsidian-plugin`, untouched by this change.
- `semanticReferenceState` remains `"absent"` — SEM-03's empty-map realization (S03) is not surfaced
  through inspection in this slice; no requirement in scope asks for that yet.
- The six-state workflow vocabulary itself (BRG-05/BRG-06) is S11's; this slice only reuses the one
  status literal (`"ready_for_review"`) `prepare` already established, for consistency, not as a
  formalization of the full vocabulary.

## Decisions

### D1 — Extend `CandidateWorkspace` with a read method; no new port

```java
public interface CandidateWorkspace {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);
    Optional<CandidatePaths> find(PublicationIdentity identity);
}
```

**Alternatives considered:** a separate read-only port (e.g. `CandidateLookup`) kept apart from the
existing write-only `CandidateWorkspace`, on CQS grounds (a port with only a command method gaining a
query method).

**Why not separate:** the plan's slice discipline caps this slice at one new production adapter, and the
proposal already committed to reusing the existing boundary rather than adding a parallel one. Both
`NullCandidateWorkspace` and `FilesystemCandidateWorkspace` already own exactly the state (`installed`
list; `canonicalReviewRoot`) a read method needs — a second port would either duplicate that state or
require awkward cross-port wiring for no behavioural benefit. `find()` is still a pure query (no
mutation, no side effect), so CQS is respected at the method level even though the interface now carries
both a command and a query.

### D2 — `CandidatePaths`: a paths-only Whole Value, not the existing `InstalledCandidate` shape

```java
public final class CandidatePaths {
    private CandidatePaths(Path ruPath, Path enPath) { ... }
    public static CandidatePaths of(Path ruPath, Path enPath) { ... }
    public Path ruPath() { ... }
    public Path enPath() { ... }
}
```

**Alternatives considered:** reusing `NullCandidateWorkspace.InstalledCandidate` (already holds
`identity`/`ruBody`/`enBody`/`referenceMap`) as the `find()` return type.

**Why not:** `InstalledCandidate` carries in-memory *bodies* (test-observation surface for the fake), not
*paths* — the real adapter has no bodies to return (it never keeps installed content in memory after
writing it), only the filesystem locations a review plan needs. Forcing one shape to serve both purposes
would make the real adapter either read file contents back just to satisfy the type (wasted I/O, no
requirement asks for it) or leave body fields null in a type whose fields are otherwise
`Objects.requireNonNull`-guarded. A dedicated paths-only value type stays honest about what both adapters
can actually produce.

### D3 — `ReviewPlan`/`ReviewTarget`: new bridge-response value types; `BridgeResponse` gains one nullable field

```java
public final class ReviewTarget {
    // language ("ru"|"en"), proposedPath (String, absolute), publishedPath (String, nullable)
}

public final class ReviewPlan {
    // baselineState ("absent" this slice), targets (exactly 2, ru then en)
    public static ReviewPlan firstPublication(CandidatePaths paths) {
        return new ReviewPlan("absent", List.of(
                ReviewTarget.of("ru", paths.ruPath().toString(), null),
                ReviewTarget.of("en", paths.enPath().toString(), null)));
    }
}
```

`BridgeResponse.essayInspected(...)` gains an 8th parameter, `ReviewPlan reviewPlan` (nullable); the
existing `@JsonInclude(JsonInclude.Include.NON_NULL)` at the class level already omits it from JSON when
null, so the "no candidate yet" response shape is byte-for-byte unchanged.

**Alternatives considered:** a single `firstPublication(...)` static factory only, with no general
`ReviewPlan` constructor exposed — versus exposing a general constructor now for the `"complete"` case
S08/S09 will need.

**Why factory-only:** S08/S09 do not exist yet and their exact diff-carrying shape is unknown; adding a
general constructor now is exactly the kind of "reusable abstraction guessed ahead of a second real case"
the plan's stopping rules warn against. `firstPublication(CandidatePaths)` is the only shape this slice's
acceptance test needs; a `forExistingPublication(...)` factory (or a constructor parameter change) is
S08/S09's decision to make once that slice's real diff data exists.

### D4 — Candidate completeness is existence of `ru.md` and `en.md`; no separate partial-candidate detection

`FilesystemCandidateWorkspace.find(identity)` reuses the already-private `candidateDirectory(identity)`
helper `install()` computes, and returns `Optional.of(CandidatePaths.of(dir.resolve("ru.md"),
dir.resolve("en.md")))` only if both files exist, else `Optional.empty()`.

**Why this is sufficient:** `install()` already writes the full triple via stage-then-`ATOMIC_MOVE` (D3
in S03's design) — the directory either contains all three files together or does not exist at all. No
code path in this slice (or any slice before S09's replacement/recovery work) can produce a partial
candidate directory, so checking for partial completeness here would be defensive code against a case
the current write path cannot produce. Revisit if S09's replacement/recovery introduces an interruption
window that could leave a partial directory visible.

### D5 — Top-level `status` becomes `"ready_for_review"` when a candidate exists, matching `prepare`

```java
if (candidatePaths.isPresent()) {
    return BridgeResponse.essayInspected(COMMAND, "ready_for_review", identity,
            "ready", ABSENT, ABSENT, ABSENT, ReviewPlan.firstPublication(candidatePaths.get()));
}
return BridgeResponse.essayInspected(COMMAND, "not_prepared", identity,
        ABSENT, ABSENT, ABSENT, ABSENT, null);
```

**Alternatives considered:** leaving `status` as `"not_prepared"` even once a candidate is ready, since
BRG-05's six-state vocabulary (which enumerates `ready_for_review` formally) is not introduced until S11.

**Why match `prepare`:** `prepare` already returns `status: "ready_for_review"` for the identical
underlying condition (a complete, unapproved candidate) since S03. Reporting a different top-level status
for the same condition depending on which command observed it would contradict the spirit of BRG-06
("both commands return the same state ... for the same observation window") years before that
requirement is formally exercised — and would require a silent status-string migration at S11 instead of
consistency from the moment both commands can observe the same condition. `"not_prepared"` continues to
cover exactly the case it always has: no candidate exists yet.

### D6 — Per-adapter tests for `find()`, no shared contract-test base class

`find()` gets tests added directly to the existing `NullCandidateWorkspaceTest` and
`FilesystemCandidateWorkspaceTest` files, matching how `install()` is already covered in each file
independently — no new `CandidateWorkspaceContractTest` abstraction.

**Alternatives considered:** a shared abstract contract-test base class parameterized over both adapters.

**Why not:** the codebase has never introduced this pattern, even for `install()` across two slices (S03
introduced both adapters without one). A shared base class is exactly the kind of foundation-only,
not-yet-evidenced abstraction the plan's stopping rules ask to justify with concrete evidence (a third
adapter, or observed drift between the two adapters' test coverage) before introducing — neither exists
yet. `find()`'s actual contract (present after install, absent before, paths are absolute, ru before en)
is small enough that duplicating a handful of assertions across two files costs less than a new
abstraction with only two current implementors.

### D7 — `bridge-contract/schema-v2.json` declares `reviewPlan`/`reviewTarget` now, including `"complete"`

```json
"reviewPlan": {
  "type": "object",
  "required": ["baselineState", "targets"],
  "properties": {
    "baselineState": { "type": "string", "enum": ["absent", "complete"] },
    "targets": { "type": "array", "minItems": 2, "maxItems": 2,
                 "items": { "$ref": "#/definitions/reviewTarget" } }
  }
},
```
```json
"reviewTarget": {
  "type": "object",
  "required": ["language", "proposedPath", "publishedPath"],
  "properties": {
    "language": { "type": "string", "enum": ["ru", "en"] },
    "proposedPath": { "type": "string" },
    "publishedPath": { "type": ["string", "null"] }
  }
}
```

**Why declare `"complete"` before it's producible:** matches the existing precedent set by `command`'s
enum, which already lists all four bridge commands (`mark-reviewed`, `refresh-publication-queue`
included) even though only two are implemented so far — `schema-v2.json` declares the stable target
contract surface derived from the requirements baseline up front, and BRG-03 requires it stay
single-sourced for both sides rather than growing piecemeal per slice. `publishedPath` is required
(nullable) rather than optional so the plugin's `target.publishedPath !== null` / `typeof ... === "string"`
checks in `validateReviewPlan` always have a field to inspect.

## Risks / Trade-offs

- **[Risk]** `CandidateWorkspace` now mixes a command (`install`) and a query (`find`) on one interface,
  which a strict CQS reading would flag. **Mitigation:** accepted per D1's reasoning — the alternative
  (a second port) costs a real adapter this slice's discipline caps at one; `find()` itself performs no
  mutation, so the violation is at the interface-cohesion level, not the method-behaviour level. Revisit
  if a third capability (e.g. deleting a candidate) makes the interface's responsibility genuinely
  unclear.
- **[Risk]** `InspectPublicationHandler`'s constructor changes (gains a required `CandidateWorkspace`
  parameter), and its previous no-arg construction sites (CLI, tests) all need updating in the same
  commit. **Mitigation:** small, mechanical, and caught immediately by the compiler — no behavioural risk,
  purely a wiring change tracked in `tasks.md`.
- **[Risk]** Declaring `"complete"`/non-null `publishedPath` in the schema now, before S05-S09 can produce
  them, means the schema temporarily documents a shape no exporter response yet emits.
  **Mitigation:** accepted per D7's precedent (the `command` enum already does the same for
  `mark-reviewed`/`refresh-publication-queue`); the JS/Java conformance tests for this slice only assert
  the `"absent"` branch, and `"complete"` gets its own test coverage when S08/S09 make it reachable.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit(s); S01-S03's
existing behaviour, CLI option surface, and every existing test remain unchanged and green throughout.
The `--review` option on `inspect-publication` was already declared (previously inert); this change is
what makes it load-bearing.

## Open Questions

None outstanding. D1-D7 close every fork raised during the technical collaborative-design pass.
