## Context

S15 realizes ADM-06 (`openspec/specs/publication-admission/spec.md`) inside `publication-exporter/`. The functional design pass (recorded via `note-20260811-c3a5aa84` and this change's `specs/publication-admission/spec.md`) already fixed: the response is a standalone `PublicationContract` document, not a `BridgeResponse`; the contract has `contractVersion` + one `kinds[]` entry per implemented kind (today: `blog/essay` only); each kind entry lists required frontmatter fields (type, allowed-value-or-pattern, non-blank) and structured-body requirements (empty for essay); and a shared fixture table drives both `EssayAdmission`'s existing tests and a new independent contract-conformance harness.

This slice is pure in-process behaviour: no vault, no filesystem, no worker. Per the plan's outside-in discipline ("pure in-process behaviour uses no port merely for architectural symmetry"), it introduces **zero** new ports/adapters — just a CLI command plus a handful of small application-layer value types reading `EssayAdmission`'s existing rules.

## Goals / Non-Goals

**Goals**
- One command, `write-publication-contract`, with no options — the contract depends on no note, vault, or review workspace, only on the exporter's own compiled admission rules.
- A single source of truth for essay's field rules that both `EssayAdmission.admit(...)` and the contract reader consume, so they cannot drift silently.
- A fixture table shared by `EssayAdmissionTest` and a new `PublicationContractConformanceTest`.

**Non-Goals**
- No kind-registry/plugin abstraction for future kinds (S17a–f). Only `blog/essay` exists; building a registry now is speculative reuse the plan explicitly warns against.
- No change to `EssayAdmission`'s external behaviour or its `Result` type.
- No whole-vault discovery, no new content kind, no schema-v2 change.

## Decision 1 — Extract `EssayAdmission`'s field rules into a small declarative list `admit()` already effectively follows

**Current shape** (`EssayAdmission.java`): `admit()` calls `requireValidPublicId`, `requireCollection`, `requireContentType`, `requireSourceId`, `requireNonBlank("title", ...)`, `requireNonBlank("description", ...)` in sequence — each a private method with its own literal (`"blog"`, `"essay"`, the slug `Pattern`) baked in.

**Change**: extract those literals into one package-visible, ordered constant:

```java
static final List<FieldRule> FIELD_RULES = List.of(
        FieldRule.mustEqual("publicCollection", REQUIRED_COLLECTION),
        FieldRule.mustEqual("publicContentType", REQUIRED_CONTENT_TYPE),
        FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
        FieldRule.nonBlank("id"),
        FieldRule.nonBlank("title"),
        FieldRule.nonBlank("description"));
```

`FieldRule` is a small immutable value type (own file, `admission` package):

```java
public final class FieldRule {
    public enum Kind { MUST_EQUAL, MUST_MATCH, NON_BLANK }

    private final String field;
    private final Kind kind;
    private final String allowedValueOrPatternDescription;

    // mustEqual/mustMatch/nonBlank static factories; field()/kind()/description() accessors
}
```

`admit()`'s existing `requireCollection`/`requireContentType`/`requireValidPublicId`/`requireSourceId`/`requireNonBlank` methods are **not** deleted or generalized into a loop — the special-case ordering (`publicContentType`'s validity depends on `publicCollection` already being valid) and the `publish`/existence gate stay exactly as they are today, hand-written, because that cross-field dependency and the early-return-on-unpublished gate do not fit a uniform "iterate rules" loop without contorting `FieldRule` to express conditional dependencies it doesn't need for six fields. `FIELD_RULES` exists **only** to be read by the contract; it is not itself the mechanism `admit()` uses to validate. This is a deliberate seam, not an oversight — `admit()`'s existing behaviour is verified unchanged by the existing `EssayAdmissionTest` suite (now fixture-table-driven, see Decision 3), and `FIELD_RULES`'s faithfulness to that behaviour is what `PublicationContractConformanceTest` exists to prove per-fixture, not by construction.

Why not go further and make `admit()` itself iterate `FIELD_RULES`? That would touch working, tested logic for six fields to serve an external contract's shape — the exact "generate internal OOP design from the external contract" the plan excludes. The one-line `publish` gate and the two-line collection/content-type cross-check are cheaper to read as they are than as a generalized rule-interpreter.

## Decision 2 — `PublicationContract` value types (new `contract` package)

```
dev.eugene.publicationexporter.contract/
  PublicationContract.java   (record: contractVersion, List<KindContract> kinds)
  KindContract.java          (record: collection, contentType, List<FieldContract> requiredFields, List<String> structuredBody)
  FieldContract.java         (record: name, FieldContract.Type type, List<String> allowedValues, String pattern, boolean nonBlank)
  EssayPublicationContract.java  (one static method: KindContract essayKind(), maps EssayAdmission.FIELD_RULES -> FieldContract list)
  PublicationContractWriter.java (one method: PublicationContract write(), composes PublicationContract(1, kindsSortedByCollectionThenContentType))
```

`FieldContract.Type` is `BOOLEAN` (for `publish`, expressed separately — see below) or `STRING`. `allowedValues` is populated for `MUST_EQUAL` rules (a singleton list — `["blog"]`, `["essay"]`), `pattern` for `MUST_MATCH` rules, `nonBlank=true` for `NON_BLANK` rules. The `publish` gate is not a `FieldRule` today (it is checked before any diagnostics are collected) — `EssayPublicationContract.essayKind()` adds it as one explicit leading `FieldContract("publish", BOOLEAN, allowedValues=["true"], pattern=null, nonBlank=false)` rather than forcing `FieldRule` to model booleans it doesn't otherwise need to.

`PublicationContractWriter.write()` sorts its (currently single-element) kind list by `(collection, contentType)` — a one-line `Comparator`, not a registry — satisfying the spec's determinism scenario now and requiring no change when S17 adds kinds.

`PublicationContract`, `KindContract`, and `FieldContract` follow this project's existing value-type convention (`Diagnostic`, `PublicationIdentity`, `CandidateAsset`, `BridgeResponse`) rather than Java records: `public final class`, a private all-args constructor, one or more named static factories, `@JsonProperty`-annotated accessor methods (no `get`-prefix), `@JsonInclude(NON_NULL)` on the class so `pattern`/`allowedValues` are omitted rather than serialized as `null`, plus `equals`/`hashCode`/`toString`. `EssayPublicationContract` and `PublicationContractWriter` are stateless `final` classes with one public method each — no factories needed since they hold no state. This keeps every serialized type in the codebase constructed and printed the same way instead of mixing records into an all-classes convention.

## Decision 3 — CLI command and fixture table

`WritePublicationContractCommand` (new file, `cli` package), registered in `Main`'s `subcommands`:

```java
@Command(name = "write-publication-contract")
public final class WritePublicationContractCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        PublicationContract contract = new PublicationContractWriter().write();
        System.out.println(new ObjectMapper().writeValueAsString(contract));
        return 0;
    }
}
```

No `--vault`/`--review`/`--note` options (unlike every other command) — the contract is a pure function of the compiled exporter edition. Exit code is always `0`: there is no failure mode for a static document (unlike `inspect-publication`/`prepare`, which return `1` on a blocked/failed outcome).

**Fixture table** — new test-support class `EssayAdmissionFixtures` (`src/test/java/.../admission/`). Fixtures carry a raw note-source string in this project's existing hand-written-frontmatter-block style (`EssayAdmissionTest`'s current tests already write these inline) rather than a `Map<String,Object>` — there is no YAML-writer in this codebase to turn a map back into frontmatter text, and `MarkdownNote.parse(String)` is already the one proven frontmatter reader both `EssayAdmission` and the new contract interpreter can share:

```java
public final class EssayAdmissionFixture {
    private final String name;
    private final String noteSource;
    private final boolean expectedAccepted;
    private final List<String> expectedBlockedFields;
    // private constructor + of(...) factory; name()/noteSource()/expectedAccepted()/expectedBlockedFields() accessors
}

public final class EssayAdmissionFixtures {
    public static List<EssayAdmissionFixture> all() { ... }
    // one accepted fixture (all six fields valid, publish=true)
    // one fixture per blocking condition: publish absent/false/string,
    // wrong publicCollection, wrong publicContentType, non-slug publicId,
    // blank/missing id, blank/missing title, blank/missing description
}
```

`EssayAdmissionTest` parses each fixture's `noteSource()` into a `MarkdownNote` and asserts `EssayAdmission.admit(...)` matches `expectedAccepted`/`expectedBlockedFields` (existing test, now `@ParameterizedTest` over `EssayAdmissionFixtures.all()` instead of one-off hand-built cases — a same-behaviour refactor, not a new test dimension).

`PublicationContractConformanceTest` (new) does **not** call `EssayAdmission.admit(...)` and compare to the contract by construction — it parses the same `noteSource()` into a `MarkdownNote` and independently interprets `PublicationContract`'s `FieldContract` rules against it via `MarkdownNote.string(name)`/`.flag(name)` (own small interpreter, no YAML re-parsing: `allowedValues != null` → membership check, `pattern != null` → regex match, `nonBlank` → non-blank check, `type == BOOLEAN` → `flag(name)` truthiness), then asserts that verdict equals **both** the fixture's `expectedAccepted` **and** `EssayAdmission.admit(...)`'s real verdict on the same fixture. Three independent readings of the same fixture (fixture label, contract interpretation, runtime validator) must agree — this is what makes "validator and published contract disagree" a real, catchable failure mode rather than a tautology.

## Risks / Trade-offs

- `FIELD_RULES` and `admit()`'s hand-written methods encode the same six rules twice (as data and as code). This is the accepted cost of Decision 1: the fixture-table-driven conformance test is the thing that keeps them honest, not structural sharing of the validation algorithm itself. If a seventh rule is ever added to `EssayAdmission` without a matching `FieldRule`, `PublicationContractConformanceTest`'s three-way check catches the gap immediately (contract's fixture-implied verdict diverges from `EssayAdmission`'s real verdict) rather than shipping a silently incomplete contract.
- No port/adapter is introduced. If a future kind's admission rules need I/O (unlikely — admission is frontmatter-only today), that is a decision for the slice that introduces it, not this one.

## Migration Plan

None — additive command, no existing behaviour changes, no data migration.
