<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: write the failing acceptance test before any production code (openspec/implementation-plan.md's
  discipline). The fast acceptance test uses the in-memory VaultReader.createNull(...) fake; the real
  FilesystemVaultReader gets its own dedicated contract tests (ordering, ignored-path) in section 5, per this
  project's outside-in-fake-first discipline.
- Zero new ports/adapters this slice — FilesystemVaultReader already exists and is only hardened (a deterministic
  sort added to its existing listPublishCandidates() method), never replaced or wrapped. Do not add
  create()/createNull() factories anywhere — VaultReader already has them.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: value types follow this project's
  existing convention exactly (`Diagnostic`, `PublicationIdentity`, `ReleaseResult`) — public final class, private
  all-args constructor, named static factories (SBPP-BEH-02 Constructor Method), no getter-prefixed accessors
  (Elegant Objects 3.5), `@JsonProperty` on every accessor, `@JsonInclude(NON_NULL)` where a field can be
  legitimately absent, `equals`/`hashCode`/`toString`. `PublicationManifest.of(...)` derives `ok` from its
  entries rather than accepting it as a parameter — an invariant enforced by construction, not by caller
  discipline (Elegant Objects 2.6 be immutable / SBPP-BEH-03 Constructor Parameter Method). No comments in
  production code beyond what non-obvious rationale demands — this file's own comments are plan scaffolding,
  not a model for the code you write.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Never modify EssayAdmission or NoteIntake's existing logic — this slice only calls NoteIntake.admit(...) exactly
  as RefreshPublicationQueueHandler already does, reporting every outcome instead of silently excluding failures.
- Full reference documents (read before starting any task): proposal.md, specs/publication-admission/spec.md,
  design.md — all in openspec/changes/2026-08-11-s16-whole-vault-discovery/. design.md's Decisions 1-5 map
  directly onto the classes this file creates; read it first if anything below is unclear on *why*, not just
  *what*.
- GraalVM reflect-config.json registration (section 4) is a mandatory task step in this slice, not deferred —
  S15's final review found this exact class of gap (new command + new Jackson DTOs silently missing from
  publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json, invisible to the JVM test
  suite, breaking only the native build) and left a standing lesson that every future plan must include this
  registration directly.
-->

## 1. Failing acceptance test at the handler boundary (RED)

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/manifest/PublicationManifestHandlerTest.java`

This is the fast, in-memory acceptance test the outside-in discipline calls for: it exercises the whole
aggregate-admission behaviour (discovery, lookalike exclusion, ignored-path inclusion, mixed valid/invalid
admission, deterministic order, the `ok` derivation) through `VaultReader.createNull(...)` — no filesystem I/O.
It references `PublicationManifestHandler`, `PublicationManifest`, and `ManifestEntry`, none of which exist yet,
so it fails to compile. That is the expected RED state.

- [x] 1.1 Write the failing test:

```java
package dev.eugene.publicationexporter.manifest;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationManifestHandlerTest {

    private static final String HIDDEN = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: hidden-essay
            id: h1
            title: Hidden Essay
            description: An essay under a normally ignored path.
            ---
            """;

    private static final String BROKEN = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: broken-essay
            id: b1
            description: Missing its title.
            ---
            """;

    private static final String FIRST = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: first-essay
            id: f1
            title: First Essay
            description: The first valid essay.
            ---
            """;

    private static final String SECOND = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: second-essay
            id: s1
            title: Second Essay
            description: The second valid essay.
            ---
            """;

    private static final String LOOKALIKE = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft-essay
            id: d1
            title: Draft Essay
            description: Not actually selected.
            ---
            """;

    @Test
    void manifestListsEveryEntryInSortedOrderAndIsIncompleteWhenAnyEntryIsBlocked() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of(".obsidian/blog/hidden-essay.md"), HIDDEN,
                VaultRelativePath.of("blog/broken-essay.md"), BROKEN,
                VaultRelativePath.of("blog/first-essay.md"), FIRST,
                VaultRelativePath.of("blog/second-essay.md"), SECOND,
                VaultRelativePath.of("blog/draft.md"), LOOKALIKE));

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertFalse(manifest.ok());
        List<ManifestEntry> entries = manifest.entries();
        assertEquals(4, entries.size());

        ManifestEntry hidden = entries.get(0);
        assertEquals(".obsidian/blog/hidden-essay.md", hidden.path());
        assertTrue(hidden.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "hidden-essay"), hidden.identity());
        assertEquals(List.of(), hidden.diagnostics());

        ManifestEntry broken = entries.get(1);
        assertEquals("blog/broken-essay.md", broken.path());
        assertFalse(broken.admitted());
        assertNull(broken.identity());
        assertEquals("title", broken.diagnostics().get(0).field());

        ManifestEntry first = entries.get(2);
        assertEquals("blog/first-essay.md", first.path());
        assertTrue(first.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "first-essay"), first.identity());

        ManifestEntry second = entries.get(3);
        assertEquals("blog/second-essay.md", second.path());
        assertTrue(second.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "second-essay"), second.identity());
    }

    @Test
    void manifestIsCompleteWhenEveryEntryAdmits() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/first-essay.md"), FIRST,
                VaultRelativePath.of("blog/second-essay.md"), SECOND));

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertTrue(manifest.ok());
        assertEquals(2, manifest.entries().size());
    }

    @Test
    void emptyVaultProducesACompleteEmptyManifest() {
        VaultReader vaultReader = VaultReader.createNull(Map.of());

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertTrue(manifest.ok());
        assertEquals(List.of(), manifest.entries());
    }
}
```

- [x] 1.2 Run it and confirm it fails to compile for the expected reason (`PublicationManifestHandler`,
      `PublicationManifest`, `ManifestEntry` do not exist yet — not an unrelated failure).

Run: `cd publication-exporter && mvn -q -o test -Dtest=PublicationManifestHandlerTest 2>&1 | tail -60`

Do not proceed to section 2 until you can see exactly why the build fails.

## 2. Deterministic ordering fix (REFACTOR — stays green throughout)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`

This step changes no observable behaviour for any existing caller (`RefreshPublicationQueueHandler`, every
existing test asserts on set membership or a single-element list, never on a specific unsorted order) — it only
makes an already-relied-upon ordering explicit and tested instead of accidental. Run the full suite before and
after to prove it.

- [x] 2.1 Read the current `NullVaultReader.java` in full first (reproduced below as of this writing — confirm
      it still matches before editing). Add one `.sorted(...)` call to `listPublishCandidates()`:

```java
package dev.eugene.publicationexporter.vault;

import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Override
    public List<VaultRelativePath> listPublishCandidates() {
        return sourceByPath.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".md"))
                .filter(entry -> MarkdownNote.parse(entry.getValue()).flag("publish"))
                .map(entry -> VaultRelativePath.of(entry.getKey()))
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }

    private static Map<VaultRelativePath, String> defaultToEmptySource(VaultRelativePath... paths) {
        Map<VaultRelativePath, String> notesBySource = new LinkedHashMap<>();
        Arrays.stream(paths).forEach(path -> notesBySource.put(path, ""));
        return notesBySource;
    }
}
```

  (Only the `import java.util.Comparator;` addition and the `.sorted(Comparator.comparing(VaultRelativePath::value))`
  line inside `listPublishCandidates()` change — everything else in the file stays exactly as it is today.)

- [x] 2.2 Read the current `FilesystemVaultReader.java` in full first (reproduced below as of this writing —
      confirm it still matches before editing). Add the same one-line sort:

```java
package dev.eugene.publicationexporter.vault;

import dev.eugene.publicationexporter.note.MarkdownNote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

final class FilesystemVaultReader implements VaultReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

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

    @Override
    public List<VaultRelativePath> listPublishCandidates() {
        try (var paths = Files.walk(canonicalVaultRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> realPathOf(path).filter(this::isInsideVault).isPresent())
                    .filter(this::hasPublishTrueFlag)
                    .map(this::toVaultRelativePath)
                    .sorted(Comparator.comparing(VaultRelativePath::value))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private boolean hasPublishTrueFlag(Path file) {
        try {
            return MarkdownNote.parse(readUtf8(file)).flag("publish");
        } catch (UncheckedIOException unreadable) {
            return false;
        }
    }

    private VaultRelativePath toVaultRelativePath(Path file) {
        return VaultRelativePath.of(canonicalVaultRoot.relativize(file).toString().replace('\\', '/'));
    }

    private Optional<Path> resolveWithinVault(VaultRelativePath notePath) {
        return candidateFor(notePath)
                .flatMap(FilesystemVaultReader::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
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

  (Only the `import java.util.Comparator;` addition and the `.sorted(Comparator.comparing(VaultRelativePath::value))`
  line inside `listPublishCandidates()` change — everything else in the file stays exactly as it is today.)

- [x] 2.3 Run the full suite and confirm it is exactly as green as before this change (section 1's test still
      fails to compile, for the same reason as before — everything else must be unchanged).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 2.4 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java
git commit -m "refactor(exporter): make listPublishCandidates() ordering deterministic"
```

## 3. Implement the manifest package (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/manifest/ManifestEntry.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/manifest/PublicationManifest.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/manifest/PublicationManifestHandler.java`

**Interfaces:**
- Consumes: `VaultReader.listPublishCandidates()` (already sorted, section 2), `NoteIntake.admit(VaultRelativePath,
  VaultReader)` → `NoteIntake.Result` with `.accepted()`, `.identity()`, `.diagnostics()` (unchanged, existing).
- Produces: `PublicationManifestHandler#manifest(VaultReader)` → `PublicationManifest`; `PublicationManifest#ok()`,
  `#entries()` → `List<ManifestEntry>`; `ManifestEntry#path()`, `#identity()` (nullable), `#diagnostics()`,
  `#admitted()` — section 4's CLI command and section 1's test consume these directly.

- [x] 3.1 Create `ManifestEntry.java`:

```java
package dev.eugene.publicationexporter.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManifestEntry {

    private final String path;
    private final PublicationIdentity identity;
    private final List<Diagnostic> diagnostics;

    private ManifestEntry(String path, PublicationIdentity identity, List<Diagnostic> diagnostics) {
        this.path = Objects.requireNonNull(path, "path");
        this.identity = identity;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static ManifestEntry admitted(String path, PublicationIdentity identity) {
        return new ManifestEntry(path, Objects.requireNonNull(identity, "identity"), List.of());
    }

    public static ManifestEntry blocked(String path, List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("blocked() requires at least one diagnostic");
        }
        return new ManifestEntry(path, null, diagnostics);
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("diagnostics")
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean admitted() {
        return diagnostics.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManifestEntry that)) {
            return false;
        }
        return path.equals(that.path) && Objects.equals(identity, that.identity)
                && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, identity, diagnostics);
    }

    @Override
    public String toString() {
        return "ManifestEntry[path=" + path + ", identity=" + identity + ", diagnostics=" + diagnostics + "]";
    }
}
```

- [x] 3.2 Create `PublicationManifest.java`:

```java
package dev.eugene.publicationexporter.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class PublicationManifest {

    private final boolean ok;
    private final List<ManifestEntry> entries;

    private PublicationManifest(boolean ok, List<ManifestEntry> entries) {
        this.ok = ok;
        this.entries = List.copyOf(entries);
    }

    public static PublicationManifest of(List<ManifestEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        boolean ok = entries.stream().allMatch(ManifestEntry::admitted);
        return new PublicationManifest(ok, entries);
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("entries")
    public List<ManifestEntry> entries() {
        return entries;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationManifest that)) {
            return false;
        }
        return ok == that.ok && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ok, entries);
    }

    @Override
    public String toString() {
        return "PublicationManifest[ok=" + ok + ", entries=" + entries + "]";
    }
}
```

- [x] 3.3 Create `PublicationManifestHandler.java`:

```java
package dev.eugene.publicationexporter.manifest;

import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PublicationManifestHandler {

    public PublicationManifest manifest(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        List<ManifestEntry> entries = new ArrayList<>();
        for (VaultRelativePath path : vaultReader.listPublishCandidates()) {
            entries.add(entryFor(path, vaultReader));
        }
        return PublicationManifest.of(entries);
    }

    private ManifestEntry entryFor(VaultRelativePath path, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(path, vaultReader);
        return intake.accepted()
                ? ManifestEntry.admitted(path.value(), intake.identity())
                : ManifestEntry.blocked(path.value(), intake.diagnostics());
    }
}
```

- [x] 3.4 Run section 1's test and confirm it now passes.

Run: `cd publication-exporter && mvn -q -o test -Dtest=PublicationManifestHandlerTest 2>&1 | tail -60`

- [x] 3.5 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/manifest/ \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/manifest/PublicationManifestHandlerTest.java
git commit -m "feat(exporter): add PublicationManifestHandler for whole-vault aggregate admission (ADM-01, ADM-05)"
```

## 4. CLI command, Main registration, and GraalVM reflect-config.json

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/WritePublicationManifestCommand.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationManifestCliAcceptanceTest.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java`
- Modify: `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json`

- [x] 4.1 Create `WritePublicationManifestCommand.java` — only `--vault`, no `--review`/`--note` (this command
      touches no candidate/approved/workflow state):

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.manifest.PublicationManifest;
import dev.eugene.publicationexporter.manifest.PublicationManifestHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "write-publication-manifest")
public final class WritePublicationManifestCommand implements Callable<Integer> {

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);
        System.out.println(new ObjectMapper().writeValueAsString(manifest));
        return manifest.ok() ? 0 : 1;
    }
}
```

- [x] 4.2 Register the command in `Main.java`. Read the current file first (reproduced below as of this
      writing — confirm it still matches); add `WritePublicationManifestCommand.class` to the `subcommands`
      array:

```java
package dev.eugene.publicationexporter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class, InstallToSiteCommand.class, RefreshPublicationQueueCommand.class,
        WritePublicationContractCommand.class, WritePublicationManifestCommand.class })
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

- [x] 4.3 Write the CLI acceptance test — a real-filesystem smoke test proving end-to-end wiring (the deep
      fixture coverage already lives in section 1's in-memory handler test; this only needs enough real files
      to prove the command reads the real vault, sorts, and reports exit code correctly):

```java
package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WritePublicationManifestCliAcceptanceTest {

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
    void manifestListsAllSelectedNotesWhenEveryOneAdmits() throws Exception {
        writeNote("blog/first-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: first-essay
                id: f1
                title: First Essay
                description: The first valid essay.
                ---
                """);
        writeNote("blog/second-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: second-essay
                id: s1
                title: Second Essay
                description: The second valid essay.
                ---
                """);

        int exitCode = new CommandLine(new Main()).execute(
                "write-publication-manifest", "--vault", vaultRoot.toString());

        assertEquals(0, exitCode);
        JsonNode manifest = soleJsonValueOnStdout();
        assertEquals(true, manifest.get("ok").asBoolean());
        assertEquals(2, manifest.get("entries").size());
        assertEquals("blog/first-essay.md", manifest.get("entries").get(0).get("path").asText());
        assertEquals("blog/second-essay.md", manifest.get("entries").get(1).get("path").asText());
    }

    @Test
    void manifestReportsNotOkWhenASelectedNoteIsInvalid() throws Exception {
        writeNote("blog/broken-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: broken-essay
                id: b1
                description: Missing its title.
                ---
                """);

        int exitCode = new CommandLine(new Main()).execute(
                "write-publication-manifest", "--vault", vaultRoot.toString());

        assertEquals(1, exitCode);
        JsonNode manifest = soleJsonValueOnStdout();
        assertEquals(false, manifest.get("ok").asBoolean());
        assertEquals(1, manifest.get("entries").size());
        JsonNode entry = manifest.get("entries").get(0);
        assertEquals("blog/broken-essay.md", entry.get("path").asText());
        assertNull(entry.get("identity"));
        assertEquals("title", entry.get("diagnostics").get(0).get("field").asText());
    }

    private void writeNote(String relativePath, String source) throws Exception {
        Path note = vaultRoot.resolve(relativePath);
        Files.createDirectories(note.getParent());
        Files.writeString(note, source, StandardCharsets.UTF_8);
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

- [x] 4.4 Register the new classes in `reflect-config.json`. Read the current file first — it is a JSON array;
      follow the two existing patterns exactly (full-reflection for CLI commands, `allDeclaredFields` + explicit
      `methods` list for Jackson DTOs — see the `WritePublicationContractCommand`/`PublicationContract`/
      `KindContract`/`FieldContract` entries S15 added as the most recent example). Insert three new entries
      before the trailing `com.fasterxml.jackson.databind.ext.Java7SupportImpl` entry:

```json
  {
    "name": "dev.eugene.publicationexporter.cli.WritePublicationManifestCommand",
    "allDeclaredConstructors": true,
    "allDeclaredFields": true,
    "allDeclaredMethods": true
  },
  {
    "name": "dev.eugene.publicationexporter.manifest.PublicationManifest",
    "allDeclaredFields": true,
    "methods": [
      {"name": "ok", "parameterTypes": []},
      {"name": "entries", "parameterTypes": []}
    ]
  },
  {
    "name": "dev.eugene.publicationexporter.manifest.ManifestEntry",
    "allDeclaredFields": true,
    "methods": [
      {"name": "path", "parameterTypes": []},
      {"name": "identity", "parameterTypes": []},
      {"name": "diagnostics", "parameterTypes": []}
    ]
  },
```

  Validate the file is still well-formed JSON after editing:
  `python3 -m json.tool publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json > /dev/null && echo VALID_JSON`

- [x] 4.5 Run the full suite and confirm both new CLI acceptance tests pass and nothing else broke.

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 4.6 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/WritePublicationManifestCommand.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/Main.java \
        publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationManifestCliAcceptanceTest.java
git commit -m "feat(exporter): add write-publication-manifest command and register it for GraalVM native-image"
```

## 5. Real-adapter ordering and ignored-path contract tests

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java`

Section 2 already made `FilesystemVaultReader.listPublishCandidates()` sort its output; this section adds the
dedicated real-filesystem tests proving that sort and proving a selected note under a normally tool-ignored
path (a dotfolder) is still discovered — the two contracts `openspec/implementation-plan.md`'s S16 acceptance
boundary calls out by name for the real adapter.

- [x] 5.1 Read the current `FilesystemVaultReaderTest.java` in full first (reproduced in section 5's context —
      it already has the `writeNote(vaultRoot, relativePath, source)` helper and the `vaultRoot`/`outsideVaultRoot`
      `@TempDir` fields these new tests reuse). Add two new `@Test` methods anywhere after
      `listPublishCandidatesExcludesSymlinkEscapingTheVaultRoot()` and before the `writeNote` helper method:

```java
    @Test
    void listPublishCandidatesReturnsResultsInDeterministicSortedOrder() throws Exception {
        writeNote(vaultRoot, "blog/zebra.md", "---\npublish: true\n---\nBody.");
        writeNote(vaultRoot, "blog/apple.md", "---\npublish: true\n---\nBody.");
        writeNote(vaultRoot, "archive/middle.md", "---\npublish: true\n---\nBody.");
        VaultReader vaultReader = VaultReader.create(vaultRoot);

        assertEquals(
                List.of(
                        VaultRelativePath.of("archive/middle.md"),
                        VaultRelativePath.of("blog/apple.md"),
                        VaultRelativePath.of("blog/zebra.md")),
                vaultReader.listPublishCandidates());
    }

    @Test
    void listPublishCandidatesDiscoversASelectedNoteUnderANormallyIgnoredPath() throws Exception {
        writeNote(vaultRoot, ".obsidian/blog/hidden-essay.md", "---\npublish: true\n---\nBody.");
        writeNote(vaultRoot, "blog/visible-essay.md", "---\npublish: true\n---\nBody.");
        VaultReader vaultReader = VaultReader.create(vaultRoot);

        assertEquals(
                List.of(
                        VaultRelativePath.of(".obsidian/blog/hidden-essay.md"),
                        VaultRelativePath.of("blog/visible-essay.md")),
                vaultReader.listPublishCandidates());
    }
```

- [x] 5.2 Run the full suite.

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 5.3 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java
git commit -m "test(exporter): add ordering and ignored-path contract tests for FilesystemVaultReader"
```

## 6. Whole-suite verification and graph refresh

- [x] 6.1 Run the complete `publication-exporter` suite one more time from a clean state and confirm it is
      green end to end (not just the files touched this slice).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -150`

- [x] 6.2 Refresh the graphify index (project rule: after modifying code, run `graphify update .`).

Run: `cd /Users/eugene/Dev/personal-site && graphify update .`

- [x] 6.3 Confirm `git status` shows only the files this slice touched (no stray changes to `exporter-java/`,
      `obsidian-plugin/`, `bridge-contract/schema-v2.json`, or any file outside `publication-exporter/` and
      `openspec/changes/2026-08-11-s16-whole-vault-discovery/`).

Run: `git status --porcelain=v1`
