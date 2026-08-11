## Context

S16 realizes ADM-01 and ADM-05's aggregate scenario (`openspec/specs/publication-admission/spec.md`) inside `publication-exporter/`. The functional design pass (this change's `specs/publication-admission/spec.md`) already fixed: the command is `write-publication-manifest`; discovery order is deterministic (sorted by vault-relative path) at both adapters; the manifest reports one entry per selected note (identity when admitted, diagnostics when not) and is "complete" only when every entry admitted.

Two existing pieces already do almost all the real work and are reused unchanged:
- `VaultReader.listPublishCandidates()` already discovers `publish: true` notes (both `NullVaultReader` and `FilesystemVaultReader`).
- `NoteIntake.admit(VaultRelativePath, VaultReader)` already validates one note end-to-end (path safety, existence, parse, `EssayAdmission`) and already returns either an accepted `PublicationIdentity`+fields or blocking `Diagnostic`s.

This slice's only real production-code changes are: (1) a deterministic-order fix to `listPublishCandidates()` in both adapters, (2) a new aggregation handler that loops `listPublishCandidates()` and calls `NoteIntake.admit(...)` per path (structurally identical to `RefreshPublicationQueueHandler.refresh(...)`'s loop, but reporting every outcome instead of silently excluding failures), and (3) a new CLI command. Zero new ports/adapters — `FilesystemVaultReader` already exists and is only hardened, not replaced.

## Goals / Non-Goals

**Goals**
- `write-publication-manifest` takes only `--vault` (no `--review`, no `--note` — it touches no candidate/approved/workflow state).
- `listPublishCandidates()` returns selected notes in deterministic, sorted-by-path order from both adapters.
- The manifest never silently drops a failing note — every selected note gets exactly one entry, admitted or blocked.

**Non-Goals**
- No queue-state mutation (untouched `refresh-publication-queue` concern).
- No whole-vault release (a future slice's concern; this manifest is its precondition, not its implementation).
- No new content kind (essay only, same as every slice through S16).
- No new port/adapter — `FilesystemVaultReader` is modified in place, not replaced or wrapped.

## Decision 1 — Deterministic ordering fix in both `listPublishCandidates()` implementations

**`NullVaultReader`** (`publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`): its constructor already funnels a `LinkedHashMap` through `Map.copyOf(...)`, whose iteration order the JDK does not guarantee to preserve — confirmed already as a live concern by `note-20260808-d1ce9a1a` (a prior review noted a test name implying an ordering guarantee this class doesn't provide). Fix: sort the entry stream in `listPublishCandidates()` itself:

```java
@Override
public List<VaultRelativePath> listPublishCandidates() {
    return sourceByPath.entrySet().stream()
            .filter(entry -> entry.getKey().endsWith(".md"))
            .filter(entry -> MarkdownNote.parse(entry.getValue()).flag("publish"))
            .map(entry -> VaultRelativePath.of(entry.getKey()))
            .sorted(Comparator.comparing(VaultRelativePath::value))
            .toList();
}
```

One line added (`.sorted(...)`), no constructor or field changes — the ordering guarantee moves from "accidental map iteration order" to "explicit, tested sort," which is strictly more correct and fixes the note's concern as a side effect without a dedicated fix task for it.

**`FilesystemVaultReader`**: `Files.walk(...)`'s traversal order is filesystem-dependent, not sorted. Fix: sort after mapping to `VaultRelativePath` (sorting the resulting relative-path strings is simpler and more obviously correct than trying to sort `Path` objects before relativizing, and matches what an authoring tool actually needs — stable output regardless of which physical directory-walk order the OS gave):

```java
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
```

`VaultRelativePath` needs a public `value()` accessor for the comparator — it already has one (confirmed: `VaultRelativePath.java` already exposes `.value()`, used elsewhere in this file's own `toVaultRelativePath`).

## Decision 2 — `PublicationManifest`/`ManifestEntry` value types (new `manifest` package)

Following the precedent `write-publication-contract`'s `PublicationContract` (S15) and `build-from-review`'s `ReleaseResult` (S06) both set: a CLI-only result type with no natural fit in `BridgeResponse`'s single-identity shape gets its own small type, not wrapped in `BridgeResponse`/`bridge-contract/schema-v2.json`. A whole-vault manifest is inherently list-shaped (one entry per selected note); `BridgeResponse` has no field for "a list of per-note outcomes" and forcing one in would be the same wide-DTO mistake S15 avoided.

```
dev.eugene.publicationexporter.manifest/
  ManifestEntry.java       (path, nullable PublicationIdentity, List<Diagnostic> — admitted() derives from diagnostics.isEmpty())
  PublicationManifest.java (ok, List<ManifestEntry> — ok derives from every entry being admitted)
  PublicationManifestHandler.java (one method: manifest(VaultReader): PublicationManifest)
```

Both value types follow this project's established convention exactly (`Diagnostic`, `PublicationIdentity`, `ReleaseResult`): `public final class`, private all-args constructor, named static factories, `@JsonProperty` accessors (no getter-prefix), `equals`/`hashCode`/`toString`. `ManifestEntry` and `PublicationManifest` reuse `Diagnostic` and `PublicationIdentity` directly from the `bridge` package (no new diagnostic/identity types) — `manifest` depends on `bridge`, the same direction `contract`/`buildfromreview` already depend on `bridge` today.

```java
public final class ManifestEntry {
    private final String path;
    private final PublicationIdentity identity;   // null when blocked
    private final List<Diagnostic> diagnostics;   // empty when admitted

    public static ManifestEntry admitted(String path, PublicationIdentity identity) { ... }
    public static ManifestEntry blocked(String path, List<Diagnostic> diagnostics) { ... }

    public boolean admitted() { return diagnostics.isEmpty(); }
    // path()/identity()/diagnostics() accessors, @JsonProperty each, @JsonInclude(NON_NULL) on the class
}
```

```java
public final class PublicationManifest {
    private final boolean ok;
    private final List<ManifestEntry> entries;

    public static PublicationManifest of(List<ManifestEntry> entries) {
        boolean ok = entries.stream().allMatch(ManifestEntry::admitted);
        return new PublicationManifest(ok, List.copyOf(entries));
    }
    // ok()/entries() accessors, @JsonProperty each
}
```

`PublicationManifest.of(...)` is the single constructor path (Elegant Objects 1.2: one primary construction route) — there is no separate `blocked(...)`/`complete(...)` factory pair the way `ReleaseResult` has, because "complete" here is a derived property of the entry list, not an independent choice the caller makes; computing it from `entries` instead of accepting it as a parameter makes the invariant ("ok is true iff every entry is admitted") impossible to violate by construction.

## Decision 3 — `PublicationManifestHandler` reuses `NoteIntake` exactly as `RefreshPublicationQueueHandler` does, but never excludes

```java
public final class PublicationManifestHandler {

    public PublicationManifest manifest(VaultReader vaultReader) {
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

No new admission logic — `NoteIntake.admit(...)` is the same single-note validation `RefreshPublicationQueueHandler.reconcileOne(...)` and every note-scoped command already trust. `listPublishCandidates()`'s new sort (Decision 1) is what makes this loop's output order deterministic; the handler does not re-sort.

## Decision 4 — CLI command surface

```java
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

Unlike `write-publication-contract` (always exit `0` — a static document has no failure mode), this command has a real success/failure axis (`ok`), so it follows every other command's `ok() ? 0 : 1` convention instead. Registered in `Main.java`'s `subcommands`, same as every command.

## Decision 5 — GraalVM `reflect-config.json` registration is part of this slice's own tasks, not deferred to the final review

S15's final whole-branch review found the exact same class of gap (new command + new Jackson DTOs missing from `reflect-config.json`, invisible to the JVM test suite, would silently break the native build) and recorded a standing lesson (`note-20260811-1c8808b8`) that future `tasks.md` plans must include this registration as an explicit step. This slice's `tasks.md` therefore registers `WritePublicationManifestCommand`, `PublicationManifest`, and `ManifestEntry` in `reflect-config.json` directly, following the exact same two patterns (full-reflection for the command, `allDeclaredFields` + explicit `methods` list for the DTOs) already in that file.

## Risks / Trade-offs

- The ordering fix touches two already-shipped, already-tested adapter methods. Both changes are additive (`.sorted(...)` inserted into an existing stream pipeline) and behavior-preserving for every existing caller (`RefreshPublicationQueueHandler`, any existing test asserting on set membership rather than order) — no existing test asserts a specific *unsorted* order, so this is safe, but the tasks.md plan must run the full suite after this change specifically to confirm before building anything on top of it.
- `ManifestEntry`'s `path` field is a plain `String` (from `VaultRelativePath.value()`), not the `VaultRelativePath` domain type itself — consistent with how every other Jackson-serialized DTO in this codebase (`Diagnostic`, `PublicationIdentity`) uses plain `String` fields rather than domain value objects, avoiding the need to make `VaultRelativePath` itself Jackson-serializable for a use case (JSON output) it was never designed for.

## Migration Plan

None — additive command, no existing behaviour changes beyond the two ordering fixes (which are behavior-*preserving* refinements, not behavior changes any test or caller depends on), no data migration.
