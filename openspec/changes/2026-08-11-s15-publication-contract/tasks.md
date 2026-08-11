<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: write the failing acceptance test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- This slice is pure in-process behaviour: no new port/adapter, no filesystem, no vault, no worker. Do not invent
  one "for architectural symmetry" (design.md Goals).
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: value types follow this project's
  existing convention exactly (`Diagnostic`, `PublicationIdentity`, `CandidateAsset`, `BridgeResponse`) — public
  final class, private all-args constructor, named static factories (SBPP-BEH-02 Constructor Method), no getter
  prefixes (Elegant Objects 3.5 / SBPP-STA-09 Getting Method — accessor named after the noun, e.g. `field()` not
  `getField()`), `@JsonProperty` on every accessor, `@JsonInclude(NON_NULL)` where a field can be legitimately
  absent, `equals`/`hashCode`/`toString`. Constructors stay code-free beyond `Objects.requireNonNull` and
  `List.copyOf` (Elegant Objects 1.3 / SBPP-BEH-03 Constructor Parameter Method) — no branching, no I/O. No
  getters/setters that just expose storage; every accessor here also is the only way to read that state (Elegant
  Objects 3.5). No public constants carrying domain semantics (Elegant Objects 2.5) — `EssayAdmission` exposes its
  field rules through a method (`fieldRules()`), not a public field. No comments in production code beyond what
  non-obvious rationale demands — this file's own comments are plan scaffolding, not a model for the code you
  write.
- No new adapter/port this slice — `write-publication-contract` reads no vault, no filesystem, nothing nulled.
  Do not add `create()`/`createNull()` factories to any class in this slice; there is no I/O to null.
- Full reference documents (read before starting any task): proposal.md, specs/publication-admission/spec.md,
  design.md — all in openspec/changes/2026-08-11-s15-publication-contract/. design.md's Decisions 1-3 map
  directly onto the classes this file creates; read it first if anything below is unclear on *why*, not just
  *what*.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Never modify EssayAdmission.admit(...) or any of its existing private require* methods' logic — this slice
  only adds a new `FieldRule`/`fieldRules()` seam alongside the untouched validation code (design.md Decision 1).
  Confirm after section 2 that every pre-existing EssayAdmissionTest assertion still holds before touching
  anything else.
-->

## 1. Failing CLI acceptance test (RED)

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java`

This test references only `Main`, `picocli.CommandLine`, and Jackson's `JsonNode`/`ObjectMapper`/`JsonParser` — it
compiles today. It fails at runtime because `write-publication-contract` is not yet a registered subcommand
(picocli returns a nonzero exit code and prints a usage error to stderr; stdout stays empty, so the JSON-parsing
helper throws). That is the expected RED state — read it in full before running, so you recognize *why* it fails
and don't mistake it for a different error once section 3 changes the failure mode.

- [x] 1.1 Write the failing test, mirroring this project's existing CLI-acceptance-test shape (see
      `RefreshPublicationQueueCliAcceptanceTest.java` for the stdout-capture pattern this copies):

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WritePublicationContractCliAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void contractDescribesTheEssayKind() throws Exception {
        int exitCode = new CommandLine(new Main()).execute("write-publication-contract");

        assertEquals(0, exitCode);
        JsonNode contract = soleJsonValueOnStdout();
        assertEquals(1, contract.get("contractVersion").asInt());

        JsonNode kinds = contract.get("kinds");
        assertEquals(1, kinds.size());
        JsonNode essayKind = kinds.get(0);
        assertEquals("blog", essayKind.get("collection").asText());
        assertEquals("essay", essayKind.get("contentType").asText());
        assertTrue(essayKind.get("structuredBody").isEmpty());

        JsonNode requiredFields = essayKind.get("requiredFields");
        assertEquals(7, requiredFields.size());
        assertFieldNamed(requiredFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredFields, "publicCollection", field ->
                assertEquals("blog", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredFields, "publicContentType", field ->
                assertEquals("essay", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredFields, "description", field -> assertTrue(field.get("nonBlank").asBoolean()));
    }

    @Test
    void contractIsByteEquivalentAcrossTwoRequests() throws Exception {
        new CommandLine(new Main()).execute("write-publication-contract");
        String firstResponse = capturedOut.toString(StandardCharsets.UTF_8);
        capturedOut.reset();

        new CommandLine(new Main()).execute("write-publication-contract");
        String secondResponse = capturedOut.toString(StandardCharsets.UTF_8);

        assertEquals(firstResponse, secondResponse);
    }

    private void assertFieldNamed(JsonNode requiredFields, String name, Consumer<JsonNode> assertion) {
        for (JsonNode field : requiredFields) {
            if (field.get("name").asText().equals(name)) {
                assertion.accept(field);
                return;
            }
        }
        fail("No required field named " + name + " in " + requiredFields);
    }

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(),
                    () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }
}
```

- [x] 1.2 Run it and confirm it fails for the expected reason (unmatched subcommand, not a compile error or an
      unrelated exception).

Run: `cd publication-exporter && mvn -q -o test -Dtest=WritePublicationContractCliAcceptanceTest 2>&1 | tail -60`

Do not proceed to section 2 until you can see exactly why it fails.

## 2. Extract `FieldRule` + `fieldRules()` from `EssayAdmission` (REFACTOR — stays green throughout)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/FieldRule.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java`

**Interfaces:**
- Produces: `FieldRule.mustEqual(String field, String literalValue)`, `FieldRule.mustMatch(String field, Pattern
  pattern, String patternDescription)`, `FieldRule.nonBlank(String field)`; instance accessors `field()`,
  `kind()` (`FieldRule.Kind.MUST_EQUAL | MUST_MATCH | NON_BLANK`), `literalValue()`, `pattern()`,
  `patternDescription()`. `EssayAdmission.fieldRules()` returns `List<FieldRule>` — section 3's
  `EssayPublicationContract` consumes this directly.

This is data extraction, not a rewrite: `admit()` and its six existing private `require*` methods keep their
exact current bodies. `FieldRule`/`fieldRules()` exist only to be *read* by the contract in section 3 — nothing
in `EssayAdmission` iterates them. Read `EssayAdmission.java` in full first (reproduced below as of this
writing — confirm it still matches before editing; design.md Decision 1 explains why this deliberately does not
generalize `admit()` into a rule-interpreter loop).

- [x] 2.1 Create `FieldRule.java`:

```java
package dev.eugene.publicationexporter.admission;

import java.util.Objects;
import java.util.regex.Pattern;

public final class FieldRule {

    public enum Kind { MUST_EQUAL, MUST_MATCH, NON_BLANK }

    private final String field;
    private final Kind kind;
    private final String literalValue;
    private final Pattern pattern;
    private final String patternDescription;

    private FieldRule(String field, Kind kind, String literalValue, Pattern pattern, String patternDescription) {
        this.field = Objects.requireNonNull(field, "field");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.literalValue = literalValue;
        this.pattern = pattern;
        this.patternDescription = patternDescription;
    }

    public static FieldRule mustEqual(String field, String literalValue) {
        return new FieldRule(field, Kind.MUST_EQUAL, Objects.requireNonNull(literalValue, "literalValue"),
                null, null);
    }

    public static FieldRule mustMatch(String field, Pattern pattern, String patternDescription) {
        return new FieldRule(field, Kind.MUST_MATCH, null,
                Objects.requireNonNull(pattern, "pattern"),
                Objects.requireNonNull(patternDescription, "patternDescription"));
    }

    public static FieldRule nonBlank(String field) {
        return new FieldRule(field, Kind.NON_BLANK, null, null, null);
    }

    public String field() {
        return field;
    }

    public Kind kind() {
        return kind;
    }

    public String literalValue() {
        return literalValue;
    }

    public Pattern pattern() {
        return pattern;
    }

    public String patternDescription() {
        return patternDescription;
    }

    @Override
    public String toString() {
        return "FieldRule[field=" + field + ", kind=" + kind + ", literalValue=" + literalValue
                + ", pattern=" + pattern + ", patternDescription=" + patternDescription + "]";
    }
}
```

- [x] 2.2 Add `FIELD_RULES` and `fieldRules()` to `EssayAdmission.java` — insert immediately after the existing
      `PUBLIC_ID_SLUG`/`REQUIRED_COLLECTION`/`REQUIRED_CONTENT_TYPE` constants, before `admit(...)`. Nothing
      else in the file changes — `admit(...)` and every `require*` method keep calling the same constants they
      call today:

```java
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustEqual("publicCollection", REQUIRED_COLLECTION),
            FieldRule.mustEqual("publicContentType", REQUIRED_CONTENT_TYPE),
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));

    public static List<FieldRule> fieldRules() {
        return FIELD_RULES;
    }
```

  (`java.util.List` is already imported in this file.)

- [x] 2.3 Run the full suite and confirm it is exactly as green as before this change (the section-1 test still
      fails the same way as in step 1.2 — everything else must be unchanged).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 2.4 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/FieldRule.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java
git commit -m "refactor(exporter): extract FieldRule/fieldRules() from EssayAdmission"
```

## 3. Implement the contract package and register the CLI command (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/FieldContract.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/KindContract.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/PublicationContract.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/EssayPublicationContract.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/PublicationContractWriter.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/WritePublicationContractCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`

**Interfaces:**
- Consumes: `EssayAdmission.fieldRules()` → `List<FieldRule>` (section 2).
- Produces: `PublicationContractWriter#write()` → `PublicationContract`; `PublicationContract#kinds()` →
  `List<KindContract>`; `KindContract#requiredFields()` → `List<FieldContract>` — section 4's conformance test
  consumes all three directly.

- [x] 3.1 Create `FieldContract.java` (design.md Decision 2):

```java
package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FieldContract {

    public enum Type { BOOLEAN, STRING }

    private final String name;
    private final Type type;
    private final List<String> allowedValues;
    private final String pattern;
    private final boolean nonBlank;

    private FieldContract(String name, Type type, List<String> allowedValues, String pattern, boolean nonBlank) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
        this.pattern = pattern;
        this.nonBlank = nonBlank;
    }

    public static FieldContract allowedValue(String name, Type type, String literalValue) {
        return new FieldContract(name, type, List.of(Objects.requireNonNull(literalValue, "literalValue")),
                null, false);
    }

    public static FieldContract matchingPattern(String name, String patternText) {
        return new FieldContract(name, Type.STRING, null, Objects.requireNonNull(patternText, "patternText"), false);
    }

    public static FieldContract nonBlank(String name) {
        return new FieldContract(name, Type.STRING, null, null, true);
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("type")
    public Type type() {
        return type;
    }

    @JsonProperty("allowedValues")
    public List<String> allowedValues() {
        return allowedValues;
    }

    @JsonProperty("pattern")
    public String pattern() {
        return pattern;
    }

    @JsonProperty("nonBlank")
    public boolean nonBlank() {
        return nonBlank;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldContract that)) {
            return false;
        }
        return nonBlank == that.nonBlank && name.equals(that.name) && type == that.type
                && Objects.equals(allowedValues, that.allowedValues) && Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, allowedValues, pattern, nonBlank);
    }

    @Override
    public String toString() {
        return "FieldContract[name=" + name + ", type=" + type + ", allowedValues=" + allowedValues
                + ", pattern=" + pattern + ", nonBlank=" + nonBlank + "]";
    }
}
```

- [x] 3.2 Create `KindContract.java`:

```java
package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class KindContract {

    private final String collection;
    private final String contentType;
    private final List<FieldContract> requiredFields;
    private final List<String> structuredBody;

    private KindContract(
            String collection, String contentType, List<FieldContract> requiredFields, List<String> structuredBody) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.requiredFields = List.copyOf(Objects.requireNonNull(requiredFields, "requiredFields"));
        this.structuredBody = List.copyOf(Objects.requireNonNull(structuredBody, "structuredBody"));
    }

    public static KindContract of(
            String collection, String contentType, List<FieldContract> requiredFields, List<String> structuredBody) {
        return new KindContract(collection, contentType, requiredFields, structuredBody);
    }

    @JsonProperty("collection")
    public String collection() {
        return collection;
    }

    @JsonProperty("contentType")
    public String contentType() {
        return contentType;
    }

    @JsonProperty("requiredFields")
    public List<FieldContract> requiredFields() {
        return requiredFields;
    }

    @JsonProperty("structuredBody")
    public List<String> structuredBody() {
        return structuredBody;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KindContract that)) {
            return false;
        }
        return collection.equals(that.collection) && contentType.equals(that.contentType)
                && requiredFields.equals(that.requiredFields) && structuredBody.equals(that.structuredBody);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collection, contentType, requiredFields, structuredBody);
    }

    @Override
    public String toString() {
        return "KindContract[collection=" + collection + ", contentType=" + contentType
                + ", requiredFields=" + requiredFields + ", structuredBody=" + structuredBody + "]";
    }
}
```

- [x] 3.3 Create `PublicationContract.java`:

```java
package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class PublicationContract {

    private final int contractVersion;
    private final List<KindContract> kinds;

    private PublicationContract(int contractVersion, List<KindContract> kinds) {
        this.contractVersion = contractVersion;
        this.kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds"));
    }

    public static PublicationContract of(int contractVersion, List<KindContract> kinds) {
        return new PublicationContract(contractVersion, kinds);
    }

    @JsonProperty("contractVersion")
    public int contractVersion() {
        return contractVersion;
    }

    @JsonProperty("kinds")
    public List<KindContract> kinds() {
        return kinds;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationContract that)) {
            return false;
        }
        return contractVersion == that.contractVersion && kinds.equals(that.kinds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contractVersion, kinds);
    }

    @Override
    public String toString() {
        return "PublicationContract[contractVersion=" + contractVersion + ", kinds=" + kinds + "]";
    }
}
```

- [x] 3.4 Create `EssayPublicationContract.java` — the only place that maps `EssayAdmission.fieldRules()` onto
      `FieldContract`s, plus the one explicit `publish` boolean rule that `FieldRule` doesn't model (design.md
      Decision 2 — `admit()`'s `publish` gate is a guard clause, not a `FieldRule`):

```java
package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.admission.FieldRule;

import java.util.ArrayList;
import java.util.List;

final class EssayPublicationContract {

    private EssayPublicationContract() {
    }

    static KindContract kind() {
        List<FieldContract> requiredFields = new ArrayList<>();
        requiredFields.add(FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"));
        for (FieldRule rule : EssayAdmission.fieldRules()) {
            requiredFields.add(toFieldContract(rule));
        }
        return KindContract.of("blog", "essay", requiredFields, List.of());
    }

    private static FieldContract toFieldContract(FieldRule rule) {
        return switch (rule.kind()) {
            case MUST_EQUAL ->
                    FieldContract.allowedValue(rule.field(), FieldContract.Type.STRING, rule.literalValue());
            case MUST_MATCH -> FieldContract.matchingPattern(rule.field(), rule.pattern().pattern());
            case NON_BLANK -> FieldContract.nonBlank(rule.field());
        };
    }
}
```

  This class is package-private — nothing outside `contract` needs it directly, only `PublicationContractWriter`
  (Elegant Objects 2.1: encapsulate as little as possible, but no further than the actual client set).

- [x] 3.5 Create `PublicationContractWriter.java` — the CLI command's one collaborator:

```java
package dev.eugene.publicationexporter.contract;

import java.util.Comparator;
import java.util.List;

public final class PublicationContractWriter {

    public PublicationContract write() {
        List<KindContract> kinds = List.of(EssayPublicationContract.kind()).stream()
                .sorted(Comparator.comparing(KindContract::collection).thenComparing(KindContract::contentType))
                .toList();
        return PublicationContract.of(1, kinds);
    }
}
```

  The sort is a one-line `Comparator`, not a kind registry (design.md Non-Goals) — it satisfies the spec's
  determinism scenario now, at zero cost, and needs no change when S17 adds kinds.

- [x] 3.6 Create `WritePublicationContractCommand.java` — no `--vault`/`--review`/`--note` options, unlike every
      other command, because the contract depends on none of them; exit code is always `0` since a static
      document has no failure mode:

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.contract.PublicationContract;
import dev.eugene.publicationexporter.contract.PublicationContractWriter;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

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

- [x] 3.7 Register the command in `Main.java`. Read the current file first (reproduced in full below as of this
      writing — confirm it still matches); add `WritePublicationContractCommand.class` to the `subcommands`
      array:

```java
package dev.eugene.publicationexporter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class, InstallToSiteCommand.class, RefreshPublicationQueueCommand.class,
        WritePublicationContractCommand.class })
public final class Main implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        throw new CommandLine.ParameterException(
                new CommandLine(this), "Missing required subcommand");
    }
}
```

- [x] 3.8 Run the full suite and confirm section 1's test now passes and nothing else broke.

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 3.9 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/ \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/WritePublicationContractCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java
git commit -m "feat(exporter): add write-publication-contract command (ADM-06)"
```

## 4. Shared fixture table: parameterize `EssayAdmissionTest`, add `PublicationContractConformanceTest`

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionFixture.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionFixtures.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java`

**Interfaces:**
- Consumes: `EssayAdmission.admit(MarkdownNote)` (unchanged), `PublicationContractWriter#write()` (section 3).
- Produces: `EssayAdmissionFixtures.all()` → `List<EssayAdmissionFixture>`, consumed by both test classes below.

This is the seam design.md calls out as the actual safety net: `EssayAdmission`'s `FIELD_RULES` (section 2) and
`EssayPublicationContract`'s mapping (section 3) restate the same six rules as data: `PublicationContractConformanceTest`
is what proves they didn't drift, by checking every fixture through both readings and requiring agreement — not
by construction.

- [x] 4.1 Create `EssayAdmissionFixture.java` — a fixture carries a full note-source string (this project's
      existing hand-written-frontmatter-block style; there is no YAML writer here to build one from a map, and
      `MarkdownNote.parse(String)` is the one proven reader both test classes below can share):

```java
package dev.eugene.publicationexporter.admission;

import java.util.List;
import java.util.Objects;

public final class EssayAdmissionFixture {

    private final String name;
    private final String noteSource;
    private final boolean expectedAccepted;
    private final List<String> expectedBlockedFields;

    private EssayAdmissionFixture(
            String name, String noteSource, boolean expectedAccepted, List<String> expectedBlockedFields) {
        this.name = Objects.requireNonNull(name, "name");
        this.noteSource = Objects.requireNonNull(noteSource, "noteSource");
        this.expectedAccepted = expectedAccepted;
        this.expectedBlockedFields = List.copyOf(Objects.requireNonNull(expectedBlockedFields, "expectedBlockedFields"));
    }

    public static EssayAdmissionFixture accepted(String name, String noteSource) {
        return new EssayAdmissionFixture(name, noteSource, true, List.of());
    }

    public static EssayAdmissionFixture blocked(String name, String noteSource, List<String> expectedBlockedFields) {
        return new EssayAdmissionFixture(name, noteSource, false, expectedBlockedFields);
    }

    public String name() {
        return name;
    }

    public String noteSource() {
        return noteSource;
    }

    public boolean expectedAccepted() {
        return expectedAccepted;
    }

    public List<String> expectedBlockedFields() {
        return expectedBlockedFields;
    }

    @Override
    public String toString() {
        return name;
    }
}
```

- [x] 4.2 Create `EssayAdmissionFixtures.java`, one fixture per existing `EssayAdmissionTest` case (the accepted
      case plus every blocking condition `EssayAdmission.admit(...)` covers today):

```java
package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class EssayAdmissionFixtures {

    private EssayAdmissionFixtures() {
    }

    public static List<EssayAdmissionFixture> all() {
        return List.of(
                EssayAdmissionFixture.accepted("validEssay", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """),
                EssayAdmissionFixture.blocked("unpublished", """
                        ---
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publish")),
                EssayAdmissionFixture.blocked("wrongCollection", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicCollection", "publicContentType")),
                EssayAdmissionFixture.blocked("wrongContentType", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: claim
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicContentType")),
                EssayAdmissionFixture.blocked("invalidPublicId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: My_Essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicId")),
                EssayAdmissionFixture.blocked("missingSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayAdmissionFixture.blocked("blankSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: "   "
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayAdmissionFixture.blocked("nullSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: null
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayAdmissionFixture.blocked("missingTitle", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        description: A valid description.
                        ---
                        """, List.of("title")),
                EssayAdmissionFixture.blocked("blankDescription", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: "   "
                        ---
                        """, List.of("description")),
                EssayAdmissionFixture.blocked("missingPublicIdAndSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicId", "id")));
    }
}
```

- [x] 4.3 Replace `EssayAdmissionTest.java`'s eleven hand-written test methods with one fixture-driven
      parameterized test plus the one case the fixture shape doesn't carry (the accepted result's full identity/
      title/description fields) as its own small test. Read the current file in full first — this is a
      same-behaviour refactor: every fixture in `EssayAdmissionFixtures.all()` must produce the identical
      accept/reject-with-fields verdict its matching original test asserted.

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssayAdmissionTest {

    private final EssayAdmission admission = new EssayAdmission();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.EssayAdmissionFixtures#all")
    void admitsOrBlocksPerFixture(EssayAdmissionFixture fixture) {
        MarkdownNote frontmatter = MarkdownNote.parse(fixture.noteSource());

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(fixture.expectedBlockedFields(), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validEssayResultCarriesIdentityAndFields() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "essay", "my-essay"), result.identity());
        assertEquals("8f2c-my-essay", result.sourceId());
        assertEquals("My Essay", result.title());
        assertEquals("A valid description.", result.description());
    }

    private List<String> blockedFields(EssayAdmission.Result result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
```

- [x] 4.4 Run the admission tests alone and confirm all twelve pass (eleven fixtures + the identity test), with
      no change to what `EssayAdmission` itself does.

Run: `cd publication-exporter && mvn -q -o test -Dtest=EssayAdmissionTest 2>&1 | tail -60`

- [x] 4.5 Create `PublicationContractConformanceTest.java` — the ADM-06 "validator and published contract
      disagree" scenario, made concrete. It does not call `EssayAdmission.admit(...)` and compare to the
      contract by construction: it independently interprets `PublicationContract`'s `FieldContract` rules
      against the same parsed note, then asserts three-way agreement (fixture's own label, the contract's
      verdict, and `EssayAdmission`'s real verdict):

```java
package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.admission.EssayAdmissionFixture;
import dev.eugene.publicationexporter.admission.EssayAdmissionFixtures;
import dev.eugene.publicationexporter.note.MarkdownNote;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicationContractConformanceTest {

    private final EssayAdmission admission = new EssayAdmission();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.EssayAdmissionFixtures#all")
    void contractVerdictAgreesWithFixtureAndRuntimeValidator(EssayAdmissionFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract essayKind = new PublicationContractWriter().write().kinds().get(0);

        boolean contractAccepts = contractAccepts(essayKind, note);
        boolean runtimeAccepts = admission.admit(note).accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    private boolean contractAccepts(KindContract kind, MarkdownNote note) {
        for (FieldContract field : kind.requiredFields()) {
            if (!fieldSatisfied(field, note)) {
                return false;
            }
        }
        return true;
    }

    private boolean fieldSatisfied(FieldContract field, MarkdownNote note) {
        if (field.type() == FieldContract.Type.BOOLEAN) {
            return field.allowedValues().contains(String.valueOf(note.flag(field.name())));
        }
        return note.string(field.name()).map(value -> stringFieldSatisfied(field, value)).orElse(false);
    }

    private boolean stringFieldSatisfied(FieldContract field, String value) {
        if (field.nonBlank()) {
            return !value.isBlank();
        }
        if (field.pattern() != null) {
            return Pattern.compile(field.pattern()).matcher(value).matches();
        }
        return field.allowedValues().contains(value);
    }
}
```

  Note on scope: this proves the *overall accept/reject verdict* agrees for every fixture, not that per-field
  diagnostics match — ADM-06's scenario is phrased as the exporter edition failing acceptance on disagreement,
  not as diagnostic-text equality (spec.md). The `wrongCollection` fixture is the interesting case: the
  contract's `publicContentType` field is checked independently of `publicCollection` (it doesn't model
  `EssayAdmission`'s cross-field "content type only means something once the collection is valid" dependency),
  but the fixture still correctly resolves to `false` on both sides because `publicCollection` itself already
  fails independently — walk through this fixture by hand before moving on if the loop's short-circuit isn't
  immediately obvious.

- [x] 4.6 Run the full suite.

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 4.7 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionFixture.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionFixtures.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java
git commit -m "test(exporter): share EssayAdmissionFixtures between EssayAdmissionTest and PublicationContractConformanceTest"
```

## 5. Whole-suite verification and graph refresh

- [x] 5.1 Run the complete `publication-exporter` suite one more time from a clean state and confirm it is
      green end to end (not just the files touched this slice).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -150`

- [x] 5.2 Refresh the graphify index (project rule: after modifying code, run `graphify update .` to keep the
      graph current — AST-only, no API cost).

Run: `cd /Users/eugene/Dev/personal-site && graphify update .`

- [x] 5.3 Confirm `git status` shows only the files this slice touched (no stray changes to `exporter-java/`,
      `obsidian-plugin/`, `bridge-contract/schema-v2.json`, or any file outside `publication-exporter/` and
      `openspec/changes/2026-08-11-s15-publication-contract/`).

Run: `git status --porcelain=v1`
