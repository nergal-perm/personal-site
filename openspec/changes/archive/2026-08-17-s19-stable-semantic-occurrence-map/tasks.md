<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- At most one new production boundary adapter is budgeted for this slice, and this plan uses zero: every
  new type is pure in-process (no I/O of its own). No new port is introduced; VaultSourceIdentityIndex is a
  rename of S18's PrivateNoteIdentityIndex (same implementation, same VaultReader dependency), not a new
  adapter.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance, explicit sealed outcome types over exceptions for expected business
  outcomes (this codebase's existing idiom — see LinkResolutionOutcome, SourceFreshnessOutcome,
  AssetResolutionOutcome, DirectTargetIdentityOutcome — match it, don't invent a new shape), guard clauses
  over nested conditionals, Composed Method (small, single-purpose private methods) throughout,
  package-private visibility by default (public only where a different package needs the type — Occurrence
  must be public, it's serialized by Jackson from the `reference` package and consumed by PrepareHandler in
  the `prepare` package), and never a null return — every "maybe absent" result is Optional or a sealed
  outcome. No comments in production code beyond what non-obvious rationale demands — this file's own
  comments are plan scaffolding, not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, scope-pins.md, design.md — all in
  openspec/changes/2026-08-17-s19-stable-semantic-occurrence-map/. design.md's pipeline diagram and
  per-type sections map directly onto the classes this file creates; read it first if anything below is
  unclear on *why*, not just *what*. In particular, read design.md's "Pipeline insertion points" section
  before touching PrepareHandler.java — it explains the two invariants (installed bodies stay
  byte-identical in shape to today; TranslationWorker/TranslationJob/EnglishTranslation stay untouched)
  that every task below is written to preserve.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Whole prior acceptance suite (802+ tests as of this slice's baseline, 2026-08-17, `mvn -q test` exits 0)
  must stay green after every task's step that runs the full suite. If anything outside this task's own
  new/modified tests turns red, stop and investigate before continuing — do not proceed past an unexplained
  regression.
- Governed by Haft problem prob-20260817-035bb310. Do not archive the OpenSpec change or touch Haft
  artifacts from this task list — those steps are owned by the orchestrating session, not an implementer.
-->

# S19 — Stable semantic occurrence map: implementation plan

**Goal:** Preparing a linked publication emits RU, EN, and `references.json` with identical stable
occurrence IDs and order; a translation that reorders or invents occurrences is rejected before
installation.

**Architecture:** Widen `LinkResolver` to report an ordered `List<LinkOccurrence>` instead of discarding
per-link identity. Assign stable occurrence IDs by positional reuse against the previous candidate's
`references.json`. Thread occurrence identity through translation via a transient delimiter-wrapped body
(`OccurrenceLabelMarkers`) so both public- and private-target occurrences survive as comparable spans —
`TranslationWorker`/`TranslationJob`/`EnglishTranslation` are never touched. Validate RU-vs-EN occurrence
correspondence before `installCandidate`. Un-stub `ReferenceMap.occurrences()` and give `ReferenceMapCodec`
a real, validated round trip.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson (`ObjectMapper`), this project's existing nullable-object
test doubles (`VaultReader.createNull`, `TranslationWorker.createNull`, `NullCandidateWorkspace`,
`ApprovedSnapshotWorkspace.createNull`) — no mocking library.

**Spec:** openspec/changes/2026-08-17-s19-stable-semantic-occurrence-map/proposal.md,
openspec/changes/2026-08-17-s19-stable-semantic-occurrence-map/scope-pins.md,
openspec/changes/2026-08-17-s19-stable-semantic-occurrence-map/design.md

## Global Constraints

(see HTML comment block above — this repo's convention keeps machine-readable constraints there so they
travel with the file into archive/ unedited; both blocks say the same thing)

---

## 1. `LinkOccurrence` and `Occurrence` value types (RED → GREEN, no behavior change yet)

Introduce the two new record types design.md defines, with unit tests, before anything consumes them. This
task produces no observable behavior change — it exists so later tasks compile against real types instead
of sketching them inline.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkOccurrence.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/Occurrence.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/OccurrenceTest.java`

**Interfaces:**
- Produces: `record LinkOccurrence(String targetStem, String label, Optional<String> route, int spanStart, int spanEnd)`
  (package-private, `prepare` package — only `LinkResolver`/`PrepareHandler`/`OccurrenceAssignment`/
  `OccurrenceLabelMarkers` in that package need it).
- Produces: `public record Occurrence(String id, int order, String targetSourceId, String ruLabel, String enLabel)`
  (public, `reference` package — Jackson-serialized, consumed across packages).

- [x] 1.1 Write `OccurrenceTest.java`:

```java
package dev.eugene.publicationexporter.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OccurrenceTest {

    @Test
    void accessorsReturnConstructedValues() {
        Occurrence occurrence = new Occurrence("occ-1", 0, "src-a", "дед Шведов", "Grandpa Shvedov");

        assertEquals("occ-1", occurrence.id());
        assertEquals(0, occurrence.order());
        assertEquals("src-a", occurrence.targetSourceId());
        assertEquals("дед Шведов", occurrence.ruLabel());
        assertEquals("Grandpa Shvedov", occurrence.enLabel());
    }

    @Test
    void idIsRejectedWhenNull() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new Occurrence(null, 0, "src-a", "ru", "en"));
        assertEquals("id", exception.getMessage());
    }

    @Test
    void targetSourceIdIsRejectedWhenNull() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new Occurrence("occ-1", 0, null, "ru", "en"));
        assertEquals("targetSourceId", exception.getMessage());
    }
}
```

- [x] 1.2 Run `mvn -q -Dtest=OccurrenceTest test` from `publication-exporter/`. Expected: FAIL (class
      `Occurrence` does not exist).

- [x] 1.3 Write `Occurrence.java`. A `record`'s canonical constructor is where `Objects.requireNonNull`
      guard clauses go — Java records don't null-check by default, so this is not optional boilerplate:

```java
package dev.eugene.publicationexporter.reference;

import java.util.Objects;

public record Occurrence(String id, int order, String targetSourceId, String ruLabel, String enLabel) {

    public Occurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetSourceId, "targetSourceId");
        Objects.requireNonNull(ruLabel, "ruLabel");
        Objects.requireNonNull(enLabel, "enLabel");
    }
}
```

- [x] 1.4 Write `LinkOccurrence.java` (no test file — it is an internal carrier consumed and exercised
      entirely through `LinkResolverTest` and `PrepareHandlerTest` in later tasks, matching this codebase's
      existing convention of not unit-testing plain internal records separately from their producer/consumer):

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.Optional;

record LinkOccurrence(String targetStem, String label, Optional<String> route, int spanStart, int spanEnd) {

    LinkOccurrence {
        Objects.requireNonNull(targetStem, "targetStem");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(route, "route");
    }
}
```

- [x] 1.5 Run `mvn -q -Dtest=OccurrenceTest test` from `publication-exporter/`. Expected: PASS.

- [x] 1.6 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkOccurrence.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/Occurrence.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/OccurrenceTest.java
git commit -m "Add LinkOccurrence and Occurrence value types for S19"
```

---

## 2. Widen `LinkResolver` / `LinkResolutionOutcome` to report ordered occurrences

Replace the unordered, deduplicated `Set<String> privateTargetStems` with the ordered
`List<LinkOccurrence>` from Task 1. `resolvedBody`'s actual characters do not change in this task — only
what accompanies it. This is the one task that touches S13/S18 code directly; keep it isolated so a
regression is easy to bisect.

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java`
  (find its existing `resolve(...)` call sites first — `grep -n "LinkResolver.resolve\|\.resolve(" publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java`
  — and update each lambda's second parameter type from `Set<String>` to `List<LinkOccurrence>` without
  changing any assertion on the returned body string).
- Modify (compile-fix only, no behavior change): every call site of `LinkResolutionOutcome.resolve(...)`
  outside this package's own tests — `grep -rn "LinkResolutionOutcome\|LinkResolver.resolve" publication-exporter/src/main/java publication-exporter/src/test/java`
  to find them all before starting; expect `PrepareHandler.prepare()`, `PrepareHandler.sourceFreshness()`,
  and any `DirectTargetIdentityCheckTest`/`PrepareHandlerTest` call sites that construct a
  `LinkResolutionOutcome.resolved(...)` directly for fixture purposes.

**Interfaces:**
- Consumes: `LinkOccurrence` from Task 1.
- Produces: `LinkResolutionOutcome.resolved(String body, List<LinkOccurrence> occurrences)`;
  `<T> T resolve(BiFunction<String, List<LinkOccurrence>, T> onResolved, Function<String, T> onBlockedTransclusion)`.

- [x] 2.1 In `LinkResolverTest.java`, add a failing test proving occurrences are reported in source order,
      one per plain wikilink, for both a public and a private target in the same body:

```java
@Test
void resolvedOutcomeReportsOccurrencesInSourceOrderForPublicAndPrivateTargets() {
    PublicNoteIndex knownNotes = PublicNoteIndex.from(
            VaultReader.createNull(Map.of(
                    VaultRelativePath.of("blog/public-essay.md"),
                    "---\npublish: true\ncollection: blog\ncontentType: essay\nid: pub-1\ntitle: Public\ndescription: d\n---\nBody.")),
            new NoteIntake(PublicationKinds.installed()));
    String body = "See [[private-note]] and also [[public-essay]].";

    List<LinkOccurrence> occurrences = LinkResolver.resolve(body, knownNotes).resolve(
            (resolvedBody, seen) -> seen,
            target -> fail("expected resolved links, got blocked transclusion: " + target));

    assertEquals(2, occurrences.size());
    assertEquals("private-note", occurrences.get(0).targetStem());
    assertTrue(occurrences.get(0).route().isEmpty());
    assertEquals("public-essay", occurrences.get(1).targetStem());
    assertEquals(Optional.of("/blog/public-essay/"), occurrences.get(1).route());
}
```

      Read the existing file's imports and any existing `PublicNoteIndex.from(...)` fixture construction
      first — match its exact current pattern rather than inventing a new one; the snippet above shows the
      shape but copy the file's real helper methods/constants if it already has them (e.g. a
      `publicNoteIndexWith(...)` helper).

- [x] 2.2 Run `mvn -q -Dtest=LinkResolverTest test` from `publication-exporter/`. Expected: FAIL (compile
      error — `LinkOccurrence` unused / wrong type, or the new test doesn't compile against the still-`Set`
      signature).

- [x] 2.3 Widen `LinkResolutionOutcome.java`: change every `Set<String> privateTargetStems` to
      `List<LinkOccurrence> occurrences` (imports: replace `java.util.Set`/`LinkedHashSet` usage as needed
      with `java.util.List`; keep the sealed-interface shape and both variant classes' structure identical
      otherwise — this is a type substitution, not a redesign):

```java
public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body, List<LinkOccurrence> occurrences) {
        return new ResolvedLinks(body, occurrences);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;
    private final List<LinkOccurrence> occurrences;

    ResolvedLinks(String body, List<LinkOccurrence> occurrences) {
        this.body = Objects.requireNonNull(body, "body");
        this.occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
    }

    @Override
    public <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body, occurrences);
    }
}
```

      (`BlockedTransclusion` is unchanged — it never carried occurrence data.)

- [x] 2.4 Widen `LinkResolver.java`'s `resolve`/`appendLink` to build an ordered `List<LinkOccurrence>`
      instead of a `Set<String>`, recording each occurrence's span in `output` at the point its label text
      is written (needed by Task 6's `OccurrenceLabelMarkers`):

```java
public static LinkResolutionOutcome resolve(String body, PublicNoteIndex knownNotes) {
    StringBuilder output = new StringBuilder(body.length());
    List<LinkOccurrence> occurrences = new ArrayList<>();
    int cursor = 0;
    while (cursor < body.length()) {
        ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
        Matcher link = nextLink(body, cursor);
        if (protectedSpanBeforeLink(protectedSpan, link)) {
            cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
        } else if (link != null) {
            Optional<String> blockedTarget = appendLink(body, output, cursor, link, knownNotes, occurrences);
            if (blockedTarget.isPresent()) {
                return LinkResolutionOutcome.blockedTransclusion(blockedTarget.get());
            }
            cursor = link.end();
        } else {
            break;
        }
    }
    output.append(body, cursor, body.length());
    return LinkResolutionOutcome.resolved(output.toString(), occurrences);
}

private static Optional<String> appendLink(
        String body, StringBuilder output, int cursor, Matcher link, PublicNoteIndex knownNotes,
        List<LinkOccurrence> occurrences) {
    output.append(body, cursor, link.start());
    boolean isEmbed = !link.group(1).isEmpty();
    String target = link.group(2).strip();
    String label = labelFor(link, target);
    if (isEmbed && AssetTargets.isAssetTarget(target)) {
        output.append(link.group());
        return Optional.empty();
    }
    String stem = lastPathSegment(target);
    Optional<String> route = knownNotes.routeFor(target);
    if (route.isPresent()) {
        output.append('[');
        int spanStart = output.length();
        output.append(label);
        int spanEnd = output.length();
        output.append("](").append(route.get()).append(')');
        occurrences.add(new LinkOccurrence(stem, label, route, spanStart, spanEnd));
        return Optional.empty();
    }
    if (isEmbed) {
        return Optional.of(stem);
    }
    int spanStart = output.length();
    output.append(label);
    int spanEnd = output.length();
    occurrences.add(new LinkOccurrence(stem, label, Optional.empty(), spanStart, spanEnd));
    return Optional.empty();
}
```

      Delete the now-unused `Set`/`LinkedHashSet` imports; add `java.util.ArrayList`/`java.util.List`.
      `labelFor`, `lastPathSegment`, `nextLink`, `protectedSpanBeforeLink`, `copyProtectedSpan` are
      unchanged — do not touch them.

- [x] 2.5 Run `mvn -q -Dtest=LinkResolverTest test` from `publication-exporter/`. Expected: PASS, including
      every pre-existing `LinkResolverTest` case (they assert on `resolvedBody`'s characters, which this
      task does not change).

- [x] 2.6 Fix every remaining compile error from the widened signature (`PrepareHandler.prepare()`,
      `PrepareHandler.sourceFreshness()`, any `PrepareHandlerTest`/`DirectTargetIdentityCheckTest` fixture
      that constructs `LinkResolutionOutcome.resolved(...)` or destructures a `resolve(...)` call). For each
      call site, change the lambda's second parameter type from `Set<String>` to `List<LinkOccurrence>`. In
      `PrepareHandler.prepareAfterIdentityCheck(...)`, derive the `Set<String>` `DirectTargetIdentityCheck`
      still expects from the new list, preserving exact prior behavior:

```java
Set<String> privateTargetStems = occurrences.stream()
        .filter(occurrence -> occurrence.route().isEmpty())
        .map(LinkOccurrence::targetStem)
        .collect(Collectors.toCollection(LinkedHashSet::new));
```

      Rename the parameter `occurrences` is derived from consistently (`prepareAfterIdentityCheck`'s own
      parameter becomes `List<LinkOccurrence> occurrences` instead of `Set<String> privateTargetStems`).
      `sourceFreshness`'s lambda already ignores its second parameter (`ignoredPrivateTargetStems` today) —
      rename it `ignoredOccurrences` and leave the type change as the only edit there.

- [x] 2.7 Run `mvn -q test` from `publication-exporter/` (whole suite). Expected: PASS, 0 regressions. This
      is the checkpoint the global constraints block requires — do not proceed to Task 3 with any red test.

- [x] 2.8 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java
git commit -m "Widen LinkResolver to report ordered link occurrences instead of a stem set"
```

---

## 3. Rename `PrivateNoteIdentityIndex` → `VaultSourceIdentityIndex`; broaden when it is built

Pure rename plus one condition change in `PrepareHandler` — no new logic. Isolated so it is trivially
reviewable on its own diff.

**Files:**
- Modify (rename): `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndex.java`
  → `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/VaultSourceIdentityIndex.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityCheck.java`
  (parameter type only)
- Modify (rename references): `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndexTest.java`
  → `VaultSourceIdentityIndexTest.java`, and any other test referencing the old name
  (`grep -rln "PrivateNoteIdentityIndex" publication-exporter/src/test/java` first).

**Interfaces:**
- Produces: `static VaultSourceIdentityIndex from(VaultReader vaultReader)`,
  `Optional<TargetIdentity> identityFor(String filenameStem)` — identical signatures to today, new class
  name only. `record TargetIdentity(Optional<String> sourceId)` is unchanged.

- [x] 3.1 There is no new failing test for this task — it is a behavior-preserving rename, verified by the
      existing (renamed) test file continuing to pass unchanged. Rename the file and class:
      `git mv publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndex.java publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/VaultSourceIdentityIndex.java`,
      then change `final class PrivateNoteIdentityIndex` to `final class VaultSourceIdentityIndex` and its
      constructor name to match. `TargetIdentity` (the trailing record in the same file) is unchanged.

- [x] 3.2 `git mv` the test file the same way and replace every `PrivateNoteIdentityIndex` reference with
      `VaultSourceIdentityIndex` inside it.

- [x] 3.3 In `PrepareHandler.java` and `DirectTargetIdentityCheck.java`, replace every remaining
      `PrivateNoteIdentityIndex` reference with `VaultSourceIdentityIndex` (type only — variable names like
      `identityIndex` stay as-is, they were never type-derived).

- [x] 3.4 In `PrepareHandler.prepareAfterIdentityCheck(...)`, change the build condition from
      `if (privateTargetStems.isEmpty())` to `if (occurrences.isEmpty())`, and build
      `VaultSourceIdentityIndex` unconditionally whenever occurrences exist (today it is built only inside
      the non-empty-private-stems branch). Restructure so that: (a) the index is built once whenever
      `!occurrences.isEmpty()`; (b) `DirectTargetIdentityCheck.verify(...)` still runs only when the derived
      private-stem `Set<String>` (Task 2.6) is non-empty, exactly matching today's blocking behavior; (c)
      the built index and the full `occurrences` list both continue to flow forward to
      `prepareAfterAssetResolution(...)` and beyond — Task 5 needs them later in the pipeline to resolve
      every occurrence's `targetSourceId`, not only private ones'. Introduce a small carrier record to avoid
      a long, growing parameter list on every intermediate method (Composed Method / small value object over
      parameter-list sprawl):

```java
record OccurrenceContext(List<LinkOccurrence> occurrences, Optional<VaultSourceIdentityIndex> identityIndex) {

    static OccurrenceContext empty() {
        return new OccurrenceContext(List.of(), Optional.empty());
    }
}
```

      `identityIndex` is `Optional.empty()` only in the genuinely-no-occurrences case, matching this
      codebase's "never a null return" convention rather than a nullable field — Task 7's
      `assignOccurrences` unwraps it with `.orElseThrow()` only after its own early return on empty
      occurrences already guarantees it is present (see Task 7.6). Thread `OccurrenceContext` through
      `prepareAfterAssetResolution`, `prepareNormalizedEssay`, `prepareWithInstallLock`, and
      `prepareAdmittedEssay` as one additional parameter (replacing the now-unused bare `occurrences`
      parameter from Task 2's threading, if you introduced one there — collapse to this single carrier).

- [x] 3.5 Run `mvn -q test` from `publication-exporter/`. Expected: PASS, 0 regressions — this task changes
      *when* the index is built (now also for public-only-link notes) but not what `DirectTargetIdentityCheck`
      does with it, so no existing assertion should change.

- [x] 3.6 Commit:

```bash
git add -A publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/ \
           publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/
git commit -m "Rename PrivateNoteIdentityIndex to VaultSourceIdentityIndex and build it for public-only occurrences too"
```

---

## 4. `ReferenceMap.occurrences()` becomes real; `ReferenceMapCodec` round-trips and validates

Un-stub `ReferenceMap.occurrences()` and give `ReferenceMapCodec` real read/write plus the validation
scenarios SEM-03 names (duplicate keys, wrong order, unknown/unused references). This task is independent
of the `prepare` pipeline wiring (Tasks 5–6) — `ReferenceMap`/`ReferenceMapCodec` gain a real capability
here that Task 7 then plugs into `PrepareHandler`.

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`

**Interfaces:**
- Consumes: `Occurrence` from Task 1.
- Produces: `ReferenceMap.of(identity, ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, List<Occurrence> occurrences)`;
  `ReferenceMap.empty(...)` (both existing overloads) now delegate to `of(...)` with `List.of()`;
  `ReferenceMapCodec.read(json)` throws `ReferenceMapCodecException` (new, extends `RuntimeException`) on
  duplicate occurrence IDs, out-of-order `order` values, or malformed occurrence fields.

- [x] 4.1 Replace `ReferenceMapTest.occurrencesIsAlwaysEmpty` (it locks in the old stub — this is a
      deliberate, expected test change, not a regression) with tests proving `occurrences()` returns exactly
      what was constructed, and that the empty-map factories still produce an empty list:

```java
@Test
void occurrencesIsEmptyForTheEmptyFactory() {
    ReferenceMap map = referenceMap();

    assertTrue(map.occurrences().isEmpty());
}

@Test
void occurrencesReturnsConstructedListInOrder() {
    Occurrence first = new Occurrence("occ-1", 0, "src-a", "ru-a", "en-a");
    Occurrence second = new Occurrence("occ-2", 1, "src-b", "ru-b", "en-b");
    ReferenceMap map = ReferenceMap.of(
            IDENTITY, "ru-hash", "en-hash",
            "ru-fields-hash", "en-fields-hash", "structured-data-hash",
            List.of(first, second));

    assertEquals(List.of(first, second), map.occurrences());
}
```

- [x] 4.2 Run `mvn -q -Dtest=ReferenceMapTest test` from `publication-exporter/`. Expected: FAIL
      (`occurrencesIsAlwaysEmpty` no longer exists as a method to fail against — the new
      `occurrencesReturnsConstructedListInOrder` fails to compile: no `ReferenceMap.of(...)` overload
      taking a `List<Occurrence>` exists yet).

- [x] 4.3 Widen `ReferenceMap.java`: add an `occurrences` field, the `of(...)` factory, and make both
      `empty(...)` overloads delegate to it with `List.of()`:

```java
private final List<Occurrence> occurrences;

private ReferenceMap(
        PublicationIdentity identity, String ruHash, String enHash,
        String ruFieldsHash, String enFieldsHash, String structuredDataHash,
        List<Occurrence> occurrences) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
    this.enHash = Objects.requireNonNull(enHash, "enHash");
    this.ruFieldsHash = Objects.requireNonNull(ruFieldsHash, "ruFieldsHash");
    this.enFieldsHash = Objects.requireNonNull(enFieldsHash, "enFieldsHash");
    this.structuredDataHash = Objects.requireNonNull(structuredDataHash, "structuredDataHash");
    this.occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
}

public static ReferenceMap of(
        PublicationIdentity identity, String ruHash, String enHash,
        String ruFieldsHash, String enFieldsHash, String structuredDataHash,
        List<Occurrence> occurrences) {
    return new ReferenceMap(identity, ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences);
}

public static ReferenceMap empty(
        PublicationIdentity identity, String ruHash, String enHash,
        String ruFieldsHash, String enFieldsHash, String structuredDataHash) {
    return of(identity, ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, List.of());
}
```

      (The `@Deprecated` six-hash-argument `empty(...)` overload keeps delegating to the four-hash `empty(...)`
      exactly as today — no change needed there beyond it now transitively landing on `of(...)`.) Change
      `occurrences()` to return the real field:

```java
@JsonProperty("occurrences")
public List<Occurrence> occurrences() {
    return occurrences;
}
```

      Add `occurrences` to `equals`/`hashCode`/`toString` alongside the existing hash fields, matching this
      class's existing full-value-equality style.

- [x] 4.4 Run `mvn -q -Dtest=ReferenceMapTest test` from `publication-exporter/`. Expected: PASS.

- [x] 4.5 In `ReferenceMapCodecTest.java`, rename `writeProducesTheDeclaredSchemaVersionIdentityHashesAndEmptyOccurrences`
      to keep asserting the empty case (no behavior change there — still pass `List.of()`), and add new
      failing tests for the non-empty case and the validation scenarios SEM-03 names:

```java
@Test
void writeProducesOccurrencesInOrder() throws Exception {
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    Occurrence occurrence = new Occurrence("occ-1", 0, "src-a", "ru-label", "en-label");
    ReferenceMap map = ReferenceMap.of(
            identity, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash",
            List.of(occurrence));

    String json = ReferenceMapCodec.write(map);
    JsonNode occurrences = new ObjectMapper().readTree(json).get("occurrences");

    assertEquals(1, occurrences.size());
    assertEquals("occ-1", occurrences.get(0).get("id").asText());
    assertEquals(0, occurrences.get(0).get("order").asInt());
    assertEquals("src-a", occurrences.get(0).get("targetSourceId").asText());
    assertEquals("ru-label", occurrences.get(0).get("ruLabel").asText());
    assertEquals("en-label", occurrences.get(0).get("enLabel").asText());
}

@Test
void writeThenReadRoundTripsOccurrences() {
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    Occurrence occurrence = new Occurrence("occ-1", 0, "src-a", "ru-label", "en-label");
    ReferenceMap original = ReferenceMap.of(
            identity, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash",
            List.of(occurrence));

    ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

    assertEquals(original, roundTripped);
}

@Test
void readRejectsDuplicateOccurrenceIds() {
    String json = referenceMapJsonWithOccurrences(
            "{\"id\":\"occ-1\",\"order\":0,\"targetSourceId\":\"src-a\",\"ruLabel\":\"ru\",\"enLabel\":\"en\"},"
            + "{\"id\":\"occ-1\",\"order\":1,\"targetSourceId\":\"src-b\",\"ruLabel\":\"ru2\",\"enLabel\":\"en2\"}");

    assertThrows(ReferenceMapCodecException.class, () -> ReferenceMapCodec.read(json));
}

@Test
void readRejectsOrderNotMatchingArrayPosition() {
    String json = referenceMapJsonWithOccurrences(
            "{\"id\":\"occ-1\",\"order\":1,\"targetSourceId\":\"src-a\",\"ruLabel\":\"ru\",\"enLabel\":\"en\"}");

    assertThrows(ReferenceMapCodecException.class, () -> ReferenceMapCodec.read(json));
}

private static String referenceMapJsonWithOccurrences(String occurrencesJson) {
    return "{\"schemaVersion\":1,"
            + "\"publicationIdentity\":{\"publicCollection\":\"blog\",\"publicContentType\":\"essay\",\"publicId\":\"my-essay\"},"
            + "\"ruHash\":\"ru-hash\",\"enHash\":\"en-hash\","
            + "\"ruFieldsHash\":\"ru-fields-hash\",\"enFieldsHash\":\"en-fields-hash\","
            + "\"structuredDataHash\":\"structured-data-hash\","
            + "\"occurrences\":[" + occurrencesJson + "]}";
}
```

      (Add `import static org.junit.jupiter.api.Assertions.assertThrows;` if not already present.)

- [x] 4.6 Run `mvn -q -Dtest=ReferenceMapCodecTest test` from `publication-exporter/`. Expected: FAIL
      (`ReferenceMapCodecException` does not exist; `occurrences` array is still always empty on write).

- [x] 4.7 Create `ReferenceMapCodecException.java`:

```java
package dev.eugene.publicationexporter.reference;

public final class ReferenceMapCodecException extends RuntimeException {

    public ReferenceMapCodecException(String message) {
        super(message);
    }
}
```

- [x] 4.8 Widen `ReferenceMapCodec.java` to write real occurrences and validate them on read:

```java
public static ReferenceMap read(String json) {
    try {
        return referenceMapFrom(MAPPER.readTree(json));
    } catch (JsonProcessingException error) {
        throw new UncheckedIOException(new IOException(error));
    }
}

private static ReferenceMap referenceMapFrom(JsonNode root) {
    PublicationIdentity identity = identityFrom(root.get("publicationIdentity"));
    return ReferenceMap.of(
            identity,
            root.get("ruHash").asText(),
            root.get("enHash").asText(),
            root.get("ruFieldsHash").asText(),
            root.get("enFieldsHash").asText(),
            root.get("structuredDataHash").asText(),
            occurrencesFrom(root.get("occurrences")));
}

private static List<Occurrence> occurrencesFrom(JsonNode occurrencesNode) {
    List<Occurrence> occurrences = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    int expectedOrder = 0;
    for (JsonNode node : occurrencesNode) {
        String id = node.get("id").asText();
        if (!seenIds.add(id)) {
            throw new ReferenceMapCodecException("Duplicate occurrence id \"" + id + "\".");
        }
        int order = node.get("order").asInt();
        if (order != expectedOrder) {
            throw new ReferenceMapCodecException(
                    "Occurrence \"" + id + "\" has order " + order + " but position " + expectedOrder + ".");
        }
        occurrences.add(new Occurrence(
                id, order,
                node.get("targetSourceId").asText(),
                node.get("ruLabel").asText(),
                node.get("enLabel").asText()));
        expectedOrder++;
    }
    return occurrences;
}
```

      Add imports: `java.util.ArrayList`, `java.util.HashSet`, `java.util.List`, `java.util.Set`. `write(...)`
      needs no change — Jackson already serializes `occurrences()`'s real `List<Occurrence>` once `Occurrence`
      is a normal record with public accessors (Task 1); its four fields serialize as their record component
      names (`id`, `order`, `targetSourceId`, `ruLabel`, `enLabel`) without extra `@JsonProperty` annotations,
      matching Jackson's default record handling already relied on elsewhere in this codebase (verify by
      running 4.9 below rather than assuming — if field names don't match, add `@JsonProperty` per component
      the same way `ReferenceMap`'s own accessors do).

- [x] 4.9 Run `mvn -q -Dtest=ReferenceMapCodecTest test` from `publication-exporter/`. Expected: PASS.

- [x] 4.10 Run `mvn -q test` from `publication-exporter/` (whole suite). Expected: PASS, 0 regressions.

- [x] 4.11 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/
git commit -m "Un-stub ReferenceMap.occurrences() and validate references.json occurrence entries on read"
```

---

## 5. `OccurrenceAssignment` — positional reuse of prior occurrence IDs

Pure, stateless function implementing scope-pins.md's "same target source ID at the same index" rule.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/OccurrenceAssignment.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/OccurrenceAssignmentTest.java`

**Interfaces:**
- Consumes: `LinkOccurrence` (Task 1), `Occurrence` (Task 1).
- Produces: `static List<AssignedOccurrence> assign(List<LinkOccurrence> ruOccurrences, Map<String, String> targetSourceIdsByStem, List<Occurrence> previousOccurrences)`;
  `record AssignedOccurrence(String id, int order, String targetSourceId, String ruLabel)`.

- [x] 5.1 Write `OccurrenceAssignmentTest.java`:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.Occurrence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OccurrenceAssignmentTest {

    @Test
    void reusesPriorIdWhenTargetSourceIdMatchesAtTheSameIndex() {
        LinkOccurrence current = new LinkOccurrence("grandpa-shvedov", "дед Шведов", Optional.empty(), 0, 10);
        Occurrence previous = new Occurrence("occ-existing", 0, "src-grandpa", "дед Шведов", "Grandpa Shvedov");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("grandpa-shvedov", "src-grandpa"), List.of(previous));

        assertEquals(1, assigned.size());
        assertEquals("occ-existing", assigned.get(0).id());
        assertEquals(0, assigned.get(0).order());
        assertEquals("src-grandpa", assigned.get(0).targetSourceId());
    }

    @Test
    void assignsAFreshIdWhenNoPreviousOccurrenceExists() {
        LinkOccurrence current = new LinkOccurrence("new-target", "New Target", Optional.empty(), 0, 10);

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("new-target", "src-new"), List.of());

        assertEquals(1, assigned.size());
        assertEquals("src-new", assigned.get(0).targetSourceId());
    }

    @Test
    void assignsAFreshIdWhenTargetSourceIdDiffersAtTheSameIndex() {
        LinkOccurrence current = new LinkOccurrence("changed-target", "Changed", Optional.empty(), 0, 10);
        Occurrence previous = new Occurrence("occ-old", 0, "src-old", "Old", "Old");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("changed-target", "src-new"), List.of(previous));

        assertNotEquals("occ-old", assigned.get(0).id());
        assertEquals("src-new", assigned.get(0).targetSourceId());
    }

    @Test
    void breaksCorrespondenceFromTheFirstMismatchOnward() {
        LinkOccurrence firstCurrent = new LinkOccurrence("a", "A", Optional.empty(), 0, 1);
        LinkOccurrence secondCurrent = new LinkOccurrence("b", "B", Optional.empty(), 2, 3);
        Occurrence firstPrevious = new Occurrence("occ-a", 0, "src-a", "A", "A");
        Occurrence secondPrevious = new Occurrence("occ-b", 1, "src-b", "B", "B");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(firstCurrent, secondCurrent),
                Map.of("a", "src-x", "b", "src-b"),
                List.of(firstPrevious, secondPrevious));

        assertNotEquals("occ-a", assigned.get(0).id());
        assertNotEquals("occ-b", assigned.get(1).id());
    }
}
```

      (The last test proves the documented limitation from scope-pins.md: a mismatch at index 0 means index
      1's `occ-b` is NOT reused even though its own target source ID still matches — reuse is positional,
      not any-position lookup.)

- [x] 5.2 Run `mvn -q -Dtest=OccurrenceAssignmentTest test` from `publication-exporter/`. Expected: FAIL
      (class does not exist).

- [x] 5.3 Write `OccurrenceAssignment.java`:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class OccurrenceAssignment {

    private OccurrenceAssignment() {
    }

    static List<AssignedOccurrence> assign(
            List<LinkOccurrence> ruOccurrences,
            Map<String, String> targetSourceIdsByStem,
            List<Occurrence> previousOccurrences) {
        List<AssignedOccurrence> assigned = new ArrayList<>();
        for (int index = 0; index < ruOccurrences.size(); index++) {
            LinkOccurrence current = ruOccurrences.get(index);
            String targetSourceId = targetSourceIdsByStem.get(current.targetStem());
            assigned.add(new AssignedOccurrence(
                    idFor(index, targetSourceId, previousOccurrences), index, targetSourceId, current.label()));
        }
        return assigned;
    }

    private static String idFor(int index, String targetSourceId, List<Occurrence> previousOccurrences) {
        if (index >= previousOccurrences.size()) {
            return UUID.randomUUID().toString();
        }
        Occurrence previous = previousOccurrences.get(index);
        return previous.targetSourceId().equals(targetSourceId) ? previous.id() : UUID.randomUUID().toString();
    }

    record AssignedOccurrence(String id, int order, String targetSourceId, String ruLabel) {

        AssignedOccurrence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetSourceId, "targetSourceId");
            Objects.requireNonNull(ruLabel, "ruLabel");
        }
    }
}
```

- [x] 5.4 Run `mvn -q -Dtest=OccurrenceAssignmentTest test` from `publication-exporter/`. Expected: PASS.

- [x] 5.5 Run `mvn -q test` from `publication-exporter/` (whole suite). Expected: PASS, 0 regressions (this
      class is not wired into `PrepareHandler` yet — that is Task 7).

- [x] 5.6 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/OccurrenceAssignment.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/OccurrenceAssignmentTest.java
git commit -m "Add OccurrenceAssignment: positional reuse of prior occurrence IDs"
```

---

## 6. `OccurrenceLabelMarkers` — delimit/scan/strip for surviving translation

The marker mechanism design.md specifies: wrap each occurrence's label span with a paired delimiter before
translation; scan a translated body for delimited spans to recover order/count/content; strip delimiters to
produce the final installed body. This task builds and unit-tests the mechanism in isolation, with no
`PrepareHandler` wiring yet (Task 7).

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/OccurrenceLabelMarkers.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/OccurrenceLabelMarkersTest.java`

**Interfaces:**
- Consumes: `LinkOccurrence` (Task 1).
- Produces: `static String delimit(String resolvedBody, List<LinkOccurrence> occurrences)`;
  `static List<String> scan(String delimitedBody)`; `static String strip(String delimitedBody)`.

- [x] 6.1 Write `OccurrenceLabelMarkersTest.java`:

```java
package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OccurrenceLabelMarkersTest {

    @Test
    void delimitWrapsEachOccurrenceLabelSpan() {
        String resolvedBody = "See дед Шведов here.";
        LinkOccurrence occurrence = new LinkOccurrence("grandpa-shvedov", "дед Шведов", Optional.empty(), 4, 15);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(occurrence));

        assertEquals("See дед Шведов here.", delimited);
    }

    @Test
    void delimitWrapsMultipleOccurrencesWithoutShiftingLaterSpans() {
        String resolvedBody = "A then B.";
        LinkOccurrence first = new LinkOccurrence("a", "A", Optional.empty(), 0, 1);
        LinkOccurrence second = new LinkOccurrence("b", "B", Optional.empty(), 7, 8);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(first, second));

        assertEquals("A then B.", delimited);
    }

    @Test
    void scanRecoversDelimitedSpanContentsInOrder() {
        String delimited = "As he wrote Grandpa Shvedov and referenced public essay.";

        List<String> scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(List.of("Grandpa Shvedov", "public essay"), scanned);
    }

    @Test
    void scanReturnsEmptyListWhenNoDelimitersPresent() {
        assertEquals(List.of(), OccurrenceLabelMarkers.scan("Plain prose, no markers."));
    }

    @Test
    void stripRemovesDelimitersButKeepsContent() {
        String delimited = "As he wrote Grandpa Shvedov today.";

        String stripped = OccurrenceLabelMarkers.strip(delimited);

        assertEquals("As he wrote Grandpa Shvedov today.", stripped);
    }
}
```

- [x] 6.2 Run `mvn -q -Dtest=OccurrenceLabelMarkersTest test` from `publication-exporter/`. Expected: FAIL
      (class does not exist).

- [x] 6.3 Write `OccurrenceLabelMarkers.java`:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.List;

final class OccurrenceLabelMarkers {

    private static final char DELIMITER_OPEN = '';
    private static final char DELIMITER_CLOSE = '';

    private OccurrenceLabelMarkers() {
    }

    static String delimit(String resolvedBody, List<LinkOccurrence> occurrences) {
        StringBuilder delimited = new StringBuilder(resolvedBody.length() + occurrences.size() * 2);
        int cursor = 0;
        for (LinkOccurrence occurrence : occurrences) {
            delimited.append(resolvedBody, cursor, occurrence.spanStart());
            delimited.append(DELIMITER_OPEN);
            delimited.append(resolvedBody, occurrence.spanStart(), occurrence.spanEnd());
            delimited.append(DELIMITER_CLOSE);
            cursor = occurrence.spanEnd();
        }
        delimited.append(resolvedBody, cursor, resolvedBody.length());
        return delimited.toString();
    }

    static List<String> scan(String delimitedBody) {
        List<String> spans = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int open = delimitedBody.indexOf(DELIMITER_OPEN, cursor);
            if (open < 0) {
                break;
            }
            int close = delimitedBody.indexOf(DELIMITER_CLOSE, open + 1);
            if (close < 0) {
                break;
            }
            spans.add(delimitedBody.substring(open + 1, close));
            cursor = close + 1;
        }
        return spans;
    }

    static String strip(String delimitedBody) {
        StringBuilder stripped = new StringBuilder(delimitedBody.length());
        for (int i = 0; i < delimitedBody.length(); i++) {
            char c = delimitedBody.charAt(i);
            if (c != DELIMITER_OPEN && c != DELIMITER_CLOSE) {
                stripped.append(c);
            }
        }
        return stripped.toString();
    }
}
```

- [x] 6.4 Run `mvn -q -Dtest=OccurrenceLabelMarkersTest test` from `publication-exporter/`. Expected: PASS.

- [x] 6.5 Run `mvn -q test` from `publication-exporter/` (whole suite). Expected: PASS, 0 regressions.

- [x] 6.6 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/OccurrenceLabelMarkers.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/OccurrenceLabelMarkersTest.java
git commit -m "Add OccurrenceLabelMarkers: delimit/scan/strip for occurrence identity through translation"
```

---

## 7. Wire it all into `PrepareHandler` — the S19 acceptance tests (RED → GREEN)

This is the task that makes the slice's visible result real. Write the acceptance tests FIRST (they will
fail against every piece built in Tasks 1–6, since none of it is wired into `PrepareHandler` yet), then wire
`prepareAdmittedEssay`/`prepareTranslatedEssay`/`buildReferenceMap` to make them pass.

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `PrepareHandler.buildReferenceMap(..., List<Occurrence> occurrences)` (was: no occurrences
  parameter); no change to `PrepareHandler`'s public `prepare(...)` signature.

- [x] 7.1 Read the existing constructor and `prepare(...)` call-site convention before writing anything —
      match exactly (from this file, confirmed at the earlier research pass):

```java
PrepareHandler handler = new PrepareHandler(
        new NoteIntake(PublicationKinds.installed()),
        TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
        new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);
...
BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());
```

      Also read the existing S18 two-note fixture (`prepareSucceedsWhenDirectPrivateTargetsHaveUniqueSourceIds`,
      built from `VaultReader.createNull(Map.of(referrerPath, referrer, privateTargetPath, privateTarget))`)
      before writing the new fixtures below — reuse its frontmatter shape for the target note (`id`,
      `publish: false` or simply no `publish: true`) rather than inventing a new one. Retrieve an installed
      candidate the same way the rest of this file already does — `NullCandidateWorkspace` has no
      per-path lookup; every existing test instead does
      `NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);`
      (e.g. lines 356, 404, 1139) after calling `handler.prepare(...)` — match that pattern exactly (`get(1)`
      for a second `prepare()` call, since `install(...)` appends rather than replaces; `workspace.installed()`
      stays empty when `prepare` is expected to block before installing).

- [x] 7.2 Add the first failing acceptance test: a referrer linking to one already-admitted public target
      installs a candidate whose `references.json` carries exactly one non-empty occurrence, bound to the
      target's source ID and RU/EN labels:

```java
@Test
void preparingALinkedEssayInstallsANonEmptyOccurrenceMap() {
    String target = """
            ---
            publish: true
            collection: blog
            contentType: essay
            id: src-target-1
            title: Target Essay
            description: A target.
            ---
            Target body.""";
    String referrer = essayWithBody("See [[target-essay]].");
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
    VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()),
            TranslationWorker.createNull(
                    "As he wrote, see [Target Essay](/blog/target-essay/).",
                    fields("Translated title", "Translated description.")),
            workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

    assertTrue(response.ok());
    NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
    List<Occurrence> occurrences = installed.referenceMap().occurrences();
    assertEquals(1, occurrences.size());
    assertEquals(0, occurrences.get(0).order());
    assertEquals("src-target-1", occurrences.get(0).targetSourceId());
    assertEquals("target-essay", occurrences.get(0).ruLabel());
}
```

      (`essayWithBody(...)` and `fields(...)` are existing helpers in this file — grep for them and match
      their exact existing shape; do not redefine them. `workspace.installed().get(0)` is this file's
      existing convention for retrieving what a `prepare()` call installed, e.g. lines 356, 404, 1139.)

- [x] 7.3 Add the second failing acceptance test: reusing the same referrer/target unchanged across two
      `prepare` calls reuses the occurrence ID:

```java
@Test
void reprepareReusesThePriorOccurrenceIdWhenNothingChanged() {
    String target = """
            ---
            publish: true
            collection: blog
            contentType: essay
            id: src-target-2
            title: Target Essay
            description: A target.
            ---
            Target body.""";
    String referrer = essayWithBody("See [[target-essay]].");
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
    VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()),
            TranslationWorker.createNull(
                    "As he wrote, see [Target Essay](/blog/target-essay/).",
                    fields("Translated title", "Translated description.")),
            workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());
    String firstOccurrenceId = workspace.installed().get(0).referenceMap().occurrences().get(0).id();

    handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());
    String secondOccurrenceId = workspace.installed().get(1).referenceMap().occurrences().get(0).id();

    assertEquals(firstOccurrenceId, secondOccurrenceId);
}
```

- [x] 7.4 Add the third failing acceptance test: TRP-05's divergence scenario — a worker whose translation
      drops an occurrence marker is rejected before installation:

```java
@Test
void prepareBlocksWhenTranslationInventsOrDropsAnOccurrence() {
    String target = """
            ---
            publish: true
            collection: blog
            contentType: essay
            id: src-target-3
            title: Target Essay
            description: A target.
            ---
            Target body.""";
    String referrer = essayWithBody("See [[target-essay]].");
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
    VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()),
            TranslationWorker.createNull(
                    "As he wrote, nothing here at all.",
                    fields("Translated title", "Translated description.")),
            workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

    assertFalse(response.ok());
    assertTrue(workspace.installed().isEmpty());
}
```

- [x] 7.5 Run `mvn -q -Dtest=PrepareHandlerTest test` from `publication-exporter/`. Expected: FAIL — the new
      tests fail (occurrences still empty / not wired); `validEssayInstallsOneCandidateAndReturnsReadyForReview`'s
      existing `assertTrue(installed.referenceMap().occurrences().isEmpty())` assertion should still PASS
      unchanged (its fixture has no links) — if it now fails, something in Tasks 2–4 broke the no-link path;
      stop and fix that before continuing.

- [x] 7.6 Wire `PrepareHandler.prepareAdmittedEssay`/`prepareTranslatedEssay`: build the delimited RU body,
      pass it as the translate-call argument (keeping `TranslationJob.forSource(ruBody, ...)` on the plain
      body, per design.md's invariant), validate the returned body's delimited-span count against the
      assigned RU occurrences, strip delimiters from both bodies, and pass the labeled occurrence list into
      `buildReferenceMap`:

```java
private BridgeResponse prepareAdmittedEssay(
        VaultRelativePath notePath, VaultReader vaultReader,
        PublicationIdentity identity, String sourceHash,
        String ruBody, List<PublicField> ruFields, String structuredData, List<CandidateAsset> assets,
        PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader, OccurrenceContext occurrenceContext) {
    List<OccurrenceAssignment.AssignedOccurrence> assignedRu = assignOccurrences(
            identity, occurrenceContext);
    String delimitedRuBody = OccurrenceLabelMarkers.delimit(ruBody, occurrenceContext.occurrences());
    TranslationJob job = TranslationJob.forSource(ruBody, ruFields);
    return translateCandidate(job, delimitedRuBody, ruFields).resolve(
            translation -> prepareTranslatedEssay(
                    notePath, vaultReader, identity, sourceHash,
                    ruBody, ruFields, structuredData, assets, job, translation, knownNotes, vaultAssetReader,
                    assignedRu),
            failure -> {
                recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                return translationFailure(failure);
            });
}

private List<OccurrenceAssignment.AssignedOccurrence> assignOccurrences(
        PublicationIdentity identity, OccurrenceContext occurrenceContext) {
    if (occurrenceContext.occurrences().isEmpty()) {
        return List.of();
    }
    VaultSourceIdentityIndex identityIndex = occurrenceContext.identityIndex().orElseThrow();
    Map<String, String> targetSourceIdsByStem = new LinkedHashMap<>();
    for (LinkOccurrence occurrence : occurrenceContext.occurrences()) {
        targetSourceIdsByStem.put(occurrence.targetStem(),
                identityIndex.identityFor(occurrence.targetStem())
                        .flatMap(TargetIdentity::sourceId)
                        .orElseThrow());
    }
    List<Occurrence> previous = candidateWorkspace.read(identity)
            .map(snapshot -> snapshot.referenceMap().occurrences())
            .orElse(List.of());
    return OccurrenceAssignment.assign(occurrenceContext.occurrences(), targetSourceIdsByStem, previous);
}
```

      (`.orElseThrow()` on the identity lookup is safe here, not a shortcut: every occurrence reaching this
      point already passed `DirectTargetIdentityCheck` if private, or public-kind admission if public — see
      design.md's `OccurrenceAssignment` section for why an unresolvable identity cannot occur here. Import
      `java.util.LinkedHashMap`.)

- [x] 7.7 Wire `prepareTranslatedEssay`: validate the translated body's occurrence spans against
      `assignedRu`, strip delimiters from both bodies, extract `enLabel` per occurrence, and build the final
      `List<Occurrence>` for `buildReferenceMap`:

```java
private BridgeResponse prepareTranslatedEssay(
        VaultRelativePath notePath, VaultReader vaultReader,
        PublicationIdentity identity, String sourceHash,
        String ruBody, List<PublicField> ruFields, String structuredData,
        List<CandidateAsset> assets, TranslationJob job,
        EnglishTranslation translation, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
        List<OccurrenceAssignment.AssignedOccurrence> assignedRu) {
    List<String> enSpans = OccurrenceLabelMarkers.scan(translation.body());
    if (enSpans.size() != assignedRu.size()) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
        return BridgeResponse.translationFailed(COMMAND, Diagnostic.blocking(
                "candidate", "Translated candidate reordered or invented semantic occurrences."));
    }
    String enBody = OccurrenceLabelMarkers.strip(translation.body());
    List<PublicField> enFields = translation.fields();
    List<Occurrence> occurrences = occurrencesWithEnglishLabels(assignedRu, enSpans);

    EnglishCandidateValidator.Result validation = validateEnglishCandidate(ruBody, ruFields, enBody, enFields);
    if (!validation.valid()) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
        return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
    }
    SourceFreshnessOutcome freshness;
    try {
        freshness = sourceFreshness(notePath, vaultReader, identity, job, structuredData, knownNotes, vaultAssetReader, noteIntake);
    } catch (UncheckedIOException failure) {
        return assetResolutionLookupFailure(failure);
    }
    return freshness.resolve(
            currentSourceHash -> {
                ReferenceMap referenceMap = buildReferenceMap(
                        identity, ruBody, enBody, ruFields, enFields, structuredData, occurrences);
                BridgeResponse response = installCandidate(identity, ruBody, enBody, ruFields, enFields,
                        structuredData, referenceMap, assets);
                if (response.ok()) {
                    recordWorkflowStatus(notePath, currentSourceHash, WorkflowState.READY_FOR_REVIEW);
                }
                return response;
            },
            () -> {
                recordStaleWorkflowStatus(notePath, vaultReader);
                return BridgeResponse.stale(COMMAND,
                        Diagnostic.blocking("candidate", "Source note changed while translation was in progress."));
            },
            PrepareHandler::unclosedCommentFailure,
            PrepareHandler::transclusionBlockedFailure,
            PrepareHandler::assetBlockedFailure);
}

private static List<Occurrence> occurrencesWithEnglishLabels(
        List<OccurrenceAssignment.AssignedOccurrence> assignedRu, List<String> enLabels) {
    List<Occurrence> occurrences = new ArrayList<>();
    for (int i = 0; i < assignedRu.size(); i++) {
        OccurrenceAssignment.AssignedOccurrence ru = assignedRu.get(i);
        occurrences.add(new Occurrence(ru.id(), ru.order(), ru.targetSourceId(), ru.ruLabel(), enLabels.get(i)));
    }
    return occurrences;
}
```

      `buildReferenceMap` gains the `occurrences` parameter and calls `ReferenceMap.of(...)` instead of
      `ReferenceMap.empty(...)` — same hashing arguments as today, plus the list:

```java
private static ReferenceMap buildReferenceMap(
        PublicationIdentity identity, String ruBody, String enBody,
        List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
        List<Occurrence> occurrences) {
    return ReferenceMap.of(
            identity,
            ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
            ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
            ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
            ContentHash.sha256Hex(structuredData),
            occurrences);
}
```

      (Same five hash arguments as today's `ReferenceMap.empty(...)` call, unchanged; only the trailing
      `occurrences` parameter is new and the factory call becomes `ReferenceMap.of(...)`.) Update every call
      site of `prepareAdmittedEssay`/`prepareTranslatedEssay`/`buildReferenceMap` to thread
      `occurrenceContext`/`assignedRu`/`occurrences` through, and update `translateCandidate`'s existing
      `try`/`catch` wrapper to accept the delimited body as its `ruBody` argument name (no signature change
      needed there — it already takes a plain `String ruBody`).

- [x] 7.8 Run `mvn -q -Dtest=PrepareHandlerTest test` from `publication-exporter/`. Expected: PASS, including
      all three new tests and every pre-existing test in this file (2600+ lines) unchanged.

- [x] 7.9 Run `mvn -q test` from `publication-exporter/` (whole suite, all modules). Expected: PASS, 0
      regressions. This is the slice's real acceptance gate — do not consider the slice done until this is
      green.

- [x] 7.10 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "Wire occurrence assignment and TRP-05 validation into PrepareHandler"
```

---

## 8. Final full-suite verification

- [x] 8.1 From `publication-exporter/`, run `mvn -q test` and confirm exit code 0 and no output beyond
      Maven's own warnings (matching this repo's baseline — see this file's global constraints block for
      the pre-slice count).
- [x] 8.2 `git status` — confirm no unintended files are staged or modified (in particular, nothing under
      `exporter-java/`).
- [x] 8.3 Report the final test count and confirm every task above is checked off. Do not archive the
      OpenSpec change or touch Haft artifacts — those are the orchestrating session's job, not this task
      list's.
