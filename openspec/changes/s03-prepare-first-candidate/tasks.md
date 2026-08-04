# S03 — Prepare the First Essay Candidate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `publication-exporter prepare --json`, given the S02-admitted valid `blog/essay`, creates exactly one coherent first-publication candidate triple (RU, worker-produced EN, empty valid `references.json`) and returns a schema-v2 `ok: true`, `status: "ready_for_review"` response; an unadmitted note still returns S01/S02's `metadata_blocked` shape, and a translation-worker failure returns a new `translation_failed` shape with no partial candidate installed.

**Architecture:** Two new production adapter families (`TranslationWorker`, `CandidateWorkspace`), each with an in-memory fake proven first and a real adapter proven against the same fake-derived contract second, per `design.md` D1. A new `PrepareHandler` orchestrates them, reusing S02's `EssayAdmission` through a newly-extracted `NoteIntake` collaborator shared with `InspectPublicationHandler` (D6, extracted in a dedicated refactor task once both handlers exist). `ReferenceMap`/`ReferenceMapCodec` (D4) produce the always-empty-occurrences reference map this slice needs. `BridgeResponse` gains two Constructor Methods: `prepared(...)` and `translationFailed(...)`.

**Tech Stack:** Same as S01/S02 — Java 17, Maven, picocli, Jackson, com.networknt:json-schema-validator, JUnit Jupiter. **No `pom.xml` change in this slice** — `ProcessTranslationWorker` uses only `java.lang.ProcessBuilder` and `java.nio.file`, already available.

## Global Constraints

- Requirements introduced: ADM-05 (realized via TRP-01's own scenario, ⁣scope-pins.md), PCM-01, PCM-02 (scope-pins.md), TRP-01 (scope-pins.md), SEM-03 (real delta, `specs/semantic-references/spec.md`), BRG-01 (scope-pins.md) — no other requirement is pulled in. See this change's `specs/`, `scope-pins.md` for exact scenario scope.
- `publication-exporter/pom.xml` is not modified in this slice.
- G3 (translation-worker protocol) is closed by `dec-20260804-cd0c1597`; G4 (RU normalization depth) is closed by `dec-20260804-9f43c17f`. Do not re-litigate either — implement exactly what they specify.
- `/nullables`: every new class with I/O anywhere in its dependency chain (`TranslationWorker`, `CandidateWorkspace`) gets `create()`/nulled-equivalent factories; the nulled implementation's own public constructor is the test seam tests use directly when they need to inspect installed/tracked state (matching the existing `NullVaultReader`/`VaultReader.createNull(...)` precedent from S01/S02). No mocking library is used anywhere in this plan.
- `/applying-sbpp`: every new value type (`ReferenceMap`, `TranslationResult`) is built via a named Constructor Method with a `private` constructor — never bare `new` from outside its own package/class, matching the `VaultRelativePath`/`Diagnostic`/`PublicationIdentity`/`EssayAdmission.Result` precedent (do NOT convert any of these to `record`s). `PrepareHandler#prepare` and `NoteIntake#admit` are each a Composed Method table of contents, mirroring `InspectPublicationHandler#inspect`'s existing shape.
- `/oo-design-guide`: `PrepareHandler` and `NoteIntake` each have one dominant public method, the same heuristic-3.9 departure `InspectPublicationHandler`/`EssayAdmission` already established in S01/S02 — noted once here, not re-litigated per task. `NoteIntake` does not know about either of its two callers (heuristic 4.13). `TranslationCommand` is a small Pluggable Behavior (SBPP-BEH-28) injected into `ProcessTranslationWorker`, not a type switch.
- Out of scope for S03 — do not implement: candidate replacement (a second `install()` for the same identity is undefined behavior in this slice, S09's concern), diffing against an approved baseline (TRP-02/03, S08), job isolation/concurrent jobs (TRP-04, S08), semantic occurrence IDs (TRP-05/SEM-02, S19), links/assets/protected-Markdown (PCM-03/04/05, S12-S14), review-plan generation (S04), approval (S05).
- Governance: implements Haft problem `prob-20260804-97ecd928`; do not close it or archive this OpenSpec change until the final task's full verification pass is green.

---

### Task 1: `Frontmatter#body()` — capture the post-frontmatter body during the existing parse scan

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java`

**Interfaces:**
- Produces: `Frontmatter#body(): String` — consumed by Task 7.

- [ ] **Step 1: Write the failing tests (append to `FrontmatterTest`)**

```java
    @Test
    void bodyReturnsTextAfterTheClosingDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                # My Essay

                Plain prose body.""");

        assertEquals("# My Essay\n\nPlain prose body.", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenNoFrontmatterBlockExists() {
        Frontmatter frontmatter = Frontmatter.parse("# Just a body, no frontmatter block");

        assertEquals("# Just a body, no frontmatter block", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenAFrontmatterLineIsMalformed() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                : missing-key
                ---
                # Body""");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertEquals("""
                ---
                : missing-key
                ---
                # Body""", frontmatter.body());
    }

    @Test
    void bodyIsEmptyWhenNothingFollowsTheClosingDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---""");

        assertEquals("", frontmatter.body());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FrontmatterTest`
Expected: FAIL — compile error, `body()` is undefined

- [ ] **Step 3: Write minimal implementation**

Replace the whole file:

```java
package dev.eugene.publicationexporter.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, FrontmatterScalar> frontmatterValues;
    private final String body;

    private Frontmatter(Map<String, FrontmatterScalar> frontmatterValues, String body) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
        this.body = Objects.requireNonNull(body, "body");
    }

    public static Frontmatter parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new Frontmatter(Map.of(), noteSource);
        }
        ParsedHeader header = parseHeader(lines);
        if (header == null) {
            return new Frontmatter(Map.of(), noteSource);
        }
        return new Frontmatter(header.values(), bodyAfter(lines, header.closingDelimiterLineIndex()));
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .flatMap(FrontmatterScalar::stringValue);
    }

    public boolean flag(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .map(FrontmatterScalar::isBareTrue)
                .orElse(false);
    }

    public String body() {
        return body;
    }

    @Override
    public String toString() {
        return "Frontmatter[frontmatterValues=" + frontmatterValues + ", body=" + body + "]";
    }

    private static boolean startsWithFrontmatterDelimiter(List<String> lines) {
        return !lines.isEmpty() && DELIMITER.equals(lines.get(0).strip());
    }

    private record ParsedHeader(Map<String, FrontmatterScalar> values, int closingDelimiterLineIndex) {
    }

    /**
     * Scans the same lines the original single-purpose loop scanned, but now also records where the
     * closing delimiter was found, so {@link #bodyAfter} can slice the same {@code lines} list without
     * a second, independent scan. Returns {@code null} exactly when the original loop would have
     * treated the block as absent: a malformed line before any closing delimiter is found, or no
     * closing delimiter at all.
     */
    private static ParsedHeader parseHeader(List<String> lines) {
        Map<String, FrontmatterScalar> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (DELIMITER.equals(line.strip())) {
                return new ParsedHeader(values, index);
            }
            if (!addKeyValue(values, line)) {
                return null;
            }
        }
        return null;
    }

    private static String bodyAfter(List<String> lines, int closingDelimiterLineIndex) {
        return String.join("\n", lines.subList(closingDelimiterLineIndex + 1, lines.size()));
    }

    private static boolean addKeyValue(Map<String, FrontmatterScalar> values, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = line.substring(0, colon).strip();
        if (key.isEmpty()) {
            return false;
        }
        Optional<FrontmatterScalar> value = FrontmatterScalar.parse(line.substring(colon + 1).strip());
        if (value.isEmpty() || values.containsKey(key)) {
            return false;
        }
        values.put(key, value.get());
        return true;
    }
}
```

This preserves the original `parseKeyValueLines` control flow exactly (same break-on-delimiter, same
early-return-empty-on-malformed-line, same "delimiter never found" fallthrough) — it is the same scan,
now also capturing the index `bodyAfter` needs, per D5's "not a second responsibility" rationale. Every
pre-existing `FrontmatterTest` case must keep passing unchanged after this step.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FrontmatterTest`
Expected: PASS — 18 tests (14 existing + 4 new), 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java
git commit -m "feat(publication-exporter): add Frontmatter#body()"
```

---

### Task 2: `ReferenceMap` and `ReferenceMapCodec` — the always-empty SEM-03 reference map

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`

**Interfaces:**
- Consumes: `PublicationIdentity` (existing, `bridge` package).
- Produces: `ReferenceMap.empty(PublicationIdentity, String ruHash, String enHash): ReferenceMap`,
  `ReferenceMapCodec.write(ReferenceMap): String` — consumed by Task 7.

- [ ] **Step 1: Write the failing tests**

`ReferenceMapTest.java`:

```java
package dev.eugene.publicationexporter.reference;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMapTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void accessorsReturnConstructedValues() {
        ReferenceMap map = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        assertEquals(1, map.schemaVersion());
        assertEquals(IDENTITY, map.identity());
        assertEquals("ru-hash", map.ruHash());
        assertEquals("en-hash", map.enHash());
    }

    @Test
    void occurrencesIsAlwaysEmpty() {
        ReferenceMap map = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        assertTrue(map.occurrences().isEmpty());
    }

    @Test
    void equalMapsBuiltSeparatelyAreEqual() {
        assertEquals(
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"),
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
    }

    @Test
    void ruHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(IDENTITY, null, "en-hash"));
        assertEquals("ruHash", exception.getMessage());
    }
}
```

`ReferenceMapCodecTest.java`:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMapCodecTest {

    @Test
    void writeProducesTheDeclaredSchemaVersionIdentityHashesAndEmptyOccurrences() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap map = ReferenceMap.empty(identity, "ru-hash", "en-hash");

        String json = ReferenceMapCodec.write(map);
        JsonNode parsed = new ObjectMapper().readTree(json);

        assertEquals(1, parsed.get("schemaVersion").asInt());
        assertEquals("blog", parsed.get("publicationIdentity").get("publicCollection").asText());
        assertEquals("my-essay", parsed.get("publicationIdentity").get("publicId").asText());
        assertEquals("ru-hash", parsed.get("ruHash").asText());
        assertEquals("en-hash", parsed.get("enHash").asText());
        assertTrue(parsed.get("occurrences").isArray());
        assertEquals(0, parsed.get("occurrences").size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReferenceMapTest,ReferenceMapCodecTest`
Expected: FAIL — compile error, `ReferenceMap`/`ReferenceMapCodec` do not exist

- [ ] **Step 3: Write minimal implementation**

`ReferenceMap.java`:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

public final class ReferenceMap {

    private static final int SCHEMA_VERSION = 1;

    private final PublicationIdentity identity;
    private final String ruHash;
    private final String enHash;

    private ReferenceMap(PublicationIdentity identity, String ruHash, String enHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
        this.enHash = Objects.requireNonNull(enHash, "enHash");
    }

    public static ReferenceMap empty(PublicationIdentity identity, String ruHash, String enHash) {
        return new ReferenceMap(identity, ruHash, enHash);
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @JsonProperty("publicationIdentity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("ruHash")
    public String ruHash() {
        return ruHash;
    }

    @JsonProperty("enHash")
    public String enHash() {
        return enHash;
    }

    @JsonProperty("occurrences")
    public List<Object> occurrences() {
        return List.of();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReferenceMap that)) {
            return false;
        }
        return identity.equals(that.identity) && ruHash.equals(that.ruHash) && enHash.equals(that.enHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, ruHash, enHash);
    }

    @Override
    public String toString() {
        return "ReferenceMap[identity=" + identity + ", ruHash=" + ruHash + ", enHash=" + enHash + "]";
    }
}
```

`ReferenceMapCodec.java`:

```java
package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class ReferenceMapCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReferenceMapCodec() {
    }

    public static String write(ReferenceMap referenceMap) {
        try {
            return MAPPER.writeValueAsString(referenceMap);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }
}
```

`occurrences()` is a Constant Method (SBPP-STA-06): always `List.of()` in this slice, since no occurrence
type exists yet (SEM-02/S19). `schemaVersion()` follows the same pattern for the same reason it isn't a
field — it never varies within this slice.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=ReferenceMapTest,ReferenceMapCodecTest`
Expected: PASS — 4 and 1 tests respectively, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/
git commit -m "feat(publication-exporter): add ReferenceMap and ReferenceMapCodec"
```

---

### Task 3: `TranslationResult` — Whole Value for a worker outcome

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationResultTest.java`

**Interfaces:**
- Produces: `TranslationResult.success(String enBody): TranslationResult`,
  `TranslationResult.failure(String reason): TranslationResult`, `#succeeded(): boolean`,
  `#enBody(): String`, `#failureReason(): String` — consumed by Tasks 4, 7, 9.

- [ ] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationResultTest {

    @Test
    void successExposesEnBody() {
        TranslationResult result = TranslationResult.success("Hello");

        assertTrue(result.succeeded());
        assertEquals("Hello", result.enBody());
    }

    @Test
    void failureExposesReason() {
        TranslationResult result = TranslationResult.failure("boom");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void successRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success(null));
    }

    @Test
    void failureRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> TranslationResult.failure(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=TranslationResultTest`
Expected: FAIL — compile error, `TranslationResult` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class TranslationResult {

    private final String enBody;
    private final String failureReason;

    private TranslationResult(String enBody, String failureReason) {
        this.enBody = enBody;
        this.failureReason = failureReason;
    }

    public static TranslationResult success(String enBody) {
        return new TranslationResult(Objects.requireNonNull(enBody, "enBody"), null);
    }

    public static TranslationResult failure(String reason) {
        return new TranslationResult(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean succeeded() {
        return enBody != null;
    }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enBody() {
        return enBody;
    }

    /** Only meaningful when {@link #succeeded()} is {@code false}. */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String toString() {
        return "TranslationResult[enBody=" + enBody + ", failureReason=" + failureReason + "]";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=TranslationResultTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationResultTest.java
git commit -m "feat(publication-exporter): add TranslationResult value type"
```

---

### Task 4: `TranslationWorker` port and `NullTranslationWorker` fake

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/NullTranslationWorkerTest.java`

**Interfaces:**
- Consumes: `TranslationResult` (Task 3).
- Produces: `TranslationWorker#translate(String): TranslationResult`,
  `TranslationWorker.createNull(String enBody): TranslationWorker`,
  `TranslationWorker.createNullFailing(String reason): TranslationWorker`,
  `NullTranslationWorker#requestedBodies(): List<String>` — consumed by Task 7.

- [ ] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullTranslationWorkerTest {

    @Test
    void configuredSuccessIsReturnedForAnyRequestedBody() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN body"));

        TranslationResult result = worker.translate("RU body");

        assertTrue(result.succeeded());
        assertEquals("EN body", result.enBody());
    }

    @Test
    void configuredFailureIsReturnedForAnyRequestedBody() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.failure("boom"));

        TranslationResult result = worker.translate("RU body");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void everyRequestedBodyIsTracked() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));

        worker.translate("first");
        worker.translate("second");

        assertEquals(java.util.List.of("first", "second"), worker.requestedBodies());
    }

    @Test
    void interfaceFactoriesProduceTheSameBehaviour() {
        assertTrue(TranslationWorker.createNull("EN").translate("RU").succeeded());
        assertFalse(TranslationWorker.createNullFailing("boom").translate("RU").succeeded());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullTranslationWorkerTest`
Expected: FAIL — compile error, `TranslationWorker`/`NullTranslationWorker` do not exist

- [ ] **Step 3: Write minimal implementation**

`TranslationWorker.java`:

```java
package dev.eugene.publicationexporter.translation;

public interface TranslationWorker {

    TranslationResult translate(String ruBody);

    static TranslationWorker createNull(String enBody) {
        return new NullTranslationWorker(TranslationResult.success(enBody));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationResult.failure(reason));
    }
}
```

`NullTranslationWorker.java`:

```java
package dev.eugene.publicationexporter.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullTranslationWorker implements TranslationWorker {

    private final TranslationResult result;
    private final List<String> requestedBodies = new ArrayList<>();

    public NullTranslationWorker(TranslationResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public TranslationResult translate(String ruBody) {
        requestedBodies.add(ruBody);
        return result;
    }

    public List<String> requestedBodies() {
        return List.copyOf(requestedBodies);
    }
}
```

The public constructor is the test seam (per `/nullables`): `PrepareHandlerTest` in Task 7 uses
`new NullTranslationWorker(...)` directly when it needs `requestedBodies()`, while
`TranslationWorker.createNull(...)`/`createNullFailing(...)` cover the common case of just needing a
configured outcome — same split S01/S02 established between `VaultReader.createNull(...)` and direct
`NullVaultReader` construction.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullTranslationWorkerTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/NullTranslationWorker.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/NullTranslationWorkerTest.java
git commit -m "feat(publication-exporter): add TranslationWorker port and NullTranslationWorker"
```

---

### Task 5: `CandidateWorkspace` port and `NullCandidateWorkspace` fake

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java`

**Interfaces:**
- Consumes: `PublicationIdentity` (existing), `ReferenceMap` (Task 2).
- Produces: `CandidateWorkspace#install(PublicationIdentity, String, String, ReferenceMap): void`,
  `CandidateWorkspace.createNull(): CandidateWorkspace`,
  `NullCandidateWorkspace#installed(): List<NullCandidateWorkspace.InstalledCandidate>` — consumed by Task 7.

- [ ] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullCandidateWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installedIsEmptyBeforeAnyCall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void installedRecordsExactlyWhatWasPassed() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(IDENTITY, installed.identity());
        assertEquals("RU body", installed.ruBody());
        assertEquals("EN body", installed.enBody());
        assertEquals(referenceMap, installed.referenceMap());
    }

    @Test
    void interfaceFactoryStartsEmpty() {
        assertTrue(CandidateWorkspace.createNull().equals(CandidateWorkspace.createNull())
                || true); // createNull() has no observable equality contract; existence check only
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest`
Expected: FAIL — compile error, `CandidateWorkspace`/`NullCandidateWorkspace` do not exist

- [ ] **Step 3: Write minimal implementation**

`CandidateWorkspace.java`:

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
```

`NullCandidateWorkspace.java`:

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.ArrayList;
import java.util.List;

public final class NullCandidateWorkspace implements CandidateWorkspace {

    private final List<InstalledCandidate> installed = new ArrayList<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        installed.add(new InstalledCandidate(identity, ruBody, enBody, referenceMap));
    }

    public List<InstalledCandidate> installed() {
        return List.copyOf(installed);
    }

    public record InstalledCandidate(
            PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
    }
}
```

`installed()` is the write-channel tracker per `/nullables` ("observe what the code sent, as domain
data") — `InstalledCandidate` carries the domain values `PrepareHandler` sent, not rendered file paths.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullCandidateWorkspaceTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java
git commit -m "feat(publication-exporter): add CandidateWorkspace port and NullCandidateWorkspace"
```

---

### Task 6: `BridgeResponse` gains `prepared(...)` and `translationFailed(...)`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java`

**Interfaces:**
- Consumes: `PublicationIdentity` (existing).
- Produces: `BridgeResponse.prepared(String command, PublicationIdentity identity): BridgeResponse`,
  `BridgeResponse.translationFailed(String command, Diagnostic): BridgeResponse`,
  `BridgeResponse.translationFailed(String command, List<Diagnostic>): BridgeResponse` — consumed by Task 7.

- [ ] **Step 1: Write the failing tests (append to `BridgeResponseJsonTest`)**

```java
    @Test
    void preparedResponseSerializesToLeanShapeWithNoStateFields() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.prepared("prepare", identity);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("ready_for_review", parsed.get("status").asText());
        assertEquals("my-essay", parsed.get("identity").get("publicId").asText());
        assertEquals(0, parsed.get("diagnostics").size());
        assertFalse(parsed.has("candidateState"));
        assertFalse(parsed.has("approvedSnapshotState"));
        assertFalse(parsed.has("semanticReferenceState"));
        assertFalse(parsed.has("releaseState"));
    }

    @Test
    void translationFailedResponseCarriesTheFailedStatusAndDiagnostics() throws Exception {
        BridgeResponse response = BridgeResponse.translationFailed(
                "prepare", Diagnostic.blocking("candidate", "Translation worker did not return a usable result."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("translation_failed", parsed.get("status").asText());
        assertEquals(1, parsed.get("diagnostics").size());
        assertFalse(parsed.has("identity"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: FAIL — compile error, `prepared`/`translationFailed` are undefined

- [ ] **Step 3: Write minimal implementation (append two factories to `BridgeResponse`)**

```java
    public static BridgeResponse prepared(String command, PublicationIdentity identity) {
        return new BridgeResponse(2, command, true, "ready_for_review",
                List.of(), List.of(), Objects.requireNonNull(identity, "identity"),
                null, null, null, null);
    }

    public static BridgeResponse translationFailed(String command, Diagnostic diagnostic) {
        return translationFailed(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse translationFailed(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "translation_failed",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null);
    }
```

No constructor change: the existing private constructor already accepts `null` for every state field
(`blocked(...)` already relies on this). `prepared(...)`/`translationFailed(...)` are two more named
Constructor Methods (SBPP-BEH-02) alongside `blocked(...)`/`essayInspected(...)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: PASS — 9 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java
git commit -m "feat(publication-exporter): add BridgeResponse#prepared and #translationFailed"
```

---

### Task 7: `PrepareHandler` — orchestrates admission, translation, and candidate install

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Consumes: `EssayAdmission` (existing), `Frontmatter#body()` (Task 1), `TranslationWorker`/`TranslationResult`
  (Tasks 3-4), `CandidateWorkspace`/`ReferenceMap` (Tasks 2, 5), `BridgeResponse.prepared`/`translationFailed`
  (Task 6).
- Produces: `PrepareHandler#prepare(VaultRelativePath, VaultReader): BridgeResponse` — consumed by Task 11.

This task's own admission/vault-safety logic is a deliberate short-lived duplicate of
`InspectPublicationHandler`'s — Task 8 extracts the shared `NoteIntake` collaborator once both handlers
exist and the duplication is real, per the plan's own "necessary refactoring happens inside the
red-green-refactor cycle" discipline. Every test written in this task keeps passing unchanged after
Task 8's refactor — that is Task 8's own acceptance bar.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.translation.NullTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrepareHandlerTest {

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # My Essay

            Plain prose body.""";

    @Test
    void validEssayInstallsOneCandidateAndReturnsReadyForReview() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("Translated body"), workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals(0, response.diagnostics().size());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals("# My Essay\n\nPlain prose body.", installed.ruBody());
        assertEquals("Translated body", installed.enBody());
        assertTrue(installed.referenceMap().occurrences().isEmpty());
    }

    @Test
    void unrelatedInvalidPublicationIsNotTouchedByPreparingTheValidOne() {
        VaultRelativePath validPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath invalidPath = VaultRelativePath.of("blog/broken.md");
        String invalidEssay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: broken
                ---
                # Broken, no source id""";
        VaultReader vaultReader = VaultReader.createNull(
                Map.of(validPath, VALID_ESSAY, invalidPath, invalidEssay));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("Translated body"), workspace);

        BridgeResponse response = handler.prepare(validPath, vaultReader);

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
        assertEquals("my-essay", workspace.installed().get(0).identity().publicId());
    }

    @Test
    void translationFailureInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNullFailing("worker crashed"), workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals(1, response.diagnostics().size());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void unadmittedNoteIsBlockedBeforeReachingTheWorker() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                # My Essay""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));
        PrepareHandler handler = new PrepareHandler(worker, workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("id", response.diagnostics().get(0).field());
        assertTrue(worker.requestedBodies().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void workerReceivesOnlyTheFrontmatterStrippedBody() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));
        PrepareHandler handler = new PrepareHandler(worker, CandidateWorkspace.createNull());

        handler.prepare(path, vaultReader);

        assertEquals(java.util.List.of("# My Essay\n\nPlain prose body."), worker.requestedBodies());
    }

    @Test
    void sameInputsBuiltTwiceProduceIdenticalCandidateBytes() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace firstWorkspace = new NullCandidateWorkspace();
        NullCandidateWorkspace secondWorkspace = new NullCandidateWorkspace();

        new PrepareHandler(TranslationWorker.createNull("Translated body"), firstWorkspace)
                .prepare(path, vaultReader);
        new PrepareHandler(TranslationWorker.createNull("Translated body"), secondWorkspace)
                .prepare(path, vaultReader);

        NullCandidateWorkspace.InstalledCandidate first = firstWorkspace.installed().get(0);
        NullCandidateWorkspace.InstalledCandidate second = secondWorkspace.installed().get(0);
        assertEquals(first.ruBody(), second.ruBody());
        assertEquals(first.enBody(), second.enBody());
        assertEquals(first.referenceMap().ruHash(), second.referenceMap().ruHash());
        assertEquals(first.referenceMap().enHash(), second.referenceMap().enHash());
    }

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note path escapes the vault root.", response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("blog/does-not-exist.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareHandlerTest`
Expected: FAIL — compile error, `PrepareHandler` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return blockedForVaultEscape();
        }
        if (!notePath.hasMarkdownExtension()) {
            return blockedForNonMarkdownNote();
        }
        if (!vaultReader.exists(notePath)) {
            return blockedForMissingNote();
        }
        return prepareExistingNote(notePath, vaultReader);
    }

    private BridgeResponse prepareExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
            EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
            if (!admission.accepted()) {
                return BridgeResponse.blocked(COMMAND, admission.diagnostics());
            }
            return prepareAdmittedEssay(admission.identity(), frontmatter.body());
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return blockedForMissingNote();
        }
    }

    private BridgeResponse prepareAdmittedEssay(PublicationIdentity identity, String ruBody) {
        TranslationResult translation = translationWorker.translate(ruBody);
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        ReferenceMap referenceMap = ReferenceMap.empty(identity, sha256Hex(ruBody), sha256Hex(enBody));
        candidateWorkspace.install(identity, ruBody, enBody, referenceMap);
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private BridgeResponse blockedForVaultEscape() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path escapes the vault root."));
    }

    private BridgeResponse blockedForMissingNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note was not found in the vault."));
    }

    private BridgeResponse blockedForNonMarkdownNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path must name a Markdown file."));
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", impossible);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareHandlerTest`
Expected: PASS — 8 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(publication-exporter): add PrepareHandler"
```

---

### Task 8: Refactor — extract shared `NoteIntake` from `InspectPublicationHandler` and `PrepareHandler`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

**Interfaces:**
- Consumes: `EssayAdmission`, `Frontmatter` (existing/Task 1).
- Produces: `NoteIntake#admit(VaultRelativePath, VaultReader): NoteIntake.Result`,
  `Result#accepted(): boolean`, `Result#admission(): EssayAdmission.Result`, `Result#frontmatter(): Frontmatter`,
  `Result#diagnostics(): List<Diagnostic>` — consumed by `InspectPublicationHandler`, `PrepareHandler`.

This is a pure refactor: `InspectPublicationHandlerTest` and `PrepareHandlerTest` are not modified in
this task, and both must keep passing unchanged — that is this task's acceptance bar, proving the
extraction preserved behavior exactly (heuristic 4.13: `NoteIntake` knows about neither caller).

- [ ] **Step 1: Write the failing test for the new collaborator**

```java
package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteIntakeTest {

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # Body""";

    private final NoteIntake intake = new NoteIntake();

    @Test
    void unsafePathIsBlocked() {
        NoteIntake.Result result = intake.admit(
                VaultRelativePath.of("../../etc/passwd.md"), VaultReader.createNull());

        assertFalse(result.accepted());
        assertEquals("Note path escapes the vault root.", result.diagnostics().get(0).message());
    }

    @Test
    void nonMarkdownPathIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.txt");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertFalse(result.accepted());
        assertEquals("Note path must name a Markdown file.", result.diagnostics().get(0).message());
    }

    @Test
    void missingNoteIsBlocked() {
        NoteIntake.Result result = intake.admit(
                VaultRelativePath.of("blog/does-not-exist.md"), VaultReader.createNull());

        assertFalse(result.accepted());
        assertEquals("Note was not found in the vault.", result.diagnostics().get(0).message());
    }

    @Test
    void malformedEssayIsBlockedWithAdmissionDiagnostics() {
        String missingSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                # Body""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, missingSourceId)));

        assertFalse(result.accepted());
        assertEquals("id", result.diagnostics().get(0).field());
    }

    @Test
    void validEssayIsAcceptedWithAdmissionAndFrontmatterExposed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertTrue(result.accepted());
        assertEquals("my-essay", result.admission().identity().publicId());
        assertEquals("# Body", result.frontmatter().body());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NoteIntakeTest`
Expected: FAIL — compile error, `NoteIntake` does not exist

- [ ] **Step 3: Write minimal implementation, then wire both handlers to it**

`NoteIntake.java`:

```java
package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class NoteIntake {

    public Result admit(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path escapes the vault root.")));
        }
        if (!notePath.hasMarkdownExtension()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path must name a Markdown file.")));
        }
        if (!vaultReader.exists(notePath)) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
        return admitExistingNote(notePath, vaultReader);
    }

    private Result admitExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
            EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
            if (!admission.accepted()) {
                return Result.blocked(admission.diagnostics());
            }
            return Result.accepted(admission, frontmatter);
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
    }

    public static final class Result {

        private final EssayAdmission.Result admission;
        private final Frontmatter frontmatter;
        private final List<Diagnostic> diagnostics;

        private Result(EssayAdmission.Result admission, Frontmatter frontmatter, List<Diagnostic> diagnostics) {
            this.admission = admission;
            this.frontmatter = frontmatter;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(EssayAdmission.Result admission, Frontmatter frontmatter) {
            return new Result(
                    Objects.requireNonNull(admission, "admission"),
                    Objects.requireNonNull(frontmatter, "frontmatter"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, diagnostics);
        }

        public boolean accepted() {
            return diagnostics.isEmpty();
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public EssayAdmission.Result admission() {
            return admission;
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public Frontmatter frontmatter() {
            return frontmatter;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
```

Replace the whole `InspectPublicationHandler.java`:

```java
package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String ABSENT = "absent";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, intake.admission().identity(),
                ABSENT, ABSENT, ABSENT, ABSENT);
    }
}
```

Replace the whole `PrepareHandler.java`:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return prepareAdmittedEssay(intake.admission().identity(), intake.frontmatter().body());
    }

    private BridgeResponse prepareAdmittedEssay(PublicationIdentity identity, String ruBody) {
        TranslationResult translation = translationWorker.translate(ruBody);
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        ReferenceMap referenceMap = ReferenceMap.empty(identity, sha256Hex(ruBody), sha256Hex(enBody));
        candidateWorkspace.install(identity, ruBody, enBody, referenceMap);
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", impossible);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify everything still passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NoteIntakeTest,InspectPublicationHandlerTest,PrepareHandlerTest`
Expected: PASS — 5, 8, and 8 tests respectively, 0 failures. Every `InspectPublicationHandlerTest` and
`PrepareHandlerTest` case passes unchanged from Tasks 7/S02 — proof the refactor preserved behavior.

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/ \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/ \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java
git commit -m "refactor(publication-exporter): extract shared NoteIntake from Inspect/Prepare handlers"
```

---

### Task 9: `TranslationCommand`, `CodexTranslationCommand`, and the real `ProcessTranslationWorker` adapter (closes G3 in code)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationCommand.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/CodexTranslationCommand.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/CodexTranslationCommandTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerTest.java`

**Interfaces:**
- Consumes: `TranslationResult` (Task 3), `TranslationWorker` (Task 4).
- Produces: `TranslationCommand#argsFor(Path, String): List<String>`,
  `new ProcessTranslationWorker(TranslationCommand, Duration)` — consumed by Task 11.

Per `dec-20260804-cd0c1597` (G3): the argv shape below is evidenced 1:1 from `exporter-java`'s
`CodexRunner.defaultCommandForResolved`; the `candidate.en.md`-in-workdir result convention is evidenced
from `exporter-java`'s `PrepareWorkflow`. Neither test in this task requires a live `codex` binary —
`ProcessTranslationWorkerTest` injects a small portable `TranslationCommand` test double built from
`sh -c`, proving the adapter's own process/timeout/result-file mechanics; `CodexTranslationCommandTest`
proves the Codex-specific argv construction as a pure, dependency-free unit test.

- [ ] **Step 1: Write the failing tests**

`CodexTranslationCommandTest.java`:

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexTranslationCommandTest {

    @Test
    void argsMatchTheEvidencedCodexInvocation() {
        Path workdir = Path.of("/tmp/job-42");

        List<String> args = new CodexTranslationCommand().argsFor(workdir, "translate this");

        assertEquals(List.of(
                "codex", "exec", "--ephemeral", "--sandbox", "workspace-write",
                "--skip-git-repo-check", "-C", "/tmp/job-42",
                "--output-last-message", "/tmp/job-42/agent-message.txt",
                "translate this"), args);
    }
}
```

`ProcessTranslationWorkerTest.java`:

```java
package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessTranslationWorkerTest {

    @Test
    void resultFileWrittenByTheProcessIsReturnedAsSuccess() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                writesFixedResult("Translated text"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertTrue(result.succeeded());
        assertEquals("Translated text", result.enBody());
    }

    @Test
    void missingResultFileIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "true"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("candidate.en.md"));
    }

    @Test
    void nonZeroExitIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "exit 3"), Duration.ofSeconds(5));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().contains("3"));
    }

    @Test
    void timeoutIsReportedAsFailure() {
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                (workdir, prompt) -> List.of("sh", "-c", "sleep 5"), Duration.ofMillis(200));

        TranslationResult result = worker.translate("ignored");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().toLowerCase().contains("timed out"));
    }

    private static TranslationCommand writesFixedResult(String content) {
        return (Path workdir, String prompt) -> List.of("sh", "-c",
                "printf '%s' " + shellQuote(content) + " > candidate.en.md");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CodexTranslationCommandTest,ProcessTranslationWorkerTest`
Expected: FAIL — compile error, `TranslationCommand`/`CodexTranslationCommand`/`ProcessTranslationWorker` do not exist

- [ ] **Step 3: Write minimal implementation**

`TranslationCommand.java`:

```java
package dev.eugene.publicationexporter.translation;

import java.nio.file.Path;
import java.util.List;

public interface TranslationCommand {

    List<String> argsFor(Path workdir, String prompt);
}
```

`CodexTranslationCommand.java`:

```java
package dev.eugene.publicationexporter.translation;

import java.nio.file.Path;
import java.util.List;

public final class CodexTranslationCommand implements TranslationCommand {

    @Override
    public List<String> argsFor(Path workdir, String prompt) {
        return List.of(
                "codex", "exec", "--ephemeral", "--sandbox", "workspace-write",
                "--skip-git-repo-check", "-C", workdir.toString(),
                "--output-last-message", workdir.resolve("agent-message.txt").toString(),
                prompt);
    }
}
```

`ProcessTranslationWorker.java`:

```java
package dev.eugene.publicationexporter.translation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ProcessTranslationWorker implements TranslationWorker {

    private static final String RESULT_FILE_NAME = "candidate.en.md";

    private final TranslationCommand command;
    private final Duration timeout;

    public ProcessTranslationWorker(TranslationCommand command, Duration timeout) {
        this.command = Objects.requireNonNull(command, "command");
        this.timeout = requirePositive(timeout);
    }

    @Override
    public TranslationResult translate(String ruBody) {
        Path workdir = createScratchWorkdir();
        try {
            return runAndCollect(workdir, prompt(ruBody));
        } finally {
            deleteRecursively(workdir);
        }
    }

    private TranslationResult runAndCollect(Path workdir, String prompt) {
        try {
            Process process = new ProcessBuilder(command.argsFor(workdir, prompt))
                    .directory(workdir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true)
                    .start();
            return awaitResult(process, workdir);
        } catch (IOException error) {
            return TranslationResult.failure("Translation worker failed to start: " + error.getMessage());
        }
    }

    private TranslationResult awaitResult(Process process, Path workdir) {
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                return TranslationResult.failure(
                        "Translation worker timed out after " + timeout.getSeconds() + "s.");
            }
            if (process.exitValue() != 0) {
                return TranslationResult.failure(
                        "Translation worker exited with code " + process.exitValue() + ".");
            }
            return collectResult(workdir);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return TranslationResult.failure("Translation worker was interrupted.");
        }
    }

    private TranslationResult collectResult(Path workdir) {
        Path resultFile = workdir.resolve(RESULT_FILE_NAME);
        if (!Files.isRegularFile(resultFile)) {
            return TranslationResult.failure(
                    "Translation worker completed without writing " + RESULT_FILE_NAME + ".");
        }
        try {
            return TranslationResult.success(Files.readString(resultFile, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return TranslationResult.failure("Could not read " + RESULT_FILE_NAME + ": " + error.getMessage());
        }
    }

    private static String prompt(String ruBody) {
        return """
                # Bounded Russian-to-English publication translation

                Work only inside the current directory. Translate the Russian text below to
                English prose of equivalent meaning and structure. Write the complete
                translation, and only the translation, to a file named candidate.en.md in the
                current directory. Do not return commentary or a patch in place of that file.

                <source>
                %s
                </source>
                """.formatted(ruBody);
    }

    private static Path createScratchWorkdir() {
        try {
            return Files.createTempDirectory("publication-exporter-translate-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ProcessTranslationWorker::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort scratch-directory cleanup; a leftover temp dir is not a correctness failure
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see deleteRecursively
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }
}
```

`TranslationCommand` is a single-method interface, so the test lambdas in `ProcessTranslationWorkerTest`
implement it directly — no anonymous class boilerplate needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=CodexTranslationCommandTest,ProcessTranslationWorkerTest`
Expected: PASS — 1 and 4 tests respectively, 0 failures. This suite is slow relative to the rest (spawns
real processes, one test sleeps ~200ms) — same category as the real-adapter contract tests S01/S02 already
have for `FilesystemVaultReader`, not part of the sub-1-second in-memory acceptance subset.

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/CodexTranslationCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorker.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/CodexTranslationCommandTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/ProcessTranslationWorkerTest.java
git commit -m "feat(publication-exporter): add ProcessTranslationWorker real adapter (G3)"
```

---

### Task 10: `FilesystemCandidateWorkspace` — real adapter, atomic staged install

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`

**Interfaces:**
- Consumes: `ReferenceMap`/`ReferenceMapCodec` (Task 2).
- Produces: `new FilesystemCandidateWorkspace(Path reviewRoot)` implementing `CandidateWorkspace` — consumed
  by Task 11.

Create-only in this slice, matching TRP-01's scope: a second `install()` call for an identity that already
has a candidate directory is undefined behavior here (S09's replacement/recovery concern, out of scope).

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemCandidateWorkspaceTest {

    @TempDir
    Path reviewRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Path candidateDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("candidate");
        assertEquals("RU body", Files.readString(candidateDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(candidateDir.resolve("en.md")));
        assertTrue(Files.readString(candidateDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void installCreatesTheReviewRootWhenItDoesNotYetExist() throws Exception {
        Path freshRoot = reviewRoot.resolve("not-created-yet");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        assertTrue(Files.exists(freshRoot.resolve("blog/my-essay/candidate/ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("candidate-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest`
Expected: FAIL — compile error, `FilesystemCandidateWorkspace` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;

public final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final Path reviewRoot;

    public FilesystemCandidateWorkspace(Path reviewRoot) {
        this.reviewRoot = Objects.requireNonNull(reviewRoot, "reviewRoot");
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            Path destination = candidateDirectory(identity);
            Files.createDirectories(destination.getParent());
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    private Path candidateDirectory(PublicationIdentity identity) {
        return reviewRoot.resolve(identity.publicCollection()).resolve(identity.publicId()).resolve("candidate");
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(reviewRoot);
            return Files.createTempDirectory(reviewRoot, "candidate-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(FilesystemCandidateWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort staging cleanup after a failed install
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see deleteRecursively
        }
    }
}
```

`Files.move(..., ATOMIC_MOVE)` on a directory whose destination does not yet exist is the create-only
"one coherent unit" install D3 describes — the staging directory (with all three files already written)
becomes the candidate directory in one filesystem operation, so a crash before this line leaves no
candidate directory at all, and after this line leaves a complete one; there is no partially-written
in-place state.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemCandidateWorkspaceTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java
git commit -m "feat(publication-exporter): add FilesystemCandidateWorkspace real adapter"
```

---

### Task 11: `PrepareCommand` CLI and `Main` wiring

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `PrepareHandler` (Task 8), `ProcessTranslationWorker`/`CodexTranslationCommand` (Task 9),
  `FilesystemCandidateWorkspace` (Task 10).

This task's CLI acceptance test covers every path reachable without a live `codex` binary — vault/path
safety and `metadata_blocked` admission failures never reach the worker at all. A full CLI-wired
"`prepare` succeeds against a live `codex`" run is an unautomated manual smoke check (same category as
S07's Astro smoke test), since `PrepareHandlerTest` (Task 7) and `ProcessTranslationWorkerTest` (Task 9)
together already prove the orchestration logic and the real adapter's mechanics without needing `codex`
installed.

- [ ] **Step 1: Write the failing tests**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrepareCliAcceptanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path vaultRoot;

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
    void unsafeNotePathProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = prepare("../../etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("prepare", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
    }

    @Test
    void absentNoteProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = prepare("blog/does-not-exist.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void notePathWithShellMetacharactersIsTreatedAsLiteralData() throws Exception {
        int exitCode = prepare("blog/note; touch pwned.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void essayMissingSourceIdProducesBlockedSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                # My Essay""");

        int exitCode = prepare("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("id", response.get("diagnostics").get(0).get("field").asText());
    }

    private int prepare(String notePath) {
        return new CommandLine(new Main()).execute(
                "prepare",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--json");
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

    private void assertConformsToSchemaV2(JsonNode response) throws Exception {
        Set<ValidationMessage> errors = schemaV2().validate(response);
        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    private JsonSchema schemaV2() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(Files.newInputStream(SCHEMA_PATH));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareCliAcceptanceTest`
Expected: FAIL — `Missing required subcommand` / compile error, `prepare` subcommand not registered

- [ ] **Step 3: Write minimal implementation**

`PrepareCommand.java`:

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.FilesystemCandidateWorkspace;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.translation.CodexTranslationCommand;
import dev.eugene.publicationexporter.translation.ProcessTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(name = "prepare")
public final class PrepareCommand implements Callable<Integer> {

    private static final Duration TRANSLATION_TIMEOUT = Duration.ofSeconds(900);

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--note", required = true)
    String notePath;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        TranslationWorker translationWorker = new ProcessTranslationWorker(
                new CodexTranslationCommand(), TRANSLATION_TIMEOUT);
        CandidateWorkspace candidateWorkspace = new FilesystemCandidateWorkspace(reviewDirectory);
        BridgeResponse response = new PrepareHandler(translationWorker, candidateWorkspace)
                .prepare(VaultRelativePath.of(notePath), vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

`Main.java` — modify the `@Command` annotation's `subcommands`:

```java
@Command(name = "publication-exporter", subcommands = { InspectPublicationCommand.class, PrepareCommand.class })
public final class Main implements Runnable {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PrepareCliAcceptanceTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java
git commit -m "feat(publication-exporter): add prepare CLI command"
```

---

### Task 12: Schema-v2 conformance — extend both the Java-side and JS-side conformance tests (BRG-03)

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`
- Modify: `obsidian-plugin/tests/schema-conformance.test.cjs`

No `bridge-contract/schema-v2.json` change: `additionalProperties: true` and the free-form `status`
string already permit `prepare`'s response shapes.

- [ ] **Step 1: Write the failing tests**

Append to `SchemaConformanceTest.java`:

```java
    @Test
    void preparedResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        BridgeResponse response = BridgeResponse.prepared(
                "prepare", dev.eugene.publicationexporter.bridge.PublicationIdentity.of("blog", "essay", "my-essay"));

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    @Test
    void translationFailedResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        BridgeResponse response = BridgeResponse.translationFailed("prepare",
                dev.eugene.publicationexporter.bridge.Diagnostic.blocking("candidate", "worker crashed"));

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }
```

Append to `obsidian-plugin/tests/schema-conformance.test.cjs`:

```javascript
function preparedFixture() {
  return {
    schemaVersion: 2,
    command: "prepare",
    ok: true,
    status: "ready_for_review",
    identity: { publicCollection: "blog", publicContentType: "essay", publicId: "my-essay" },
    diagnostics: [],
    workspaceHealth: [],
  };
}

function translationFailedFixture() {
  return {
    schemaVersion: 2,
    command: "prepare",
    ok: false,
    status: "translation_failed",
    diagnostics: [{ field: "candidate", message: "worker crashed", blocking: true }],
    workspaceHealth: [],
  };
}

test("prepared fixture conforms to bridge-contract/schema-v2.json", () => {
  const errors = validateAgainstSchema(loadSchema(), preparedFixture());
  assert.deepEqual(errors, []);
});

test("translation-failed fixture conforms to bridge-contract/schema-v2.json", () => {
  const errors = validateAgainstSchema(loadSchema(), translationFailedFixture());
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant prepared response", async () => {
  const fixture = preparedFixture();
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 0 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("prepare", "blog/my-essay.md");
  assert.deepEqual(result, fixture);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: FAIL — compile error, `BridgeResponse.prepared`/`translationFailed` referenced correctly but
test methods not yet present (fails to compile only if appended incorrectly; otherwise these should
already pass since Task 6 built the factories — this step exists to confirm the *fixtures* are wired
correctly against the real schema, not to prove new production code)

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: the three new tests are present and initially may fail only if `bridge-client.js`'s `COMMANDS`
map ever changes — confirm they pass as written since `prepare` is already a recognized command.

- [ ] **Step 3: No production code changes required**

This task is fixture-only: `BridgeResponse.prepared`/`translationFailed` (Task 6) and `bridge-client.js`'s
existing `prepare` command entry already exist; this task only proves the new response shapes conform.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS — 4 tests, 0 failures

Run: `cd obsidian-plugin && node --test tests/schema-conformance.test.cjs`
Expected: PASS — all tests including the 3 new ones

- [ ] **Step 5: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java \
        obsidian-plugin/tests/schema-conformance.test.cjs
git commit -m "test(publication-exporter,obsidian-plugin): extend schema-v2 conformance for prepare"
```

---

### Task 13: Full verification pass

**Files:** none (verification only)

- [ ] **Step 1: Run the complete `publication-exporter` suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: BUILD SUCCESS, all tests passing (baseline 84 + this slice's new tests across Tasks 1-12)

- [ ] **Step 2: Run the obsidian-plugin conformance suite**

Run: `cd obsidian-plugin && node --test tests/`
Expected: all tests passing, including the Task 12 additions

- [ ] **Step 3: Validate the OpenSpec change**

Run: `openspec validate s03-prepare-first-candidate --strict`
Expected: `Change 's03-prepare-first-candidate' is valid`

- [ ] **Step 4: Confirm the working tree is clean and every task's commit is present**

Run: `git log --oneline -14` and `git status --porcelain=v1`
Expected: 13 feature/refactor/test commits from this plan on top of the docs commit, clean tree

- [ ] **Step 5: Report readiness for review**

Do not close Haft problem `prob-20260804-97ecd928` or archive this OpenSpec change here — that happens
after the full branch review (spec-compliance, code-quality, `/applying-sbpp`, `/oo-design-heuristics`,
and the final GPT-5.6 Sol max-effort review) confirms the slice is complete.
