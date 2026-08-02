# Publication-Frontier ID Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only, fail-closed semantic Prepare gate that requires a valid, globally unique, human-assigned source `id` on the current note and every uniquely resolved direct note target.

**Architecture:** A Nullable filesystem wrapper is the only new cut around the live-vault `java.nio.file.Files` reads owned by identity scanning; `testable-io` supplies configured reads and failures, while `output-tracking` records outbound filesystem requests as domain data. Existing Prepare state I/O remains unchanged. All vault walking, frontmatter parsing, ID normalization, duplicate detection, direct-link parsing, live resolution, frontier construction, and diagnostics remain production logic above the new cut and run unchanged in nulled tests; `PrepareWorkflow` then invokes the same admission component after acquiring the semantic lease and before `resolveEntry` or publication-state mutation.

**Tech Stack:** Java 21, Maven, JUnit Jupiter 6.1.0, SnakeYAML Engine 3.0.1 through `FrontmatterDocument`, `testable-io` 0.3.2, `output-tracking` 1.0.2, existing `MarkdownScanner`, existing semantic-operation locking.

## Global Constraints

- Binding authority is `.haft/decisions/dec-20260802-bind-the-vanilla-publication-frontier-zettelkast-b77c183c.md`; `docs/superpowers/specs/2026-08-02-publication-frontier-zettelkasten-identity.md` is its durable implementation-facing projection.
- A human assigns or repairs source IDs; the exporter never invents or edits one.
- A source ID is a nonblank YAML string in frontmatter field `id`; `/` and `\` are invalid, and no numeric-only or timestamp-only format is imposed.
- Source ID is semantic identity and may directly resolve an authored target. `publicId`, route, title, alias, path, and filename stem remain routing or lookup data and never become fallback identity.
- Admission covers the current note plus deduplicated uniquely resolved non-embedded inline wikilink targets, ordered current note first and targets by first authored occurrence.
- Escaped wikilinks and wikilinks inside protected Markdown spans are excluded by the same parser used by `SemanticReferencePlanner`; embeds are excluded from the identity frontier.
- Unresolved links retain the planner's existing nonblocking diagnostic and label fallback; ambiguous links remain blocking. The identity gate does not guess their destination or manufacture identity.
- Cross-check every authored target against both live descriptors and the current catalog. Live ambiguity, live/catalog disagreement, or a catalog target absent from the live identity scan blocks before catalog-backed planning.
- Identity failure returns `PrepareResult.status() == "metadata_blocked"` with blocking `PublicationDiagnostic` entries whose field is exactly `semantic-id`.
- Identity failure must not write a source note, catalog, translation job, candidate, approved snapshot, review baseline, or publication output. The existing `.semantic-links/operations.lock` leaf is the only permitted operational write.
- Do not call `VaultReferenceCatalog.reconcile(Path, List<VaultNoteDescriptor>)`, `writeAtomically(Path)`, or any migration apply path in this slice.
- Do not change persistent `pageRef`/`targetRef`, catalog schema, approval, publication projection, Obsidian code, or site code in this slice.
- `migrate-semantic-links --apply` remains separately approval-gated and is not an execution or test step in this plan.
- New scanner and admission tests follow Nullables: stub only the lowest `java.nio.file.Files` edge, configure reads/errors through production `NullFileSystem`, assert results and tracked requests as state, and add no mocks or test-local filesystem stubs.
- `FileSystem.createNull()` must perform no real I/O; bare construction yields an empty world, while configured sequences exhaust with the named `testable-io` failure.
- A narrow real-filesystem test documents the real adapter behavior that `NullFileSystem` mirrors. One existing-style `PrepareWorkflowTest` remains the workflow smoke check for gate placement: admission is nulled through the production seam while real vault/review/job state proves the no-mutation boundary.
- Implement only in an isolated worktree created at execution time; do not implement on `main` or `master` without explicit user authorization.
- The commission supplies immutable `COMMISSION_BASE_SHA`; before Task 1, verify the isolated worktree starts at that SHA. All final path and whitespace checks compare `COMMISSION_BASE_SHA..HEAD`, never only the last task commit.

---

## File Map and Commission Boundary

Only these fifteen files may change in the implementation commission:

- Modify `exporter-java/src/main/java/dev/eugene/astroexport/fs/FileSystem.java` — become the shared tracked read-only wrapper and define raw edge hooks plus request values.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/fs/RealFileSystem.java` — forward raw operations to `java.nio.file.Files`.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/fs/NullFileSystem.java` — configure the same raw operations with `testable-io`, loud defaults, and sequences.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteDescriptor.java` — retain declared and usable IDs and preserve the legacy real-scan entry point.
- Create `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteScanner.java` — own vault walking, note reads, frontmatter extraction, and global duplicate sanitization above the filesystem cut.
- Create `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteTargetResolver.java` — resolve one authored target against live descriptors without allocating identity.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferencePlanner.java` — expose ordered non-embed targets from the existing parser.
- Create `exporter-java/src/main/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmission.java` — construct the frontier and return blocking diagnostics without writes.
- Modify `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java` — invoke admission at the semantic Prepare boundary and return a pure blocked result.
- Create `exporter-java/src/test/java/dev/eugene/astroexport/fs/FileSystemTest.java` — pair nulled behavior with a narrow real-filesystem adapter test.
- Create `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteScannerTest.java` — test all scanner logic through `FileSystem.createNull()`.
- Create `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteTargetResolverTest.java` — test pure lookup layers and ambiguity.
- Modify `exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferencePlannerTest.java` — lock direct-target parser parity.
- Create `exporter-java/src/test/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmissionTest.java` — lock frontier completeness, ordering, uniqueness, catalog-change detection, and outbound reads.
- Modify `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java` — prove fail-closed integration and the real no-mutation boundary.

The commission must use this exact set for affected files, allowed paths, and locks. It must forbid `.haft`, `docs`, `obsidian-plugin`, `site`, `tools/astro-export/scripts`, `e2e`, `reports`, `exporter-java/review`, and every migration source/test path.

## Execution Preflight

Before Task 1, the isolated implementation worktree must be clean and exactly at the immutable base recorded by the prepared commission:

```bash
test -n "${COMMISSION_BASE_SHA:-}"
git cat-file -e "${COMMISSION_BASE_SHA}^{commit}"
test "$(git rev-parse HEAD)" = "$(git rev-parse "$COMMISSION_BASE_SHA")"
test -z "$(git status --porcelain)"
```

Expected: all four commands exit zero and print nothing. `COMMISSION_BASE_SHA` is commission data, not a value for an implementer to infer. If any command fails, stop and refresh or review the commission.

### Task 1: Complete the Nullable Filesystem Edge

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/FileSystem.java:1-28`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/RealFileSystem.java:1-25`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/fs/NullFileSystem.java:1-90`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/fs/FileSystemTest.java`

**Interfaces:**
- Consumes: `Files.walk(Path)`, `Files.isRegularFile(Path)`, `Files.isSymbolicLink(Path)`, `Path.toRealPath()`, and `Files.readString(Path, UTF_8)` at the third-party/JDK edge.
- Consumes: `StubFacade`, `RawResponse`, `ExceptionResponse`, and `SequencedResponse` from `testable-io` 0.3.2.
- Consumes: `OutputListener<T>.track(T)` and `OutputTracker<T>` from `output-tracking` 1.0.2.
- Produces: final shared `List<Path> FileSystem.walk(Path root) throws IOException`, `boolean isSymbolicLink(Path path)`, and the three existing filesystem methods. Each tracks once and delegates to a protected raw edge method; shared `FileSystem.walk` owns, closes, and materializes the raw `Stream<Path>` in both real and nulled modes.
- Produces: `OutputTracker<FileSystem.Request> FileSystem.trackRequests()`.
- Produces: `FileSystem.Request(Operation operation, Path path)` and enum values `WALK`, `IS_REGULAR_FILE`, `IS_SYMBOLIC_LINK`, `TO_REAL_PATH`, `READ_STRING`.
- Produces: `NullFileSystem.withWalk(Path, List<Path>)`, `withWalk(Path, Response)`, `withWalkError(Path, IOException)`, `withFile(Path, String)`, `withFile(Path, Response)`, `withReadError(Path, IOException)`, `withSymbolicLink(Path)`, `withRealPath(Path, Path)`, and `withRealPathError(Path, IOException)`.

- [ ] **Step 1: Write failing nulled and real-adapter tests**

Create `FileSystemTest.java`. These tests assert whole request values, configured response use, checked-exception recovery, sequence exhaustion, empty-world defaults, and one real `Files` exchange:

```java
package dev.eugene.astroexport.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ewc.utilities.testableio.exceptions.NoMoreResponsesException;
import ewc.utilities.testableio.responses.ExceptionResponse;
import ewc.utilities.testableio.responses.RawResponse;
import ewc.utilities.testableio.responses.SequencedResponse;
import ewc.utilities.testableio.tracking.OutputTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemTest {
  @TempDir
  Path temp;

  @Test
  void nulledFilesystemUsesConfiguredResponsesAndTracksRequests() throws Exception {
    Path vault = Path.of("/nulled/vault");
    Path note = vault.resolve("Note.md");
    Path resolved = Path.of("/nulled/real/Note.md");
    Path brokenLink = vault.resolve("Broken link.md");
    IOException denied = new IOException("Nulled FileSystem configured read failure");
    IOException broken = new IOException("Nulled FileSystem configured real-path failure");
    NullFileSystem fileSystem = FileSystem.createNull()
        .withWalk(vault, List.of(note))
        .withRealPath(note, resolved)
        .withRealPathError(brokenLink, broken)
        .withFile(note, new SequencedResponse(
            new RawResponse("first body"),
            new ExceptionResponse(new UncheckedIOException(denied))));
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();

    assertEquals(List.of(note), fileSystem.walk(vault));
    assertTrue(fileSystem.isRegularFile(note));
    assertFalse(fileSystem.isSymbolicLink(note));
    assertEquals(resolved, fileSystem.toRealPath(note));
    assertEquals(broken, assertThrows(IOException.class,
        () -> fileSystem.toRealPath(brokenLink)));
    assertEquals("first body", fileSystem.readString(note));
    assertEquals(denied, assertThrows(IOException.class, () -> fileSystem.readString(note)));
    NoMoreResponsesException exhausted =
        assertThrows(NoMoreResponsesException.class, () -> fileSystem.readString(note));
    assertEquals(
        "No more responses available for query: FileSystem.readString at " + note,
        exhausted.getMessage());

    assertEquals(List.of(
        new FileSystem.Request(FileSystem.Operation.WALK, vault),
        new FileSystem.Request(FileSystem.Operation.IS_REGULAR_FILE, note),
        new FileSystem.Request(FileSystem.Operation.IS_SYMBOLIC_LINK, note),
        new FileSystem.Request(FileSystem.Operation.TO_REAL_PATH, note),
        new FileSystem.Request(FileSystem.Operation.TO_REAL_PATH, brokenLink),
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note),
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note),
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note)), requests.data());
  }

  @Test
  void bareNulledFilesystemIsAnEmptyWorld() throws Exception {
    Path absent = Path.of("/nulled/absent.md");
    NullFileSystem fileSystem = FileSystem.createNull();

    assertEquals(List.of(), fileSystem.walk(Path.of("/nulled")));
    assertFalse(fileSystem.isRegularFile(absent));
    assertFalse(fileSystem.isSymbolicLink(absent));
    assertEquals(
        Path.of("/__NULLED_FILE_SYSTEM_DEFAULT_REAL_PATH__"),
        fileSystem.toRealPath(absent));
    assertEquals("Nulled FileSystem default content", fileSystem.readString(absent));
  }

  @Test
  void oneConfiguredRawResponseRepeatsAndTracksEveryConsumption() throws Exception {
    Path note = Path.of("/nulled/repeated.md");
    NullFileSystem fileSystem = FileSystem.createNull().withFile(note, "repeatable body");
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();

    assertEquals("repeatable body", fileSystem.readString(note));
    assertEquals("repeatable body", fileSystem.readString(note));
    assertEquals(List.of(
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note),
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note)),
        requests.data());
  }

  @Test
  void realFilesystemForwardsTheSameObservableProtocol() throws Exception {
    Path note = temp.resolve("Note.md");
    Files.writeString(note, "real body");
    Path link = temp.resolve("Note link.md");
    Files.createSymbolicLink(link, note.getFileName());
    FileSystem fileSystem = FileSystem.create();
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();

    assertEquals(Set.of(temp, note, link), Set.copyOf(fileSystem.walk(temp)));
    assertTrue(fileSystem.isRegularFile(note));
    assertFalse(fileSystem.isSymbolicLink(note));
    assertTrue(fileSystem.isRegularFile(link));
    assertTrue(fileSystem.isSymbolicLink(link));
    assertEquals(note.toRealPath(), fileSystem.toRealPath(note));
    assertEquals("real body", fileSystem.readString(note));
    assertEquals(List.of(
        new FileSystem.Request(FileSystem.Operation.WALK, temp),
        new FileSystem.Request(FileSystem.Operation.IS_REGULAR_FILE, note),
        new FileSystem.Request(FileSystem.Operation.IS_SYMBOLIC_LINK, note),
        new FileSystem.Request(FileSystem.Operation.IS_REGULAR_FILE, link),
        new FileSystem.Request(FileSystem.Operation.IS_SYMBOLIC_LINK, link),
        new FileSystem.Request(FileSystem.Operation.TO_REAL_PATH, note),
        new FileSystem.Request(FileSystem.Operation.READ_STRING, note)), requests.data());
  }
}
```

- [ ] **Step 2: Run the filesystem test and observe the missing-operation failures**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=FileSystemTest test`

Expected: FAIL because `walk`, `isSymbolicLink`, tracked request types, response converters, and request tracking do not exist.

- [ ] **Step 3: Extend the read-only filesystem contract**

Replace `FileSystem.java` with this abstract wrapper. It remains read-only, centralizes tracking for both real and nulled modes, and prevents admission code from acquiring a mutation capability:

```java
package dev.eugene.astroexport.fs;

import ewc.utilities.testableio.tracking.OutputListener;
import ewc.utilities.testableio.tracking.OutputTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Lowest tracked wrapper around java.nio filesystem I/O. */
public abstract class FileSystem {
  private final OutputListener<Request> requests = new OutputListener<>();

  public final List<Path> walk(Path root) throws IOException {
    requests.track(new Request(Operation.WALK, root));
    try (Stream<Path> paths = walkRaw(root)) {
      return paths.toList();
    } catch (UncheckedIOException traversalFailure) {
      throw traversalFailure.getCause();
    }
  }

  public final boolean isRegularFile(Path path) {
    requests.track(new Request(Operation.IS_REGULAR_FILE, path));
    return isRegularFileRaw(path);
  }

  public final boolean isSymbolicLink(Path path) {
    requests.track(new Request(Operation.IS_SYMBOLIC_LINK, path));
    return isSymbolicLinkRaw(path);
  }

  public final Path toRealPath(Path path) throws IOException {
    return tracked(Operation.TO_REAL_PATH, path, () -> toRealPathRaw(path));
  }

  public final String readString(Path path) throws IOException {
    return tracked(Operation.READ_STRING, path, () -> readStringRaw(path));
  }

  public final OutputTracker<Request> trackRequests() {
    return requests.trackOutput();
  }

  public static FileSystem create() {
    return new RealFileSystem();
  }

  public static NullFileSystem createNull() {
    return new NullFileSystem();
  }

  protected abstract Stream<Path> walkRaw(Path root) throws IOException;

  protected abstract boolean isRegularFileRaw(Path path);

  protected abstract boolean isSymbolicLinkRaw(Path path);

  protected abstract Path toRealPathRaw(Path path) throws IOException;

  protected abstract String readStringRaw(Path path) throws IOException;

  private <T> T tracked(Operation operation, Path path, IoQuery<T> query) throws IOException {
    requests.track(new Request(operation, path));
    return query.get();
  }

  @FunctionalInterface
  private interface IoQuery<T> {
    T get() throws IOException;
  }

  public record Request(Operation operation, Path path) { }

  public enum Operation {
    WALK,
    IS_REGULAR_FILE,
    IS_SYMBOLIC_LINK,
    TO_REAL_PATH,
    READ_STRING
  }
}
```

- [ ] **Step 4: Forward every real raw operation**

Replace `RealFileSystem` with this pure-forwarding adapter. Tracking, stream ownership, materialization, and lazy-failure normalization all happen in the final superclass path shared with the null:

```java
package dev.eugene.astroexport.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Production filesystem edge; all tracking is inherited from FileSystem. */
final class RealFileSystem extends FileSystem {
  @Override
  protected Stream<Path> walkRaw(Path root) throws IOException {
    return Files.walk(root);
  }

  @Override
  protected boolean isRegularFileRaw(Path path) {
    return Files.isRegularFile(path);
  }

  @Override
  protected boolean isSymbolicLinkRaw(Path path) {
    return Files.isSymbolicLink(path);
  }

  @Override
  protected Path toRealPathRaw(Path path) throws IOException {
    return path.toRealPath();
  }

  @Override
  protected String readStringRaw(Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 5: Configure the embedded stub through `testable-io`**

Make `NullFileSystem` extend `FileSystem`, add query IDs `WALK` and `SYMBOLIC`, and initialize its `StubFacade`. The converters are mandatory because `StubFacade.next(..., Class)` does not itself cast a raw response:

Use these exact imports and remove the old `UnconfiguredStubException` import:

```java
import ewc.utilities.testableio.core.QueryId;
import ewc.utilities.testableio.core.SourceId;
import ewc.utilities.testableio.core.StubFacade;
import ewc.utilities.testableio.exceptions.NoMoreResponsesException;
import ewc.utilities.testableio.responses.ExceptionResponse;
import ewc.utilities.testableio.responses.RawResponse;
import ewc.utilities.testableio.responses.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
```

```java
private static final QueryId WALK = new QueryId("walk");
private static final QueryId EXISTS = new QueryId("isRegularFile");
private static final QueryId SYMBOLIC = new QueryId("isSymbolicLink");
private static final QueryId REAL_PATH = new QueryId("toRealPath");
private static final QueryId READ = new QueryId("readString");

private final StubFacade responses = StubFacade.basic();

public NullFileSystem() {
  responses.setConverterForQuery(
      WALK,
      (content, metadata) -> pathList(content, metadata).stream());
  responses.setConverterForQuery(EXISTS, (content, metadata) -> (Boolean) content);
  responses.setConverterForQuery(SYMBOLIC, (content, metadata) -> (Boolean) content);
  responses.setConverterForQuery(REAL_PATH, (content, metadata) -> (Path) content);
  responses.setConverterForQuery(READ, (content, metadata) -> (String) content);
  responses.setDefaultStubForQuery(WALK, new RawResponse(List.of()));
  responses.setDefaultStubForQuery(EXISTS, new RawResponse(Boolean.FALSE));
  responses.setDefaultStubForQuery(SYMBOLIC, new RawResponse(Boolean.FALSE));
  responses.setDefaultStubForQuery(
      READ,
      new RawResponse("Nulled FileSystem default content"));
  responses.setDefaultStubForQuery(
      REAL_PATH,
      new RawResponse(Path.of("/__NULLED_FILE_SYSTEM_DEFAULT_REAL_PATH__")));
}

private static List<Path> pathList(Object content, Map<String, Object> metadata) {
  if (!(content instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof Path))) {
    throw new IllegalArgumentException("Nulled FileSystem walk response must be a List<Path>");
  }
  return values.stream().map(Path.class::cast).toList();
}
```

Add these configuration methods; `Response` overloads preserve `testable-io` single-response repetition and sequence exhaustion:

```java
public NullFileSystem withWalk(Path root, List<Path> paths) {
  return withWalk(root, new RawResponse(List.copyOf(paths)));
}

public NullFileSystem withWalk(Path root, Response response) {
  responses.setStubForQuerySource(source(root), WALK, response);
  return this;
}

public NullFileSystem withWalkError(Path root, IOException error) {
  return withWalk(root, new ExceptionResponse(new UncheckedIOException(error)));
}

public NullFileSystem withFile(Path path, String content) {
  return withFile(path, new RawResponse(content));
}

public NullFileSystem withFile(Path path, Response readResponse) {
  responses.setStubForQuerySource(source(path), EXISTS, new RawResponse(Boolean.TRUE));
  responses.setStubForQuerySource(source(path), SYMBOLIC, new RawResponse(Boolean.FALSE));
  responses.setStubForQuerySource(source(path), READ, readResponse);
  return this;
}

public NullFileSystem withReadError(Path path, IOException error) {
  return withFile(path, new ExceptionResponse(new UncheckedIOException(error)));
}

public NullFileSystem withSymbolicLink(Path path) {
  responses.setStubForQuerySource(source(path), EXISTS, new RawResponse(Boolean.TRUE));
  responses.setStubForQuerySource(source(path), SYMBOLIC, new RawResponse(Boolean.TRUE));
  return this;
}

public NullFileSystem withRealPath(Path path, Path resolved) {
  responses.setStubForQuerySource(source(path), REAL_PATH, new RawResponse(resolved));
  return this;
}

public NullFileSystem withRealPathError(Path path, IOException error) {
  responses.setStubForQuerySource(
      source(path),
      REAL_PATH,
      new ExceptionResponse(new UncheckedIOException(error)));
  return this;
}
```

Implement the raw edge methods exactly once in `NullFileSystem`. The three checked operations unwrap the configured `UncheckedIOException`; every sequence exhaustion is enriched with both operation and path:

```java
@Override
@SuppressWarnings("unchecked")
protected Stream<Path> walkRaw(Path root) throws IOException {
  try {
    return (Stream<Path>) responses.next(source(root), WALK, Stream.class);
  } catch (NoMoreResponsesException sequence) {
    throw exhausted("walk", root);
  } catch (UncheckedIOException wrapped) {
    throw wrapped.getCause();
  }
}

@Override
protected boolean isRegularFileRaw(Path path) {
  try {
    return responses.next(source(path), EXISTS, Boolean.class);
  } catch (NoMoreResponsesException sequence) {
    throw exhausted("isRegularFile", path);
  }
}

@Override
protected boolean isSymbolicLinkRaw(Path path) {
  try {
    return responses.next(source(path), SYMBOLIC, Boolean.class);
  } catch (NoMoreResponsesException sequence) {
    throw exhausted("isSymbolicLink", path);
  }
}

@Override
protected Path toRealPathRaw(Path path) throws IOException {
  try {
    return responses.next(source(path), REAL_PATH, Path.class);
  } catch (NoMoreResponsesException sequence) {
    throw exhausted("toRealPath", path);
  } catch (UncheckedIOException wrapped) {
    throw wrapped.getCause();
  }
}

@Override
protected String readStringRaw(Path path) throws IOException {
  try {
    return responses.next(source(path), READ, String.class);
  } catch (NoMoreResponsesException sequence) {
    throw exhausted("readString", path);
  } catch (UncheckedIOException wrapped) {
    throw wrapped.getCause();
  }
}

private static NoMoreResponsesException exhausted(String operation, Path path) {
  return new NoMoreResponsesException("FileSystem." + operation + " at " + path);
}

private static SourceId source(Path path) {
  return new SourceId(path.toString());
}
```

Do not track inside `NullFileSystem`; the final superclass methods are the one shared tracking path.

- [ ] **Step 6: Run the filesystem tests**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=FileSystemTest test`

Expected: PASS. The fake absolute paths remain untouched on disk, configured failures are deterministic, the third read exhausts its sequence, and the real adapter produces the same request vocabulary.

- [ ] **Step 7: Commit Task 1**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/fs/FileSystem.java exporter-java/src/main/java/dev/eugene/astroexport/fs/RealFileSystem.java exporter-java/src/main/java/dev/eugene/astroexport/fs/NullFileSystem.java exporter-java/src/test/java/dev/eugene/astroexport/fs/FileSystemTest.java
git commit -m "test: complete nullable filesystem boundary"
```

### Task 2: Nullable Vault Scanning and Lossless Identity Data

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteDescriptor.java:21-119`
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteScanner.java`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteScannerTest.java`

**Interfaces:**
- Consumes: Task 1's read-only `FileSystem` and request tracker.
- Produces: `VaultNoteDescriptor(String vaultPath, String filenameStem, String declaredStableNoteId, String stableNoteId, String title, List<String> aliases, List<String> diagnostics)`.
- Produces: `VaultNoteScanner.create()`, `VaultNoteScanner.createNull()`, `new VaultNoteScanner(FileSystem fileSystem)`, and `List<VaultNoteDescriptor> scan(Path vaultRoot)`. The Nullables factory chain composes a safe empty-world null; configured scanner tests continue to inject the held `NullFileSystem` through the constructor.
- Preserves: `VaultNoteDescriptor.scan(Path vaultRoot)` as a compatibility delegate to `VaultNoteScanner.create().scan(vaultRoot)` so migration code and existing catalog tests do not change.

- [ ] **Step 1: Write scanner tests with a nulled filesystem**

Create `VaultNoteScannerTest.java`. Use no temporary directory; the fake `/nulled/vault` world is supplied entirely through the production `NullFileSystem`:

```java
package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.FileSystem;
import dev.eugene.astroexport.fs.NullFileSystem;
import ewc.utilities.testableio.tracking.OutputTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class VaultNoteScannerTest {
  private static final Path VAULT = Path.of("/nulled/vault");

  @Test
  void retainsDeclaredIdsAndClassifiesEveryUnusableIdentity() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("valid.md", markdown("id: valid-id\ntitle: Valid"));
    notes.put("slash.md", markdown("id: invalid/id\ntitle: Slash"));
    notes.put("blank.md", markdown("id: '   '\ntitle: Blank"));
    notes.put("typed.md", markdown("id: 42\ntitle: Typed"));
    notes.put("missing.md", markdown("title: Missing"));
    notes.put("copy-a.md", markdown("id: copied-id\ntitle: Copy A"));
    notes.put("copy-b.md", markdown("id: copied-id\ntitle: Copy B"));
    ScannerHarness harness = scan(notes);
    Map<String, VaultNoteDescriptor> actual = harness.descriptors().stream()
        .collect(Collectors.toMap(VaultNoteDescriptor::vaultPath, Function.identity()));

    assertEquals("valid-id", actual.get("valid.md").declaredStableNoteId());
    assertEquals("valid-id", actual.get("valid.md").stableNoteId());
    assertEquals("invalid/id", actual.get("slash.md").declaredStableNoteId());
    assertNull(actual.get("slash.md").stableNoteId());
    assertTrue(actual.get("slash.md").diagnostics().stream()
        .anyMatch(value -> value.startsWith("invalid-stable-note-id:")));
    assertEquals("   ", actual.get("blank.md").declaredStableNoteId());
    assertTrue(actual.get("blank.md").diagnostics().contains("blank-stable-note-id"));
    assertNull(actual.get("typed.md").declaredStableNoteId());
    assertTrue(actual.get("typed.md").diagnostics().contains("non-string-stable-note-id"));
    assertTrue(actual.get("missing.md").diagnostics().contains("missing-stable-note-id"));
    assertEquals("copied-id", actual.get("copy-a.md").declaredStableNoteId());
    assertNull(actual.get("copy-a.md").stableNoteId());
    assertTrue(actual.get("copy-a.md").diagnostics().stream()
        .anyMatch(value -> value.startsWith("duplicate-stable-id:")));
    assertEquals(expectedScanRequests(notes.keySet().stream().map(VAULT::resolve).toList()),
        harness.requests().data());
  }

  @Test
  void reportsInvalidUtf8UnreadableAndMalformedNotesAndSkipsSymlinks() {
    Path invalid = VAULT.resolve("invalid.md");
    Path unreadable = VAULT.resolve("unreadable.md");
    Path malformed = VAULT.resolve("malformed.md");
    Path link = VAULT.resolve("link.md");
    NullFileSystem fileSystem = FileSystem.createNull()
        .withWalk(VAULT, List.of(invalid, unreadable, malformed, link))
        .withReadError(invalid, new MalformedInputException(1))
        .withReadError(unreadable, new AccessDeniedException(unreadable.toString()))
        .withFile(malformed, "---\ninvalid: [\n---\nBody.\n")
        .withSymbolicLink(link);

    List<VaultNoteDescriptor> actual = new VaultNoteScanner(fileSystem).scan(VAULT);

    assertEquals(List.of("invalid.md", "malformed.md", "unreadable.md"),
        actual.stream().map(VaultNoteDescriptor::vaultPath).toList());
    assertTrue(actual.getFirst().diagnostics().getFirst().startsWith("invalid-utf-8:"));
    assertTrue(actual.get(1).diagnostics().getFirst().startsWith("invalid-frontmatter:"));
    assertTrue(actual.getLast().diagnostics().getFirst().startsWith("unreadable-note:"));
  }

  @Test
  void filtersDirectoriesNonMarkdownFilesAndSymlinksAboveTheNullableCut() {
    Path directory = VAULT.resolve("private");
    Path text = VAULT.resolve("private/readme.txt");
    Path note = VAULT.resolve("private/Note.md");
    Path link = VAULT.resolve("private/Linked.md");
    NullFileSystem fileSystem = FileSystem.createNull()
        .withWalk(VAULT, List.of(directory, text, note, link))
        .withFile(text, "not Markdown")
        .withFile(note, markdown("id: note-id\ntitle: Note"))
        .withSymbolicLink(link);
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();

    List<VaultNoteDescriptor> actual = new VaultNoteScanner(fileSystem).scan(VAULT);

    assertEquals(List.of("private/Note.md"),
        actual.stream().map(VaultNoteDescriptor::vaultPath).toList());
    assertFalse(requests.data().contains(
        new FileSystem.Request(FileSystem.Operation.READ_STRING, text)));
    assertFalse(requests.data().contains(
        new FileSystem.Request(FileSystem.Operation.READ_STRING, link)));
    assertFalse(requests.data().contains(
        new FileSystem.Request(FileSystem.Operation.READ_STRING, directory)));
  }

  @Test
  void reportsConfiguredWalkFailureAndTracksOnlyTheAttemptedBoundaryCall() {
    IOException configured = new IOException("configured vault walk failure");
    NullFileSystem fileSystem = FileSystem.createNull().withWalkError(VAULT, configured);
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();

    UncheckedIOException actual = assertThrows(
        UncheckedIOException.class,
        () -> new VaultNoteScanner(fileSystem).scan(VAULT));

    assertEquals(configured, actual.getCause());
    assertEquals(List.of(new FileSystem.Request(FileSystem.Operation.WALK, VAULT)),
        requests.data());
  }

  private static ScannerHarness scan(LinkedHashMap<String, String> notes) {
    List<Path> paths = notes.keySet().stream().map(VAULT::resolve).toList();
    NullFileSystem fileSystem = FileSystem.createNull().withWalk(VAULT, paths);
    notes.forEach((relative, source) -> fileSystem.withFile(VAULT.resolve(relative), source));
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();
    return new ScannerHarness(new VaultNoteScanner(fileSystem).scan(VAULT), requests);
  }

  private static List<FileSystem.Request> expectedScanRequests(List<Path> paths) {
    java.util.ArrayList<FileSystem.Request> expected = new java.util.ArrayList<>();
    expected.add(new FileSystem.Request(FileSystem.Operation.WALK, VAULT));
    for (Path path : paths) {
      expected.add(new FileSystem.Request(FileSystem.Operation.IS_REGULAR_FILE, path));
      expected.add(new FileSystem.Request(FileSystem.Operation.IS_SYMBOLIC_LINK, path));
      expected.add(new FileSystem.Request(FileSystem.Operation.READ_STRING, path));
    }
    return List.copyOf(expected);
  }

  private static String markdown(String frontmatter) {
    return "---\n" + frontmatter + "\n---\nBody.\n";
  }

  private record ScannerHarness(
      List<VaultNoteDescriptor> descriptors,
      OutputTracker<FileSystem.Request> requests) { }
}
```

- [ ] **Step 2: Run the scanner test and observe the missing types/fields**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=VaultNoteScannerTest test`

Expected: FAIL because `VaultNoteScanner` and `declaredStableNoteId()` do not exist.

- [ ] **Step 3: Make `VaultNoteDescriptor` a lossless value and compatibility facade**

Change its record declaration and static scan method to this shape; keep defensive list copies:

```java
public record VaultNoteDescriptor(
    String vaultPath,
    String filenameStem,
    String declaredStableNoteId,
    String stableNoteId,
    String title,
    List<String> aliases,
    List<String> diagnostics) {

  public VaultNoteDescriptor {
    aliases = List.copyOf(aliases);
    diagnostics = List.copyOf(diagnostics);
  }

  public static List<VaultNoteDescriptor> scan(Path vaultRoot) {
    return VaultNoteScanner.create().scan(vaultRoot);
  }
}
```

Move the existing filesystem and parsing implementation into `VaultNoteScanner`; do not duplicate it in the record.

- [ ] **Step 4: Implement `VaultNoteScanner` above the I/O cut**

Create the scanner with production/null factories and a public constructor test seam:

```java
public final class VaultNoteScanner {
  private final FileSystem fileSystem;

  public static VaultNoteScanner create() {
    return new VaultNoteScanner(FileSystem.create());
  }

  public static VaultNoteScanner createNull() {
    return new VaultNoteScanner(FileSystem.createNull());
  }

  public VaultNoteScanner(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  public List<VaultNoteDescriptor> scan(Path vaultRoot) {
    Map<String, Integer> stableIdCounts = new LinkedHashMap<>();
    List<VaultNoteDescriptor> descriptors = new ArrayList<>();
    try {
      for (Path path : fileSystem.walk(vaultRoot)) {
        if (!fileSystem.isRegularFile(path)
            || fileSystem.isSymbolicLink(path)
            || !path.getFileName().toString().endsWith(".md")) {
          continue;
        }
        VaultNoteDescriptor descriptor = readNote(vaultRoot, path);
        descriptors.add(descriptor);
        if (descriptor.stableNoteId() != null) {
          stableIdCounts.merge(descriptor.stableNoteId(), 1, Integer::sum);
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot scan vault: " + vaultRoot, error);
    }
    return descriptors.stream()
        .map(descriptor -> sanitizeIdentity(descriptor, stableIdCounts))
        .sorted(Comparator.comparing(VaultNoteDescriptor::vaultPath))
        .toList();
  }
}
```

Keep filename, path, title, alias, unsafe-path, malformed-frontmatter, invalid-UTF-8, and unreadable-note behavior from the existing scanner. Replace ID extraction with this exact classification helper:

```java
private static Identity readIdentity(
    Map<String, Object> metadata,
    List<String> diagnostics) {
  if (!metadata.containsKey("id")) {
    diagnostics.add("missing-stable-note-id");
    return new Identity(null, null);
  }
  Object value = metadata.get("id");
  if (!(value instanceof String declared)) {
    diagnostics.add("non-string-stable-note-id");
    return new Identity(null, null);
  }
  String normalized = declared.strip();
  if (normalized.isBlank()) {
    diagnostics.add("blank-stable-note-id");
    return new Identity(declared, null);
  }
  if (normalized.contains("/") || normalized.contains("\\")) {
    diagnostics.add("invalid-stable-note-id: " + normalized);
    return new Identity(declared, null);
  }
  return new Identity(declared, normalized);
}

private record Identity(String declared, String usable) { }
```

Use these remaining scanner methods so every parsing and normalization branch stays above the nullable cut:

```java
private VaultNoteDescriptor readNote(Path vaultRoot, Path path) {
  String vaultPath = vaultRoot.relativize(path).toString().replace('\\', '/');
  List<String> diagnostics = new ArrayList<>();
  if (isUnsafeVaultPath(vaultPath)) {
    diagnostics.add("unsafe-vault-path: " + vaultPath);
  }

  String fileName = path.getFileName().toString();
  String filenameStem = fileName.endsWith(".md")
      ? fileName.substring(0, fileName.length() - 3)
      : fileName;
  String content;
  try {
    content = fileSystem.readString(path);
  } catch (MalformedInputException error) {
    diagnostics.add("invalid-utf-8: " + vaultPath);
    return descriptor(vaultPath, filenameStem, null, null, null, List.of(), diagnostics);
  } catch (IOException error) {
    diagnostics.add("unreadable-note: " + vaultPath);
    return descriptor(vaultPath, filenameStem, null, null, null, List.of(), diagnostics);
  }

  String declaredStableNoteId = null;
  String stableNoteId = null;
  String title = null;
  List<String> aliases = List.of();
  try {
    FrontmatterDocument frontmatter = FrontmatterDocument.parse(path, vaultPath, content);
    Identity identity = readIdentity(frontmatter.metadata(), diagnostics);
    declaredStableNoteId = identity.declared();
    stableNoteId = identity.usable();
    title = extractString(frontmatter.metadata().get("title"));
    aliases = extractAliases(frontmatter.metadata().get("aliases"));
  } catch (RuntimeException error) {
    diagnostics.add("invalid-frontmatter: " + vaultPath);
  }
  return descriptor(
      vaultPath,
      filenameStem,
      declaredStableNoteId,
      stableNoteId,
      title,
      aliases,
      diagnostics);
}

private static VaultNoteDescriptor descriptor(
    String vaultPath,
    String filenameStem,
    String declaredStableNoteId,
    String stableNoteId,
    String title,
    List<String> aliases,
    List<String> diagnostics) {
  return new VaultNoteDescriptor(
      vaultPath,
      filenameStem,
      declaredStableNoteId,
      stableNoteId,
      title,
      aliases,
      diagnostics);
}

private static VaultNoteDescriptor sanitizeIdentity(
    VaultNoteDescriptor descriptor,
    Map<String, Integer> stableIdCounts) {
  if (descriptor.stableNoteId() == null
      || stableIdCounts.getOrDefault(descriptor.stableNoteId(), 0) <= 1) {
    return descriptor;
  }
  List<String> diagnostics = new ArrayList<>(descriptor.diagnostics());
  diagnostics.add("copied-identity: " + descriptor.stableNoteId());
  diagnostics.add("duplicate-stable-id: " + descriptor.stableNoteId());
  return descriptor(
      descriptor.vaultPath(),
      descriptor.filenameStem(),
      descriptor.declaredStableNoteId(),
      null,
      descriptor.title(),
      descriptor.aliases(),
      diagnostics);
}

private static String extractString(Object value) {
  if (!(value instanceof String raw) || raw.isBlank()) {
    return null;
  }
  String normalized = raw.strip();
  return normalized.isBlank() ? null : normalized;
}

private static List<String> extractAliases(Object value) {
  if (value instanceof String alias) {
    String normalized = alias.strip();
    return normalized.isBlank() ? List.of() : List.of(normalized);
  }
  if (!(value instanceof List<?> values)) {
    return List.of();
  }
  LinkedHashSet<String> aliases = new LinkedHashSet<>();
  for (Object item : values) {
    if (item instanceof String alias && !alias.strip().isBlank()) {
      aliases.add(alias.strip());
    }
  }
  return List.copyOf(aliases);
}

private static boolean isUnsafeVaultPath(String vaultPath) {
  if (vaultPath.isBlank() || vaultPath.startsWith("/") || vaultPath.startsWith(".\\")) {
    return true;
  }
  return !Path.of(vaultPath).normalize().toString().replace('\\', '/').equals(vaultPath);
}
```

Import `FrontmatterDocument`, `FileSystem`, `IOException`, `UncheckedIOException`, `MalformedInputException`, `Path`, and the collection types used above. For duplicate usable IDs, preserve `descriptor.declaredStableNoteId()` while setting only `stableNoteId` to `null`. Preserve both existing `copied-identity` and `duplicate-stable-id` codes; Task 4 collapses them to one human repair item.

- [ ] **Step 5: Run scanner and existing catalog tests**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=FileSystemTest,VaultNoteScannerTest,VaultReferenceCatalogTest test`

Expected: PASS. Scanner tests perform no real I/O, and `VaultNoteDescriptor.scan(Path)` keeps existing migration/catalog callers source-compatible.

- [ ] **Step 6: Commit Task 2**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteDescriptor.java exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteScanner.java exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteScannerTest.java
git commit -m "feat: scan source identities through nullable filesystem"
```

### Task 3: Pure Live Target Resolution and Shared Direct-Link Parsing

**Files:**
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteTargetResolver.java`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferencePlanner.java:25-158`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteTargetResolverTest.java`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferencePlannerTest.java:12-223`

**Interfaces:**
- Consumes: Task 2's pure `VaultNoteDescriptor` values; no filesystem dependency exists in this task.
- Produces: `new VaultNoteTargetResolver(List<VaultNoteDescriptor> descriptors)` and `Resolution resolve(String authoredTarget)`.
- Produces: `VaultNoteTargetResolver.Resolution(Status status, VaultNoteDescriptor note, List<VaultNoteDescriptor> matches)` and enum values `RESOLVED`, `AMBIGUOUS`, `UNRESOLVED`; ambiguous results retain the distinct candidates for actionable diagnostics.
- Produces: `public static List<String> SemanticReferencePlanner.directTargets(String body)`; it preserves repeated authored occurrences and excludes embeds, escaped links, and protected spans.

- [ ] **Step 1: Write pure resolver tests**

Create `VaultNoteTargetResolverTest.java` using descriptor values directly:

```java
package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class VaultNoteTargetResolverTest {
  @Test
  void resolvesEachLookupLayerWithoutManufacturingIdentity() {
    VaultNoteTargetResolver resolver = new VaultNoteTargetResolver(List.of(
        note("notes/One.md", "id-one", "One", List.of()),
        note("notes/Two.md", "id-two", "Display Two", List.of()),
        note("notes/202608021200 Timed.md", "id-timed", "Timed title", List.of()),
        note("notes/Alias.md", "id-alias", "Alias note", List.of("Nickname"))));

    assertResolved(resolver.resolve("notes/One"), "notes/One.md");
    assertResolved(resolver.resolve("id-two"), "notes/Two.md");
    assertResolved(resolver.resolve("Timed"), "notes/202608021200 Timed.md");
    assertResolved(resolver.resolve("Display Two"), "notes/Two.md");
    assertResolved(resolver.resolve("Nickname"), "notes/Alias.md");
    assertEquals(VaultNoteTargetResolver.Status.UNRESOLVED, resolver.resolve("Absent").status());
  }

  @Test
  void reportsAmbiguityWhenAnyLookupCluesPointToDifferentNotes() {
    VaultNoteTargetResolver resolver = new VaultNoteTargetResolver(List.of(
        note("notes/A.md", "id-a", "Shared", List.of("Same")),
        note("notes/B.md", "id-b", "Shared", List.of("Same"))));

    VaultNoteTargetResolver.Resolution result = resolver.resolve("Shared");

    assertEquals(VaultNoteTargetResolver.Status.AMBIGUOUS, result.status());
    assertNull(result.note());
  }

  @Test
  void sourceIdAndFilenameStemCollisionIsAmbiguousRatherThanSilentlyPrioritized() {
    VaultNoteTargetResolver resolver = new VaultNoteTargetResolver(List.of(
        note("notes/Collision.md", "path-note-id", "Path note", List.of()),
        note("notes/Identity.md", "Collision", "Identity note", List.of())));

    VaultNoteTargetResolver.Resolution result = resolver.resolve("Collision");

    assertEquals(VaultNoteTargetResolver.Status.AMBIGUOUS, result.status());
    assertNull(result.note());
  }

  @Test
  void copiedDeclaredIdsRemainVisibleAsLiveAmbiguityAfterSanitization() {
    VaultNoteTargetResolver resolver = new VaultNoteTargetResolver(List.of(
        duplicate("notes/Copy A.md", "copied-id"),
        duplicate("notes/Copy B.md", "copied-id")));

    VaultNoteTargetResolver.Resolution result = resolver.resolve("copied-id");

    assertEquals(VaultNoteTargetResolver.Status.AMBIGUOUS, result.status());
    assertEquals(List.of("notes/Copy A.md", "notes/Copy B.md"),
        result.matches().stream().map(VaultNoteDescriptor::vaultPath).toList());
  }

  private static VaultNoteDescriptor note(
      String path,
      String id,
      String title,
      List<String> aliases) {
    String stem = java.nio.file.Path.of(path).getFileName().toString().replaceFirst("\\.md$", "");
    return new VaultNoteDescriptor(path, stem, id, id, title, aliases, List.of());
  }

  private static VaultNoteDescriptor duplicate(String path, String id) {
    String stem = java.nio.file.Path.of(path).getFileName().toString().replaceFirst("\\.md$", "");
    return new VaultNoteDescriptor(
        path,
        stem,
        id,
        null,
        stem,
        List.of(),
        List.of("copied-identity: " + id, "duplicate-stable-id: " + id));
  }

  private static void assertResolved(VaultNoteTargetResolver.Resolution result, String path) {
    assertEquals(VaultNoteTargetResolver.Status.RESOLVED, result.status());
    assertEquals(path, result.note().vaultPath());
  }
}
```

- [ ] **Step 2: Add a failing parser-parity test**

Add this method to `SemanticReferencePlannerTest`:

```java
@Test
void exposesOrderedDirectTargetsUsingThePlannerParser() {
  String body = """
      [[One]] and [[One|again]] and [[Two#Section|two]].
      ![[Embedded]]
      \\[[Escaped]]
      `[[Inline code]]`
      <!-- [[Commented]] -->
      """;

  assertEquals(
      List.of("One", "One", "Two"),
      SemanticReferencePlanner.directTargets(body));
}
```

- [ ] **Step 3: Run both focused tests and observe missing production APIs**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=VaultNoteTargetResolverTest,SemanticReferencePlannerTest test`

Expected: FAIL because `VaultNoteTargetResolver` and `directTargets(String)` do not exist.

- [ ] **Step 4: Implement the pure live resolver**

Create `VaultNoteTargetResolver.java` with this complete pure implementation. It considers every current lookup layer together: multiple clues may confirm one note, but clues that point to different notes are ambiguous. A syntactically valid declared ID remains a lookup candidate after duplicate sanitization, so `[[copied-id]]` resolves as live ambiguity instead of disappearing as unresolved. Task 4 cross-checks this live result against the existing catalog resolver rather than silently inheriting its path-first priority:

```java
package dev.eugene.astroexport.references;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Resolves an authored target against current vault-note descriptors. */
public final class VaultNoteTargetResolver {
  private static final Pattern TIMESTAMP = Pattern.compile("^\\d{12}\\s+");
  private final List<VaultNoteDescriptor> descriptors;

  public VaultNoteTargetResolver(List<VaultNoteDescriptor> descriptors) {
    this.descriptors = List.copyOf(descriptors);
  }

  public Resolution resolve(String authoredTarget) {
    String target = normalize(authoredTarget);
    LinkedHashSet<VaultNoteDescriptor> matches = new LinkedHashSet<>();
    for (VaultNoteDescriptor descriptor : descriptors) {
      if (pathLayer(descriptor, target)
          || stableIdLayer(descriptor, target)
          || stemLayer(descriptor, target)
          || titleLayer(descriptor, target)
          || aliasLayer(descriptor, target)) {
        matches.add(descriptor);
      }
    }
    if (matches.size() == 1) {
      VaultNoteDescriptor note = matches.iterator().next();
      return new Resolution(Status.RESOLVED, note, List.of(note));
    }
    if (matches.size() > 1) {
      return new Resolution(Status.AMBIGUOUS, null, List.copyOf(matches));
    }
    return new Resolution(Status.UNRESOLVED, null, List.of());
  }

  private boolean pathLayer(VaultNoteDescriptor descriptor, String target) {
    return stripExtension(path(target)).equals(stripExtension(path(descriptor.vaultPath())));
  }

  private boolean stableIdLayer(VaultNoteDescriptor descriptor, String target) {
    String lookupId = descriptor.stableNoteId();
    if (lookupId == null && descriptor.declaredStableNoteId() != null) {
      String declared = descriptor.declaredStableNoteId().strip();
      if (!declared.isBlank() && !declared.contains("/") && !declared.contains("\\")) {
        lookupId = declared;
      }
    }
    return lookupId != null && lookupId.equals(target);
  }

  private boolean stemLayer(VaultNoteDescriptor descriptor, String target) {
    String targetStem = stripTimestamp(stripExtension(stem(path(target))));
    String descriptorStem = stripTimestamp(stripExtension(stem(path(descriptor.vaultPath()))));
    return !targetStem.isBlank() && targetStem.equals(descriptorStem);
  }

  private boolean titleLayer(VaultNoteDescriptor descriptor, String target) {
    return descriptor.title() != null
        && !descriptor.title().isBlank()
        && descriptor.title().equals(target);
  }

  private boolean aliasLayer(VaultNoteDescriptor descriptor, String target) {
    return descriptor.aliases().contains(target);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String path(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private static String stripExtension(String value) {
    return value.endsWith(".md") ? value.substring(0, value.length() - 3) : value;
  }

  private static String stem(String value) {
    try {
      return Path.of(value).getFileName().toString();
    } catch (RuntimeException error) {
      return value;
    }
  }

  private static String stripTimestamp(String value) {
    return TIMESTAMP.matcher(value).replaceFirst("");
  }

  public record Resolution(
      Status status,
      VaultNoteDescriptor note,
      List<VaultNoteDescriptor> matches) {
    public Resolution {
      matches = List.copyOf(matches);
    }
  }

  public enum Status {
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED
  }
}
```

- [ ] **Step 5: Expose targets from the existing parser**

Add this method immediately before private `parse(String)`. Do not add another regular expression or Markdown scan:

```java
public static List<String> directTargets(String body) {
  return parse(body).stream()
      .filter(occurrence -> !occurrence.embed())
      .map(Occurrence::target)
      .toList();
}
```

- [ ] **Step 6: Run pure resolver and planner tests**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=VaultNoteTargetResolverTest,SemanticReferencePlannerTest,VaultReferenceResolverTest test`

Expected: PASS. The new resolver performs no I/O, and parser behavior remains one-source-of-truth.

- [ ] **Step 7: Commit Task 3**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/references/VaultNoteTargetResolver.java exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferencePlanner.java exporter-java/src/test/java/dev/eugene/astroexport/references/VaultNoteTargetResolverTest.java exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferencePlannerTest.java
git commit -m "feat: resolve direct targets from live descriptors"
```

### Task 4: Nullable Publication-Frontier Admission

**Files:**
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmission.java`
- Test: `exporter-java/src/test/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmissionTest.java`

**Interfaces:**
- Consumes: Task 2's `VaultNoteScanner`, Task 3's live resolver/parser APIs, and existing `VaultReferenceResolver.resolveTarget(...)` for a read-only live/catalog consistency cross-check.
- Produces: `PublicationFrontierIdentityAdmission.create()`, `PublicationFrontierIdentityAdmission.createNull()`, and `new PublicationFrontierIdentityAdmission(VaultNoteScanner scanner)`. The null factory composes the safe scanner null; configured tests inject a scanner backed by the held low-level null.
- Produces: `Result inspect(Path vaultRoot, String sourcePath, String body, VaultReferenceCatalog catalog)`.
- Produces: `Result(List<PublicationDiagnostic> diagnostics)` with `boolean admitted()`.

- [ ] **Step 1: Write admission tests with one signature-shielding Nullable harness**

Create `PublicationFrontierIdentityAdmissionTest.java`. The helper owns all construction/configuration, returns domain results plus the filesystem request tracker, and uses safe explicit defaults:

```java
package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.FileSystem;
import dev.eugene.astroexport.fs.NullFileSystem;
import ewc.utilities.testableio.tracking.OutputTracker;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class PublicationFrontierIdentityAdmissionTest {
  private static final Path VAULT = Path.of("/nulled/frontier-vault");
  private static final String IRRELEVANT_BODY = "No direct targets.";

  @Test
  void admitsValidCurrentNoteAndDeduplicatedDirectTargets() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-id\ntitle: Source",
        "[[Target]] and [[Target|again]] and ![[Embed]] and [[Absent]]."));
    notes.put("private/Target.md", markdown("id: target-id\ntitle: Target", "Private."));
    notes.put("private/Shared A.md", markdown("id: shared-a\ntitle: Shared", "Private."));
    notes.put("private/Shared B.md", markdown("id: shared-b\ntitle: Shared", "Private."));

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[Target]] and [[Target|again]] and ![[Embed]] and [[Absent]].",
        VaultReferenceCatalog.empty());

    assertTrue(actual.result().admitted());
    assertEquals(List.of(), actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void mapsBlankCurrentAndNonStringTargetIdsToCompleteRepairDiagnostics() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: '   '\ntitle: Source", "[[Typed]]."));
    notes.put("private/Typed.md", markdown("id: 42\ntitle: Typed", "Private."));

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[Typed]].",
        VaultReferenceCatalog.empty());

    assertEquals(List.of(
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "blog/Source.md: replace the blank frontmatter id with a globally unique string."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Typed.md: replace the non-string frontmatter id with a globally unique string.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void reportsBothDuplicationAndIdentityChangeForTheCurrentNote() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: changed-and-copied\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Copy.md", markdown("id: changed-and-copied\ntitle: Copy", "Private."));
    VaultReferenceCatalog catalog = catalogEntry(
        "vault-ref-source", "blog/Source.md", "source-before", "Source");

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        IRRELEVANT_BODY,
        catalog);

    assertEquals(List.of(
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "blog/Source.md: frontmatter id is not globally unique (changed-and-copied); "
                + "assign one note a new id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "blog/Source.md: source id changed from source-before to changed-and-copied; "
                + "restore the prior id or use an explicitly approved identity migration.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void multipleActiveCatalogEntriesForOneCurrentPathBlockDeterministically() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-now\ntitle: Source", IRRELEVANT_BODY));
    VaultReferenceCatalog catalog = catalogEntries(
        entry("vault-ref-z", "blog/Source.md", "source-now", "Source"),
        entry("vault-ref-a", "blog/Source.md", "source-before", "Source"));

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        IRRELEVANT_BODY,
        catalog);

    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "blog/Source.md: multiple active catalog entries claim this current path "
            + "(vault-ref-a, vault-ref-z); repair the catalog before Prepare.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void historicalCatalogIdStillBringsTheChangedLiveTargetIntoAdmission() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-id\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Target.md", markdown("id: target-now\ntitle: Target", "Private."));
    VaultReferenceCatalog catalog = catalogEntry(
        "vault-ref-target", "private/Target.md", "target-before", "Target");

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[target-before]].",
        catalog);

    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "private/Target.md: source id changed from target-before to target-now; "
            + "restore the prior id or use an explicitly approved identity migration.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void reportsAllDefectsInDeterministicFrontierOrder() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: invalid/source\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Missing.md", markdown("title: Missing", "Private."));
    notes.put("private/Copied.md", markdown("id: copied-id\ntitle: Copied", "Private."));
    notes.put("private/Unrelated copy.md", markdown("id: copied-id\ntitle: Unrelated", "Private."));
    notes.put("private/Changed.md", markdown("id: changed-now\ntitle: Changed", "Private."));
    VaultReferenceCatalog catalog = catalogEntry(
        "vault-ref-changed", "private/Changed.md", "changed-before", "Changed");

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[Missing]] then [[Copied]] then [[Copied|again]] then [[Changed]].",
        catalog);

    assertFalse(actual.result().admitted());
    assertEquals(List.of(
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "blog/Source.md: frontmatter id cannot contain slash or backslash (invalid/source)."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Missing.md: add a nonblank string frontmatter id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Copied.md: frontmatter id is not globally unique (copied-id); "
                + "assign one note a new id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Changed.md: source id changed from changed-before to changed-now; "
                + "restore the prior id or use an explicitly approved identity migration.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void historicalCatalogIdCannotBypassACopiedLiveIdentity() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-id\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Target.md", markdown("id: copied-id\ntitle: Target", "Private."));
    notes.put("private/Copy.md", markdown("id: copied-id\ntitle: Copy", "Private."));
    VaultReferenceCatalog catalog = catalogEntry(
        "vault-ref-target", "private/Target.md", "copied-id", "Target");

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[copied-id]] and [[Absent]] and ![[Embed]].",
        catalog);

    assertFalse(actual.result().admitted());
    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "blog/Source.md: direct target copied-id is a duplicated source id on "
            + "private/Copy.md, private/Target.md; assign one note a new id before Prepare.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void blocksLiveAmbiguityBeforeCatalogBackedPlanning() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-id\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Shared A.md", markdown("id: shared-a\ntitle: Shared", "Private."));
    notes.put("private/Shared B.md", markdown("id: shared-b\ntitle: Shared", "Private."));

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[Shared]].",
        VaultReferenceCatalog.empty());

    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "blog/Source.md: direct target Shared matches multiple live notes "
            + "(private/Shared A.md, private/Shared B.md); "
            + "disambiguate the authored link before Prepare.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void blocksWhenLiveAndCatalogResolutionChooseDifferentNotes() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("blog/Source.md", markdown("id: source-id\ntitle: Source", IRRELEVANT_BODY));
    notes.put("private/Live.md", markdown("id: collision\ntitle: Live", "Private."));
    notes.put("private/Catalog.md", markdown("id: catalog-id\ntitle: Catalog", "Private."));
    VaultReferenceCatalog catalog = catalogEntry(
        "vault-ref-catalog", "private/Catalog.md", "collision", "Catalog");

    AdmissionHarness actual = inspect(
        notes,
        "blog/Source.md",
        "[[collision]].",
        catalog);

    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "blog/Source.md: direct target collision resolves live to private/Live.md "
            + "but catalog planning resolves to private/Catalog.md; repair the semantic catalog "
            + "or authored link before Prepare.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void blocksWhenTheRequestedCurrentSourceWasAbsentFromTheLiveScan() {
    LinkedHashMap<String, String> notes = new LinkedHashMap<>();
    notes.put("private/Other.md", markdown("id: other-id\ntitle: Other", "Private."));

    AdmissionHarness actual = inspect(
        notes,
        "blog/Absent.md",
        IRRELEVANT_BODY,
        VaultReferenceCatalog.empty());

    assertFalse(actual.result().admitted());
    assertEquals(List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
        "semantic-id",
        "blog/Absent.md: current note was absent from the live identity scan.")),
        actual.result().diagnostics());
    assertOneVaultWalk(actual.requests());
  }

  @Test
  void blocksMalformedAndUnreadableFrontierNotesButDoesNotFollowASymlink() {
    Path source = VAULT.resolve("blog/Source.md");
    Path backslash = VAULT.resolve("private/Backslash.md");
    Path invalidUtf8 = VAULT.resolve("private/Invalid UTF8.md");
    Path unreadable = VAULT.resolve("private/Unreadable.md");
    Path malformed = VAULT.resolve("private/Malformed.md");
    Path link = VAULT.resolve("private/Link.md");
    List<Path> paths = List.of(source, backslash, invalidUtf8, unreadable, malformed, link);
    AdmissionHarness actual = inspect(
        paths,
        fileSystem -> fileSystem
            .withFile(source, markdown("id: source-id\ntitle: Source", IRRELEVANT_BODY))
            .withFile(backslash, markdown("id: 'bad\\id'\ntitle: Backslash", "Private."))
            .withReadError(invalidUtf8, new MalformedInputException(1))
            .withReadError(unreadable, new AccessDeniedException(unreadable.toString()))
            .withFile(malformed, "---\ninvalid: [\n---\nPrivate.\n")
            .withSymbolicLink(link),
        "blog/Source.md",
        "[[private/Backslash]] [[private/Invalid UTF8]] [[private/Unreadable]] "
            + "[[private/Malformed]] [[old-link-id]]",
        catalogEntry("vault-ref-link", "private/Link.md", "old-link-id", "Link"));

    assertEquals(List.of(
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Backslash.md: frontmatter id cannot contain slash or backslash (bad\\id)."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Invalid UTF8.md: save the note as valid UTF-8 before assigning or checking its id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Unreadable.md: make the note readable before assigning or checking its id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "private/Malformed.md: repair frontmatter before assigning or checking its id."),
        new dev.eugene.astroexport.validation.PublicationDiagnostic(
            "semantic-id",
            "blog/Source.md: catalog resolves direct target old-link-id to private/Link.md, "
                + "but that path is absent from the live identity scan.")),
        actual.result().diagnostics());
    assertFalse(actual.requests().data().contains(new FileSystem.Request(
        FileSystem.Operation.READ_STRING,
        link)));
    assertOneVaultWalk(actual.requests());
  }

  private static AdmissionHarness inspect(
      LinkedHashMap<String, String> notes,
      String sourcePath,
      String body,
      VaultReferenceCatalog catalog) {
    List<Path> paths = notes.keySet().stream().map(VAULT::resolve).toList();
    return inspect(
        paths,
        fileSystem -> notes.forEach((relative, source) ->
            fileSystem.withFile(VAULT.resolve(relative), source)),
        sourcePath,
        body,
        catalog);
  }

  private static AdmissionHarness inspect(
      List<Path> paths,
      Consumer<NullFileSystem> configure,
      String sourcePath,
      String body,
      VaultReferenceCatalog catalog) {
    NullFileSystem fileSystem = FileSystem.createNull().withWalk(VAULT, paths);
    configure.accept(fileSystem);
    OutputTracker<FileSystem.Request> requests = fileSystem.trackRequests();
    PublicationFrontierIdentityAdmission admission =
        new PublicationFrontierIdentityAdmission(new VaultNoteScanner(fileSystem));
    return new AdmissionHarness(
        admission.inspect(VAULT, sourcePath, body, catalog),
        requests);
  }

  private static void assertOneVaultWalk(OutputTracker<FileSystem.Request> requests) {
    assertEquals(1L, requests.data().stream()
        .filter(request -> request.equals(
            new FileSystem.Request(FileSystem.Operation.WALK, VAULT)))
        .count());
  }

  private static String markdown(String frontmatter, String body) {
    return "---\n" + frontmatter + "\n---\n" + body + "\n";
  }

  private static VaultReferenceCatalog catalogEntry(
      String pageRef,
      String currentPath,
      String stableId,
      String title) {
    return catalogEntries(entry(pageRef, currentPath, stableId, title));
  }

  private static VaultReferenceCatalog.CatalogEntry entry(
      String pageRef,
      String currentPath,
      String stableId,
      String title) {
    return new VaultReferenceCatalog.CatalogEntry(
        pageRef, currentPath, stableId, title, List.of(), List.of(),
        VaultReferenceCatalog.STATE_ACTIVE);
  }

  private static VaultReferenceCatalog catalogEntries(
      VaultReferenceCatalog.CatalogEntry... entries) {
    Map<String, VaultReferenceCatalog.CatalogEntry> byRef = java.util.Arrays.stream(entries)
        .collect(java.util.stream.Collectors.toMap(
            VaultReferenceCatalog.CatalogEntry::pageRef,
            java.util.function.Function.identity()));
    return new VaultReferenceCatalog(VaultReferenceCatalog.SCHEMA_VERSION, byRef);
  }

  private record AdmissionHarness(
      PublicationFrontierIdentityAdmission.Result result,
      OutputTracker<FileSystem.Request> requests) { }
}
```

- [ ] **Step 2: Run the admission test and observe the missing-class failure**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=PublicationFrontierIdentityAdmissionTest test`

Expected: FAIL because `PublicationFrontierIdentityAdmission` does not exist.

- [ ] **Step 3: Implement the Nullable admission component**

Create `PublicationFrontierIdentityAdmission.java` with the complete implementation below. Every authored target is checked live and through the catalog-backed resolver before planning: a historical catalog match still pulls its current path into identity validation, while live ambiguity, resolution disagreement, or an absent catalog path blocks. The `duplicateRepairAdded` flag deliberately collapses the scanner's compatibility-preserved `copied-identity` and `duplicate-stable-id` codes into one repair item for one frontier note; `comparableStableId` preserves the simultaneous change diagnostic when a valid declared ID was sanitized only because it is duplicated:

```java
package dev.eugene.astroexport.references;

import dev.eugene.astroexport.validation.PublicationDiagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates source-owned identity for one semantic publication frontier without writes. */
public final class PublicationFrontierIdentityAdmission {
  private final VaultNoteScanner scanner;

  public static PublicationFrontierIdentityAdmission create() {
    return new PublicationFrontierIdentityAdmission(VaultNoteScanner.create());
  }

  public static PublicationFrontierIdentityAdmission createNull() {
    return new PublicationFrontierIdentityAdmission(VaultNoteScanner.createNull());
  }

  public PublicationFrontierIdentityAdmission(VaultNoteScanner scanner) {
    this.scanner = scanner;
  }

  public Result inspect(
      Path vaultRoot,
      String sourcePath,
      String body,
      VaultReferenceCatalog catalog) {
    List<VaultNoteDescriptor> descriptors = scanner.scan(vaultRoot);
    Map<String, VaultNoteDescriptor> byPath = new LinkedHashMap<>();
    for (VaultNoteDescriptor descriptor : descriptors) {
      byPath.put(descriptor.vaultPath(), descriptor);
    }

    VaultNoteDescriptor current = byPath.get(sourcePath);
    if (current == null) {
      return new Result(List.of(new PublicationDiagnostic(
          "semantic-id",
          sourcePath + ": current note was absent from the live identity scan.")));
    }

    List<PublicationDiagnostic> diagnostics = new ArrayList<>();
    Set<String> includedPaths = new LinkedHashSet<>();
    Set<String> reportedResolutionProblems = new LinkedHashSet<>();
    appendFrontierNote(current, catalog, includedPaths, diagnostics);

    VaultNoteTargetResolver liveResolver = new VaultNoteTargetResolver(descriptors);
    VaultReferenceResolver catalogResolver = new VaultReferenceResolver(catalog);
    for (String target : SemanticReferencePlanner.directTargets(body)) {
      VaultNoteTargetResolver.Resolution live = liveResolver.resolve(target);
      VaultReferenceResolver.Resolution planned = catalogResolver.resolveTarget(target);

      if (live.status() == VaultNoteTargetResolver.Status.AMBIGUOUS) {
        addResolutionProblem(
            "ambiguous:" + target,
            sourcePath,
            ambiguityMessage(target, live.matches()),
            reportedResolutionProblems,
            diagnostics);
        continue;
      }

      if (live.status() == VaultNoteTargetResolver.Status.RESOLVED) {
        appendFrontierNote(live.note(), catalog, includedPaths, diagnostics);
        if (planned.status() == VaultReferenceResolver.Status.RESOLVED
            && !live.note().vaultPath().equals(planned.currentPath())) {
          addResolutionProblem(
              "disagreement:" + target,
              sourcePath,
              "direct target " + target + " resolves live to " + live.note().vaultPath()
                  + " but catalog planning resolves to " + planned.currentPath()
                  + "; repair the semantic catalog or authored link before Prepare.",
              reportedResolutionProblems,
              diagnostics);
        }
        continue;
      }

      if (planned.status() == VaultReferenceResolver.Status.RESOLVED) {
        VaultNoteDescriptor historicalTarget = byPath.get(planned.currentPath());
        if (historicalTarget == null) {
          addResolutionProblem(
              "catalog-absent:" + target,
              sourcePath,
              "catalog resolves direct target " + target + " to " + planned.currentPath()
                  + ", but that path is absent from the live identity scan.",
              reportedResolutionProblems,
              diagnostics);
        } else {
          appendFrontierNote(historicalTarget, catalog, includedPaths, diagnostics);
        }
      }
    }
    return new Result(diagnostics);
  }

  private static void appendFrontierNote(
      VaultNoteDescriptor descriptor,
      VaultReferenceCatalog catalog,
      Set<String> includedPaths,
      List<PublicationDiagnostic> diagnostics) {
    if (!includedPaths.add(descriptor.vaultPath())) {
      return;
    }
    appendDescriptorDiagnostics(descriptor, diagnostics);
    appendCatalogIdentityDiagnostics(descriptor, catalog, diagnostics);
  }

  private static void appendCatalogIdentityDiagnostics(
      VaultNoteDescriptor descriptor,
      VaultReferenceCatalog catalog,
      List<PublicationDiagnostic> diagnostics) {
    List<VaultReferenceCatalog.CatalogEntry> previousEntries = catalog.entries().values().stream()
        .filter(entry -> VaultReferenceCatalog.STATE_ACTIVE.equals(entry.state())
            && descriptor.vaultPath().equals(entry.currentPath()))
        .sorted(java.util.Comparator.comparing(VaultReferenceCatalog.CatalogEntry::pageRef))
        .toList();
    if (previousEntries.size() > 1) {
      String pageRefs = previousEntries.stream()
          .map(VaultReferenceCatalog.CatalogEntry::pageRef)
          .collect(java.util.stream.Collectors.joining(", "));
      diagnostics.add(blocking(
          descriptor.vaultPath(),
          "multiple active catalog entries claim this current path (" + pageRefs
              + "); repair the catalog before Prepare."));
      return;
    }
    if (previousEntries.isEmpty()) {
      return;
    }
    String before = previousEntries.getFirst().stableNoteId();
    String now = comparableStableId(descriptor);
    if (before != null && now != null && !before.equals(now)) {
      diagnostics.add(new PublicationDiagnostic(
          "semantic-id",
          descriptor.vaultPath() + ": source id changed from " + before + " to " + now
              + "; restore the prior id or use an explicitly approved identity migration."));
    }
  }

  private static String comparableStableId(VaultNoteDescriptor descriptor) {
    if (descriptor.stableNoteId() != null) {
      return descriptor.stableNoteId();
    }
    String declared = descriptor.declaredStableNoteId();
    if (declared == null) {
      return null;
    }
    String normalized = declared.strip();
    if (normalized.isBlank() || normalized.contains("/") || normalized.contains("\\")) {
      return null;
    }
    return normalized;
  }

  private static void addResolutionProblem(
      String key,
      String sourcePath,
      String message,
      Set<String> reported,
      List<PublicationDiagnostic> diagnostics) {
    if (reported.add(key)) {
      diagnostics.add(new PublicationDiagnostic("semantic-id", sourcePath + ": " + message));
    }
  }

  private static String ambiguityMessage(
      String target,
      List<VaultNoteDescriptor> matches) {
    String paths = matches.stream()
        .map(VaultNoteDescriptor::vaultPath)
        .sorted()
        .collect(java.util.stream.Collectors.joining(", "));
    boolean duplicatedSourceId = matches.size() > 1
        && matches.stream().allMatch(descriptor -> declaresDuplicatedTargetId(descriptor, target));
    if (duplicatedSourceId) {
      return "direct target " + target + " is a duplicated source id on " + paths
          + "; assign one note a new id before Prepare.";
    }
    return "direct target " + target + " matches multiple live notes (" + paths
        + "); disambiguate the authored link before Prepare.";
  }

  private static boolean declaresDuplicatedTargetId(
      VaultNoteDescriptor descriptor,
      String target) {
    String declared = descriptor.declaredStableNoteId();
    return declared != null
        && declared.strip().equals(target)
        && descriptor.diagnostics().stream().anyMatch(defect ->
            defect.startsWith("copied-identity:")
                || defect.startsWith("duplicate-stable-id:"));
  }

  private static void appendDescriptorDiagnostics(
      VaultNoteDescriptor descriptor,
      List<PublicationDiagnostic> diagnostics) {
    boolean duplicateRepairAdded = false;
    for (String defect : descriptor.diagnostics()) {
      if (defect.startsWith("copied-identity:")
          || defect.startsWith("duplicate-stable-id:")) {
        if (!duplicateRepairAdded) {
          diagnostics.add(blocking(descriptor.vaultPath(),
              "frontmatter id is not globally unique ("
                  + descriptor.declaredStableNoteId()
                  + "); assign one note a new id."));
          duplicateRepairAdded = true;
        }
        continue;
      }
      diagnostics.add(blocking(descriptor.vaultPath(), explain(descriptor, defect)));
    }
  }

  private static PublicationDiagnostic blocking(String path, String message) {
    return new PublicationDiagnostic("semantic-id", path + ": " + message);
  }

  private static String explain(VaultNoteDescriptor descriptor, String defect) {
    return switch (defect) {
      case "missing-stable-note-id" -> "add a nonblank string frontmatter id.";
      case "blank-stable-note-id" ->
          "replace the blank frontmatter id with a globally unique string.";
      case "non-string-stable-note-id" ->
          "replace the non-string frontmatter id with a globally unique string.";
      default -> explainPrefixed(descriptor, defect);
    };
  }

  private static String explainPrefixed(VaultNoteDescriptor descriptor, String defect) {
    if (defect.startsWith("invalid-stable-note-id:")) {
      return "frontmatter id cannot contain slash or backslash ("
          + descriptor.declaredStableNoteId() + ").";
    }
    if (defect.startsWith("invalid-utf-8:")) {
      return "save the note as valid UTF-8 before assigning or checking its id.";
    }
    if (defect.startsWith("unreadable-note:")) {
      return "make the note readable before assigning or checking its id.";
    }
    if (defect.startsWith("invalid-frontmatter:")) {
      return "repair frontmatter before assigning or checking its id.";
    }
    return "repair source identity (" + defect + ").";
  }

  public record Result(List<PublicationDiagnostic> diagnostics) {
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    public boolean admitted() {
      return diagnostics.stream().noneMatch(PublicationDiagnostic::blocking);
    }
  }
}
```

- [ ] **Step 4: Run the Nullable admission and scanner suites**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=PublicationFrontierIdentityAdmissionTest,VaultNoteScannerTest,VaultNoteTargetResolverTest,SemanticReferencePlannerTest test`

Expected: PASS. All identity logic runs against production classes; only the `java.nio.file.Files` edge is nulled, and tracked requests show exactly one complete vault scan per inspection.

- [ ] **Step 5: Commit Task 4**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmission.java exporter-java/src/test/java/dev/eugene/astroexport/references/PublicationFrontierIdentityAdmissionTest.java
git commit -m "feat: admit publication frontier source identities"
```

### Task 5: Fail-Closed Prepare Integration

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java:109-326,865-893`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java:98-197,1908-1966`

**Interfaces:**
- Consumes: Task 4's `PublicationFrontierIdentityAdmission.create()`, `inspect(Path, String, String, VaultReferenceCatalog)`, `Result.admitted()`, and `Result.diagnostics()`.
- Produces: a package-private constructor seam that accepts `PublicationFrontierIdentityAdmission`; every existing production constructor still delegates with `PublicationFrontierIdentityAdmission.create()`.
- Produces: semantic Prepare returns `new PrepareResult("metadata_blocked", null, diagnostics, List.of(), null, null)` before `resolveEntry` and before publication/job/review mutation when identity admission fails.
- Produces: `private static PrepareResult identityBlocked(List<PublicationDiagnostic> diagnostics)`; unlike `metadataBlocked(...)`, it performs no source workflow update.

- [ ] **Step 1: Make the semantic fixture represent live source-identified targets**

In `semanticFixture()`, create both direct target notes and give the source and targets the same stable IDs recorded in the catalog:

```java
Path first = fixture.vault().resolve("private/Target One.md");
Path second = fixture.vault().resolve("private/Target Two.md");
Files.createDirectories(first.getParent());
Files.writeString(first, """
    ---
    id: target-one
    title: Target One
    ---
    private
    """);
Files.writeString(second, """
    ---
    id: target-two
    title: Target Two
    ---
    private
    """);
```

Change the three semantic catalog entries to use `essay`, `target-one`, and `target-two` instead of `null`. Do not call catalog reconciliation.

- [ ] **Step 2: Add the single workflow-boundary Prepare smoke test**

These two tests intentionally use the existing `PrepareWorkflowTest` fixture as the higher-level boundary checks allowed by the Nullables strategy. Inject the real admission component over `NullFileSystem`, while the actual vault/review/job trees remain real state surfaces for the no-mutation assertions. Scanner/admission correctness remains owned by Tasks 2–4; these tests own ordinary rejection placement, caller recovery from inspection failure, one observed admission call per Prepare, and observable non-mutation. `RecordingRunner` is the fixture's grandfathered safety sensor for the non-filesystem translation port, not evidence for the Nullable I/O design.

```java
@Test
void semanticIdentityFailureBlocksBeforeRunnerAndLeavesPublicationStateUntouched()
    throws Exception {
  Fixture fixture = semanticFixture();
  String sourceWithoutId = Files.readString(fixture.source()).replace("id: essay\n", "");
  Files.writeString(fixture.source(), sourceWithoutId);
  Path target = fixture.vault().resolve("private/Target One.md");
  String targetWithoutId = Files.readString(target).replace("id: target-one\n", "");
  Files.writeString(target, targetWithoutId);
  Path second = fixture.vault().resolve("private/Target Two.md");
  NullFileSystem identityFileSystem = FileSystem.createNull()
      .withWalk(fixture.vault(), List.of(fixture.source(), target, second))
      .withFile(fixture.source(), sourceWithoutId)
      .withFile(target, targetWithoutId)
      .withFile(second, Files.readString(second));
  OutputTracker<FileSystem.Request> identityRequests = identityFileSystem.trackRequests();
  PublicationFrontierIdentityAdmission identityAdmission =
      new PublicationFrontierIdentityAdmission(new VaultNoteScanner(identityFileSystem));
  seedPublicationState(fixture);
  Map<String, String> vaultBefore = treeManifest(fixture.vault(), Set.of());
  Map<String, String> reviewBefore = treeManifest(
      fixture.review(), Set.of(".semantic-links/operations.lock"));
  Map<String, String> jobsBefore = treeManifest(fixture.jobs(), Set.of());
  RecordingRunner runner = new RecordingRunner(job -> {
    throw new AssertionError("runner must not start");
  });

  PrepareWorkflow.PrepareResult result = workflow(runner, identityAdmission)
      .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

  assertEquals("metadata_blocked", result.status());
  assertEquals(0, runner.calls.get());
  assertEquals(1L, identityRequests.data().stream()
      .filter(request -> request.equals(new FileSystem.Request(
          FileSystem.Operation.WALK, fixture.vault())))
      .count());
  assertEquals(List.of(
      new PublicationDiagnostic(
          "semantic-id", "blog/Essay.md: add a nonblank string frontmatter id."),
      new PublicationDiagnostic(
          "semantic-id", "private/Target One.md: add a nonblank string frontmatter id.")),
      result.diagnostics());
  assertEquals(vaultBefore, treeManifest(fixture.vault(), Set.of()));
  assertEquals(reviewBefore, treeManifest(
      fixture.review(), Set.of(".semantic-links/operations.lock")));
  assertEquals(jobsBefore, treeManifest(fixture.jobs(), Set.of()));
  assertTrue(Files.isRegularFile(fixture.review().resolve(".semantic-links/operations.lock")));
}

@Test
void semanticIdentityInspectionFailureIsFailClosedAndLeavesStateUntouched()
    throws Exception {
  Fixture fixture = semanticFixture();
  seedPublicationState(fixture);
  IOException configured = new IOException("configured identity scan failure");
  NullFileSystem identityFileSystem = FileSystem.createNull()
      .withWalkError(fixture.vault(), configured);
  OutputTracker<FileSystem.Request> identityRequests = identityFileSystem.trackRequests();
  PublicationFrontierIdentityAdmission identityAdmission =
      new PublicationFrontierIdentityAdmission(new VaultNoteScanner(identityFileSystem));
  Map<String, String> vaultBefore = treeManifest(fixture.vault(), Set.of());
  Map<String, String> reviewBefore = treeManifest(
      fixture.review(), Set.of(".semantic-links/operations.lock"));
  Map<String, String> jobsBefore = treeManifest(fixture.jobs(), Set.of());
  RecordingRunner runner = new RecordingRunner(job -> {
    throw new AssertionError("runner must not start");
  });

  PrepareWorkflow.PrepareResult result = workflow(runner, identityAdmission)
      .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

  assertEquals("metadata_blocked", result.status());
  assertEquals(0, runner.calls.get());
  assertEquals(List.of(new PublicationDiagnostic(
      "semantic-id",
      "blog/Essay.md: could not inspect publication-frontier identity: cannot scan vault: "
          + fixture.vault())), result.diagnostics());
  assertEquals(List.of(new FileSystem.Request(
      FileSystem.Operation.WALK, fixture.vault())), identityRequests.data());
  assertEquals(vaultBefore, treeManifest(fixture.vault(), Set.of()));
  assertEquals(reviewBefore, treeManifest(
      fixture.review(), Set.of(".semantic-links/operations.lock")));
  assertEquals(jobsBefore, treeManifest(fixture.jobs(), Set.of()));
  assertTrue(Files.isRegularFile(fixture.review().resolve(".semantic-links/operations.lock")));
}

private static void seedPublicationState(Fixture fixture) throws Exception {
  Path reviewPage = fixture.review().resolve("blog/essay");
  Files.createDirectories(reviewPage.resolve("candidate"));
  Files.createDirectories(reviewPage.resolve("published"));
  Files.writeString(reviewPage.resolve("ru.md"), "review baseline sentinel");
  Files.writeString(reviewPage.resolve("candidate/ru.md"), "candidate sentinel");
  Files.writeString(reviewPage.resolve("published/ru.md"), "approved Russian sentinel");
  Files.writeString(reviewPage.resolve("published/en.md"), "approved English sentinel");
  Files.writeString(reviewPage.resolve("published/references.json"), "approved map sentinel");
  Path priorJob = fixture.jobs().resolve("sentinel/job.txt");
  Files.createDirectories(priorJob.getParent());
  Files.writeString(priorJob, "job sentinel");
}

private static Map<String, String> treeManifest(Path root, Set<String> excluded)
    throws Exception {
  if (!Files.exists(root)) {
    return Map.of();
  }
  Map<String, String> entries = new LinkedHashMap<>();
  try (var paths = Files.walk(root)) {
    for (Path path : paths.sorted().toList()) {
      String relative = root.relativize(path).toString().replace('\\', '/');
      if (excluded.contains(relative)) {
        continue;
      }
      String entry = relative.isEmpty() ? "." : relative;
      if (Files.isSymbolicLink(path)) {
        entries.put(entry, "symlink:" + Files.readSymbolicLink(path));
      } else if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        entries.put(entry, "directory");
      } else if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        entries.put(entry, "file:" + sha256(Files.readAllBytes(path)));
      } else {
        entries.put(entry, "other");
      }
    }
  }
  return Map.copyOf(entries);
}
```

Add imports for `FileSystem`, `NullFileSystem`, `OutputTracker`, `PublicationFrontierIdentityAdmission`, `VaultNoteScanner`, `LinkedHashMap`, and `Set`; `IOException`, `List`, and `Map` are already imported. Do not add a test-local scanner, mock, or filesystem fake.

- [ ] **Step 3: Run both workflow-boundary tests and observe current Prepare crossing the boundary**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=PrepareWorkflowTest#semanticIdentityFailureBlocksBeforeRunnerAndLeavesPublicationStateUntouched test`

Run: `mvn -q -f exporter-java/pom.xml -Dtest=PrepareWorkflowTest#semanticIdentityInspectionFailureIsFailClosedAndLeavesStateUntouched test`

Expected: both FAIL because current semantic Prepare reaches `resolveEntry` and later processing instead of returning either the complete ordinary `semantic-id` repair list or the fail-closed inspection diagnostic.

- [ ] **Step 4: Constructor-inject admission and invoke it before `resolveEntry`**

Import `PublicationFrontierIdentityAdmission` and add the field without hard-wiring its construction:

```java
private final PublicationFrontierIdentityAdmission identityAdmission;
```

Keep every existing constructor signature source-compatible. Turn the current terminal constructor into a production-wired delegate, add one package-private focused constructor for tests, and move assignments into a new terminal overload:

```java
PrepareWorkflow(
    TranslationRunner runner,
    Clock clock,
    WorkflowStateService workflowState,
    AtomicExchange atomicExchange,
    ExistingEnglishReadHook existingEnglishReadHook,
    RecoveryFilePreserver recoveryFilePreserver,
    LockAcquisitionHook lockAcquisitionHook,
    FirstDraftInstallHook firstDraftInstallHook,
    IoHooks ioHooks,
    EntryResolver entryResolver) {
  this(
      runner,
      clock,
      workflowState,
      atomicExchange,
      existingEnglishReadHook,
      recoveryFilePreserver,
      lockAcquisitionHook,
      firstDraftInstallHook,
      ioHooks,
      entryResolver,
      PublicationFrontierIdentityAdmission.create());
}

PrepareWorkflow(
    TranslationRunner runner,
    Clock clock,
    PublicationFrontierIdentityAdmission identityAdmission) {
  this(
      runner,
      clock,
      new WorkflowStateService(),
      new JnaAtomicExchange(),
      path -> { },
      PrepareWorkflow::preserve,
      path -> { },
      (target, temporary) -> { },
      new IoHooks() { },
      defaultEntryResolver(),
      identityAdmission);
}

PrepareWorkflow(
    TranslationRunner runner,
    Clock clock,
    WorkflowStateService workflowState,
    AtomicExchange atomicExchange,
    ExistingEnglishReadHook existingEnglishReadHook,
    RecoveryFilePreserver recoveryFilePreserver,
    LockAcquisitionHook lockAcquisitionHook,
    FirstDraftInstallHook firstDraftInstallHook,
    IoHooks ioHooks,
    EntryResolver entryResolver,
    PublicationFrontierIdentityAdmission identityAdmission) {
  this.runner = runner;
  this.clock = clock;
  this.entryResolver = entryResolver;
  this.workflowState = workflowState;
  this.atomicExchange = atomicExchange;
  this.existingEnglishReadHook = existingEnglishReadHook;
  this.recoveryFilePreserver = recoveryFilePreserver;
  this.lockAcquisitionHook = lockAcquisitionHook;
  this.firstDraftInstallHook = firstDraftInstallHook;
  this.ioHooks = ioHooks;
  this.identityAdmission = identityAdmission;
}
```

In `PrepareWorkflowTest`, add the matching helper used by Step 2:

```java
private PrepareWorkflow workflow(
    RecordingRunner runner,
    PublicationFrontierIdentityAdmission identityAdmission) {
  return new PrepareWorkflow(
      runner,
      Clock.fixed(NOW, ZoneOffset.UTC),
      identityAdmission);
}
```

After migration-incomplete handling and before the existing `ResolvedEntry resolvedEntry` declaration, add:

```java
if (semanticMode == SemanticSchemaState.Mode.SEMANTIC) {
  PublicationFrontierIdentityAdmission.Result identity;
  try {
    identity = identityAdmission.inspect(
        vault,
        notePath,
        initial.note().body(),
        VaultReferenceCatalog.load(reviewRoot));
  } catch (RuntimeException error) {
    return identityBlocked(List.of(new PublicationDiagnostic(
        "semantic-id",
        notePath + ": could not inspect publication-frontier identity: " + safeMessage(error))));
  }
  if (!identity.admitted()) {
    return identityBlocked(identity.diagnostics());
  }
}
```

Add the pure helper beside `metadataBlocked(...)`:

```java
private static PrepareResult identityBlocked(List<PublicationDiagnostic> diagnostics) {
  return new PrepareResult(
      "metadata_blocked",
      null,
      diagnostics,
      List.of(),
      null,
      null);
}
```

Do not route identity failures through `metadataBlocked(...)`; that method calls `updateSource(...)` for publishable notes and would violate the decision.

- [ ] **Step 5: Run the focused Prepare and Nullable suites**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=FileSystemTest,VaultNoteScannerTest,VaultNoteTargetResolverTest,PublicationFrontierIdentityAdmissionTest,SemanticReferencePlannerTest,PrepareWorkflowTest test`

Expected: PASS. Existing semantic scenarios retain their previous outcomes with explicit IDs, and the new failure case stops after the permitted semantic lock leaf.

- [ ] **Step 6: Run the bounded reference regression suite**

Run: `mvn -q -f exporter-java/pom.xml -Dtest=VaultReferenceCatalogTest,VaultReferenceResolverTest,SemanticReferencePlannerTest,PrepareWorkflowTest test`

Expected: PASS.

- [ ] **Step 7: Run the complete exporter suite**

Run: `mvn -f exporter-java/pom.xml test`

Expected: BUILD SUCCESS with no failed or errored tests.

- [ ] **Step 8: Commit Task 5**

```bash
git add exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java
git commit -m "feat: block semantic prepare on invalid source identity"
```

- [ ] **Step 9: Verify the full implementation boundary against the commission base**

Run:

```bash
test -n "${COMMISSION_BASE_SHA:-}"
git cat-file -e "${COMMISSION_BASE_SHA}^{commit}"
git merge-base --is-ancestor "$COMMISSION_BASE_SHA" HEAD
git diff --check "$COMMISSION_BASE_SHA"..HEAD
git diff --name-only "$COMMISSION_BASE_SHA"..HEAD
git ls-files --others --exclude-standard
```

Expected: the first three commands exit zero. The fourth and sixth print nothing. The fifth prints exactly the fifteen paths in the File Map and Commission Boundary section; no `.haft`, documentation, migration, Obsidian, site, review-workspace, report, or publication-output path appears. If `COMMISSION_BASE_SHA` is absent or does not name the prepared commission's recorded base, stop rather than substituting `HEAD~1` or another inferred revision.

## Final Evidence Required by the Commission

The implementation is ready for task review only after all five commands pass in this order:

```bash
mvn -q -f exporter-java/pom.xml -Dtest=FileSystemTest,VaultNoteScannerTest test
mvn -q -f exporter-java/pom.xml -Dtest=PublicationFrontierIdentityAdmissionTest test
mvn -q -f exporter-java/pom.xml -Dtest=PrepareWorkflowTest test
mvn -q -f exporter-java/pom.xml -Dtest=VaultReferenceCatalogTest,VaultReferenceResolverTest,SemanticReferencePlannerTest,PrepareWorkflowTest test
mvn -f exporter-java/pom.xml test
```

Passing this plan verifies only the first, read-only admission slice. It does not verify the DecisionRecord's later source-ID-derived target reference, rename/index rebuild, commit-boundary revalidation, fresh-site initialization, legacy-workspace rehearsal, or 20-run friction-measurement requirements.
