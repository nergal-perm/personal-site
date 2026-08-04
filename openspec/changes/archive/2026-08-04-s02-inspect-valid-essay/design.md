## Context

S01 restored a plugin-accepted schema-v2 boundary, but only for the blocked path:
`InspectPublicationHandler.inspect()` throws `UnsupportedOperationException` the moment vault/path
safety (`VaultRelativePath.isWithinVault()`, `VaultReader.exists()`) passes. S02 replaces that stub
with real behaviour for exactly one publication kind, `blog/essay`: a valid essay reports its
publication identity plus four independent, currently-always-absent state dimensions; a note with
malformed identity or a missing source ID still reports `metadata_blocked`, reusing S01's existing
blocked-response shape.

All code in this slice is governed by the same standing conventions as S01 (memory
`feedback-exporter-design-testing-conventions`): `/nullables` as the default testing technique,
`/applying-sbpp` for class/method structure, and `/oo-design-guide` for encapsulation/cohesion/coupling.

## Goals / Non-Goals

**Goals**
- A valid `blog/essay` note (`publish: true`, lowercase-slug `publicId`, `publicCollection: "blog"`,
  `publicContentType: "essay"`, a present, non-empty `sourceId`) produces `ok: true` with a resolved
  publication identity and `candidateState`/`approvedSnapshotState`/`semanticReferenceState`/
  `releaseState` each reported independently — all `"absent"` in this slice, since no earlier slice
  produces a candidate, approval, reference map, or release (RVA-01, BRG-04 absent-state scenarios).
- A note failing identity or source-ID admission (ADM-03, ADM-04-essay, SEM-01-current-source) returns
  `ok: false`, `status: "metadata_blocked"`, with field-specific diagnostics — the S01 blocked shape,
  extended with new diagnostic causes, not replaced.
- Both the Java-side and JS-side schema-v2 conformance tests validate the new valid-essay response
  shape against the real `bridge-contract/schema-v2.json`, per BRG-03.

**Non-Goals** (deferred to later slices, per this change's specs/ scope pins)
- Every publication kind other than `blog/essay` (S17a-f) — no generic kind-dispatch framework yet.
- Whole-vault discovery and duplicate-identity detection across notes (S16) — `ADM-03`'s and
  `SEM-01`'s "unique"/"duplicate" clauses are vacuous in this slice: there is exactly one note in the
  acceptance boundary, nothing else to compare against.
- Links, transclusions, assets (S12-S14); candidate preparation and translation (S03); review-plan
  generation (S04); approval (S05).
- Formalizing BRG-05's six-state workflow vocabulary (S11) — see D3 below.

## Decisions

### D1 — `VaultReader.readSource(path): String`, plus a separate pure `Frontmatter` parser

```java
public interface VaultReader {
    boolean exists(VaultRelativePath notePath);
    String readSource(VaultRelativePath notePath);
}
```

`FilesystemVaultReader` gains a `readSource` that reads the file's bytes as UTF-8 text through the
same `toRealPath()`-canonicalized, symlink-safe resolution S01's final fix wave already built for
`exists()` — no new path-safety surface. `NullVaultReader` is seeded with `(path, sourceText)` pairs
instead of bare paths.

A new, I/O-free `Frontmatter` value type parses that raw text:

```java
public final class Frontmatter {
    public static Frontmatter parse(String noteSource) { ... }
    public Optional<String> string(String key) { ... }
    public boolean flag(String key) { ... }
}
```

**Alternatives considered:** `VaultReader.readFrontmatter(path): Map<String,Object>`, with YAML
parsing inside the I/O adapter itself.

**Why the split:** keeps `VaultReader` scoped to exactly what an I/O port should own — fetching bytes
— while frontmatter parsing (genuinely combinatorial: quoting, booleans-as-strings, missing delimiter,
empty document) stays pure and unit-testable without any filesystem fixture, per the implementation
plan's step 6 ("add a unit test only for genuinely combinatorial parsing ... unclear at acceptance-test
scope"). `NullVaultReader`'s fakes stay faithful to what the real adapter actually returns (text), not
to a pre-parsed shape only the real adapter would normally produce.

### D2 — No kind-dispatch framework; essay's rules are direct and inline

`blog/essay` carries no requirement beyond the shared identity fields — confirmed against
`exporter-java`'s `PublicationKind.BLOG_ESSAY` entry, which adds no extra `PublicationRequirement`
beyond `selectionRequirements()` (compatibility-oracle evidence, not a code donor). A new
`EssayAdmission` collaborator evaluates the fixed essay rule set directly:

```java
public final class EssayAdmission {
    public static Result evaluate(Frontmatter frontmatter) { ... }
    // Result is either a PublicationIdentity + sourceId, or a list of blocking Diagnostics.
}
```

**Alternatives considered:** a generic `PublicationKind` enum/table (mirroring the legacy shape) that
maps `(collection, contentType)` to a requirement list, ready for `claim`/`book`/`album`/etc.

**Why not yet:** the plan explicitly warns against inventing a generic schema framework before a
second kind exhibits the same behaviour (S17's own framing: "prevents a generic schema framework from
being invented before repeated behaviour exists"; a stop-and-split trigger is "a requirement is added
only to make a guessed abstraction reusable"). One kind is not evidence of a pattern. `EssayAdmission`
is deliberately not designed for extension — S17a (`blog/note`, the next kind with the same trivial
shape as essay) is the earliest point a real generalization has two data points to generalize from.

### D3 — `BridgeResponse` gains five new nullable fields directly; no generic per-command payload bag

```java
public static BridgeResponse essayInspected(
        String command, String status, PublicationIdentity identity,
        String candidateState, String approvedSnapshotState,
        String semanticReferenceState, String releaseState) { ... }
```

`candidateState`/`approvedSnapshotState`/`semanticReferenceState`/`releaseState` stay plain `String`,
not an enum — S02 only ever produces `"absent"` for all four; later slices add more producible values
as their owning commands are built (S03 candidate states, S05 approval states, S09/S10 replacement
states). An enum with one instance member today would be speculative.

The status string for "admitted, nothing prepared yet" is a new literal, `"not_prepared"` — none of
BRG-05's six states (`metadata_blocked`, `translating`, `ready_for_review`, `ready_to_publish`,
`translation_failed`, `stale`) mean this, and formalizing that vocabulary as exhaustive is explicitly
S11's job (BRG-05). `status` is a plain string in schema v2, not a closed enum, so this costs nothing
at the contract level; S11 must explicitly decide whether `"not_prepared"` joins the formal vocabulary,
is renamed, or is subsumed — flagged in Risks below, not silently resolved here.

**Alternatives considered:** a generic `Map<String,Object>` "extra fields" bag on `BridgeResponse`,
serialized flat via Jackson's `@JsonAnyGetter`, so every future command's fields share one mechanism
instead of `BridgeResponse` accumulating typed fields per command over S02-S23.

**Why not yet:** only one command (`inspect-publication`) needs extra fields so far; S03/S05/S11's
concrete field shapes don't exist yet to generalize from, and building the bag now would be exactly
the kind of guessed, unevidenced abstraction the plan's stop-and-split rule warns against. Revisit
once a second command's fields land (S03) and the shape of repetition (or its absence) is real
evidence instead of a guess — flagged in Risks below as a concrete revisit trigger, not an open-ended
maybe.

### D4 — `PublicationIdentity` as a hand-written Whole Value, not a `record`

```java
public final class PublicationIdentity {
    private PublicationIdentity(String publicCollection, String publicContentType, String publicId) { ... }
    public static PublicationIdentity of(String publicCollection, String publicContentType, String publicId) { ... }
    // equals/hashCode/toString hand-written; @JsonProperty-annotated bare accessors
}
```

Follows the S01 precedent exactly (`VaultRelativePath`, `Diagnostic`, `BridgeResponse`): a `record`
cannot have a non-public canonical constructor (JLS constraint), which breaks the project's
sole-construction-via-named-factory invariant. No new decision needed here — this is the already-
settled pattern, applied to the one new value type this slice introduces.

## Risks / Trade-offs

- **[Risk]** `"not_prepared"` is a status literal with no home in BRG-05's baseline vocabulary yet.
  **Mitigation:** documented here and in this change's `specs/workflow-bridge/spec.md` delta as
  provisional; S11 must explicitly reconcile it (adopt, rename, or fold into an existing state) when
  BRG-05 is implemented — tracked as a concrete open item for that slice, not left implicit.
- **[Risk]** `BridgeResponse` accumulating typed nullable fields per command (D3) could become an
  unwieldy single class by S11 if the pattern of "one command, a few new fields" repeats 3-4 more
  times without ever being generalized. **Mitigation:** explicit revisit trigger — reconsider a
  generic payload mechanism at S03, once `prepare`'s own field needs are real rather than guessed.
- **[Risk]** `EssayAdmission`'s hard-coded essay-only rule set (D2) will need real refactoring at S17a
  once `blog/note` shows the same trivial shape repeating. **Mitigation:** expected and accepted per
  the plan's own sequencing — S17a is explicitly where "repeated behaviour" evidence first exists;
  refactoring then happens inside that slice's red-green-refactor cycle, not preemptively here.
- **[Risk]** Frontmatter parsing (D1) is a new combinatorial surface (malformed YAML, missing
  delimiters, non-string values in string fields). **Mitigation:** `Frontmatter.parse` is pure and
  gets targeted unit tests per the plan's step-6 allowance, independent of the acceptance suite's
  1-second budget.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit; S01's blocked
path, CLI option surface, and every existing test remain unchanged and green throughout.

## Open Questions

None outstanding. D1-D4 close every fork raised during design; `"not_prepared"`'s eventual home in
BRG-05 is a flagged risk for S11, not an open question blocking S02.
