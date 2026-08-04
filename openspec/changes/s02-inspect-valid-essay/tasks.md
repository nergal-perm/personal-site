# S02 — Inspect One Valid Plain Essay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `publication-exporter inspect-publication --json`, given a valid `blog/essay` note, emits exactly one schema-v2 `ok: true` JSON response reporting its publication identity and four independently-absent state dimensions; a note with malformed identity or a missing source ID still emits S01's existing `metadata_blocked` shape.

**Architecture:** Three new collaborators, each a Whole Value or single-purpose evaluator, composed into the existing S01 seam (`InspectPublicationHandler`) without changing its public signature. `Frontmatter` (new `note` package) is a pure, I/O-free parser turning raw note text into typed key lookups. `VaultReader` (existing `vault` package) gains one new port message, `readSource`, so `FilesystemVaultReader` can hand the handler real bytes the same symlink-safe way it already resolves `exists`. `PublicationIdentity` (new value type, `bridge` package — it is JSON-serialized as part of a response, same reason `Diagnostic` lives there) is the accepted-essay's identity. `EssayAdmission` (new `admission` package) is a single-method evaluator — same deliberate `oo-design-guide` 3.9 departure `InspectPublicationHandler` already established in S01 Task 5 — that turns a `Frontmatter` into either an accepted `(PublicationIdentity, sourceId)` pair or a list of blocking `Diagnostic`s. `BridgeResponse` gains a second Constructor Method, `essayInspected(...)`, and its existing `blocked(...)` gains a multi-diagnostic overload; both stay `private`-constructed named factories.

**Tech Stack:** Same as S01 — Java 17, Maven, picocli, Jackson, com.networknt:json-schema-validator, JUnit Jupiter. **No `pom.xml` change in this slice** — frontmatter parsing is hand-rolled specifically so this slice does not have to pick and justify a YAML library; the note format S02 needs (flat `key: value` pairs, one string or boolean per key) does not need one.

## Global Constraints

- Requirements introduced: ADM-02 (formally claimed; mechanism already built in S01), ADM-03, ADM-04 (essay only), SEM-01 (current-source scenario only), RVA-01 (absent-state scenario), BRG-04 — no other requirement is pulled in. See `specs/*/spec.md` in this change for the exact scenario scope.
- `publication-exporter/pom.xml` is not modified in this slice.
- `status: "not_prepared"` is a new, provisional literal (design.md D3) for "admitted, nothing prepared yet" — none of BRG-05's six states fit, and BRG-05 itself is S11 scope. Do not invent a second new status literal in this slice; every other still-absent dimension is reported via the `*State: "absent"` fields, not via `status`.
- `/nullables`: `VaultReader.createNull(...)` gains an overload that also seeds source text (Task 2); the existing no-arg and `VaultRelativePath...`-only overloads from S01 are unchanged and must keep passing their existing call sites unmodified. `readSource(path)` may only be called after `exists(path)` has returned `true` for that same path — callers (i.e. `InspectPublicationHandler`) must preserve that call order; it is a documented precondition, not a re-validated one.
- `/applying-sbpp`: every new value type (`Frontmatter`, `PublicationIdentity`, `EssayAdmission.Result`) is built via a named Constructor Method (`parse`, `of`, `accepted`/`blocked`) with a `private` constructor — never bare `new` from outside its own package/class, matching the `VaultRelativePath`/`Diagnostic`/`BridgeResponse` precedent (do NOT convert any of these to `record`s — see S01 Task 2/3's resolution, which still governs). Every method that performs more than one logical step is decomposed as a Composed Method (SBPP-BEH-01): each task below shows the resulting table-of-contents shape, not one flat block. `EssayAdmission.Result`'s diagnostic-accumulation across several private helper methods is a deliberate Collecting Parameter (SBPP-BEH-31) — the `List<Diagnostic>` is passed down and appended to, not returned and merged.
- `/oo-design-guide`: `EssayAdmission` has a single public method (`admit`), the same heuristic-3.9 ("do not turn operations into classes") departure S01 Task 5 already accepted for `InspectPublicationHandler` — noted once here, not re-litigated per task. `EssayAdmission` hard-codes essay-only rules directly (no generic `PublicationKind`-style dispatch table) per design.md D2 — heuristic 5.19 ("build reusable frameworks, not just reusable components") does not license inventing a framework from a single observed case; that is heuristic 3.9's counterpart mistake, generalizing a class into a framework before two real cases exist. `PublicationIdentity` and `Frontmatter` keep all data private (2.1) with a minimal, intention-revealing protocol (2.3) — `string`/`flag` on `Frontmatter`, `publicCollection`/`publicContentType`/`publicId` on `PublicationIdentity` — and neither knows about its container (4.13): `Frontmatter` doesn't know `EssayAdmission`, `EssayAdmission` doesn't know `InspectPublicationHandler`.
- Out of scope for S02 — do not implement: any publication kind other than `blog/essay`, whole-vault discovery or duplicate-identity detection across notes, links/transclusions/assets, candidate preparation or translation, review-plan generation, approval, and formalizing BRG-05's six-state vocabulary.
- Governance: implements Haft problem `prob-20260804-60dfda6c`; do not close it or archive this OpenSpec change until Task 10's full verification pass is green.

---

### Task 1: `Frontmatter` — pure frontmatter-block parser

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java`

**Interfaces:**
- Produces: `Frontmatter.parse(String noteSource): Frontmatter`, `Frontmatter#string(String key): Optional<String>`, `Frontmatter#flag(String key): boolean` — consumed by Task 4.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.note;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterTest {

    @Test
    void parsesStringValue() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                # Body""");

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void parsesBooleanTrueFlag() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                ---
                # Body""");

        assertTrue(frontmatter.flag("publish"));
    }

    @Test
    void missingKeyReturnsEmptyOptional() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void missingFlagReturnsFalse() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void noOpeningDelimiterYieldsAllValuesAbsent() {
        Frontmatter frontmatter = Frontmatter.parse("# Just a body, no frontmatter block");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void quotedValueIsUnquoted() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: "my-essay"
                ---
                """);

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void contentAfterClosingDelimiterIsNotParsedAsFrontmatter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                sourceId: this-looks-like-frontmatter-but-is-body-text""");

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void nullSourceIsRejectedAtParseTime() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> Frontmatter.parse(null));
        assertEquals("noteSource", exception.getMessage());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FrontmatterTest`
Expected: FAIL — compile error, `Frontmatter` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, String> frontmatterValues;

    private Frontmatter(Map<String, String> frontmatterValues) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
    }

    public static Frontmatter parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new Frontmatter(Map.of());
        }
        return new Frontmatter(parseKeyValueLines(lines));
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(frontmatterValues.get(key));
    }

    public boolean flag(String key) {
        return "true".equals(frontmatterValues.get(key));
    }

    @Override
    public String toString() {
        return "Frontmatter[frontmatterValues=" + frontmatterValues + "]";
    }

    private static boolean startsWithFrontmatterDelimiter(List<String> lines) {
        return !lines.isEmpty() && DELIMITER.equals(lines.get(0).strip());
    }

    private static Map<String, String> parseKeyValueLines(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (DELIMITER.equals(line.strip())) {
                break;
            }
            addKeyValueIfPresent(values, line);
        }
        return values;
    }

    private static void addKeyValueIfPresent(Map<String, String> values, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = line.substring(0, colon).strip();
        if (key.isEmpty()) {
            return;
        }
        values.put(key, unquote(line.substring(colon + 1).strip()));
    }

    private static String unquote(String value) {
        boolean doubleQuoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
        boolean singleQuoted = value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
        return (doubleQuoted || singleQuoted) ? value.substring(1, value.length() - 1) : value;
    }
}
```

`parse` reads as a Composed Method: reject `null`, check for the opening delimiter (guard clause), delegate line-scanning to a named helper. `parseKeyValueLines`/`addKeyValueIfPresent`/`unquote` each stay at one abstraction level. The constructor takes an already-built `Map` and copies it defensively (`Map.copyOf`) — no parsing logic inside the constructor itself (Constructor Parameter Method, SBPP-BEH-03).

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FrontmatterTest`
Expected: PASS — 8 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java
git commit -m "feat(publication-exporter): add Frontmatter pure parser"
```

---

### Task 2: `VaultReader#readSource` — extend the vault port and both adapters

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java`

**Interfaces:**
- Produces: `VaultReader#readSource(VaultRelativePath): String`, `VaultReader.createNull(Map<VaultRelativePath, String>): VaultReader` — consumed by Task 6.

- [x] **Step 1: Write the failing tests (append to existing test classes)**

Append to `NullVaultReaderTest`:

```java
    @Test
    void configuredNoteReadsBackItsSourceText() {
        VaultRelativePath path = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = VaultReader.createNull(Map.of(path, "---\npublish: true\n---\n"));

        assertEquals("---\npublish: true\n---\n", reader.readSource(path));
    }

    @Test
    void pathSeededWithoutContentReadsBackAsEmptySource() {
        VaultRelativePath path = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = VaultReader.createNull(path);

        assertEquals("", reader.readSource(path));
    }

    @Test
    void readingSourceForAnUnseededPathThrows() {
        VaultReader reader = VaultReader.createNull();
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/missing.md")));
    }
```

Add imports `java.util.Map`, `java.util.NoSuchElementException`, and `static org.junit.jupiter.api.Assertions.assertThrows` to `NullVaultReaderTest`.

Append to `FilesystemVaultReaderTest`:

```java
    @Test
    void readSourceReturnsRealFileContent() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "---\npublish: true\n---\n");

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertEquals("---\npublish: true\n---\n",
                reader.readSource(VaultRelativePath.of("blog/real-note.md")));
    }

    @Test
    void readSourceThrowsForMissingFile() {
        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/missing.md")));
    }

    @Test
    void readSourceThrowsForSymlinkEscapingTheVaultRoot() throws Exception {
        Path secret = Files.writeString(
                outsideVaultRoot.resolve("secret.md"), "# Outside the vault");
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/link.md"), secret);

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/link.md")));
    }
```

Add imports `java.util.NoSuchElementException` and `static org.junit.jupiter.api.Assertions.assertEquals` / `assertThrows` to `FilesystemVaultReaderTest`.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullVaultReaderTest,FilesystemVaultReaderTest`
Expected: FAIL — compile error, `readSource` is undefined and the `Map` overload of `createNull` is undefined

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.vault;

import java.util.Map;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    String readSource(VaultRelativePath notePath);

    static VaultReader create(java.nio.file.Path vaultRoot) {
        return new FilesystemVaultReader(vaultRoot);
    }

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }

    static VaultReader createNull(Map<VaultRelativePath, String> notesBySource) {
        return new NullVaultReader(notesBySource);
    }
}
```

```java
package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

final class NullVaultReader implements VaultReader {

    private final Map<String, String> sourceByPath;

    NullVaultReader(VaultRelativePath... existing) {
        this(defaultToEmptySource(existing));
    }

    NullVaultReader(Map<VaultRelativePath, String> notesBySource) {
        Map<String, String> bySourcePath = new LinkedHashMap<>();
        notesBySource.forEach((path, source) -> bySourcePath.put(path.value(), source));
        this.sourceByPath = Map.copyOf(bySourcePath);
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return sourceByPath.containsKey(notePath.value());
    }

    @Override
    public String readSource(VaultRelativePath notePath) {
        String source = sourceByPath.get(notePath.value());
        if (source == null) {
            throw new NoSuchElementException("Note not found: " + notePath.value());
        }
        return source;
    }

    private static Map<VaultRelativePath, String> defaultToEmptySource(VaultRelativePath... paths) {
        Map<VaultRelativePath, String> notesBySource = new LinkedHashMap<>();
        Arrays.stream(paths).forEach(path -> notesBySource.put(path, ""));
        return notesBySource;
    }
}
```

```java
package dev.eugene.publicationexporter.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

final class FilesystemVaultReader implements VaultReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    /**
     * Reports whether the note really exists <em>inside</em> the vault. A path that resolves —
     * through symbolic links — to a location outside the canonical vault root is reported as
     * absent, so a link planted in the vault cannot expose an external file.
     */
    @Override
    public boolean exists(VaultRelativePath notePath) {
        return resolveWithinVault(notePath).isPresent();
    }

    @Override
    public String readSource(VaultRelativePath notePath) {
        Path real = resolveWithinVault(notePath)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + notePath.value()));
        return readUtf8(real);
    }

    private Optional<Path> resolveWithinVault(VaultRelativePath notePath) {
        return candidateFor(notePath)
                .flatMap(FilesystemVaultReader::realPathOf)
                .filter(this::isInsideVault);
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> candidateFor(VaultRelativePath notePath) {
        try {
            return Optional.of(canonicalVaultRoot.resolve(notePath.value()));
        } catch (InvalidPathException unrepresentable) {
            return Optional.empty();
        }
    }

    private boolean isInsideVault(Path realNotePath) {
        return realNotePath.startsWith(canonicalVaultRoot);
    }

    private static Path canonicalize(Path vaultRoot) {
        return realPathOf(vaultRoot).orElseGet(() -> vaultRoot.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
        }
    }
}
```

`exists` and `readSource` now share one Composed Method, `resolveWithinVault` — the symlink-safe resolution S01's final fix wave built is exercised identically by both messages, so there is exactly one place that decides "is this candidate really inside the vault," matching heuristic 4.6 (most methods use most data members most of the time) instead of duplicating the resolution logic. `readUtf8` isolates the one checked-to-unchecked exception translation at one call site.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullVaultReaderTest,FilesystemVaultReaderTest`
Expected: PASS — 6 and 9 tests respectively, 0 failures; every pre-existing test in both classes still passes unchanged

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java
git commit -m "feat(publication-exporter): add VaultReader#readSource to both adapters"
```

---

### Task 3: `PublicationIdentity` — Whole Value

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/PublicationIdentity.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/PublicationIdentityTest.java`

**Interfaces:**
- Produces: `PublicationIdentity.of(String publicCollection, String publicContentType, String publicId): PublicationIdentity` with accessors and hand-written `equals`/`hashCode`/`toString` — consumed by Tasks 4, 5.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicationIdentityTest {

    @Test
    void accessorsReturnConstructedValues() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");

        assertEquals("blog", identity.publicCollection());
        assertEquals("essay", identity.publicContentType());
        assertEquals("my-essay", identity.publicId());
    }

    @Test
    void equalIdentitiesBuiltSeparatelyAreEqual() {
        assertEquals(
                PublicationIdentity.of("blog", "essay", "my-essay"),
                PublicationIdentity.of("blog", "essay", "my-essay"));
    }

    @Test
    void publicIdIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> PublicationIdentity.of("blog", "essay", null));
        assertEquals("publicId", exception.getMessage());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PublicationIdentityTest`
Expected: FAIL — compile error, `PublicationIdentity` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class PublicationIdentity {

    private final String publicCollection;
    private final String publicContentType;
    private final String publicId;

    private PublicationIdentity(String publicCollection, String publicContentType, String publicId) {
        this.publicCollection = Objects.requireNonNull(publicCollection, "publicCollection");
        this.publicContentType = Objects.requireNonNull(publicContentType, "publicContentType");
        this.publicId = Objects.requireNonNull(publicId, "publicId");
    }

    public static PublicationIdentity of(String publicCollection, String publicContentType, String publicId) {
        return new PublicationIdentity(publicCollection, publicContentType, publicId);
    }

    @JsonProperty("publicCollection")
    public String publicCollection() {
        return publicCollection;
    }

    @JsonProperty("publicContentType")
    public String publicContentType() {
        return publicContentType;
    }

    @JsonProperty("publicId")
    public String publicId() {
        return publicId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationIdentity that)) {
            return false;
        }
        return publicCollection.equals(that.publicCollection)
                && publicContentType.equals(that.publicContentType)
                && publicId.equals(that.publicId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicCollection, publicContentType, publicId);
    }

    @Override
    public String toString() {
        return "PublicationIdentity[publicCollection=" + publicCollection
                + ", publicContentType=" + publicContentType + ", publicId=" + publicId + "]";
    }
}
```

Same shape as `Diagnostic` (Task 3 of S01): `private` constructor, `of(...)` as the sole Constructor Method, hand-written `equals`/`hashCode`/`toString`, `@JsonProperty`-annotated bare accessors — not a `record`, per the standing invariant.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=PublicationIdentityTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/PublicationIdentity.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/PublicationIdentityTest.java
git commit -m "feat(publication-exporter): add PublicationIdentity value type"
```

---

### Task 4: `EssayAdmission` — essay identity and source-ID rules

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java`

**Interfaces:**
- Consumes: `Frontmatter#string`/`#flag` (Task 1), `PublicationIdentity.of` (Task 3), `Diagnostic.blocking` (S01 Task 3).
- Produces: `EssayAdmission#admit(Frontmatter): EssayAdmission.Result`, `Result#accepted(): boolean`, `Result#identity(): PublicationIdentity`, `Result#sourceId(): String`, `Result#diagnostics(): List<Diagnostic>` — consumed by Task 6.

**Design note (oo-design-guide 3.9, same departure as `InspectPublicationHandler`):** `EssayAdmission` has one public method. It stays a class, not a static function, because it is the seam Task 6 depends on and this task's own test exercises directly — a real collaborator boundary matching S01 Task 5's precedent, not an operation wrapped in a class for its own sake.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.Frontmatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssayAdmissionTest {

    private final EssayAdmission admission = new EssayAdmission();

    @Test
    void validEssayIsAccepted() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "essay", "my-essay"), result.identity());
        assertEquals("8f2c-my-essay", result.sourceId());
    }

    @Test
    void unpublishedNoteIsBlockedOnPublishAlone() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(1, result.diagnostics().size());
        assertEquals("publish", result.diagnostics().get(0).field());
    }

    @Test
    void invalidPublicIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: My_Essay
                sourceId: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("publicId", result.diagnostics().get(0).field());
    }

    @Test
    void wrongCollectionBlocksBothCollectionAndContentType() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: bibliography
                publicContentType: essay
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(2, result.diagnostics().size());
        assertEquals("publicCollection", result.diagnostics().get(0).field());
        assertEquals("publicContentType", result.diagnostics().get(1).field());
    }

    @Test
    void wrongContentTypeAloneBlocksOnlyContentType() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(1, result.diagnostics().size());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }

    @Test
    void missingSourceIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("sourceId", result.diagnostics().get(0).field());
    }

    @Test
    void multipleFailuresAreAllReported() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(2, result.diagnostics().size());
    }

    private void assertFalseAccepted(EssayAdmission.Result result) {
        org.junit.jupiter.api.Assertions.assertFalse(result.accepted());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=EssayAdmissionTest`
Expected: FAIL — compile error, `EssayAdmission` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.Frontmatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EssayAdmission {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final String REQUIRED_COLLECTION = "blog";
    private static final String REQUIRED_CONTENT_TYPE = "essay";

    public Result admit(Frontmatter frontmatter) {
        if (!isPublished(frontmatter)) {
            return Result.blocked(List.of(publishDiagnostic()));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String collection = requireCollection(frontmatter, diagnostics);
        String contentType = requireContentType(frontmatter, collection, diagnostics);
        String sourceId = requireSourceId(frontmatter, diagnostics);

        if (!diagnostics.isEmpty()) {
            return Result.blocked(diagnostics);
        }
        return Result.accepted(PublicationIdentity.of(collection, contentType, publicId), sourceId);
    }

    private boolean isPublished(Frontmatter frontmatter) {
        return frontmatter.flag("publish");
    }

    private Diagnostic publishDiagnostic() {
        return Diagnostic.blocking("publish", "must be true; allowed value: true");
    }

    private String requireValidPublicId(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String publicId = frontmatter.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireCollection(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String collection = frontmatter.string("publicCollection").orElse(null);
        if (!REQUIRED_COLLECTION.equals(collection)) {
            diagnostics.add(Diagnostic.blocking("publicCollection",
                    "must be \"" + REQUIRED_COLLECTION + "\""));
        }
        return collection;
    }

    private String requireContentType(Frontmatter frontmatter, String collection, List<Diagnostic> diagnostics) {
        String contentType = frontmatter.string("publicContentType").orElse(null);
        if (!REQUIRED_COLLECTION.equals(collection)) {
            diagnostics.add(Diagnostic.blocking("publicContentType",
                    "requires a valid publicCollection to determine allowed values"));
        } else if (!REQUIRED_CONTENT_TYPE.equals(contentType)) {
            diagnostics.add(Diagnostic.blocking("publicContentType",
                    "must be \"" + REQUIRED_CONTENT_TYPE + "\""));
        }
        return contentType;
    }

    private String requireSourceId(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String sourceId = frontmatter.string("sourceId").orElse(null);
        if (sourceId == null) {
            diagnostics.add(Diagnostic.blocking("sourceId", "Note has no source ID."));
        }
        return sourceId;
    }

    public static final class Result {

        private final PublicationIdentity identity;
        private final String sourceId;
        private final List<Diagnostic> diagnostics;

        private Result(PublicationIdentity identity, String sourceId, List<Diagnostic> diagnostics) {
            this.identity = identity;
            this.sourceId = sourceId;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(PublicationIdentity identity, String sourceId) {
            return new Result(
                    Objects.requireNonNull(identity, "identity"),
                    Objects.requireNonNull(sourceId, "sourceId"),
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
        public PublicationIdentity identity() {
            return identity;
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String sourceId() {
            return sourceId;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        @Override
        public String toString() {
            return "EssayAdmission.Result[identity=" + identity + ", sourceId=" + sourceId
                    + ", diagnostics=" + diagnostics + "]";
        }
    }
}
```

`admit` is a Composed Method table of contents: the `publish` guard clause first (it short-circuits everything else, mirroring the compatibility-oracle's own precedent that an unpublished note gets exactly one diagnostic), then four named `requireX` steps that each check one field and append to the shared `diagnostics` Collecting Parameter (SBPP-BEH-31), then one assembly step. `requireContentType` depends on `collection` because an invalid collection makes "allowed content types" undefined — same dependency the compatibility oracle's `PublicationValidator` encodes, reproduced here as evidence, not copied as code. `Result`'s two factories enforce their own invariant (`blocked` cannot be called with zero diagnostics) directly in the class definition (heuristic 4.9) rather than trusting every call site.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=EssayAdmissionTest`
Expected: PASS — 7 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java
git commit -m "feat(publication-exporter): add EssayAdmission identity and source-ID rules"
```

---

### Task 5: `BridgeResponse` — `essayInspected(...)` factory and multi-diagnostic `blocked(...)`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java`

**Interfaces:**
- Consumes: `PublicationIdentity` (Task 3).
- Produces: `BridgeResponse.essayInspected(String, String, PublicationIdentity, String, String, String, String): BridgeResponse`, `BridgeResponse.blocked(String, List<Diagnostic>): BridgeResponse`, accessors `identity()`, `candidateState()`, `approvedSnapshotState()`, `semanticReferenceState()`, `releaseState()` — consumed by Task 6.

- [x] **Step 1: Write the failing tests (append to `BridgeResponseJsonTest`)**

```java
    @Test
    void essayInspectedResponseSerializesToSchemaV2Shape() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "not_prepared", identity,
                "absent", "absent", "absent", "absent");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("not_prepared", parsed.get("status").asText());
        assertEquals("blog", parsed.get("identity").get("publicCollection").asText());
        assertEquals("absent", parsed.get("candidateState").asText());
        assertEquals("absent", parsed.get("approvedSnapshotState").asText());
        assertEquals("absent", parsed.get("semanticReferenceState").asText());
        assertEquals("absent", parsed.get("releaseState").asText());
        assertTrue(parsed.get("diagnostics").isArray());
        assertEquals(0, parsed.get("diagnostics").size());
    }

    @Test
    void blockedResponseOmitsIdentityAndStateFieldsFromJson() throws Exception {
        BridgeResponse response = BridgeResponse.blocked(
                "inspect-publication", Diagnostic.blocking("sourceId", "Note has no source ID."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertFalse(parsed.has("identity"));
        assertFalse(parsed.has("candidateState"));
    }

    @Test
    void blockedResponseAcceptsMultipleDiagnostics() {
        BridgeResponse response = BridgeResponse.blocked("inspect-publication", List.of(
                Diagnostic.blocking("publicCollection", "must be \"blog\""),
                Diagnostic.blocking("publicContentType", "requires a valid publicCollection")));

        assertEquals(2, response.diagnostics().size());
    }
```

Add imports `java.util.List` and `static org.junit.jupiter.api.Assertions.assertFalse` to `BridgeResponseJsonTest`.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: FAIL — compile error, `essayInspected` and the `List<Diagnostic>` overload of `blocked` are undefined

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeResponse {

    private final int schemaVersion;
    private final String command;
    private final boolean ok;
    private final String status;
    private final List<Diagnostic> diagnostics;
    private final List<Diagnostic> workspaceHealth;
    private final PublicationIdentity identity;
    private final String candidateState;
    private final String approvedSnapshotState;
    private final String semanticReferenceState;
    private final String releaseState;

    private BridgeResponse(
            int schemaVersion,
            String command,
            boolean ok,
            String status,
            List<Diagnostic> diagnostics,
            List<Diagnostic> workspaceHealth,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState) {
        this.schemaVersion = schemaVersion;
        this.command = Objects.requireNonNull(command, "command");
        this.ok = ok;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.workspaceHealth = Objects.requireNonNull(workspaceHealth, "workspaceHealth");
        this.identity = identity;
        this.candidateState = candidateState;
        this.approvedSnapshotState = approvedSnapshotState;
        this.semanticReferenceState = semanticReferenceState;
        this.releaseState = releaseState;
    }

    public static BridgeResponse blocked(String command, Diagnostic diagnostic) {
        return blocked(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse blocked(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "metadata_blocked",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null);
    }

    public static BridgeResponse essayInspected(
            String command,
            String status,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState) {
        return new BridgeResponse(2, command, true, status, List.of(), List.of(),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(candidateState, "candidateState"),
                Objects.requireNonNull(approvedSnapshotState, "approvedSnapshotState"),
                Objects.requireNonNull(semanticReferenceState, "semanticReferenceState"),
                Objects.requireNonNull(releaseState, "releaseState"));
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("command")
    public String command() {
        return command;
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("status")
    public String status() {
        return status;
    }

    @JsonProperty("diagnostics")
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @JsonProperty("workspaceHealth")
    public List<Diagnostic> workspaceHealth() {
        return workspaceHealth;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("candidateState")
    public String candidateState() {
        return candidateState;
    }

    @JsonProperty("approvedSnapshotState")
    public String approvedSnapshotState() {
        return approvedSnapshotState;
    }

    @JsonProperty("semanticReferenceState")
    public String semanticReferenceState() {
        return semanticReferenceState;
    }

    @JsonProperty("releaseState")
    public String releaseState() {
        return releaseState;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgeResponse that)) {
            return false;
        }
        return schemaVersion == that.schemaVersion
                && ok == that.ok
                && command.equals(that.command)
                && status.equals(that.status)
                && diagnostics.equals(that.diagnostics)
                && workspaceHealth.equals(that.workspaceHealth)
                && Objects.equals(identity, that.identity)
                && Objects.equals(candidateState, that.candidateState)
                && Objects.equals(approvedSnapshotState, that.approvedSnapshotState)
                && Objects.equals(semanticReferenceState, that.semanticReferenceState)
                && Objects.equals(releaseState, that.releaseState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, command, ok, status, diagnostics, workspaceHealth,
                identity, candidateState, approvedSnapshotState, semanticReferenceState, releaseState);
    }

    @Override
    public String toString() {
        return "BridgeResponse[schemaVersion=" + schemaVersion + ", command=" + command
                + ", ok=" + ok + ", status=" + status + ", diagnostics=" + diagnostics
                + ", workspaceHealth=" + workspaceHealth + ", identity=" + identity
                + ", candidateState=" + candidateState + ", approvedSnapshotState=" + approvedSnapshotState
                + ", semanticReferenceState=" + semanticReferenceState + ", releaseState=" + releaseState + "]";
    }
}
```

`blocked(String, Diagnostic)` now delegates to the new `blocked(String, List<Diagnostic>)` (Reversing Method-style call-forwarding, SBPP-BEH-09's spirit: the single-diagnostic form is sugar over the general form, not a second implementation to keep in sync). The five new fields stay `null` on every `blocked(...)` response; the class-level `@JsonInclude(NON_NULL)` means Jackson omits them entirely rather than emitting `"identity": null`, keeping the blocked-response JSON shape byte-identical to what S01 already produces (verified by `blockedResponseOmitsIdentityAndStateFieldsFromJson` above).

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: PASS — 7 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java
git commit -m "feat(publication-exporter): add BridgeResponse#essayInspected and multi-diagnostic blocked()"
```

---

### Task 6: Wire `InspectPublicationHandler` to real essay admission

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`

**Interfaces:**
- Consumes: `VaultReader#readSource` (Task 2), `Frontmatter.parse` (Task 1), `EssayAdmission#admit` (Task 4), `BridgeResponse.essayInspected`/`blocked(String, List<Diagnostic>)` (Task 5).
- Produces: `InspectPublicationHandler#inspect(VaultRelativePath, VaultReader): BridgeResponse` — unchanged signature, now reaches the essay-inspected path — consumed by Tasks 7, 8.

- [x] **Step 1: Write the failing tests (append to `InspectPublicationHandlerTest`)**

```java
    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            sourceId: 8f2c-my-essay
            ---
            """;

    @Test
    void validEssayIsAcceptedWithAllStatesAbsent() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());
        assertEquals("blog", response.identity().publicCollection());
        assertEquals("essay", response.identity().publicContentType());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals("absent", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
        assertEquals(0, response.diagnostics().size());
    }

    @Test
    void essayMissingSourceIdIsBlocked() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("sourceId", response.diagnostics().get(0).field());
    }

    @Test
    void essayWithUnsupportedContentTypeIsBlocked() {
        String claimNote = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: my-claim
                sourceId: 8f2c-my-claim
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-claim.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, claimNote));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("publicContentType", response.diagnostics().get(0).field());
    }
```

Add imports `java.util.Map` and `static org.junit.jupiter.api.Assertions.assertTrue` to `InspectPublicationHandlerTest`; change the existing `private final InspectPublicationHandler handler = ...` field declaration to stay shared across these new tests if not already.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: FAIL — `UnsupportedOperationException: Valid-note inspection is not implemented until S02.`

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.List;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String ABSENT = "absent";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return blockedForVaultEscape();
        }
        if (!vaultReader.exists(notePath)) {
            return blockedForMissingNote();
        }
        return inspectExistingNote(notePath, vaultReader);
    }

    private BridgeResponse inspectExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
        EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
        if (!admission.accepted()) {
            return blockedForAdmission(admission.diagnostics());
        }
        return acceptedEssay(admission);
    }

    private BridgeResponse blockedForVaultEscape() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path escapes the vault root."));
    }

    private BridgeResponse blockedForMissingNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note was not found in the vault."));
    }

    private BridgeResponse blockedForAdmission(List<Diagnostic> diagnostics) {
        return BridgeResponse.blocked(COMMAND, diagnostics);
    }

    private BridgeResponse acceptedEssay(EssayAdmission.Result admission) {
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, admission.identity(),
                ABSENT, ABSENT, ABSENT, ABSENT);
    }
}
```

`inspect` stays a three-line Composed Method table of contents, unchanged in shape from S01 — only the previously-`throw`ing third branch now delegates to `inspectExistingNote`, itself a four-line table of contents (parse → evaluate → branch → assemble). `InspectPublicationHandler` still touches no I/O directly (`VaultReader` owns that), and still owns orchestration only, matching the oo-design-guide note S01 Task 5 already recorded for this class.

- [x] **Step 4: Run tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: PASS — 5 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java
git commit -m "feat(publication-exporter): wire InspectPublicationHandler to real essay admission"
```

---

### Task 7: Extend the Java schema conformance test

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`

**Interfaces:**
- Consumes: `InspectPublicationHandler#inspect` (Task 6).

- [x] **Step 1: Write the failing test (append to `SchemaConformanceTest`)**

```java
    @Test
    void validEssayResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        String validEssay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                """;
        VaultReader vaultReader = VaultReader.createNull(java.util.Map.of(path, validEssay));

        InspectPublicationHandler handler = new InspectPublicationHandler();
        BridgeResponse response = handler.inspect(path, vaultReader);

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: FAIL until Task 6 lands — since Task 6 is a prerequisite already merged by this point in the plan, this step should instead PASS immediately (this test exercises already-green Task 6 logic against the schema file, same as S01 Task 6's own note)

- [x] **Step 3: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS — 2 tests, 0 failures

- [x] **Step 4: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java
git commit -m "test(publication-exporter): validate valid-essay response against schema-v2.json"
```

---

### Task 8: Extend the CLI acceptance test

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `InspectPublicationHandler#inspect` (Task 6), via the real CLI entry point.

- [x] **Step 1: Write the failing tests (append to `InspectPublicationCliAcceptanceTest`)**

```java
    @Test
    void validEssayNoteProducesSuccessSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                sourceId: 8f2c-my-essay
                ---
                # My Essay""");

        int exitCode = inspect("blog/my-essay.md");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("not_prepared", response.get("status").asText());
        assertEquals("my-essay", response.get("identity").get("publicId").asText());
        assertEquals("absent", response.get("candidateState").asText());
        assertEquals("absent", response.get("approvedSnapshotState").asText());
        assertEquals("absent", response.get("semanticReferenceState").asText());
        assertEquals("absent", response.get("releaseState").asText());
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

        int exitCode = inspect("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("sourceId", response.get("diagnostics").get(0).get("field").asText());
    }
```

Add import `static org.junit.jupiter.api.Assertions.assertTrue` if not already present in this file.

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: PASS immediately — Task 6 already wired the handler; this task's role is to prove the same behaviour end-to-end through real `@TempDir` files and the real CLI process wiring, not to drive new production code (same category as S01 Task 9's note)

- [x] **Step 3: Run the full existing acceptance test class to confirm no regressions**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: PASS — 8 tests, 0 failures (6 existing S01 tests + 2 new)

- [x] **Step 4: Commit**

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java
git commit -m "test(publication-exporter): extend CLI acceptance test for valid-essay and missing-source-ID cases"
```

---

### Task 9: Extend the JS-side conformance test

**Files:**
- Modify: `obsidian-plugin/tests/schema-conformance.test.cjs`

**Interfaces:**
- Consumes: `bridge-contract/schema-v2.json` (unchanged file, still `additionalProperties: true`), `createBridgeClient` (existing).

- [ ] **Step 1: Write the failing test (append to `schema-conformance.test.cjs`)**

```javascript
function essayInspectedFixture() {
  return {
    schemaVersion: 2,
    command: "inspect-publication",
    ok: true,
    status: "not_prepared",
    identity: { publicCollection: "blog", publicContentType: "essay", publicId: "my-essay" },
    candidateState: "absent",
    approvedSnapshotState: "absent",
    semanticReferenceState: "absent",
    releaseState: "absent",
    diagnostics: [],
    workspaceHealth: [],
  };
}

test("valid-essay fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = essayInspectedFixture();
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant valid-essay response", async () => {
  const fixture = essayInspectedFixture();
  const client = createBridgeClient({
    spawn: createFakeSpawn({ stdout: JSON.stringify(fixture), exitCode: 0 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/my-essay.md");
  assert.deepEqual(result, fixture);
});
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `node --test obsidian-plugin/tests/schema-conformance.test.cjs`
Expected: PASS — 2 new tests pass alongside all existing tests in the file, 0 failures

Note: like S01 Task 9, this adds no new production code — `bridge-client.js` and `bridge-contract/schema-v2.json` both already support this shape (the schema's `additionalProperties: true` was already exercised by S01), so there is no RED phase.

- [ ] **Step 3: Commit**

```bash
git add obsidian-plugin/tests/schema-conformance.test.cjs
git commit -m "test(obsidian-plugin): add valid-essay schema-v2 conformance fixture"
```

---

### Task 10: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Java suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: `BUILD SUCCESS`, all tests from Tasks 1–8 passing alongside every S01 test, unmodified and green

- [ ] **Step 2: Run the full JS suite (existing + new)**

Run: `node --test obsidian-plugin/tests/bridge-client.test.cjs obsidian-plugin/tests/schema-conformance.test.cjs`
Expected: all tests passing, no regressions in `bridge-client.test.cjs` or S01's schema-conformance tests

- [ ] **Step 3: Confirm OpenSpec status**

Run: `openspec status --change s02-inspect-valid-essay`
Expected: `4/4 artifacts complete`

Governance follow-up (not part of this checklist, performed by the operator after review): close Haft problem `prob-20260804-60dfda6c` against this implementation, then archive — this change has two real deltas (`review-and-approval`, `workflow-bridge`) alongside two pure scope-pins (`publication-admission`, `semantic-references`); use plain `openspec archive s02-inspect-valid-essay` (no `--skip-specs`), since the real deltas must be folded into the baseline specs, unlike S01's fully-`--skip-specs` archive.
