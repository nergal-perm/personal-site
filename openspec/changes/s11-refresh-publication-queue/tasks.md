# S11 — Truthful Workflow State and Queue Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementer subagents run as Codex Companion Tasks (model tier per task's stated complexity); after each task, run four parallel Codex Companion review passes: spec compliance, code quality, `/applying-sbpp`, `/oo-design-heuristics`. The final whole-branch review (after Task 10) is a separate Codex Companion Task running GPT 5.6 Sol at max effort.

**Goal:** `refresh-publication-queue` discovers already-admitted `publish: true` notes, classifies each into the
six-state workflow vocabulary via a classifier shared with `inspect-publication`, and reconciles only its own
`workflowStatus` frontmatter scalar — reporting `updated`/`unchanged`/`uncertain` counts. `prepare` gains an
additive write of the same scalar on its existing exit paths, making `translation_failed`/`stale` durably
reconstructable for the first time.

**Architecture:** Per `design.md` D1-D6: a new `workflow` package holds `WorkflowState` (the six vocabulary
constants), `WorkflowStateClassifier` (pure function), and the new `WorkflowStatusEditor` port + `Filesystem`/`Null`
adapters (temp-file + `ATOMIC_MOVE` + POSIX permission copy + hash-guard, reusing `PrepareHandler.sourceStillMatches()`'s
proven re-validate-before-commit shape). `Frontmatter` gains `withScalarSet(key, value)` — a byte-preserving
surgical rewrite over retained raw source, not a round-trip through its parsed `Map`. `VaultReader` gains
`listPublishCandidates()`, a cheap pre-filter reusing the existing `Frontmatter.parse(...).flag("publish")` call.
`InspectPublicationHandler` and the new `RefreshPublicationQueueHandler` (new `refresh` package) both call the
shared classifier. `PrepareHandler`'s three existing exit paths gain an additive `workflowStatusEditor.write(...)`
call each — no new branching. `BridgeResponse` gains a `queueRefreshed(...)` factory with three new nullable
count fields; `bridge-contract/schema-v2.json` (already anticipating `refresh-publication-queue` in its `command`
enum) gains their schema entries.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson, `com.networknt:json-schema-validator` (existing dep, already
used by `SchemaConformanceTest`) — no new dependency this slice.

## Global Constraints

- Nullables: `NullWorkflowStatusEditor` and `NullVaultReader`'s `listPublishCandidates()` are proven against
  their real counterparts via parallel test methods asserting identical behavior — the same style
  `NullApprovedSnapshotWorkspaceTest`/`FilesystemApprovedSnapshotWorkspaceTest` already use (no shared abstract
  contract base class exists in this codebase for workspace-style ports; don't introduce one here either).
- No mocking libraries. State-based assertions only. Fault injection (where needed) uses the same
  package-private functional-interface constructor-overload seam `FilesystemCandidateWorkspace`'s
  `MoveOperation` already established.
- Outside-in TDD: one failing CLI acceptance test for `refresh-publication-queue` first, in-memory adapters
  wired in, then the real `FilesystemWorkflowStatusEditor`/`FilesystemVaultReader.listPublishCandidates()`
  proven behind the same behavioral contract.
- In-memory acceptance subset stays under 1 second (implementation-plan.md's slice rule).
- Every new/changed public method keeps `Objects.requireNonNull(x, "x")` guards, matching every existing
  handler/adapter in this codebase.
- One new production boundary adapter for this slice: `WorkflowStatusEditor`. `Frontmatter.withScalarSet(...)`
  and `VaultReader.listPublishCandidates()` extend existing ports/classes, they are not new adapters.
- `translating` (BRG-05 vocabulary value) and duplicate/aliased-workflow-key detection (TRP-06) are
  scope-pinned per `scope-pins.md` — do not add fixtures or detection code for either; they are not reachable
  given this slice's actual inputs (see `scope-pins.md` for the full reasoning).
- `obsidian-plugin/` is not touched by this slice. `bridge-contract/schema-v2.json` is a shared, neutral
  contract file (not plugin-owned code) and is updated as part of Task 8 — its `command` enum already lists
  `refresh-publication-queue`, anticipating this slice.

---

## 1. `Frontmatter.withScalarSet(key, value)` — byte-preserving surgical rewrite

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java` (read
  first to match its existing test style/helper naming before adding cases)

**Interfaces:**
- Produces: `Frontmatter#withScalarSet(String key, String value)` returning the complete new note source
  `String`, used by `FilesystemWorkflowStatusEditor` (Task 4).
- Consumes: nothing new — extends the existing `parse(String noteSource)` machinery.

`grep -n "class Frontmatter" -A 5 publication-exporter/src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java`
first to confirm the current field layout before editing.

- [x] 1.1 **Write failing tests for `withScalarSet`** — three cases: key already present (replaced in place, all
  other lines byte-identical), key absent (inserted immediately before the closing `---`, all existing lines
  byte-identical), and body/spacing/comment-adjacent lines untouched either way.

```java
@Test
void withScalarSetReplacesAnExistingKeyInPlace() {
    Frontmatter frontmatter = Frontmatter.parse("""
            ---
            publish: true
            workflowStatus: ready_for_review
            publicId: my-essay
            ---
            # Body

            Text.""");

    String updated = frontmatter.withScalarSet("workflowStatus", "ready_to_publish");

    assertEquals("""
            ---
            publish: true
            workflowStatus: ready_to_publish
            publicId: my-essay
            ---
            # Body

            Text.""", updated);
}

@Test
void withScalarSetInsertsAnAbsentKeyBeforeTheClosingDelimiter() {
    Frontmatter frontmatter = Frontmatter.parse("""
            ---
            publish: true
            publicId: my-essay
            ---
            Body.""");

    String updated = frontmatter.withScalarSet("workflowStatus", "not_prepared");

    assertEquals("""
            ---
            publish: true
            publicId: my-essay
            workflowStatus: not_prepared
            ---
            Body.""", updated);
}

@Test
void withScalarSetPreservesLineEndingsAndBodyExactly() {
    String source = "---\r\npublish: true\r\n---\r\nBody with trailing space \r\n";
    Frontmatter frontmatter = Frontmatter.parse(source);

    String updated = frontmatter.withScalarSet("workflowStatus", "stale");

    assertTrue(updated.startsWith("---\r\npublish: true\r\nworkflowStatus: stale\r\n---\r\n"));
    assertTrue(updated.endsWith("Body with trailing space \r\n"));
}
```

Read `FrontmatterTest.java` first and use whichever assertion imports/style it already has (`assertEquals` vs. a
custom helper) — match it, do not invent a new style.

- [x] 1.2 **Run to confirm compilation failure** (`withScalarSet` does not exist yet)

Run: `cd publication-exporter && mvn -q -Dtest=FrontmatterTest test`
Expected: compilation FAILURE.

- [x] 1.3 **Retain the raw source and implement `withScalarSet`**

```java
// new field, set in the private constructor alongside frontmatterValues/body
private final String originalSource;

private Frontmatter(Map<String, FrontmatterScalar> frontmatterValues, String body, String originalSource) {
    this.frontmatterValues = Map.copyOf(frontmatterValues);
    this.body = Objects.requireNonNull(body, "body");
    this.originalSource = Objects.requireNonNull(originalSource, "originalSource");
}

public static Frontmatter parse(String noteSource) {
    Objects.requireNonNull(noteSource, "noteSource");
    List<String> lines = noteSource.lines().toList();
    if (!startsWithFrontmatterDelimiter(lines)) {
        return new Frontmatter(Map.of(), noteSource, noteSource);
    }
    ParsedHeader header = parseHeader(lines);
    if (header == null) {
        return new Frontmatter(Map.of(), noteSource, noteSource);
    }
    return new Frontmatter(header.values(),
            bodyAfter(noteSource, header.closingDelimiterLineIndex()), noteSource);
}

public String withScalarSet(String key, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    List<String> lines = new ArrayList<>(originalSource.lines().toList());
    int closingIndex = closingDelimiterLineIndex(lines);
    String newLine = key + ": " + value;
    int existingIndex = existingKeyLineIndex(lines, key, closingIndex);
    if (existingIndex >= 0) {
        lines.set(existingIndex, newLine);
    } else {
        lines.add(closingIndex, newLine);
    }
    String lineEnding = originalSource.contains("\r\n") ? "\r\n" : "\n";
    String rebuilt = String.join(lineEnding, lines);
    boolean sourceEndsWithNewline = originalSource.endsWith("\n");
    return sourceEndsWithNewline ? rebuilt + lineEnding : rebuilt;
}

private static int closingDelimiterLineIndex(List<String> lines) {
    for (int index = 1; index < lines.size(); index++) {
        if (DELIMITER.equals(lines.get(index).strip())) {
            return index;
        }
    }
    throw new IllegalStateException("withScalarSet requires a note with frontmatter already present.");
}

private static int existingKeyLineIndex(List<String> lines, String key, int closingIndex) {
    for (int index = 1; index < closingIndex; index++) {
        int colon = lines.get(index).indexOf(':');
        if (colon >= 0 && lines.get(index).substring(0, colon).strip().equals(key)) {
            return index;
        }
    }
    return -1;
}
```

Add `import java.util.ArrayList;`. `withScalarSet` is only ever called on a note that already has a frontmatter
block (every call site in this slice is downstream of a successful `NoteIntake.admit()`, which already requires
one) — `closingDelimiterLineIndex` throwing `IllegalStateException` for a frontmatter-less note is intentional
fail-fast, not a case any production call site should ever hit.

- [x] 1.4 **Run to confirm the tests pass**

Run: `cd publication-exporter && mvn -q -Dtest=FrontmatterTest test`
Expected: PASS.

- [x] 1.5 **Run the full suite** to confirm the new `originalSource` field and constructor signature change
  didn't break any existing `Frontmatter` caller.

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [x] 1.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/note/Frontmatter.java \
        src/test/java/dev/eugene/publicationexporter/note/FrontmatterTest.java
git commit -m "feat(note): add Frontmatter.withScalarSet for byte-preserving scalar rewrite (TRP-06)"
```

---

## 2. `workflow` package: `WorkflowState` constants + `WorkflowStateClassifier`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/workflow/WorkflowState.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/workflow/WorkflowStateClassifier.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/workflow/WorkflowStateClassifierTest.java`

**Interfaces:**
- Produces: `WorkflowState` six `public static final String` constants (used by Tasks 3, 6, 7, 8, 9).
  `WorkflowStateClassifier#classify(boolean candidatePresent, boolean approvedPresent, Optional<String>
  persistedWorkflowStatus)` returning `String` (used by Tasks 6 and 9).
- Consumes: nothing — pure, no I/O, no ports.

- [x] 2.1 **Write `WorkflowState`**

```java
package dev.eugene.publicationexporter.workflow;

public final class WorkflowState {

    public static final String NOT_PREPARED = "not_prepared";
    public static final String METADATA_BLOCKED = "metadata_blocked";
    public static final String READY_FOR_REVIEW = "ready_for_review";
    public static final String READY_TO_PUBLISH = "ready_to_publish";
    public static final String TRANSLATION_FAILED = "translation_failed";
    public static final String STALE = "stale";
    public static final String TRANSLATING = "translating";

    private WorkflowState() {
    }
}
```

- [x] 2.2 **Write the failing `WorkflowStateClassifierTest`** — one case per branch:

```java
package dev.eugene.publicationexporter.workflow;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowStateClassifierTest {

    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    @Test
    void candidatePresentIsAlwaysReadyForReview() {
        assertEquals(WorkflowState.READY_FOR_REVIEW,
                classifier.classify(true, true, Optional.of(WorkflowState.STALE)));
        assertEquals(WorkflowState.READY_FOR_REVIEW,
                classifier.classify(true, false, Optional.empty()));
    }

    @Test
    void approvedOnlyIsReadyToPublish() {
        assertEquals(WorkflowState.READY_TO_PUBLISH,
                classifier.classify(false, true, Optional.empty()));
        assertEquals(WorkflowState.READY_TO_PUBLISH,
                classifier.classify(false, true, Optional.of(WorkflowState.TRANSLATION_FAILED)));
    }

    @Test
    void neitherPresentTrustsPersistedTranslationFailedOrStale() {
        assertEquals(WorkflowState.TRANSLATION_FAILED,
                classifier.classify(false, false, Optional.of(WorkflowState.TRANSLATION_FAILED)));
        assertEquals(WorkflowState.STALE,
                classifier.classify(false, false, Optional.of(WorkflowState.STALE)));
    }

    @Test
    void neitherPresentAndNoUsablePersistedValueDefaultsToNotPrepared() {
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.empty()));
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.of(WorkflowState.READY_FOR_REVIEW)));
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.of("garbage")));
    }
}
```

- [x] 2.3 **Run to confirm compilation failure** (`WorkflowStateClassifier` does not exist yet)

Run: `cd publication-exporter && mvn -q -Dtest=WorkflowStateClassifierTest test`
Expected: compilation FAILURE.

- [x] 2.4 **Implement `WorkflowStateClassifier`**

```java
package dev.eugene.publicationexporter.workflow;

import java.util.Optional;

public final class WorkflowStateClassifier {

    public String classify(boolean candidatePresent, boolean approvedPresent,
            Optional<String> persistedWorkflowStatus) {
        if (candidatePresent) {
            return WorkflowState.READY_FOR_REVIEW;
        }
        if (approvedPresent) {
            return WorkflowState.READY_TO_PUBLISH;
        }
        return persistedWorkflowStatus
                .filter(this::isDurableFailureState)
                .orElse(WorkflowState.NOT_PREPARED);
    }

    private boolean isDurableFailureState(String status) {
        return WorkflowState.TRANSLATION_FAILED.equals(status) || WorkflowState.STALE.equals(status);
    }
}
```

- [x] 2.5 **Run to confirm the tests pass**

Run: `cd publication-exporter && mvn -q -Dtest=WorkflowStateClassifierTest test`
Expected: PASS.

- [x] 2.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/workflow/WorkflowState.java \
        src/main/java/dev/eugene/publicationexporter/workflow/WorkflowStateClassifier.java \
        src/test/java/dev/eugene/publicationexporter/workflow/WorkflowStateClassifierTest.java
git commit -m "feat(workflow): add WorkflowState vocabulary and WorkflowStateClassifier (BRG-05)"
```

---

## 3. `WorkflowStatusEditor` port + `NullWorkflowStatusEditor`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/workflow/WorkflowStatusEditor.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/workflow/NullWorkflowStatusEditor.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/workflow/NullWorkflowStatusEditorTest.java`

**Interfaces:**
- Produces: `WorkflowStatusEditor#write(VaultRelativePath notePath, String expectedSourceHash, String newValue)`
  returning `WorkflowStatusEditor.Result` (a small sealed-style value: `written()` or `blocked(reason)`), used by
  Tasks 6, 7, 9. `WorkflowStatusEditor.create(Path vaultRoot)` / `createNull()` static factories, matching every
  other port in this codebase (`CandidateWorkspace.create/createNull`, `VaultReader.create/createNull`).
- Consumes: `VaultRelativePath` (existing, `vault` package).

- [ ] 3.1 **Write the port and its `Result` type**

```java
package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.nio.file.Path;
import java.util.Objects;

public interface WorkflowStatusEditor {

    Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue);

    static WorkflowStatusEditor create(Path vaultRoot) {
        return new FilesystemWorkflowStatusEditor(vaultRoot);
    }

    static WorkflowStatusEditor createNull() {
        return new NullWorkflowStatusEditor();
    }

    final class Result {

        private final boolean written;
        private final String blockedReason;

        private Result(boolean written, String blockedReason) {
            this.written = written;
            this.blockedReason = blockedReason;
        }

        public static Result written() {
            return new Result(true, null);
        }

        public static Result blocked(String reason) {
            return new Result(false, Objects.requireNonNull(reason, "reason"));
        }

        public boolean written() {
            return written;
        }

        public String blockedReason() {
            return blockedReason;
        }
    }
}
```

- [ ] 3.2 **Write the failing `NullWorkflowStatusEditorTest`**

```java
package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullWorkflowStatusEditorTest {

    private static final VaultRelativePath PATH = VaultRelativePath.of("blog/my-essay.md");
    private static final String SOURCE = "---\npublish: true\n---\nBody.";

    @Test
    void writeSucceedsWhenExpectedHashMatchesSeededSource() {
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(PATH, SOURCE));

        WorkflowStatusEditor.Result result =
                editor.write(PATH, ContentHash.sha256Hex(SOURCE), "ready_for_review");

        assertTrue(result.written());
        assertEquals("ready_for_review", editor.currentValue(PATH, "workflowStatus"));
    }

    @Test
    void writeBlocksWhenExpectedHashDoesNotMatch() {
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(PATH, SOURCE));

        WorkflowStatusEditor.Result result = editor.write(PATH, "stale-hash", "ready_for_review");

        assertFalse(result.written());
        assertEquals("Source changed since it was validated.", result.blockedReason());
    }
}
```

- [ ] 3.3 **Run to confirm compilation failure** (`NullWorkflowStatusEditor` does not exist yet)

Run: `cd publication-exporter && mvn -q -Dtest=NullWorkflowStatusEditorTest test`
Expected: compilation FAILURE.

- [ ] 3.4 **Implement `NullWorkflowStatusEditor`**, in-memory, using the same `Frontmatter.withScalarSet` the
  real adapter will use — this is intentional: the fake reuses the real domain logic (`Frontmatter`), it only
  fakes the I/O boundary, per nullables discipline.

```java
package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NullWorkflowStatusEditor implements WorkflowStatusEditor {

    private final Map<VaultRelativePath, String> sourceByPath;

    public NullWorkflowStatusEditor() {
        this(Map.of());
    }

    public NullWorkflowStatusEditor(Map<VaultRelativePath, String> sourceByPath) {
        this.sourceByPath = new LinkedHashMap<>(sourceByPath);
    }

    @Override
    public Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue) {
        Objects.requireNonNull(notePath, "notePath");
        Objects.requireNonNull(expectedSourceHash, "expectedSourceHash");
        Objects.requireNonNull(newValue, "newValue");
        String current = sourceByPath.get(notePath.value());
        if (current == null || !ContentHash.sha256Hex(current).equals(expectedSourceHash)) {
            return Result.blocked("Source changed since it was validated.");
        }
        String updated = Frontmatter.parse(current).withScalarSet("workflowStatus", newValue);
        sourceByPath.put(notePath.value(), updated);
        return Result.written();
    }

    public String currentValue(VaultRelativePath notePath, String key) {
        String current = sourceByPath.get(notePath.value());
        return current == null ? null : Frontmatter.parse(current).string(key).orElse(null);
    }
}
```

`sourceByPath` is keyed by `notePath.value()` (a `String`) not `VaultRelativePath` directly, matching
`NullVaultReader`'s existing convention (`VaultRelativePath` has no `equals`/`hashCode`-friendly map use
elsewhere beyond what `NullVaultReader` already does — read it first, it uses the same `String`-keyed pattern).

- [ ] 3.5 **Run to confirm the tests pass**

Run: `cd publication-exporter && mvn -q -Dtest=NullWorkflowStatusEditorTest test`
Expected: PASS.

- [ ] 3.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/workflow/WorkflowStatusEditor.java \
        src/main/java/dev/eugene/publicationexporter/workflow/NullWorkflowStatusEditor.java \
        src/test/java/dev/eugene/publicationexporter/workflow/NullWorkflowStatusEditorTest.java
git commit -m "feat(workflow): add WorkflowStatusEditor port and its in-memory Null adapter (TRP-06)"
```

---

## 4. `FilesystemWorkflowStatusEditor` — real adapter, atomic + byte/permission-preserving

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/workflow/FilesystemWorkflowStatusEditor.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/workflow/FilesystemWorkflowStatusEditorTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`
  (confinement pattern to mirror), `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
  lines 123-133 (`sourceStillMatches` — the guard shape being reused).

**Interfaces:**
- Produces: same `WorkflowStatusEditor` contract as Task 3's fake — this task's tests assert the identical
  written/blocked behavior against real files, plus real-filesystem-only guarantees (byte preservation,
  permission preservation, atomicity).
- Consumes: `Frontmatter.withScalarSet` (Task 1), `ContentHash.sha256Hex` (existing).

- [ ] 4.1 **Write failing tests**: hash-match writes and updates only the declared key; hash-mismatch blocks
  without touching the file; POSIX permissions are preserved; every other byte (including CRLF line endings and
  a body with unicode) is preserved.

```java
package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemWorkflowStatusEditorTest {

    @TempDir
    Path vaultRoot;

    private static final String SOURCE = "---\npublish: true\npublicId: my-essay\n---\n# Title\n\nBody тест.";

    @Test
    void writeUpdatesOnlyTheDeclaredKeyAndPreservesEveryOtherByte() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        WorkflowStatusEditor.Result result = editor.write(
                VaultRelativePath.of("blog/my-essay.md"), ContentHash.sha256Hex(SOURCE), "ready_for_review");

        assertTrue(result.written());
        String updated = Files.readString(note, StandardCharsets.UTF_8);
        assertEquals("---\npublish: true\npublicId: my-essay\nworkflowStatus: ready_for_review\n---\n"
                + "# Title\n\nBody тест.", updated);
    }

    @Test
    void writeBlocksWithoutTouchingTheFileWhenHashDoesNotMatch() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        WorkflowStatusEditor.Result result = editor.write(
                VaultRelativePath.of("blog/my-essay.md"), "stale-hash", "ready_for_review");

        assertFalse(result.written());
        assertEquals(SOURCE, Files.readString(note, StandardCharsets.UTF_8));
    }

    @Test
    void writePreservesPosixPermissions() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        Set<PosixFilePermission> restrictive = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(note, restrictive);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        editor.write(VaultRelativePath.of("blog/my-essay.md"), ContentHash.sha256Hex(SOURCE), "stale");

        assertEquals(restrictive, Files.getPosixFilePermissions(note));
    }

    private Path writeNote(String relativePath, String source) throws Exception {
        Path note = vaultRoot.resolve(relativePath);
        Files.createDirectories(note.getParent());
        Files.writeString(note, source, StandardCharsets.UTF_8);
        return note;
    }
}
```

- [ ] 4.2 **Run to confirm compilation failure**

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemWorkflowStatusEditorTest test`
Expected: compilation FAILURE.

- [ ] 4.3 **Implement `FilesystemWorkflowStatusEditor`**

```java
package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FilesystemWorkflowStatusEditor implements WorkflowStatusEditor {

    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";

    private final Path canonicalVaultRoot;

    FilesystemWorkflowStatusEditor(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    @Override
    public Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue) {
        Objects.requireNonNull(notePath, "notePath");
        Objects.requireNonNull(expectedSourceHash, "expectedSourceHash");
        Objects.requireNonNull(newValue, "newValue");
        Path real = resolveWithinVault(notePath)
                .orElseThrow(() -> new IllegalStateException("Note not found: " + notePath.value()));
        String current = readUtf8(real);
        if (!ContentHash.sha256Hex(current).equals(expectedSourceHash)) {
            return Result.blocked("Source changed since it was validated.");
        }
        String updated = Frontmatter.parse(current).withScalarSet(WORKFLOW_STATUS_KEY, newValue);
        atomicReplace(real, updated);
        return Result.written();
    }

    private void atomicReplace(Path target, String newContent) {
        Path temp = target.resolveSibling(target.getFileName() + ".workflow-" + UUID.randomUUID());
        try {
            Files.writeString(temp, newContent, StandardCharsets.UTF_8);
            copyPosixPermissionsIfSupported(target, temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            deleteQuietly(temp);
            throw new UncheckedIOException(error);
        }
    }

    private static void copyPosixPermissionsIfSupported(Path source, Path temp) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        if (view != null) {
            Files.setPosixFilePermissions(temp, view.readAttributes().permissions());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of the temp file after a failed write; the ATOMIC_MOVE never ran
        }
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> resolveWithinVault(VaultRelativePath notePath) {
        return candidateFor(notePath)
                .flatMap(FilesystemWorkflowStatusEditor::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
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

This mirrors `FilesystemVaultReader`'s confinement shape exactly (`canonicalize`/`resolveWithinVault`/
`candidateFor`/`isInsideVault`/`realPathOf`) per `design.md` D4 — every existing `Filesystem*` adapter in this
codebase implements this locally rather than sharing a utility; this one does the same. `Files.move(...,
ATOMIC_MOVE, REPLACE_EXISTING)` requires the temp file and target to be on the same filesystem, guaranteed here
since the temp file is created as a sibling of the target (`target.resolveSibling(...)`).

- [ ] 4.4 **Run to confirm the tests pass**

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemWorkflowStatusEditorTest test`
Expected: PASS. (The POSIX-permissions test is a no-op assertion-skip concern only on non-POSIX filesystems,
which this project's existing CI/dev environment is not — matches the assumption other filesystem tests in this
codebase already make.)

- [ ] 4.5 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 4.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/workflow/FilesystemWorkflowStatusEditor.java \
        src/test/java/dev/eugene/publicationexporter/workflow/FilesystemWorkflowStatusEditorTest.java
git commit -m "feat(workflow): add FilesystemWorkflowStatusEditor — atomic, byte/permission-preserving (TRP-06)"
```

---

## 5. `VaultReader.listPublishCandidates()`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`
- Create/modify test files for both (`NullVaultReaderTest.java`, `FilesystemVaultReaderTest.java` — read first,
  they likely already exist; add cases rather than assuming file layout).

**Interfaces:**
- Produces: `VaultReader#listPublishCandidates()` returning `List<VaultRelativePath>`, used by
  `RefreshPublicationQueueHandler` (Task 9).

- [ ] 5.1 **Add the method to the `VaultReader` interface**

```java
public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    String readSource(VaultRelativePath notePath);

    java.util.List<VaultRelativePath> listPublishCandidates();

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

- [ ] 5.2 **Write failing `NullVaultReader` tests, then implement**

```java
@Test
void listPublishCandidatesReturnsOnlyNotesWithPublishTrue() {
    VaultRelativePath published = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath draft = VaultRelativePath.of("blog/draft.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            published, "---\npublish: true\n---\nBody.",
            draft, "---\npublish: false\n---\nBody."));

    assertEquals(List.of(published), vaultReader.listPublishCandidates());
}
```

```java
// NullVaultReader — reuses the existing Frontmatter parser, same pre-filter the real adapter uses
@Override
public List<VaultRelativePath> listPublishCandidates() {
    return sourceByPath.entrySet().stream()
            .filter(entry -> Frontmatter.parse(entry.getValue()).flag("publish"))
            .map(entry -> VaultRelativePath.of(entry.getKey()))
            .toList();
}
```

Add `import dev.eugene.publicationexporter.note.Frontmatter;` and `import java.util.List;` to
`NullVaultReader.java`.

Run: `cd publication-exporter && mvn -q -Dtest=NullVaultReaderTest test` — confirm FAIL then PASS across the
write/implement steps as usual.

- [ ] 5.3 **Write failing `FilesystemVaultReader` tests, then implement**

```java
@Test
void listPublishCandidatesWalksTheVaultAndFiltersByPublishFlag(@TempDir Path vaultRoot) throws Exception {
    writeNote(vaultRoot, "blog/my-essay.md", "---\npublish: true\n---\nBody.");
    writeNote(vaultRoot, "blog/draft.md", "---\npublish: false\n---\nBody.");
    writeNote(vaultRoot, "scratch/todo.md", "No frontmatter here.");
    VaultReader vaultReader = VaultReader.create(vaultRoot);

    assertEquals(List.of(VaultRelativePath.of("blog/my-essay.md")), vaultReader.listPublishCandidates());
}

private void writeNote(Path vaultRoot, String relativePath, String source) throws IOException {
    Path note = vaultRoot.resolve(relativePath);
    Files.createDirectories(note.getParent());
    Files.writeString(note, source, StandardCharsets.UTF_8);
}
```

```java
// FilesystemVaultReader
@Override
public List<VaultRelativePath> listPublishCandidates() {
    try (var paths = Files.walk(canonicalVaultRoot)) {
        return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(this::hasPublishTrueFlag)
                .map(this::toVaultRelativePath)
                .toList();
    } catch (IOException error) {
        throw new UncheckedIOException(error);
    }
}

private boolean hasPublishTrueFlag(Path file) {
    return Frontmatter.parse(readUtf8(file)).flag("publish");
}

private VaultRelativePath toVaultRelativePath(Path file) {
    return VaultRelativePath.of(canonicalVaultRoot.relativize(file).toString().replace('\\', '/'));
}
```

Add `import dev.eugene.publicationexporter.note.Frontmatter;`, `import java.util.List;` to
`FilesystemVaultReader.java`. The `.replace('\\', '/')` keeps the returned path's separator convention
consistent with every other vault-relative path already produced/consumed in this codebase (`/`-separated), in
case the code ever runs on Windows.

- [ ] 5.4 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 5.5 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java \
        src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java \
        src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java \
        src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java \
        src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java
git commit -m "feat(vault): add VaultReader.listPublishCandidates as a cheap publish:true pre-filter"
```

---

## 6. `InspectPublicationHandler` uses the shared classifier (BRG-04 fix)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`
  (add a case; no schema change needed here, `status` is already `{"type": "string"}`)

**Interfaces:**
- Consumes: `WorkflowStateClassifier` (Task 2).
- Produces: unchanged public API (`InspectPublicationHandler(CandidateWorkspace, ApprovedSnapshotWorkspace)`,
  `inspect(VaultRelativePath, VaultReader)`) — only internal classification changes.

- [ ] 6.1 **Write the failing test for the previously-mishandled case**

```java
@Test
void approvedSnapshotWithNoCandidateReportsReadyToPublishNotNotPrepared() {
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
    approved.install(identity, "RU body", "EN body", "RU title", "EN title",
            "RU description.", "EN description.", ReferenceMap.empty(identity,
                    ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                    ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                    ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));
    InspectPublicationHandler handler =
            new InspectPublicationHandler(CandidateWorkspace.createNull(), approved);
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY));

    BridgeResponse response = handler.inspect(VaultRelativePath.of("blog/my-essay.md"), vaultReader);

    assertTrue(response.ok());
    assertEquals("ready_to_publish", response.status());
    assertEquals("absent", response.candidateState());
    assertEquals("ready", response.approvedSnapshotState());
}
```

Reuse the existing `VALID_ESSAY` constant already in this test file (its `publicId` must be `my-essay` to match
`identity` above — confirm before running, adjust the literal if the existing constant uses a different id).

- [ ] 6.2 **Run to confirm it fails** — current code returns `not_prepared` for this case.

Run: `cd publication-exporter && mvn -q -Dtest=InspectPublicationHandlerTest test`
Expected: FAILURE — `expected: <ready_to_publish> but was: <not_prepared>`.

- [ ] 6.3 **Wire the classifier into `inspect(...)`**

```java
private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
    NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
    if (!intake.accepted()) {
        return BridgeResponse.blocked(COMMAND, intake.diagnostics());
    }
    Optional<CandidatePaths> candidatePaths;
    Optional<CandidateSnapshot> candidateSnapshot;
    try {
        candidatePaths = candidateWorkspace.find(intake.identity());
        candidateSnapshot = candidatePaths.isPresent()
                ? candidateWorkspace.read(intake.identity())
                : Optional.empty();
    } catch (UncheckedIOException failure) {
        return candidateLookupFailure(IoFailureMessages.describe("Candidate lookup failed", failure));
    } catch (CandidateWorkspaceConfinementException failure) {
        return candidateLookupFailure("Candidate lookup failed: " + failure.getMessage());
    }
    if (candidatePaths.isPresent() && candidateSnapshot.isPresent()) {
        try {
            return readyForReviewResponse(intake.identity(), candidatePaths.get(), candidateSnapshot.get());
        } catch (UncheckedIOException failure) {
            return approvedLookupFailure(
                    IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        }
    }
    return notPreparedOrReadyToPublishResponse(intake.identity());
}

private BridgeResponse notPreparedOrReadyToPublishResponse(PublicationIdentity identity) {
    boolean approvedPresent;
    try {
        approvedPresent = approvedSnapshotWorkspace.read(identity).isPresent();
    } catch (UncheckedIOException failure) {
        return approvedLookupFailure(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
    } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
        return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
    }
    String status = classifier.classify(false, approvedPresent, Optional.empty());
    String approvedState = approvedPresent ? READY : ABSENT;
    return BridgeResponse.essayInspected(COMMAND, status, identity, ABSENT, approvedState, ABSENT, ABSENT, null);
}
```

Delete the old `notPreparedResponse(...)` method — replaced by `notPreparedOrReadyToPublishResponse(...)` above.
Add `import dev.eugene.publicationexporter.workflow.WorkflowStateClassifier;` and `import java.util.Optional;`
(the latter likely already imported — check first). The candidate-present branch (`readyForReviewResponse`) is
intentionally left untouched — its status is always `ready_for_review` regardless of approved state, per
BRG-04's first scenario ("neither is collapsed into `ready_to_publish`"), which the classifier's own
`candidatePresent` branch (Task 2) already encodes identically; wiring it in here too is optional polish, not
required, since the observable behavior is already correct without it — if you do wire it in for consistency,
confirm `readyForReviewResponse`'s existing tests still pass unchanged.

- [ ] 6.4 **Run to confirm the new test passes and nothing regressed**

Run: `cd publication-exporter && mvn -q -Dtest=InspectPublicationHandlerTest test`
Expected: PASS, all cases including the pre-existing "no publication work has started" (neither candidate nor
approved present) case, which must still return `not_prepared` — `classifier.classify(false, false,
Optional.empty())` returns `NOT_PREPARED` per Task 2, so this should hold without further changes; verify it does.

- [ ] 6.5 **Add a `SchemaConformanceTest` case for the new status value**

```java
@Test
void readyToPublishInspectionResponseConformsToSchemaV2() throws Exception {
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    BridgeResponse response = BridgeResponse.essayInspected(
            "inspect-publication", "ready_to_publish", identity,
            "absent", "ready", "absent", "absent", null);

    assertConformsToSchemaV2(response);
}
```

- [ ] 6.6 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 6.7 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java \
        src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java \
        src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java
git commit -m "fix(inspect): report ready_to_publish for an approved snapshot with no pending candidate (BRG-04)"
```

---

## 7. `PrepareHandler` additive `workflowStatus` writes

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces:**
- Consumes: `WorkflowStatusEditor` (Task 3/4), `WorkflowState` (Task 2).
- Produces (changed constructor): `PrepareHandler(TranslationWorker, CandidateWorkspace,
  ApprovedSnapshotWorkspace, WorkflowStatusEditor)` — every existing call site (`PrepareCommand`,
  `PrepareHandlerTest`) must be updated to pass one.

`grep -rn "new PrepareHandler(" publication-exporter/src` first to find every call site before editing the
constructor.

- [ ] 7.1 **Write failing tests**: success writes `ready_for_review`, translation failure writes
  `translation_failed`, staleness writes `stale`; a write failure (editor blocks) does not change what
  `prepare` itself returns.

```java
@Test
void successfulPrepareWritesReadyForReviewWorkflowStatus() {
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

    handler.prepare(path, vaultReader);

    assertEquals("ready_for_review", editor.currentValue(path, "workflowStatus"));
}

@Test
void translationFailureWritesTranslationFailedWorkflowStatus() {
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull(TranslationResult.failure("worker crashed")),
            new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

    handler.prepare(path, vaultReader);

    assertEquals("translation_failed", editor.currentValue(path, "workflowStatus"));
}
```

Read `PrepareHandlerTest.java` in full first to confirm the exact `TranslationWorker.createNull(...)` overloads
already available (a body/title/description success overload is confirmed present from the existing test at
line 66; check whether a failure-result overload already exists or needs adding as a small, separate,
test-only-scoped addition to `NullTranslationWorker` — if it doesn't exist, add
`TranslationWorker.createNull(TranslationResult result)` mirroring the existing factory's shape).

- [ ] 7.2 **Run to confirm compilation failure** (`PrepareHandler` has no four-argument constructor yet)

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test`
Expected: compilation FAILURE.

- [ ] 7.3 **Add the constructor parameter and three additive write call sites**

```java
private final WorkflowStatusEditor workflowStatusEditor;

public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
    this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
    this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    this.approvedSnapshotWorkspace =
            Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
}
```

```java
private BridgeResponse prepareAdmittedEssay(
        VaultRelativePath notePath, VaultReader vaultReader,
        PublicationIdentity identity, String ruBody, String ruTitle, String ruDescription) {
    String sourceHash = ContentHash.sha256Hex(vaultReader.readSource(notePath));
    TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
    TranslationResult translation = translateCandidate(job, ruBody, ruTitle, ruDescription);
    if (!translation.succeeded()) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
        return translationFailure(translation);
    }
    String enBody = translation.enBody();
    String enTitle = translation.enTitle();
    String enDescription = translation.enDescription();

    EnglishCandidateValidator.Result validation = validateEnglishCandidate(
            ruBody, enBody, enTitle, enDescription);
    if (!validation.valid()) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
        return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
    }
    if (!sourceStillMatches(notePath, vaultReader, identity, job)) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.STALE);
        return BridgeResponse.stale(COMMAND,
                Diagnostic.blocking("candidate", "Source note changed while translation was in progress."));
    }
    ReferenceMap referenceMap = buildReferenceMap(
            identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription);
    BridgeResponse response = installCandidate(identity, ruBody, enBody, ruTitle, enTitle,
            ruDescription, enDescription, referenceMap);
    if (response.ok()) {
        recordWorkflowStatus(notePath, sourceHash, WorkflowState.READY_FOR_REVIEW);
    }
    return response;
}

private void recordWorkflowStatus(VaultRelativePath notePath, String sourceHash, String status) {
    workflowStatusEditor.write(notePath, sourceHash, status);
}
```

`sourceHash` is computed once, up front, from the exact source bytes `NoteIntake` already validated — the same
bytes used throughout the rest of `prepareAdmittedEssay(...)`, so the guard is checking against the true
validation-time state, not a later re-read. `recordWorkflowStatus`'s return value (`Result`) is deliberately
ignored here per `design.md` D5 — a blocked/failed workflow-status write does not change what `prepare` itself
returns; it is best-effort bookkeeping for later `inspect`/`refresh` calls, not part of `prepare`'s own contract.
Add `import dev.eugene.publicationexporter.workflow.WorkflowState;` and
`import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;`.

- [ ] 7.4 **Update `PrepareCommand`'s wiring**

```java
@Override
public Integer call() throws Exception {
    VaultReader vaultReader = VaultReader.create(vaultRoot);
    CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
    ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
    WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.create(vaultRoot);
    TranslationWorker translationWorker = translationWorkerForJobRoot.apply(jobsDirectory);
    BridgeResponse response = new PrepareHandler(
                    translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
            .prepare(VaultRelativePath.of(notePath), vaultReader);

    System.out.println(new ObjectMapper().writeValueAsString(response));
    return response.ok() ? 0 : 1;
}
```

Add `import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;`.

- [ ] 7.5 **Update every remaining `new PrepareHandler(...)` call site** found by the Task 7 preamble `grep`
  (there is at least one more in `PrepareHandlerTest.java`'s other existing test methods, and possibly
  `SchemaConformanceTest.java` or `PrepareCliAcceptanceTest.java`) — pass `WorkflowStatusEditor.createNull()` (or
  a seeded `NullWorkflowStatusEditor`, matching whatever the test needs to assert) to each.

- [ ] 7.6 **Run to confirm the new tests pass and nothing else regressed**

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest,PrepareCliAcceptanceTest test`
Expected: PASS.

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 7.7 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java \
        src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(prepare): persist workflowStatus on every exit path (TRP-06)

Additive only — no new branching. Makes translation_failed and stale
durably reconstructable by inspect-publication/refresh-publication-queue
for the first time, since a failed prepare installs nothing else."
```

---

## 8. `BridgeResponse.queueRefreshed(...)` + shared schema update

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java`
- Modify: `bridge-contract/schema-v2.json`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java`

**Interfaces:**
- Produces: `BridgeResponse.queueRefreshed(String command, int updatedCount, int unchangedCount, int
  uncertainCount)`, used by `RefreshPublicationQueueHandler` (Task 9).

- [ ] 8.1 **Add the three nullable count fields and the factory to `BridgeResponse`**

```java
private final Integer updatedCount;
private final Integer unchangedCount;
private final Integer uncertainCount;

// extend the private constructor's parameter list and field assignments with these three,
// and extend every existing call inside this class's other factory methods with `null, null, null`
// for the three new trailing parameters (blocked, prepared, translationFailed, approved, stale,
// essayInspected all gain three trailing `null` arguments at their `new BridgeResponse(...)` call site)

public static BridgeResponse queueRefreshed(
        String command, int updatedCount, int unchangedCount, int uncertainCount) {
    return new BridgeResponse(2, command, true, "queue_refreshed", List.of(), List.of(),
            null, null, null, null, null, updatedCount, unchangedCount, uncertainCount);
}

@JsonProperty("updatedCount")
public Integer updatedCount() {
    return updatedCount;
}

@JsonProperty("unchangedCount")
public Integer unchangedCount() {
    return unchangedCount;
}

@JsonProperty("uncertainCount")
public Integer uncertainCount() {
    return uncertainCount;
}
```

Update `equals`/`hashCode`/`toString` to include the three new fields, following the exact pattern already used
for every other field in this class. `"queue_refreshed"` is `refresh-publication-queue`'s own top-level
`status` value — distinct from the six BRG-05 per-note workflow states (BRG-05 governs per-note/per-queue-member
classification, which is reported per-item inside the counts here, not as this response's own top-level
`status`).

- [ ] 8.2 **Add the schema properties and a conditional requiring them for `refresh-publication-queue`**

In `bridge-contract/schema-v2.json`, add to the top-level `properties` object:

```json
"updatedCount": { "type": "integer" },
"unchangedCount": { "type": "integer" },
"uncertainCount": { "type": "integer" }
```

And add a second `allOf`-style conditional alongside the existing top-level `if`/`then` (which already handles
`inspect-publication`/`ready_for_review`/`reviewPlan`) — restructure the top level to use `allOf` with two
conditionals rather than a single `if`/`then`, since JSON Schema draft-07 only allows one `if`/`then`/`else` per
schema object:

```json
"allOf": [
  {
    "if": {
      "type": "object",
      "required": ["command", "status"],
      "properties": {
        "command": { "const": "inspect-publication" },
        "status": { "const": "ready_for_review" }
      }
    },
    "then": { "type": "object", "required": ["reviewPlan"] }
  },
  {
    "if": {
      "type": "object",
      "required": ["command"],
      "properties": { "command": { "const": "refresh-publication-queue" } }
    },
    "then": {
      "type": "object",
      "required": ["updatedCount", "unchangedCount", "uncertainCount"]
    }
  }
],
```

Replace the existing top-level `"if": {...}, "then": {...}` pair with this `"allOf": [...]` block (same two
keys removed, one `allOf` key added) — everything else in the file (`definitions`, other `properties`) stays
unchanged.

- [ ] 8.3 **Add `SchemaConformanceTest` cases**

```java
@Test
void queueRefreshedResponseConformsToSchemaV2() throws Exception {
    BridgeResponse response = BridgeResponse.queueRefreshed("refresh-publication-queue", 2, 5, 1);

    assertConformsToSchemaV2(response);
}

@Test
void queueRefreshedResponseWithoutCountsDoesNotConformToSchemaV2() throws Exception {
    ObjectNode response = responseNode(BridgeResponse.queueRefreshed("refresh-publication-queue", 2, 5, 1));
    response.remove("updatedCount");

    assertDoesNotConformToSchemaV2(response);
}
```

- [ ] 8.4 **Run the schema conformance tests and the full suite**

Run: `cd publication-exporter && mvn -q -Dtest=SchemaConformanceTest test`
Expected: PASS, including every pre-existing case (the restructured `allOf` must not change behavior for any
existing command/status combination — this is the regression risk of this task).

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 8.5 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/bridge/BridgeResponse.java \
        src/test/java/dev/eugene/publicationexporter/bridge/SchemaConformanceTest.java
git -C .. add bridge-contract/schema-v2.json
git commit -m "feat(bridge): add queueRefreshed response shape to schema v2 (BRG-01, BRG-02)

obsidian-plugin/ is not touched — the plugin's own refresh-queue fixtures
are its own future work; the schema's command enum already anticipated
refresh-publication-queue and existing fixtures are unaffected."
```

---

## 9. `RefreshPublicationQueueHandler` + `RefreshPublicationQueueCommand`

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandler.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandlerTest.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/RefreshPublicationQueueCommand.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/RefreshPublicationQueueCliAcceptanceTest.java`
  (read `PrepareCliAcceptanceTest.java` first for the harness pattern this must match)
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`

**Interfaces:**
- Produces: `RefreshPublicationQueueHandler(CandidateWorkspace, ApprovedSnapshotWorkspace,
  WorkflowStatusEditor)#refresh(VaultReader) -> BridgeResponse`.
- Consumes: `VaultReader.listPublishCandidates()` (Task 5), `WorkflowStateClassifier` (Task 2),
  `WorkflowStatusEditor` (Task 3/4), `NoteIntake` (existing, unmodified), `BridgeResponse.queueRefreshed(...)`
  (Task 8).

- [ ] 9.1 **Write the failing acceptance test first**, an in-memory small vault covering the decisive and
  uncertain cases the functional design pass specified (not `translating` — scope-pinned, no fixture for it):

```java
package dev.eugene.publicationexporter.refresh;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshPublicationQueueHandlerTest {

    private static final VaultRelativePath STALE_SCALAR_NOTE = VaultRelativePath.of("blog/stale-scalar.md");
    private static final VaultRelativePath UP_TO_DATE_NOTE = VaultRelativePath.of("blog/up-to-date.md");
    private static final VaultRelativePath MALFORMED_NOTE = VaultRelativePath.of("blog/malformed.md");
    private static final VaultRelativePath NOT_PUBLISHED_NOTE = VaultRelativePath.of("blog/draft.md");

    @Test
    void refreshCorrectsStaleScalarsCountsUnchangedAndCountsUncertain() {
        String staleScalarSource = essaySource("stale-scalar", "workflowStatus: not_prepared");
        String upToDateSource = essaySource("up-to-date", "workflowStatus: not_prepared");
        String malformedSource = "---\npublish: true\npublicId: malformed\n---\nMissing required fields.";
        String draftSource = "---\npublish: false\npublicId: draft\n---\nNot a candidate.";

        Map<VaultRelativePath, String> notes = new LinkedHashMap<>();
        notes.put(STALE_SCALAR_NOTE, staleScalarSource);
        notes.put(UP_TO_DATE_NOTE, upToDateSource);
        notes.put(MALFORMED_NOTE, malformedSource);
        notes.put(NOT_PUBLISHED_NOTE, draftSource);
        VaultReader vaultReader = VaultReader.createNull(notes);

        CandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        PublicationIdentity upToDateIdentity = PublicationIdentity.of("blog", "essay", "up-to-date");
        candidateWorkspace.install(upToDateIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(upToDateIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));
        // stale-scalar has neither candidate nor approved: classifier says not_prepared, which already
        // matches its persisted scalar — so it is NOT the stale-scalar fixture as named; instead give it
        // an approved snapshot so classification (ready_to_publish) disagrees with its persisted
        // not_prepared scalar, making it the genuinely decisive/updated case:
        PublicationIdentity staleScalarIdentity = PublicationIdentity.of("blog", "essay", "stale-scalar");
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(staleScalarIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(staleScalarIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));

        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(notes);
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                candidateWorkspace, approvedSnapshotWorkspace, editor);

        BridgeResponse response = handler.refresh(vaultReader);

        assertEquals("queue_refreshed", response.status());
        assertEquals(1, response.updatedCount());   // stale-scalar: not_prepared -> ready_to_publish
        assertEquals(1, response.unchangedCount()); // up-to-date: candidate present -> ready_for_review,
                                                      // but its persisted scalar was seeded not_prepared —
                                                      // see note below, adjust seed to make this genuinely
                                                      // "unchanged" (persisted scalar already ready_for_review)
                                                      // before finalizing this fixture.
        assertEquals(0, response.uncertainCount());  // malformed/draft are excluded before classification,
                                                      // not counted uncertain — see step 9.3 note.
        assertEquals("ready_to_publish", editor.currentValue(STALE_SCALAR_NOTE, "workflowStatus"));
    }

    private String essaySource(String publicId, String workflowStatusLine) {
        return "---\npublish: true\npublicCollection: blog\npublicContentType: essay\npublicId: " + publicId
                + "\nid: id-" + publicId + "\ntitle: Title\ndescription: A description.\n"
                + workflowStatusLine + "\n---\nBody.";
    }
}
```

**Before implementing:** this fixture has two labeled inconsistencies left in it deliberately (see the inline
comments) — fix `UP_TO_DATE_NOTE`'s seeded `workflowStatus` to `ready_for_review` so its "unchanged" label is
accurate before this test is expected to pass, and confirm the exact count assertions once the real
`refresh(...)` behavior (step 9.3) is implemented. **This is intentional**: writing the fixture forces working
through each case by hand once, which is the point of writing the failing test first — do not skip straight to
guessing the implementation from the design doc alone.

Also decide and confirm with a comment in the test: does `MALFORMED_NOTE` (fails `NoteIntake.admit`, i.e.
`metadata_blocked`) count toward `uncertainCount`, or is it simply excluded from the queue entirely (not counted
in any of the three buckets)? Per `proposal.md`'s "A note that fails admission is simply one more per-note
outcome (`metadata_blocked`), not a partial-manifest concern" — a `metadata_blocked` note is a real, observed
outcome, not "uncertain" (uncertain per TRP-06/BRG-05 means classification-worthy evidence was ambiguous, not
"inadmissible"). Recommended: `metadata_blocked` notes are excluded from all three counts (they were never a
workflow-classifiable queue member to begin with), matching this test's `assertEquals(0,
response.uncertainCount())` above. If you choose differently, update this comment and the assertion together,
and record the choice in your task completion report — this is a genuine judgment call the design docs left
implicit.

- [ ] 9.2 **Run to confirm compilation failure**

Run: `cd publication-exporter && mvn -q -Dtest=RefreshPublicationQueueHandlerTest test`
Expected: compilation FAILURE.

- [ ] 9.3 **Implement `RefreshPublicationQueueHandler`**

```java
package dev.eugene.publicationexporter.refresh;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStateClassifier;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RefreshPublicationQueueHandler {

    private static final String COMMAND = "refresh-publication-queue";
    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;
    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    public RefreshPublicationQueueHandler(CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }

    public BridgeResponse refresh(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        int updated = 0;
        int unchanged = 0;
        int uncertain = 0;
        for (VaultRelativePath notePath : vaultReader.listPublishCandidates()) {
            ReconcileOutcome outcome = reconcileOne(notePath, vaultReader);
            switch (outcome) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case UNCERTAIN -> uncertain++;
                case EXCLUDED -> { /* not admitted; not a queue member at all */ }
            }
        }
        return BridgeResponse.queueRefreshed(COMMAND, updated, unchanged, uncertain);
    }

    private ReconcileOutcome reconcileOne(VaultRelativePath notePath, VaultReader vaultReader) {
        String source = vaultReader.readSource(notePath);
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return ReconcileOutcome.EXCLUDED;
        }
        boolean candidatePresent = candidateWorkspace.find(intake.identity()).isPresent();
        boolean approvedPresent = approvedSnapshotWorkspace.read(intake.identity()).isPresent();
        Optional<String> persisted = Frontmatter.parse(source).string(WORKFLOW_STATUS_KEY);
        String classified = classifier.classify(candidatePresent, approvedPresent, persisted);
        if (persisted.isPresent() && persisted.get().equals(classified)) {
            return ReconcileOutcome.UNCHANGED;
        }
        String sourceHash = ContentHash.sha256Hex(source);
        WorkflowStatusEditor.Result write = workflowStatusEditor.write(notePath, sourceHash, classified);
        return write.written() ? ReconcileOutcome.UPDATED : ReconcileOutcome.UNCERTAIN;
    }

    private enum ReconcileOutcome {
        UPDATED, UNCHANGED, UNCERTAIN, EXCLUDED
    }
}
```

- [ ] 9.4 **Run to confirm the acceptance test passes**, iterating on the fixture's seeded values from step 9.1
  until the assertions and the implementation agree (they must meet in the middle honestly — do not change the
  production `classify`/`reconcileOne` logic just to make an arbitrary fixture number match; if a count seems
  wrong, re-derive it by hand from `design.md` D2's classifier rules first).

Run: `cd publication-exporter && mvn -q -Dtest=RefreshPublicationQueueHandlerTest test`
Expected: PASS.

- [ ] 9.5 **Write the CLI wiring** — read `PrepareCommand.java` (already fully quoted in Task 7.4) and
  `MarkReviewedCommand.java` for the exact option-declaration style first:

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.refresh.RefreshPublicationQueueHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "refresh-publication-queue")
public final class RefreshPublicationQueueCommand implements Callable<Integer> {

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.create(vaultRoot);
        BridgeResponse response = new RefreshPublicationQueueHandler(
                        candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
                .refresh(vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

No `--note` option — per BRG-01's refresh clause, this command never accepts a current-note path.

- [ ] 9.6 **Register the subcommand in `Main.java`**

```java
@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class, InstallToSiteCommand.class, RefreshPublicationQueueCommand.class })
```

- [ ] 9.7 **Write the failing `RefreshPublicationQueueCliAcceptanceTest`**, reading
  `PrepareCliAcceptanceTest.java` first to match its exact harness style (temp vault/review directory setup,
  process/CLI invocation style — confirm whether existing CLI acceptance tests invoke `Main` in-process or via a
  spawned process, and match that exactly, do not invent a new harness style):

```java
@Test
void refreshReportsQueueRefreshedWithZeroCountsForAnEmptyVault() throws Exception {
    // follow the exact setup pattern PrepareCliAcceptanceTest already uses for --vault/--review temp
    // directories, then invoke `refresh-publication-queue --vault <dir> --review <dir> --json`
    // and assert the parsed JSON response has status "queue_refreshed" and all three counts are 0.
}
```

- [ ] 9.8 **Run to confirm it fails, then run everything to confirm it passes** once the CLI is wired
  (Tasks 9.5-9.6 should already make this pass without further production code changes — if it doesn't,
  the gap is in the acceptance test harness matching, not new handler logic).

Run: `cd publication-exporter && mvn -q -Dtest=RefreshPublicationQueueCliAcceptanceTest test`
Expected: PASS.

- [ ] 9.9 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] 9.10 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandler.java \
        src/test/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandlerTest.java \
        src/main/java/dev/eugene/publicationexporter/cli/RefreshPublicationQueueCommand.java \
        src/test/java/dev/eugene/publicationexporter/cli/RefreshPublicationQueueCliAcceptanceTest.java \
        src/main/java/dev/eugene/publicationexporter/cli/Main.java
git commit -m "feat(refresh): add refresh-publication-queue command (BRG-01, BRG-05, BRG-06, TRP-06)"
```

---

## 10. Whole-branch regression pass and requirement traceability check

**Files:** none created/modified — verification only.

- [ ] 10.1 **Run the complete Maven test suite**

Run: `cd publication-exporter && mvn -B test`
Expected: `Tests run: 4XX+, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. (Baseline before this slice was
420 tests, 0 failures — confirm the new total is strictly higher and nothing regressed.)

- [ ] 10.2 **Manually trace each requirement scenario to its covering test(s):**
  - BRG-01 refresh clause ("no current-note argument required or accepted") → Task 9's CLI command has no
    `--note` option; confirm `RefreshPublicationQueueCliAcceptanceTest` doesn't pass one.
  - BRG-04 "Approved snapshot exists with no pending candidate" (new scenario) → Task 6's
    `approvedSnapshotWithNoCandidateReportsReadyToPublishNotNotPrepared`.
  - BRG-05 "State predicate is met... both commands return the same state" → Task 2's
    `WorkflowStateClassifierTest` is the single source both `InspectPublicationHandler` and
    `RefreshPublicationQueueHandler` call; confirm both call sites (Tasks 6.3 and 9.3) actually go through it.
  - BRG-06 "Stale state is decisively observable... only the exporter-owned scalar is updated" → Task 9's
    acceptance test's updated-count case.
  - BRG-06 "Translation lock is active" → scope-pinned, no test (see `scope-pins.md`); confirm no test asserts
    behavior for this scenario that isn't actually implemented.
  - TRP-06 "Workflow state update is safe" → Task 4's
    `writeUpdatesOnlyTheDeclaredKeyAndPreservesEveryOtherByte` and `writePreservesPosixPermissions`.
  - TRP-06 "Source changed concurrently" → Task 4's `writeBlocksWithoutTouchingTheFileWhenHashDoesNotMatch` and
    Task 3's `NullWorkflowStatusEditorTest` equivalent.

- [ ] 10.3 **Confirm `git status` is clean except for this slice's new/modified files**, and that
  `bridge-contract/schema-v2.json` (repo root, outside `publication-exporter/`) was actually committed in
  Task 8 — it is easy to miss since it's outside the module directory the rest of this plan works in.

- [ ] 10.4 **This task has no commit of its own** — it is the checkpoint before subagent-driven-development
  hands off to the four parallel review passes and the final whole-branch review.
