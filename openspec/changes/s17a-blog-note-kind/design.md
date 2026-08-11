## Context

Admission and contract publication are currently hardcoded to one kind:

- `EssayAdmission` (admission package) has `REQUIRED_COLLECTION="blog"`/`REQUIRED_CONTENT_TYPE="essay"` as class constants and a fixed `FIELD_RULES` list.
- `NoteIntake.admit()` (intake package) directly `new EssayAdmission()`s and returns `EssayAdmission.Result` wrapped in `NoteIntake.Result` — a kind-specific type threaded through every handler.
- `EssayPublicationContract.kind()` (contract package) is a static single-kind factory; `PublicationContractWriter.write()` wraps it in a hardcoded `List.of(EssayPublicationContract.kind())`.
- 8 call sites across 6 classes (`PrepareHandler` ×2, `InspectPublicationHandler`, `RefreshPublicationQueueHandler`, `PublicationManifestHandler`, `MarkReviewedHandler`, `PublicNoteIndex`) each `new NoteIntake()` inline — no constructor injection exists for it today, unlike `TranslationWorker`/`CandidateWorkspace`, which are already constructor-injected into `PrepareHandler`.
- `CandidateSnapshot`/`TranslationJob` already carry only `(body, title, description)` — the same triple `blog/note` needs, since `blog/note`'s only kind-specific fields (`observation`/`model`/`boundary`/`experiment` in `site/src/content.config.ts`) are all optional and not exporter-managed content in this slice.

`openspec/implementation-plan.md`'s S17a entry requires this slice to both prove `blog/note` end to end and perform "the first justified refactor": extracting `PublicationKind`/`PublicationKinds`/`AdmittedPublication`, with `NoteIntake` no longer constructing kind dependencies inline.

## Goals / Non-Goals

**Goals:**
- `blog/note` completes admit → prepare → approve → release through the same handlers already proven for `blog/essay`.
- Extract `PublicationKind` (role: owns `(collection, contentType)`, admission rules, `KindContract`) and `PublicationKinds` (deterministic lookup + sorted contract enumeration) so `EssayPublicationKind` and `NotePublicationKind` are peers, not a special case plus a bolt-on.
- `NoteIntake.admit()` returns a kind-neutral `AdmittedPublication`, constructed with an explicit `PublicationKinds` collaborator (no hidden static/singleton lookup).
- `write-publication-contract` enumerates `PublicationKinds` instead of a hardcoded single-kind list.

**Non-Goals:**
- No generalization of `CandidateSnapshot`/`TranslationJob`/diff/hash carriers beyond the existing `(body, title, description)` triple — `blog/note` doesn't need it, and the plan defers that generalization until a kind's fixture actually demonstrates heterogeneous public metadata.
- No structured-body validation machinery — `blog/note` has none.
- No new CLI command, no new production boundary adapter, no bridge schema-v2 change.
- No changes to `book`/`album`/`concept`/`curated_page` — out of scope until their own slices.

## Decisions

### D1 — `PublicationKind` is an interface implemented per kind, not a data-driven table

Each kind (`EssayPublicationKind`, `NotePublicationKind`) is a small final class implementing `PublicationKind`:

```java
public interface PublicationKind {
    String collection();
    String contentType();
    AdmissionResult admit(MarkdownNote frontmatter);
    KindContract contract();
}
```

**Alternative considered:** a single generic `PublicationKind` driven entirely by a declarative `List<FieldRule>` (already how `EssayAdmission`/`FieldRule` work today), with `blog/note` just supplying a different rule list. Rejected for now because it's exactly the "generic schema-framework" the plan explicitly excludes before a kind demonstrates it needs one — `essay` and `note` currently need *identical* rule shapes (identity + title + description, no body), so a shared declarative rule list can still live inside both kinds' `admit()` implementations without a framework; each kind class stays the seam, not a table row.

### D2 — `PublicationKinds` is a small immutable value object, constructed fresh per CLI invocation

```java
public final class PublicationKinds {
    public static PublicationKinds installed() {
        return new PublicationKinds(List.of(new EssayPublicationKind(), new NotePublicationKind()));
    }
    public Optional<PublicationKind> forIdentity(String collection, String contentType) { ... }
    public List<KindContract> sortedContracts() { ... }
}
```

Each Picocli `*Command` constructs one `PublicationKinds.installed()` per run and threads it to whichever handler(s) it uses (mirroring how commands already construct `TranslationWorker`/`CandidateWorkspace` per run). This is a one-shot CLI process — there is no cross-invocation runtime to share a singleton across, so "the same instance used by contract writing" means the same *value* (by construction, `installed()` is deterministic and side-effect-free), not a shared mutable object.

**Alternative considered:** a static-held singleton (`PublicationKinds.INSTANCE`) read directly inside `NoteIntake`'s no-arg constructor. Rejected: hidden static access from inside a collaborator is exactly the "static cling" elegant-objects and `oo-design-guide` warn against, and it would make `NoteIntake` untestable against a fixture-only kind set without reflection.

### D3 — `NoteIntake` takes `PublicationKinds` as a constructor argument; call sites stop `new`-ing it inline

`NoteIntake(PublicationKinds kinds)` replaces the no-arg constructor. All 8 call sites move from `new NoteIntake()` to a `NoteIntake` instance the enclosing `*Command` constructs once (with `PublicationKinds.installed()`) and passes into the handler's constructor — the same pattern `PrepareHandler` already uses for `TranslationWorker`/`CandidateWorkspace`. `PublicNoteIndex.from(vaultReader)` (called from `PrepareHandler`) also needs a `PublicationKinds`/`NoteIntake` passed in rather than constructing its own.

**Alternative considered:** keep `new NoteIntake()` working via an internal default (`this(PublicationKinds.installed())`), touching only `NoteIntake` itself and leaving the 8 call sites unchanged. Rejected: the plan states this rule explicitly for this slice ("Stop constructing intake or kind dependencies inside handlers"), and every one of the 6 handler classes already takes its other collaborators by constructor — leaving `NoteIntake` as the one inline-constructed exception is the inconsistency the plan is calling out, not a simplification worth keeping.

### D4 — `AdmittedPublication` replaces `NoteIntake.Result`'s admission-specific accessors with a `PublicationKind`-tagged carrier

`NoteIntake.Result` keeps its shape (`accepted()`/`diagnostics()`/`identity()`/`body()`/`title()`/`description()`/`sourceHash()`/`frontmatterString(key)`) — callers don't change — but internally it now wraps an `AdmittedPublication` (kind + identity + title + description) instead of `EssayAdmission.Result`. This keeps the diff at the 8 call sites to "construct `NoteIntake` with `kinds`" only; no caller-visible accessor changes.

### D5 — Contract migration: `EssayPublicationContract` folds into `EssayPublicationKind.contract()`

`EssayPublicationContract`'s static `kind()` body moves onto `EssayPublicationKind` as its `contract()` implementation (same `FieldContract` construction logic, same `KindContract.of("blog", "essay", ...)`). `NotePublicationKind.contract()` returns `KindContract.of("blog", "note", requiredFields, List.of())` with the note-specific field list (`publish`, `publicCollection=blog`, `publicContentType=note`, `publicId` slug pattern, non-blank `id`/`title`/`description`). `PublicationContractWriter.write()` becomes `kinds.sortedContracts()` via a `PublicationKinds` instance instead of `List.of(EssayPublicationContract.kind())`.

### D6 — `PublicationKind` also owns its public route prefix

`site/src/pages/{ru,en}/notes/[id].astro` already exists — the site side has a real `/notes/{publicId}/` route for `blog/note`, distinct from `/essays/{publicId}/`. `PublicNoteIndex.routeFor(PublicationIdentity)` (`prepare/PublicNoteIndex.java:53-55`) currently hardcodes `"/essays/" + identity.publicId() + "/"` for every admitted note regardless of kind — a latent bug that was dormant only because essay was the sole kind. Add `String routePrefix()` to `PublicationKind` (`"essays"` for `EssayPublicationKind`, `"notes"` for `NotePublicationKind`); `PublicNoteIndex.registerIfAdmitted` looks up the admitted note's kind via `PublicationKinds` and builds `"/" + kind.routePrefix() + "/" + identity.publicId() + "/"`. This is the plan's own "public route policy" ownership line for `PublicationKind`, not new scope — it is exercised by one new `LinkResolver`/`PrepareHandler` acceptance scenario (a referring note linking to a `blog/note` target) so the fix is evidence-justified, not speculative.

## Risks / Trade-offs

- **[Risk]** Threading `PublicationKinds`/`NoteIntake` through 6 handler constructors touches more call sites than a minimal single-file change → **Mitigation**: every touched constructor already takes 2–4 collaborators of the same shape; this is additive and mechanical, not a new pattern. The existing acceptance suite (all prior essay slices) must stay green as the safety net.
- **[Risk]** `AdmittedPublication`/`NoteIntake.Result` internal restructuring could silently change essay behaviour → **Mitigation**: `EssayPublicationKind`'s `admit()` body is a behaviour-preserving move of `EssayAdmission`'s existing logic (same diagnostics, same field order), verified by keeping `EssayAdmissionTest`'s assertions green under the new class (renamed, not rewritten).
- **[Risk]** Two kinds sharing near-identical field-rule shapes could tempt premature abstraction into a generic rule engine → **Mitigation**: explicitly deferred by D1; revisit only when `S17b`'s `claim` kind (which has genuinely different required fields — `statement`, `claimKinds`, etc.) provides real evidence either way.

## Migration Plan

1. Add `PublicationKind` interface, `AdmittedPublication`, `PublicationKinds` (composing only `EssayPublicationKind` initially, still wrapping today's `EssayAdmission` logic) — no behaviour change, essay suite stays green.
2. Move `EssayAdmission`'s logic into `EssayPublicationKind` (rename/behaviour-preserving); move `EssayPublicationContract.kind()` into `EssayPublicationKind.contract()`; delete `EssayAdmission`/`EssayPublicationContract`.
3. Change `NoteIntake` to take `PublicationKinds`, return results backed by `AdmittedPublication`; update its 8 call sites and their enclosing `*Command` classes to construct and thread `PublicationKinds.installed()`.
4. Change `PublicationContractWriter` to enumerate `PublicationKinds.installed().sortedContracts()`.
5. Add `NotePublicationKind`, register it in `PublicationKinds.installed()`, add the `blog/note` acceptance fixture and contract-conformance fixture row.

Steps 1–4 are one behaviour-preserving refactor commit (essay-only, suite green throughout); step 5 is the new-behaviour commit that actually admits `blog/note`. Rollback is a plain revert at either commit boundary — no persisted state or migration involved (pure in-process code, no adapter, no schema change).

## Open Questions

None — `blog/note`'s field shape, optional-only kind-specific fields, and lack of required body are fully determined by `site/src/content.config.ts`'s existing `blogNote` schema and by `EssayAdmission`'s precedent for the minimal identity+title+description contract.
