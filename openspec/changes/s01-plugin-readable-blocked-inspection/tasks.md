# S01 — Plugin-Readable Blocked Inspection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `publication-exporter inspect-publication --json`, given an unsafe or absent note, emits exactly one schema-v2 blocked JSON response on stdout and exits non-zero — the plugin's `bridge-client.js` accepts this response without modification.

**Architecture:** A new Java 17/Maven module `publication-exporter/` (sibling to `exporter-java`, which stays untouched and is never a code donor). A pure `VaultRelativePath` value type owns lexical path-safety; a single-method `VaultReader` port (`NullVaultReader` now, `FilesystemVaultReader` added within this same slice as the "real adapter last" step) owns existence checks; `InspectPublicationHandler` combines both into a `BridgeResponse`; `InspectPublicationCommand` (picocli) wires args → handler → JSON on stdout → exit code. `bridge-contract/schema-v2.json` is the single-sourced contract, validated by a Java conformance test in this module and a JS conformance test in `obsidian-plugin/tests/`.

**Tech Stack:** Java 17, Maven, picocli 4.7.7, Jackson (jackson-databind) 2.22.0, com.networknt:json-schema-validator 1.5.1, JUnit Jupiter 6.1.0. JS side stays dependency-free (`node:test`, `node:assert/strict`), matching the existing `obsidian-plugin/tests/bridge-client.test.cjs` convention — no `package.json` exists there yet and this change doesn't add one.

## Global Constraints

- Java 17, Maven, groupId `dev.eugene`, artifactId `publication-exporter`, new sibling directory (gate decision `dec-20260803-dd8d5f61`).
- Bridge contract single-sourced at `bridge-contract/schema-v2.json` (repo root), validated by both a Java-side and a JS-side conformance test — never copied into `src/test/resources` (gate decision `dec-20260803-4834d689`).
- `schemaVersion` is fixed at the JSON literal `2` (schema `const`) — this module must never emit `3`.
- No shell invocation anywhere in the CLI entry point — args are literal Java `String[]`/picocli-parsed values, never concatenated into a shell command.
- `/nullables` is the default testing technique: every I/O-touching port exposes `create()` (real) / `createNull()` (nulled) static factories on the interface itself — never `new NullVaultReader()` / `new FilesystemVaultReader()` from outside the `vault` package. Prefer `VaultReader.createNull()` over real I/O in every test except `FilesystemVaultReaderTest` (the lowest wrapper's own narrow integration test against the real filesystem) and `InspectPublicationCliAcceptanceTest` (real CLI wiring end-to-end). `NullVaultReader`/`FilesystemVaultReader` are package-private implementation details, reachable only through `VaultReader`'s factories.
- `/applying-sbpp`: `VaultRelativePath`, `Diagnostic`, `BridgeResponse` are Whole Values built via named Constructor Methods (`of`, `blocking`, `blocked`) rather than bare `new`; methods use Intention-Revealing Selectors (`inspect`, not `execute`/`handle`/`process`) — the sole exception is `InspectPublicationCommand#call()`, whose name is fixed by the `Callable<Integer>` contract picocli requires, not a free naming choice.
- `/oo-design-guide`: `VaultReader` stays a single-message-type port (2.3, minimal protocol); its two adapters are hidden behind the interface (2.1/2.2 — users depend on the public interface, not the implementation) rather than exposed as public types; `InspectPublicationHandler` owns orchestration only, never touches the filesystem directly. `InspectPublicationHandler` is a deliberate, noted departure from heuristic 3.9 (avoid classes that exist only to wrap one operation) — see Task 5's design note.
- Out of scope for S01 — do not implement: Markdown parsing, the valid-note success response, review workspace behaviour, `prepare`/`mark-reviewed`/`refresh-publication-queue` commands, OS-level packaging (deferred to gate G5).
- Governance: implements Haft problem `prob-20260803-a75ab1d8`; do not close it or archive this OpenSpec change until Task 10's full verification pass is green.

---

### Task 1: Maven project scaffold

**Files:**
- Create: `publication-exporter/pom.xml`

**Interfaces:**
- Produces: a compiling Maven module `publication-exporter` (Java 17 release, JUnit Jupiter wired via Surefire) — every later task runs `mvn -f publication-exporter/pom.xml test` against it.

- [x] **Step 1: Create the POM**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.eugene</groupId>
  <artifactId>publication-exporter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>publication-exporter</name>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>
    <picocli.version>4.7.7</picocli.version>
    <jackson.version>2.22.0</jackson.version>
    <json-schema-validator.version>1.5.1</json-schema-validator.version>
    <junit.version>6.1.0</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>${picocli.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.networknt</groupId>
      <artifactId>json-schema-validator</artifactId>
      <version>${json-schema-validator.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.3</version>
        <configuration>
          <useModulePath>false</useModulePath>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [x] **Step 2: Verify the build**

Run: `mvn -f publication-exporter/pom.xml compile`
Expected: `BUILD SUCCESS`

- [x] **Step 3: Commit**

```bash
git add publication-exporter/pom.xml
git commit -m "chore(publication-exporter): scaffold Maven project (Java 17)"
```

---

### Task 2: `VaultRelativePath` — pure path-safety value type

Revised after Task 2's SDD review round (three parallel reviewers — spec/quality, `/applying-sbpp`, `/oo-design-heuristics` — independently found the same gaps in the original hand-written-class version of this task: no `equals`/`hashCode`/`toString` on a published Whole Value, `of(null)` silently succeeding instead of failing fast, and `isWithinVault()` mixing abstraction levels instead of reading as a Composed Method).

**Revised a second time** after the fix round's own re-review (four parallel Codex reviewers — spec compliance, code quality, `/applying-sbpp`, `/oo-design-heuristics`): the first revision's chosen fix (convert to a `public record`) was itself found to be a defect by the SBPP and OO-design reviewers, independently and convergently — a public record's canonical constructor is necessarily public, so `new VaultRelativePath(...)` became a second, unguarded public construction path alongside `of(String)`, violating this plan's own Global Constraint that Whole Values are built via named Constructor Methods "rather than bare `new`." Human-confirmed final resolution: **do not use a record.** Revert to a hand-written `final` class with a `private` constructor (restoring `of(String)` as the sole construction path) and manually-written `equals`/`hashCode`/`toString` (restoring the value-equality the original review round required). This same resolution applies going forward to Task 3's `Diagnostic`/`BridgeResponse` — they must NOT be converted to records either.

The code-quality reviewer also found two issues in the fix diff, folded into this revision: the null-rejection test asserted only the exception type (would also pass for an unrelated NPE) — fixed by asserting the exception's message; and the extracted predicate `isBlank()` was misnamed (it only checks emptiness, not whitespace) — renamed to `isEmpty()`.

The published interface (`of`/`isWithinVault`/`value`) is unchanged, so this revision does not affect Tasks 4, 5, 7, 8.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultRelativePath.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/VaultRelativePathTest.java`

**Interfaces:**
- Produces: `VaultRelativePath.of(String rawPath): VaultRelativePath`, `VaultRelativePath#isWithinVault(): boolean`, `VaultRelativePath#value(): String`, plus hand-written `equals`/`hashCode`/`toString` (NOT record-derived — the constructor stays `private`) — consumed by Tasks 4, 5, 7, 8.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRelativePathTest {

    @Test
    void plainRelativePathIsWithinVault() {
        assertTrue(VaultRelativePath.of("blog/does-not-exist.md").isWithinVault());
    }

    @Test
    void parentSegmentEscapesVault() {
        assertFalse(VaultRelativePath.of("../../etc/passwd.md").isWithinVault());
    }

    @Test
    void absolutePathEscapesVault() {
        assertFalse(VaultRelativePath.of("/etc/passwd.md").isWithinVault());
    }

    @Test
    void backslashEscapesVault() {
        assertFalse(VaultRelativePath.of("blog\\..\\secrets.md").isWithinVault());
    }

    @Test
    void emptyPathEscapesVault() {
        assertFalse(VaultRelativePath.of("").isWithinVault());
    }

    @Test
    void soloDotSegmentEscapesVault() {
        assertFalse(VaultRelativePath.of("./blog/note.md").isWithinVault());
    }

    @Test
    void trailingSlashProducesEmptySegmentAndEscapesVault() {
        assertFalse(VaultRelativePath.of("blog/").isWithinVault());
    }

    @Test
    void nullPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> VaultRelativePath.of(null));
        assertEquals("value", exception.getMessage());
    }

    @Test
    void equalPathsBuiltSeparatelyAreEqual() {
        assertEquals(VaultRelativePath.of("blog/note.md"), VaultRelativePath.of("blog/note.md"));
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=VaultRelativePathTest`
Expected: FAIL — compile error, `VaultRelativePath` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Objects;

public final class VaultRelativePath {

    private final String value;

    private VaultRelativePath(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static VaultRelativePath of(String rawPath) {
        return new VaultRelativePath(rawPath);
    }

    public boolean isWithinVault() {
        if (isEmpty()) {
            return false;
        }
        if (isAbsolute() || usesWindowsSeparator()) {
            return false;
        }
        return hasOnlyOrdinarySegments();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VaultRelativePath that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "VaultRelativePath[value=" + value + "]";
    }

    private boolean isEmpty() {
        return value.isEmpty();
    }

    private boolean isAbsolute() {
        return value.startsWith("/");
    }

    private boolean usesWindowsSeparator() {
        return value.contains("\\");
    }

    private boolean hasOnlyOrdinarySegments() {
        return Arrays.stream(value.split("/", -1)).noneMatch(this::isTraversalOrEmptySegment);
    }

    private boolean isTraversalOrEmptySegment(String segment) {
        return segment.isEmpty() || segment.equals(".") || segment.equals("..");
    }
}
```

The constructor stays `private` — `of(String)` is the sole public construction path, restoring the plan's "Constructor Method, never bare `new`" requirement. `equals()`/`hashCode()`/`toString()` are hand-written (value-based, matching what the record would have derived) since a `record` would force a public canonical constructor. `Objects.requireNonNull(value, "value")` in the constructor rejects `null` before any query method sees it and gives the null-rejection test a stable message to assert on. `isWithinVault()` reads as a table of contents (empty → absolute/Windows-style → traversal segments) with each rule named instead of inlined; the guard predicate is named `isEmpty()` (not `isBlank()` — it only checks emptiness, not whitespace).

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=VaultRelativePathTest`
Expected: PASS — 9 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultRelativePath.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/VaultRelativePathTest.java
git commit -m "feat(publication-exporter): add VaultRelativePath path-safety value type"
```

Note on scope: a reviewer also flagged that this class does not enforce a `.md` extension (a parity gap with `bridge-client.js`'s client-side `validateNotePath`). That is a deliberate S01 scope boundary, not an oversight — BRG-01's in-scope text for this slice covers only "unsafe or absent note path," and extension validation belongs to a later slice's Markdown-handling scope. Do not add it here.

---

### Task 3: `Diagnostic` + `BridgeResponse` — schema-v2 value types

**Revised before implementation** per Task 2's resolution (see Task 2's "Revised a second time..." note): these are Whole Values built via named Constructor Methods (`blocking`, `blocked`), so — same as `VaultRelativePath` — they must NOT be Java `record`s. A public record's canonical constructor is necessarily public, which would give callers a second, unguarded construction path (`new Diagnostic(...)` / `new BridgeResponse(...)`) alongside the named factories, violating this plan's Global Constraint. Both are hand-written `final` classes with `private` constructors, hand-written `equals`/`hashCode`/`toString`, and constructor-time null validation — matching the pattern `VaultRelativePath` settled on.

Unlike `VaultRelativePath`, these two ARE serialized to JSON (Task 6's `SchemaConformanceTest`, Task 8's CLI output), and a plain class's bare-named accessors (`schemaVersion()`, `command()`, …) are not auto-detected as bean properties by Jackson the way a record's components are. Each accessor carries an explicit `@JsonProperty("...")` annotation (from `com.fasterxml.jackson.annotation`, already transitively available via the `jackson-databind` dependency — no `pom.xml` change needed) naming the exact schema-v2 field, so `new ObjectMapper().writeValueAsString(...)` produces byte-identical JSON to what the record would have produced.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/Diagnostic.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java`

**Interfaces:**
- Produces: `Diagnostic.blocking(String field, String message): Diagnostic` with accessors `field()`, `message()`, `blocking()`, plus hand-written `equals`/`hashCode`/`toString`; `BridgeResponse.blocked(String command, Diagnostic diagnostic): BridgeResponse` with accessors `schemaVersion()`, `command()`, `ok()`, `status()`, `diagnostics()`, `workspaceHealth()`, plus hand-written `equals`/`hashCode`/`toString` — consumed by Tasks 5, 6, 8. Both constructors are `private`; the named factory is the sole public construction path.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeResponseJsonTest {

    @Test
    void blockedResponseSerializesToSchemaV2Shape() throws Exception {
        BridgeResponse response = BridgeResponse.blocked(
                "inspect-publication",
                Diagnostic.blocking("note", "Note was not found in the vault."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals("inspect-publication", parsed.get("command").asText());
        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("metadata_blocked", parsed.get("status").asText());
        assertTrue(parsed.get("diagnostics").isArray());
        assertEquals(1, parsed.get("diagnostics").size());
        assertTrue(parsed.get("workspaceHealth").isArray());
        assertEquals(0, parsed.get("workspaceHealth").size());
    }

    @Test
    void blockedResponsesBuiltSeparatelyWithSameValuesAreEqual() {
        assertEquals(
                BridgeResponse.blocked("inspect-publication", Diagnostic.blocking("note", "msg")),
                BridgeResponse.blocked("inspect-publication", Diagnostic.blocking("note", "msg")));
    }

    @Test
    void diagnosticFieldIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> Diagnostic.blocking(null, "msg"));
        assertEquals("field", exception.getMessage());
    }

    @Test
    void bridgeResponseCommandIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> BridgeResponse.blocked(null, Diagnostic.blocking("note", "msg")));
        assertEquals("command", exception.getMessage());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: FAIL — compile error, `Diagnostic`/`BridgeResponse` do not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class Diagnostic {

    private final String field;
    private final String message;
    private final boolean blocking;

    private Diagnostic(String field, String message, boolean blocking) {
        this.field = Objects.requireNonNull(field, "field");
        this.message = Objects.requireNonNull(message, "message");
        this.blocking = blocking;
    }

    public static Diagnostic blocking(String field, String message) {
        return new Diagnostic(field, message, true);
    }

    @JsonProperty("field")
    public String field() {
        return field;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("blocking")
    public boolean blocking() {
        return blocking;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic that)) {
            return false;
        }
        return blocking == that.blocking && field.equals(that.field) && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, message, blocking);
    }

    @Override
    public String toString() {
        return "Diagnostic[field=" + field + ", message=" + message + ", blocking=" + blocking + "]";
    }
}
```

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class BridgeResponse {

    private final int schemaVersion;
    private final String command;
    private final boolean ok;
    private final String status;
    private final List<Diagnostic> diagnostics;
    private final List<Diagnostic> workspaceHealth;

    private BridgeResponse(
            int schemaVersion,
            String command,
            boolean ok,
            String status,
            List<Diagnostic> diagnostics,
            List<Diagnostic> workspaceHealth) {
        this.schemaVersion = schemaVersion;
        this.command = Objects.requireNonNull(command, "command");
        this.ok = ok;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = diagnostics;
        this.workspaceHealth = workspaceHealth;
    }

    public static BridgeResponse blocked(String command, Diagnostic diagnostic) {
        return new BridgeResponse(2, command, false, "metadata_blocked",
                List.of(diagnostic), List.of());
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
                && workspaceHealth.equals(that.workspaceHealth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, command, ok, status, diagnostics, workspaceHealth);
    }

    @Override
    public String toString() {
        return "BridgeResponse[schemaVersion=" + schemaVersion + ", command=" + command
                + ", ok=" + ok + ", status=" + status + ", diagnostics=" + diagnostics
                + ", workspaceHealth=" + workspaceHealth + "]";
    }
}
```

Both constructors stay `private` — `blocking(...)`/`blocked(...)` are the sole public construction paths, same invariant Task 2 restored for `VaultRelativePath`. `@JsonProperty` on each bare-named accessor is what keeps JSON output identical to the record version: without it, Jackson's default bean-property detection would look for `getSchemaVersion()`-style names and silently miss every field.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=BridgeResponseJsonTest`
Expected: PASS — 4 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/Diagnostic.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/BridgeResponseJsonTest.java
git commit -m "feat(publication-exporter): add Diagnostic and BridgeResponse schema-v2 value types"
```

---

### Task 4: `VaultReader` port + `NullVaultReader`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java`

**Interfaces:**
- Consumes: `VaultRelativePath` (Task 2).
- Produces: `VaultReader#exists(VaultRelativePath): boolean` and `VaultReader.createNull(VaultRelativePath...): VaultReader` (Nullables two-channel factory — default embedded behaviour is "nothing exists") — the factory, not the concrete `NullVaultReader` class, is what Tasks 5, 6, 8 consume. `VaultReader.create(Path)` is added in Task 7 once a real adapter exists to back it.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullVaultReaderTest {

    @Test
    void defaultConfigurationReportsNothingExists() {
        VaultReader reader = new NullVaultReader();
        assertFalse(reader.exists(VaultRelativePath.of("blog/anything.md")));
    }

    @Test
    void configuredPathReportsExists() {
        VaultRelativePath existing = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = new NullVaultReader(existing);
        assertTrue(reader.exists(existing));
        assertFalse(reader.exists(VaultRelativePath.of("blog/other.md")));
    }

    @Test
    void interfaceFactoryDefaultsToNothingExists() {
        VaultReader reader = VaultReader.createNull();
        assertFalse(reader.exists(VaultRelativePath.of("blog/anything.md")));
    }
}
```

Note: `NullVaultReaderTest` sits in the same package as `NullVaultReader`, so it is allowed to use the plain constructor directly (Nullables: "the plain constructor is the test seam" for the wrapper's own test) — the third test proves the public `VaultReader.createNull()` factory that every other consumer must use instead.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullVaultReaderTest`
Expected: FAIL — compile error, `VaultReader`/`NullVaultReader` do not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.vault;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }
}
```

```java
package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class NullVaultReader implements VaultReader {

    private final Set<String> existingPaths;

    NullVaultReader(VaultRelativePath... existing) {
        this.existingPaths = Arrays.stream(existing)
                .map(VaultRelativePath::value)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return existingPaths.contains(notePath.value());
    }
}
```

`NullVaultReader` is package-private on purpose: callers outside `vault` reach it only through `VaultReader.createNull(...)`, so the concrete adapter choice never leaks into `InspectPublicationHandler` or the CLI layer (oo-design-guide 2.1/2.2).

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=NullVaultReaderTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java
git commit -m "feat(publication-exporter): add VaultReader port with NullVaultReader"
```

---

### Task 5: `InspectPublicationHandler` — blocked-path domain logic

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`

**Interfaces:**
- Consumes: `VaultRelativePath#isWithinVault()`/`#value()` (Task 2), `VaultReader#exists(VaultRelativePath)` and `VaultReader.createNull(...)` (Task 4), `BridgeResponse.blocked`/`Diagnostic.blocking` (Task 3).
- Produces: `InspectPublicationHandler#inspect(VaultRelativePath, VaultReader): BridgeResponse` — consumed by Task 6 and Task 8.

**Design note (oo-design-guide 3.9):** `InspectPublicationHandler` has a single public method, which is the shape 3.9 warns about ("do not turn operations into classes"). It is kept as a class rather than a static method because it is the seam Task 8's CLI layer depends on and Task 6's conformance test exercises directly — a real collaborator boundary, not a bare function wrapped for its own sake. Noted as an intentional departure, not an oversight.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InspectPublicationHandlerTest {

    private final InspectPublicationHandler handler = new InspectPublicationHandler();

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note path escapes the vault root.",
                response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("blog/does-not-exist.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note was not found in the vault.",
                response.diagnostics().get(0).message());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: FAIL — compile error, `InspectPublicationHandler` does not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("note", "Note path escapes the vault root."));
        }
        if (!vaultReader.exists(notePath)) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("note", "Note was not found in the vault."));
        }
        throw new UnsupportedOperationException(
                "Valid-note inspection is not implemented until S02.");
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationHandlerTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java
git commit -m "feat(publication-exporter): add InspectPublicationHandler blocked-path logic"
```

---

### Task 6: `bridge-contract/schema-v2.json` + Java conformance test

**Files:**
- Create: `bridge-contract/schema-v2.json`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`

**Interfaces:**
- Consumes: `InspectPublicationHandler#inspect` (Task 5), `BridgeResponse`/`Diagnostic` (Task 3).
- Produces: the single-sourced schema file at repo root, read directly (never copied) by this test and by Task 9's JS test.

- [x] **Step 1: Create the schema file**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://personal-site.internal/bridge-contract/schema-v2.json",
  "title": "Publication bridge response (schema v2)",
  "type": "object",
  "required": ["schemaVersion", "command", "ok", "status", "diagnostics", "workspaceHealth"],
  "additionalProperties": true,
  "properties": {
    "schemaVersion": { "const": 2 },
    "command": {
      "type": "string",
      "enum": ["prepare", "inspect-publication", "mark-reviewed", "refresh-publication-queue"]
    },
    "ok": { "type": "boolean" },
    "status": { "type": "string" },
    "diagnostics": {
      "type": "array",
      "items": { "$ref": "#/definitions/diagnostic" }
    },
    "workspaceHealth": {
      "type": "array",
      "items": { "$ref": "#/definitions/diagnostic" }
    }
  },
  "definitions": {
    "diagnostic": {
      "type": "object",
      "required": ["field", "message", "blocking"],
      "additionalProperties": true,
      "properties": {
        "field": { "type": "string" },
        "message": { "type": "string" },
        "blocking": { "type": "boolean" }
      }
    }
  }
}
```

- [x] **Step 2: Write the failing test**

```java
package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.inspect.InspectPublicationHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaConformanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");

    @Test
    void blockedResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        InspectPublicationHandler handler = new InspectPublicationHandler();
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }
}
```

- [x] **Step 3: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=SchemaConformanceTest`
Expected: PASS (this test does not need a preceding RED step — it exercises Task 5's already-green logic against a new schema file, so the risk being tested is schema drift, not missing implementation)

- [x] **Step 4: Commit**

```bash
git add bridge-contract/schema-v2.json \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java
git commit -m "feat: single-source bridge-contract/schema-v2.json with Java conformance test"
```

---

### Task 7: `FilesystemVaultReader` — real adapter

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java` (add the `create(Path)` factory, now that a real adapter exists to back it)
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java`

**Interfaces:**
- Implements: `VaultReader` (Task 4).
- Produces: `VaultReader.create(Path vaultRoot): VaultReader` (Nullables' other channel, completing the pair started in Task 4) — consumed by Task 8's CLI wiring. `FilesystemVaultReader` itself stays package-private, same as `NullVaultReader`.

- [x] **Step 1: Write the failing test**

```java
package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemVaultReaderTest {

    @TempDir
    Path vaultRoot;

    @Test
    void reportsTrueForRealFile() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "# Real note");

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/real-note.md")));
    }

    @Test
    void reportsFalseForMissingFile() {
        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/missing.md")));
    }

    @Test
    void interfaceFactoryDelegatesToRealAdapter() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "# Real note");

        VaultReader reader = VaultReader.create(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/real-note.md")));
    }
}
```

Note: like `NullVaultReaderTest`, this test lives in the `vault` package, so it may construct `FilesystemVaultReader` directly for its own narrow integration coverage — this is the one place real filesystem I/O is deliberately exercised (Nullables: "only the lowest wrapper gets narrow integration tests against the real system"). The third test proves the public `VaultReader.create(...)` factory that Task 8 must use instead.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemVaultReaderTest`
Expected: FAIL — compile error, `FilesystemVaultReader` does not exist and `VaultReader.create` is undefined

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.vault;

import java.nio.file.Files;
import java.nio.file.Path;

final class FilesystemVaultReader implements VaultReader {

    private final Path vaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.vaultRoot = vaultRoot;
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return Files.exists(vaultRoot.resolve(notePath.value()));
    }
}
```

Modify `VaultReader.java` (Task 4) to add the real-adapter factory alongside the nulled one:

```java
package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    static VaultReader create(Path vaultRoot) {
        return new FilesystemVaultReader(vaultRoot);
    }

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemVaultReaderTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java
git commit -m "feat(publication-exporter): add FilesystemVaultReader real adapter"
```

---

### Task 8: `InspectPublicationCommand` + `Main` — CLI wiring and acceptance test

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java`

**Interfaces:**
- Consumes: `InspectPublicationHandler#inspect` (Task 5), `VaultReader.create(Path)` (Task 7), `VaultRelativePath.of` (Task 2), `BridgeResponse#ok()` (Task 3).
- Produces: the `publication-exporter inspect-publication --vault <path> --note <path> --review <path> --json` CLI entry point matching `bridge-client.js`'s real argv contract. Wiring this binary as the plugin's active `exporterBinary` is out of scope for S01.

- [x] **Step 1: Write the failing acceptance test**

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InspectPublicationCliAcceptanceTest {

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
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "../../etc/passwd.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertEquals(2, response.get("schemaVersion").asInt());
        assertEquals("inspect-publication", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
        assertEquals("Note path escapes the vault root.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void absentNoteProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "blog/does-not-exist.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void notePathWithShellMetacharactersIsTreatedAsLiteralData() throws Exception {
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "blog/note; touch pwned.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: FAIL — compile error, `Main`/`InspectPublicationCommand` do not exist

- [x] **Step 3: Write minimal implementation**

```java
package dev.eugene.publicationexporter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "publication-exporter", subcommands = { InspectPublicationCommand.class })
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

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.inspect.InspectPublicationHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "inspect-publication")
public final class InspectPublicationCommand implements Callable<Integer> {

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
        BridgeResponse response = new InspectPublicationHandler()
                .inspect(VaultRelativePath.of(notePath), vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

`reviewDirectory` is accepted (bridge-client.js always sends `--review`) but unused until review-workspace behaviour arrives in a later slice — a field-level Role Suggesting Name still communicates that intent while it sits idle.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml test -Dtest=InspectPublicationCliAcceptanceTest`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 5: Commit**

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InspectPublicationCliAcceptanceTest.java
git commit -m "feat(publication-exporter): wire inspect-publication CLI entry point"
```

---

### Task 9: JS-side conformance test in `obsidian-plugin`

**Files:**
- Create: `obsidian-plugin/tests/schema-conformance.test.cjs`

**Interfaces:**
- Consumes: `bridge-contract/schema-v2.json` (Task 6, read directly, never copied), `createBridgeClient` from `obsidian-plugin/bridge-client.js` (existing, exported at `bridge-client.js:237-240`).

- [x] **Step 1: Write the failing test**

```javascript
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { EventEmitter } = require("node:events");

const { createBridgeClient } = require("../bridge-client.js");

const SCHEMA_PATH = path.join(__dirname, "..", "..", "bridge-contract", "schema-v2.json");

function loadSchema() {
  return JSON.parse(fs.readFileSync(SCHEMA_PATH, "utf8"));
}

function resolveRef(ref, definitions) {
  const name = ref.replace("#/definitions/", "");
  return definitions[name];
}

function validateAgainstSchema(schema, instance, definitions) {
  definitions = definitions || schema.definitions || {};
  const errors = [];

  if (schema.$ref) {
    return validateAgainstSchema(resolveRef(schema.$ref, definitions), instance, definitions);
  }
  if (schema.const !== undefined && instance !== schema.const) {
    errors.push(`expected const ${schema.const}, got ${instance}`);
  }
  if (schema.enum && !schema.enum.includes(instance)) {
    errors.push(`expected one of [${schema.enum}], got ${instance}`);
  }
  if (schema.type === "object") {
    for (const key of schema.required || []) {
      if (!(key in instance)) errors.push(`missing required property "${key}"`);
    }
    for (const [key, propSchema] of Object.entries(schema.properties || {})) {
      if (key in instance) {
        errors.push(...validateAgainstSchema(propSchema, instance[key], definitions));
      }
    }
  }
  if (schema.type === "array") {
    for (const item of instance) {
      errors.push(...validateAgainstSchema(schema.items, item, definitions));
    }
  }
  if (schema.type === "boolean" && typeof instance !== "boolean") {
    errors.push(`expected boolean, got ${typeof instance}`);
  }
  if (schema.type === "string" && typeof instance !== "string") {
    errors.push(`expected string, got ${typeof instance}`);
  }
  if (schema.type === "integer" && !Number.isInteger(instance)) {
    errors.push(`expected integer, got ${instance}`);
  }
  return errors;
}

function blockedFixture(message) {
  return {
    schemaVersion: 2,
    command: "inspect-publication",
    ok: false,
    status: "metadata_blocked",
    diagnostics: [{ field: "note", message, blocking: true }],
    workspaceHealth: [],
  };
}

function fakeSpawnResult({ stdout, exitCode }) {
  return () => {
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    process.nextTick(() => {
      child.stdout.emit("data", Buffer.from(stdout));
      child.emit("close", exitCode, null);
    });
    return child;
  };
}

test("unsafe-path fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = blockedFixture("Note path escapes the vault root.");
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("absent-note fixture conforms to bridge-contract/schema-v2.json", () => {
  const schema = loadSchema();
  const fixture = blockedFixture("Note was not found in the vault.");
  const errors = validateAgainstSchema(schema, fixture);
  assert.deepEqual(errors, []);
});

test("plugin's real bridge client accepts a schema-conformant blocked response", async () => {
  const fixture = blockedFixture("Note was not found in the vault.");
  const client = createBridgeClient({
    spawn: fakeSpawnResult({ stdout: JSON.stringify(fixture), exitCode: 1 }),
    exporterRoot: "/tmp/exporter-root",
    vaultPath: "/tmp/vault",
    exporterBinary: "/tmp/exporter-root/publication-exporter",
  });

  const result = await client.run("inspect-publication", "blog/does-not-exist.md");
  assert.deepEqual(result, fixture);
});
```

Note: unlike Tasks 2–8, this task adds no new production code — `bridge-client.js` (existing) and `bridge-contract/schema-v2.json` (Task 6) both already exist by the time this task runs, so there is no RED phase to demonstrate; go straight to verifying green, same as Task 6's `SchemaConformanceTest`.

- [x] **Step 2: Run test to verify it passes**

Run: `node --test obsidian-plugin/tests/schema-conformance.test.cjs`
Expected: PASS — 3 tests, 0 failures

- [x] **Step 3: Commit**

```bash
git add obsidian-plugin/tests/schema-conformance.test.cjs
git commit -m "test(obsidian-plugin): add JS-side schema-v2 conformance test"
```

---

### Task 10: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Java suite**

Run: `mvn -f publication-exporter/pom.xml test`
Expected: `BUILD SUCCESS`, all tests from Tasks 2–8 passing

- [ ] **Step 2: Run the full JS suite (existing + new)**

Run: `node --test obsidian-plugin/tests/bridge-client.test.cjs obsidian-plugin/tests/schema-conformance.test.cjs`
Expected: all tests passing, no regressions in the pre-existing `bridge-client.test.cjs` suite

- [ ] **Step 3: Confirm OpenSpec status**

Run: `openspec status --change s01-plugin-readable-blocked-inspection`
Expected: `4/4 artifacts complete`

Governance follow-up (not part of this checklist, performed by the operator after review): close Haft problem `prob-20260803-a75ab1d8` against this implementation, then archive with `openspec archive --skip-specs s01-plugin-readable-blocked-inspection` per the tooling note in `specs/workflow-bridge/spec.md`.
