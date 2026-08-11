<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q test` from publication-exporter/.
- Outside-in TDD: sections 1-5 are a behaviour-preserving refactor (essay/note-only) — the existing acceptance
  suite is the safety net; `mvn -q test` MUST stay green after every task in sections 1-5. Section 6 adds a
  genuinely new failing acceptance test (blog/claim) before any claim-admitting production code — standard
  outside-in discipline (openspec/implementation-plan.md).
- Zero new production boundary adapters this slice. Claim admission/preparation/release is pure in-process
  dispatch over the existing kind-selected prepare/approve/release path.
- Never modify exporter-java/ — read-only compatibility oracle, not a code donor.
- Never touch book/album/concept/curated_page — out of scope until their own slices.
- Never touch release/ReleaseOutputStore.java, release/ReleaseProvenance.java, release/FilesystemReleaseOutputStore.java
  — design.md's Context confirms these carry only ruBody/enBody + hash provenance and never reach title/description/
  statement; FilesystemManagedSiteInstaller reads CandidateSnapshot directly via ApprovedSnapshotWorkspace.read(),
  bypassing ReleaseOutputStore. Touching these files means the design was misread — stop and re-check design.md.
- No real production data exists under the current candidate/approved file formats (confirmed while resolving
  prob-20260811-0b8b9f2d). The file-format changes in section 4 need no migration/backward-compatibility code.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: every new/changed value type follows
  this project's existing convention exactly (see PublicationIdentity, Diagnostic, ReferenceMap, ContentHash) —
  public final class, private all-args constructor, named static factories (SBPP-BEH-02 Constructor Method), no
  getter-prefixed accessors (Elegant Objects 3.5), equals/hashCode/toString on every value object, immutable
  List.copyOf(...) fields, static-utility classes get a private no-arg constructor (ContentHash's own pattern).
  Every real adapter keeps a matching Null* fake current (nullables discipline) — a signature change to
  TranslationWorker/CandidateWorkspace/ApprovedSnapshotWorkspace without updating its Null* counterpart in the
  same task is incomplete. No comments in production code beyond what non-obvious rationale demands — this file's
  own comments are plan scaffolding, not a model for the code you write.
- GraalVM reflect-config.json: REQUIRED this slice. PublicField is a new Jackson-serialized DTO (serialized into
  ru.fields.json/en.fields.json). Task 1.3 adds its reflect-config.json entry in the same commit that introduces
  the class — do not defer this to a final review (S17a's own final-review lesson: this exact gap is invisible to
  the JVM test suite and only breaks the native build).
- Full reference documents (read before starting): proposal.md, specs/publication-admission/spec.md,
  specs/public-content-model/spec.md, design.md — all in openspec/changes/s17b-blog-claim-kind/. design.md's
  Decisions D1-D6 map directly onto the classes this file creates/changes; read it first if anything below is
  unclear on *why*, not just *what*.
-->

## 1. `PublicField` value type + shared `YamlScalar` escaping helper

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/PublicField.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/YamlScalar.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java` (extract `doubleQuotedYamlScalar`/`appendJsonString` usage onto `YamlScalar`, behaviour-preserving)
- Modify: `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json`

**Design context (design.md D1, D5):** `PublicField` is the ordered `(key, value)` pair every kind's translatable content is built from. `YamlScalar` is the shared scalar-escaping helper both `FilesystemManagedSiteInstaller` (today) and `ClaimPublicationKind` (task 7) use, so the two call sites cannot silently diverge in how they escape a string into `"..."` YAML.

- [ ] 1.1 Create `PublicField`:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class PublicField {

    private final String key;
    private final String value;

    private PublicField(String key, String value) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
    }

    public static PublicField of(String key, String value) {
        return new PublicField(key, value);
    }

    @JsonProperty("key")
    public String key() {
        return key;
    }

    @JsonProperty("value")
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicField that)) {
            return false;
        }
        return key.equals(that.key) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }

    @Override
    public String toString() {
        return "PublicField[key=" + key + ", value=" + value + "]";
    }
}
```

- [ ] 1.2 Create `YamlScalar` by lifting `FilesystemManagedSiteInstaller`'s existing `doubleQuotedYamlScalar`/`appendJsonString`-based escaping verbatim (same escaping behaviour, just relocated and made public):

```java
package dev.eugene.publicationexporter.site;

public final class YamlScalar {

    private YamlScalar() {
    }

    public static String doubleQuoted(String value) {
        StringBuilder scalar = new StringBuilder();
        SiteReleaseManifest.appendJsonString(scalar, value);
        return scalar.toString();
    }
}
```

Update `FilesystemManagedSiteInstaller.appendYamlString`/`doubleQuotedYamlScalar` to delegate to `YamlScalar.doubleQuoted(value)` instead of calling `SiteReleaseManifest.appendJsonString` directly; delete the now-redundant private `doubleQuotedYamlScalar` method. No observable output change — write a quick manual check (existing `FilesystemManagedSiteInstallerTest` frontmatter assertions must stay green) before moving on.

- [ ] 1.3 Add `PublicField`'s reflect-config.json entry (mirror the shape of `PublicationIdentity`'s or `Diagnostic`'s existing entry):

```json
{
  "name": "dev.eugene.publicationexporter.reference.PublicField",
  "allDeclaredFields": true,
  "allDeclaredMethods": true
}
```

- [ ] 1.4 Run `mvn -q test` — full suite green (no behaviour changed yet, only additive).

## 2. Generalize the translation pipeline to `(body, List<PublicField>)` (behaviour-preserving)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationJob.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/EnglishTranslation.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationOutcome.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/RussianDiff.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java`
- Modify: every test referencing the old `(ruTitle, ruDescription)`/`(enTitle, enDescription)` two-string signatures of the classes above (translation/*Test.java, prepare/RussianDiffTest.java, prepare/EnglishCandidateValidatorTest.java, prepare/PrepareHandlerTest.java) — mechanical signature updates, same assertions.

**Design context (design.md D2):** `title`/`description` become `fields.get(0)`/`fields.get(1)` by construction (every kind's `admit()` puts them first, enforced in section 5) — no class in this section needs to know the literal strings `"title"`/`"description"`; they only ever iterate the list they're given.

- [ ] 2.1 `TranslationJob.forSource(String ruBody, List<PublicField> ruFields)`: fingerprint is the same canonical-length-prefix concatenation as today, generalized from two named parameters to a loop over `ruFields` (in order) after the body:

```java
private static String fingerprintFor(String ruBody, List<PublicField> ruFields) {
    StringBuilder canonical = new StringBuilder();
    canonical.append(ruBody.length()).append(':').append(ruBody);
    for (PublicField field : ruFields) {
        canonical.append(field.value().length()).append(':').append(field.value());
    }
    return ContentHash.sha256Hex(canonical.toString());
}
```

Keep `requireSourceFields` validating `ruBody` and every field's `value()` non-null (fields list itself non-null, `List.copyOf` defensively).

- [ ] 2.2 `EnglishTranslation.of(String body, List<PublicField> fields)`; `body()`/`fields()` accessors (drop `title()`/`description()`).

- [ ] 2.3 `TranslationOutcome.success(String enBody, List<PublicField> enFields)`; `TranslationOutcome.failure(...)`/`stale()` unchanged (they carry no field data).

- [ ] 2.4 `TranslationWorker.translate(TranslationJob job, String ruBody, List<PublicField> ruFields) -> TranslationOutcome`; `TranslationWorker.createNull(String enBody, List<PublicField> enFields)` (replaces the 3-string overload — update every call site).

- [ ] 2.5 `NullTranslationWorker`: `translate(...)` records `RequestedTranslation(String ruBody, List<PublicField> ruFields)` instead of the 3-string record; `requested()` unchanged in spirit.

- [ ] 2.6 `RussianDiff.between(String approvedBody, List<PublicField> approvedFields, String currentBody, List<PublicField> currentFields)`: replace the two `labeledFieldDiff("title", ...)`/`labeledFieldDiff("description", ...)` calls with one loop, zipping `approvedFields`/`currentFields` by index (both lists are always built by the same kind in the same order, so index-alignment is safe) and labeling each diff line with that field's own `key()`:

```java
for (int i = 0; i < approvedFields.size(); i++) {
    completeDiff.addAll(labeledFieldDiff(
            approvedFields.get(i).key(), approvedFields.get(i).value(), currentFields.get(i).value()));
}
```

`betweenBodies(String approvedBody, String currentBody)` stays as a convenience overload calling `between(approvedBody, List.of(), currentBody, List.of())`.

- [ ] 2.7 `EnglishCandidateValidator.validate(String ruBody, String enBody, List<PublicField> enFields)`: `blankFieldDiagnostics` iterates `enFields` (blank value → `"Translation worker produced a blank " + field.key() + "."`) instead of naming `enTitle`/`enDescription`; `internalRouteDiagnostics` scans `enBody` plus every field's `value()`. `droppedUrlDiagnostics`/`droppedAssetReferenceDiagnostics` stay body-only, unchanged.

- [ ] 2.8 Update every test in the Files list above to the new signatures — same assertions, same fixture content, just `List.of(PublicField.of("title", "..."), PublicField.of("description", "..."))` instead of two string parameters. `PrepareHandler` itself is updated in section 5, not here — its own tests may need temporary signature stubs; coordinate so this task's tests compile (a shared test-fixture helper building the standard `[title, description]` list is reasonable here if several test files need it).

- [ ] 2.9 Run `mvn -q test` — full suite green.

## 3. Generalize `CandidateSnapshot` and `ReferenceMap`/`ReferenceMapCodec` (behaviour-preserving)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidateSnapshotTest.java` (if present) and `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`, `ReferenceMapCodecTest.java`

**Design context (design.md D3, D4):** `CandidateSnapshot` drops `ruTitle()`/`enTitle()`/`ruDescription()`/`enDescription()` in favor of `ruFields()`/`enFields()` plus a new `structuredData()`. `ReferenceMap` drops the four named field-hashes for two whole-document hashes plus one structured-data hash.

- [ ] 3.1 `CandidateSnapshot.of(String ruBody, String enBody, List<PublicField> ruFields, List<PublicField> enFields, String structuredData, ReferenceMap referenceMap)`. Add a small shared helper both `CandidateSnapshot` callers and `FilesystemManagedSiteInstaller` (section 5) can use to look up a field by key:

```java
public Optional<String> field(List<PublicField> fields, String key) {
    return fields.stream().filter(f -> f.key().equals(key)).map(PublicField::value).findFirst();
}
```

(Place as a small package-visible static helper — e.g. on `PublicField` itself as `PublicField.value(List<PublicField> fields, String key)` — rather than duplicating the lookup loop at each call site.) Update `equals`/`hashCode`/`toString` to the new field set.

- [ ] 3.2 `ReferenceMap.empty(PublicationIdentity identity, String ruHash, String enHash, String ruFieldsHash, String enFieldsHash, String structuredDataHash)` (drops `ruTitleHash`/`enTitleHash`/`ruDescriptionHash`/`enDescriptionHash`, adds `ruFieldsHash`/`enFieldsHash`/`structuredDataHash`). Keep `identity()`, `ruHash()`, `enHash()`, `sameContentAs(...)`, `occurrences()` unchanged in spirit — `sameContentAs` now compares all five hash fields instead of six.

- [ ] 3.3 `ReferenceMapCodec`: `write`/`read` follow the new field names via Jackson (no manual JSON construction needed — `@JsonProperty` on `ReferenceMap`'s accessors already drives this); update `referenceMapFrom(JsonNode root)` to read `ruFieldsHash`/`enFieldsHash`/`structuredDataHash` instead of the four dropped fields.

- [ ] 3.4 Update `ReferenceMapTest`/`ReferenceMapCodecTest` to the new field shape — same behavioural assertions (round-trip, identity mismatch, hash mismatch), new field names.

- [ ] 3.5 Run `mvn -q test` — expect failures only in `FilesystemCandidateWorkspaceTest`/`FilesystemApprovedSnapshotWorkspaceTest`/`PrepareHandlerTest`/`FilesystemManagedSiteInstallerTest` (their own generalization is sections 4-5); every other test must be green.

## 4. Generalize the real filesystem workspaces' file format (behaviour-preserving)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`

**Design context (design.md D6):** `ru.title`/`en.title`/`ru.description`/`en.description` are replaced by one `ru.fields.json`/`en.fields.json` document per locale (a JSON array of `PublicField`, written/read via Jackson through a new small `PublicFieldsCodec`, mirroring `ReferenceMapCodec`'s own pattern). `ru.md`/`en.md`/`references.json` are unchanged in name; `references.json`'s content follows section 3's `ReferenceMap` shape automatically.

- [ ] 4.1 Create `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/PublicFieldsCodec.java`:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public final class PublicFieldsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PublicFieldsCodec() {
    }

    public static String write(List<PublicField> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }

    public static List<PublicField> read(String json) {
        try {
            List<Object> raw = MAPPER.readValue(json, new TypeReference<List<java.util.Map<String, String>>>() { });
            return raw.stream()
                    .map(entry -> PublicField.of(
                            ((java.util.Map<String, String>) entry).get("key"),
                            ((java.util.Map<String, String>) entry).get("value")))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
```

(Adjust the generic-typed read implementation to whatever is cleanest with the project's existing Jackson usage style — the shape that matters is: write a JSON array of `{"key":...,"value":...}` objects in order, read it back into an equal-order `List<PublicField>`. Add a `PublicFieldsCodecTest` alongside `ReferenceMapCodecTest`'s own style: round-trip, empty list, ordering preserved.)

- [ ] 4.2 `FilesystemCandidateWorkspace`: `writeSnapshot` writes `ru.fields.json`/`en.fields.json` (via `PublicFieldsCodec.write`) instead of `ru.title`/`en.title`/`ru.description`/`en.description`. `containsCandidateSnapshot` checks for `ru.md`, `en.md`, `ru.fields.json`, `en.fields.json`, `references.json` (drops the four old file checks). `snapshotFrom` reads `ru.fields.json`/`en.fields.json` via `PublicFieldsCodec.read` instead of the four `readCandidateText` calls, and reads `structuredData` from the read `ReferenceMap` (not a separate file — `references.json` already carries it per section 3). `requireNoKindCollision` (the cross-kind collision guard from `dec-20260811-02b96a37`) is untouched — it only reads `references.json`'s `identity`, unaffected by this file-set change.

- [ ] 4.3 `FilesystemApprovedSnapshotWorkspace`: same file-set change as 4.2 (`writeSnapshot`, `snapshotFrom`, `approvedFile` call sites, `validateSnapshot`'s per-file hash checks — now checking `ruFieldsHash`/`enFieldsHash` against the whole `ru.fields.json`/`en.fields.json` document bytes, plus `structuredDataHash` against the `structuredData` string, instead of four named-field hash checks).

- [ ] 4.4 Update `FilesystemCandidateWorkspaceTest`/`FilesystemApprovedSnapshotWorkspaceTest`: replace assertions reading `candidateDir.resolve("ru.title")`/etc. with assertions reading `ru.fields.json`/`en.fields.json` and checking the decoded field list; every other assertion (atomic replace, backup/recovery, confinement, the cross-kind collision tests from `CrossKindAddressCollisionAcceptanceTest`) stays behaviourally the same.

- [ ] 4.5 Run `mvn -q test` — expect failures only in `PrepareHandlerTest`/`FilesystemManagedSiteInstallerTest` (section 5); everything else green, including `CrossKindAddressCollisionAcceptanceTest`.

## 5. Update `PrepareHandler` and `FilesystemManagedSiteInstaller` to the new shapes (closes the behaviour-preserving phase)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/AdmittedPublication.java` (add `structuredData()`, default `""` for essay/note)

**Design context (design.md D3, D5):** `PrepareHandler` builds `List.of(PublicField.of("title", intake.title()), PublicField.of("description", intake.description()))` from `AdmittedPublication` (order matters — title first, description second, matching every prior section's assumption) and threads `structuredData` straight through unchanged from admission to the installed `CandidateSnapshot`. `FilesystemManagedSiteInstaller.frontmatter()` writes every `PublicField` (in order) plus, when `structuredData()` is non-blank, appends it verbatim before the closing `---`.

- [ ] 5.1 `AdmittedPublication`: add `structuredData()` accessor (default `""`, set by `EssayPublicationKind`/`NotePublicationKind`'s `admit()` — literally the empty string, since neither has structured kind-specific data). Update `accepted(...)` factory to take it as a parameter (or add an overload defaulting to `""` for the two existing kinds, whichever keeps `EssayPublicationKind`/`NotePublicationKind` diffs minimal).

- [ ] 5.2 `PrepareHandler`: replace every `ruTitle`/`ruDescription`/`enTitle`/`enDescription` parameter with `List<PublicField> ruFields`/`enFields`, built once from `intake` at the top of `prepareAdmittedEssay` (`List.of(PublicField.of("title", intake.title()), PublicField.of("description", intake.description()))`) and threaded through `TranslationJob.forSource`, `translateCandidate`, `prepareTranslatedEssay`, `validateEnglishCandidate` (now `EnglishCandidateValidator.validate(ruBody, enBody, enFields)`), `sourceFingerprintMatches` (now compares `List<PublicField>` via `TranslationJob.forSource`'s own fingerprint, unchanged logic), `buildReferenceMap` (now hashes `PublicFieldsCodec.write(ruFields)`/`PublicFieldsCodec.write(enFields)` for `ruFieldsHash`/`enFieldsHash`, and `ContentHash.sha256Hex(structuredData)` for `structuredDataHash`), and `installCandidate` (passes `structuredData` through to `CandidateSnapshot.of`). `matchingApprovedBaseline`'s `RussianDiff.between` call becomes `RussianDiff.between(baseline.ruBody(), baseline.ruFields(), currentBody, currentFields)`.

- [ ] 5.3 `FilesystemManagedSiteInstaller.frontmatter(PublicationIdentity identity, CandidateSnapshot approved, String locale)`: replace the two `appendYamlString(yaml, "title", ...)`/`appendYamlString(yaml, "description", ...)` calls with a loop over `(isRu ? approved.ruFields() : approved.enFields())`, emitting `appendYamlString(yaml, field.key(), field.value())` for each in order (title/description still land first and second, byte-identical to today's output for essay/note). After the existing fixed fields and before the closing `---`, append `approved.structuredData()` verbatim when non-blank (no YAML key wrapping — the kind already rendered complete YAML lines, per design.md D5).

- [ ] 5.4 Update `PrepareHandlerTest`/`FilesystemManagedSiteInstallerTest` to the new signatures — same assertions, same fixture content.

- [ ] 5.5 Run `mvn -q test` — full suite green. This closes the behaviour-preserving refactor phase: no essay/note observable behaviour has changed (title/description round-trip identically; `structuredData` is empty for both, so `frontmatter()`'s output is byte-identical to before this section).

## 6. Failing acceptance test: blog/claim completes admit → prepare → approve → release → site install (RED)

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java` (top-level package, mirroring `CrossKindAddressCollisionAcceptanceTest`'s placement — this fixture spans admission/prepare/approve/release/site, not one bounded package)

**Design context:** proposal.md's acceptance criterion and `prob-20260811-f60fe262`'s acceptance text: one `blog/claim` fixture (non-blank `statement`, at least one populated relationship array) completes prepare → approve → release → site install through the same handlers already proven for essay/note, and the installed site file carries `statement` and the relationship data.

- [ ] 6.1 Write `BlogClaimAcceptanceTest` following `BlogNoteAcceptanceTest`'s in-memory-null-adapter style through prepare/approve/release, PLUS a real `FilesystemManagedSiteInstaller` install step (use `@TempDir`) asserting the installed `ru/{publicId}.md` frontmatter contains `statement: "..."` and the rendered relationship-array YAML. A representative fixture note:

```yaml
---
publish: true
publicCollection: blog
publicContentType: claim
publicId: latency-budget-is-fiction
id: 91aa-latency-claim
title: A fixed latency budget is fiction
description: Why "p99 < 100ms" is usually the wrong abstraction.
statement: A fixed "p99 < 100ms" latency budget is usually the wrong abstraction.
supports:
  - label: "Queueing theory: tail latency compounds across hops"
    target: measuring-tail-latency
opposes:
  - label: "SLA templates assume a single fixed budget"
---
Body prose discussing the claim.
```

Assert: `prepare` succeeds and resolves to `PublicationIdentity.of("blog", "claim", "latency-budget-is-fiction")`; `mark-reviewed` succeeds and the approved snapshot is readable; `build-from-review` succeeds; a real `FilesystemManagedSiteInstaller.install(...)` against the approved snapshot writes `ru/latency-budget-is-fiction.md` whose frontmatter contains the `statement` line and a `supports:` YAML block with the fixture's label/target.

- [ ] 6.2 Run `mvn -q test -Dtest=BlogClaimAcceptanceTest` — confirm it fails at admission (`ClaimPublicationKind` does not exist yet / `PublicationKinds.installed()` does not include `claim` yet). This is the RED step — do not proceed to section 7 without seeing this fail for the right reason (kind not found), not a compile error or an unrelated failure.

## 7. Implement `ClaimPublicationKind` (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java` (mirror `NotePublicationKindTest.java`'s structure)

**Design context (design.md D5):** `ClaimPublicationKind.admit(...)` validates identity + non-blank `statement` (relationship arrays/`sources` optional — no validation beyond well-formed YAML-safe strings), builds `[title, description, statement]` `PublicField`s, and renders `supports`/`opposes`/`assumes`/`refines`/`contradicts`/`sources` into one `structuredData` YAML fragment via `YamlScalar.doubleQuoted(...)` — using `MarkdownNote`'s existing frontmatter-array-reading capability (check `MarkdownNote`'s current API for reading a list-of-maps field; extend it minimally if it does not yet support one, following the same non-null-tolerant style `frontmatter.string(key)` already uses).

- [ ] 7.1 Create `ClaimPublicationKind`:

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.YamlScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ClaimPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<String> RELATIONSHIP_KEYS =
            List.of("supports", "opposes", "assumes", "refines", "contradicts");

    @Override
    public String collection() {
        return "blog";
    }

    @Override
    public String contentType() {
        return "claim";
    }

    @Override
    public String routePrefix() {
        return "claims";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);
        String statement = requireNonBlank(frontmatter, "statement", diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        List<PublicField> fields = List.of(
                PublicField.of("title", title), PublicField.of("description", description),
                PublicField.of("statement", statement));
        return AdmittedPublication.accepted(
                this, PublicationIdentity.of(collection(), contentType(), publicId), sourceId,
                fields, structuredDataFrom(frontmatter));
    }

    private String structuredDataFrom(MarkdownNote frontmatter) {
        StringBuilder yaml = new StringBuilder();
        for (String key : RELATIONSHIP_KEYS) {
            appendRelationshipArray(yaml, key, frontmatter);
        }
        appendSourcesArray(yaml, frontmatter);
        return yaml.toString();
    }

    // appendRelationshipArray/appendSourcesArray: read frontmatter's list-of-maps field (extend
    // MarkdownNote minimally if needed), emit one YAML block-sequence entry per item using
    // YamlScalar.doubleQuoted(...) for every scalar value, skip the key entirely when the source
    // list is empty (no "supports: []" noise in the rendered fragment).

    // ... (contract(), requireValidPublicId, requireNonBlank, toFieldContract: same shape as
    // EssayPublicationKind/NotePublicationKind, plus one more FieldRule.nonBlank("statement") in
    // FIELD_RULES and contract())
}
```

(This is a shape sketch, not literal final code — implement `appendRelationshipArray`/`appendSourcesArray` and the constructor-method boilerplate following `EssayPublicationKind`/`NotePublicationKind`'s exact existing style. `AdmittedPublication.accepted(...)`'s signature changes from section 5's `structuredData()` addition — confirm the exact parameter order/overload you land on there before writing this call.)

- [ ] 7.2 Register `ClaimPublicationKind` in `PublicationKinds.installed()`: `List.of(new EssayPublicationKind(), new NotePublicationKind(), new ClaimPublicationKind())`.

- [ ] 7.3 Write `ClaimPublicationKindTest` (mirror `NotePublicationKindTest`): accepted fixture with `statement` + relationship arrays; blocked fixture missing `statement`; accepted fixture with zero populated relationship arrays (per `spec.md`'s `ADM-04` scenario — relationships stay optional).

- [ ] 7.4 Run `mvn -q test -Dtest=BlogClaimAcceptanceTest` (from section 6) — confirm GREEN: admission, prepare, approve, release, and site install all succeed, and the installed file's frontmatter contains `statement` and the rendered `supports:` block.

- [ ] 7.5 Run `mvn -q test` — full suite green, including every essay/note acceptance test untouched by this section.

## 8. Contract conformance, reflect-config check, and full-suite verification

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java` (add the `blog/claim` fixture row per `spec.md`'s `ADM-06` scenarios)
- Modify: `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json` (verify — see below)

**Design context:** `spec.md`'s `ADM-06` delta — `write-publication-contract` must emit a complete, independently correct `blog/claim` entry (required fields including `statement`) alongside the unchanged `blog/essay`/`blog/note` entries, drawn from the same shared fixture table the runtime validator uses.

- [ ] 8.1 Add a `blog/claim` row (or fixture) to `PublicationContractConformanceTest`'s shared fixture table, proving the published contract and `ClaimPublicationKind.admit(...)` agree on both an accepted and a missing-`statement` fixture.

- [ ] 8.2 Run `mvn -q test -Dtest=WritePublicationContractCliAcceptanceTest,PublicationContractConformanceTest` — confirm the contract lists all three kinds sorted by `(collection, contentType)` (`blog/claim` before `blog/essay` before `blog/note` alphabetically — verify against `KindContract`'s actual sort key, not assumed).

- [ ] 8.3 Inspect `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json` end to end: confirm `PublicField` (task 1.3) is present, and confirm no other class introduced in this slice (`ClaimPublicationKind`, `PublicFieldsCodec`, `YamlScalar`) needs an entry — none of them are directly Jackson-serialized (only `PublicField` itself is; `ClaimPublicationKind` is internal, `PublicFieldsCodec`/`YamlScalar` are static utilities). This mirrors S17a's own final-review lesson: verify explicitly, do not assume.

- [ ] 8.4 Run the full suite once more: `mvn -q test`. Confirm the total test count only grew (no test was silently deleted instead of migrated) and `BUILD SUCCESS`.

- [ ] 8.5 Run `graphify update .` from the repo root to refresh the knowledge graph before requesting review, per this project's CLAUDE.md.
