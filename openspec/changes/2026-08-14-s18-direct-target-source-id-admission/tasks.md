<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- At most one new production boundary adapter is budgeted for this slice, and this plan uses zero: every
  new type is pure in-process (no I/O of its own), and the one capability that does need I/O
  (listing every vault note, not just publish:true ones) is added as a *default* method on the existing
  VaultReader port, overridden only by its two existing real/fake implementations — not a new port, not a
  new abstract method any of VaultReader's 14 unrelated anonymous test implementations must touch. See
  design.md's "VaultReader (existing port, widened by one *default* method)" section for the full rationale
  before touching VaultReader.java — do not "simplify" this to a plain abstract method; that reintroduces
  the exact blast radius (14 unrelated compile failures across InspectPublicationHandlerTest,
  MarkReviewedHandlerTest, RefreshPublicationQueueHandlerTest, and PrepareHandlerTest) this design avoided.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance, explicit sealed outcome types over exceptions for expected business
  outcomes (this codebase's existing idiom — see LinkResolutionOutcome, SourceFreshnessOutcome,
  AssetResolutionOutcome — match it, don't invent a new shape), guard clauses over nested conditionals,
  Composed Method (small, single-purpose private methods) throughout, package-private visibility by default
  (public only where a different package needs the type), and never a null return — every "maybe absent"
  result is Optional or a sealed outcome. No comments in production code beyond what non-obvious rationale
  demands — this file's own comments are plan scaffolding, not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, specs/semantic-references/spec.md,
  design.md, scope-pins.md — all in openspec/changes/2026-08-14-s18-direct-target-source-id-admission/.
  design.md's pipeline diagram and per-type sections map directly onto the classes this file creates; read
  it first if anything below is unclear on *why*, not just *what*.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Whole prior acceptance suite (802 tests as of this slice's baseline, 2026-08-14) must stay green after
  every task's step that runs the full suite. If anything outside this task's own new/modified tests turns
  red, stop and investigate before continuing — do not proceed past an unexplained regression.
- Governed by Haft problem prob-20260814-9d502f85. Do not archive the OpenSpec change or touch Haft
  artifacts from this task list — those steps are owned by the orchestrating session, not an implementer.
-->

## 1. Failing acceptance tests through `prepare` (RED)

All tests in this group go into the existing file
`publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
(package `dev.eugene.publicationexporter.prepare`). Read the existing file's imports, the `VALID_ESSAY`
fixture constant, and at least one existing `new PrepareHandler(...)` call site (e.g.
`successfulPrepareWritesReadyForReviewWorkflowStatus`, near line 186) fully before adding to it — match its
exact current construction pattern:
`new PrepareHandler(new NoteIntake(PublicationKinds.installed()), TranslationWorker.createNull(...), new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor)`
and `handler.prepare(path, vaultReader, VaultAssetReader.createNull())`. Do not duplicate an import or
fixture that already exists in the file.

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`

**Interfaces (what section 3 will make these tests compile and pass against):**
- `PrepareHandler.prepare(VaultRelativePath, VaultReader, VaultAssetReader)` — unchanged signature.
- `BridgeResponse.blocked(String command, Diagnostic)` — already exists, reused for the new failure.
- `NullTranslationWorker.requested()` and `NullCandidateWorkspace.installed()` — already exist, used to
  prove no job/candidate mutation happened.

- [ ] 1.1 Write a failing test: a direct private target that has no `id` frontmatter blocks `prepare` as
      `metadata_blocked`, before any translation job is requested or candidate installed.

```java
@Test
void prepareBlocksWhenADirectPrivateTargetHasNoSourceId() {
    String privateTargetWithoutId = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft
            title: Черновик
            description: A valid description.
            ---
            # Черновик

            Not yet public.""";
    String referrer = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Смотрите также [[Черновик]].""";
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            referrerPath, referrer, privateTargetPath, privateTargetWithoutId));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    NullTranslationWorker worker = new NullTranslationWorker(
            TranslationOutcome.success("EN", fields("EN title", "EN description.")));
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()), worker, workspace,
            ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

    assertFalse(response.ok());
    assertEquals("metadata_blocked", response.status());
    assertTrue(worker.requested().isEmpty());
    assertTrue(workspace.installed().isEmpty());
}
```

  Note `privateTargetWithoutId` has no `id:` key at all — that is the "absent" half of the blocking
  scenario. `worker.requested().isEmpty()` is the key assertion proving the check runs before job dispatch
  (design.md's ordering decision), mirroring how S13's own tests proved link resolution runs before
  translation.

- [ ] 1.2 Write a failing test: a direct private target whose `id` duplicates the source's own `id` blocks
      `prepare` the same way.

```java
@Test
void prepareBlocksWhenADirectPrivateTargetSharesTheSourcesOwnSourceId() {
    String referrer = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Смотрите также [[Черновик]].""";
    String privateTargetWithDuplicateId = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft
            id: 8f2c-my-essay
            title: Черновик
            description: A valid description.
            ---
            # Черновик

            Not yet public.""";
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            referrerPath, referrer, privateTargetPath, privateTargetWithDuplicateId));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    NullTranslationWorker worker = new NullTranslationWorker(
            TranslationOutcome.success("EN", fields("EN title", "EN description.")));
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()), worker, workspace,
            ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

    assertFalse(response.ok());
    assertEquals("metadata_blocked", response.status());
    assertTrue(worker.requested().isEmpty());
    assertTrue(workspace.installed().isEmpty());
}
```

- [ ] 1.3 Write a failing test: direct private targets with unique, valid source IDs do not block
      `prepare` — the explicit positive-path proof for this slice's new check (existing S13 tests already
      incidentally exercise a similar shape, but none of them assert this new check by name).

```java
@Test
void prepareSucceedsWhenDirectPrivateTargetsHaveUniqueSourceIds() {
    String referrer = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Смотрите также [[Черновик]].""";
    String privateTarget = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft
            id: 4c1b-draft
            title: Черновик
            description: A valid description.
            ---
            # Черновик

            Not yet public.""";
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            referrerPath, referrer, privateTargetPath, privateTarget));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            new NoteIntake(PublicationKinds.installed()),
            TranslationWorker.createNull("EN body", fields("EN title", "EN description.")),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

    assertTrue(response.ok());
    assertEquals(1, workspace.installed().size());
}
```

- [ ] 1.4 Run the three new tests and confirm they fail for the expected reason (the check does not exist
      yet, so 1.1 and 1.2 currently succeed instead of blocking — not a compile error, since nothing in the
      test references a not-yet-existing type).

Run: `cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test 2>&1 | tail -100`

Expected: `prepareBlocksWhenADirectPrivateTargetHasNoSourceId` and
`prepareBlocksWhenADirectPrivateTargetSharesTheSourcesOwnSourceId` fail with `assertFalse(response.ok())`
failing (response actually succeeded) — proving today's `prepare` has no target-identity gate yet.
`prepareSucceedsWhenDirectPrivateTargetsHaveUniqueSourceIds` already passes (it describes today's behavior
too) — that is expected and fine; it stays in section 1 because it is this slice's positive-path
specification, not because it currently fails.

## 2. Widen `VaultReader` with a defaulted `listAllNotePaths()` (REFACTOR — stays green throughout)

Per design.md: this is additive and has no observable effect yet (nothing calls the new method), but it
must land before section 3 needs it. Read `FilesystemVaultReader.java` and `NullVaultReader.java` in full
first — they may have evolved since this task list was written.

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java`

- [ ] 2.1 Add the defaulted method to `VaultReader.java`:

```java
public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    String readSource(VaultRelativePath notePath);

    List<VaultRelativePath> listPublishCandidates();

    // Every .md file in the vault, publish flag or not. Defaults to listPublishCandidates() -- a safe,
    // narrower answer -- because most VaultReader test doubles only ever model a single already-admitted
    // note and have no reason to know about private notes at all. FilesystemVaultReader and NullVaultReader
    // are the only two overrides; only S18's direct-target identity check calls this.
    default List<VaultRelativePath> listAllNotePaths() {
        return listPublishCandidates();
    }

    static VaultReader create(Path vaultRoot) {
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

- [ ] 2.2 Refactor `FilesystemVaultReader.java`'s `listPublishCandidates()` to share its walk with a new
      `listAllNotePaths()` override. Extract the walk+confinement+`.md`-filter+sort pipeline into a private
      `listMarkdownFiles()` helper returning `List<ConfinedCandidate>`, so neither listing re-implements
      confinement:

```java
    @Override
    public List<VaultRelativePath> listPublishCandidates() {
        return listMarkdownFiles().stream()
                .filter(this::hasPublishTrueFlag)
                .map(ConfinedCandidate::originalPath)
                .map(this::toVaultRelativePath)
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }

    @Override
    public List<VaultRelativePath> listAllNotePaths() {
        return listMarkdownFiles().stream()
                .map(ConfinedCandidate::originalPath)
                .map(this::toVaultRelativePath)
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }

    private List<ConfinedCandidate> listMarkdownFiles() {
        try (var paths = Files.walk(canonicalVaultRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .map(this::confinedCandidate)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
```

  Delete the old `listPublishCandidates()` body (the one that called `Files.walk` directly) — the new one
  above replaces it entirely. Everything else in the file (`confinedCandidate`, `hasPublishTrueFlag`,
  `toVaultRelativePath`, `resolveWithinVault`, etc.) is unchanged.

- [ ] 2.3 Add the override to `NullVaultReader.java`, the unfiltered sibling of its existing
      `listPublishCandidates()`:

```java
    @Override
    public List<VaultRelativePath> listAllNotePaths() {
        return sourceByPath.keySet().stream()
                .filter(path -> path.endsWith(".md"))
                .map(VaultRelativePath::of)
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }
```

- [ ] 2.4 Add one test to each existing adapter test file proving the new listing includes private notes
      the old one excludes. Read each file's existing test for `listPublishCandidates()` first and match its
      fixture-building style exactly (temp-directory writes for `FilesystemVaultReaderTest`, in-memory map
      for `NullVaultReaderTest`).

```java
// FilesystemVaultReaderTest.java — new test, alongside the existing listPublishCandidates() coverage
@Test
void listAllNotePathsIncludesPrivateNotesThatListPublishCandidatesExcludes() throws IOException {
    Files.writeString(temporaryRoot.resolve("public.md"), "---\npublish: true\n---\nPublic.");
    Files.writeString(temporaryRoot.resolve("private.md"), "---\npublish: false\n---\nPrivate.");
    VaultReader reader = VaultReader.create(temporaryRoot);

    List<VaultRelativePath> all = reader.listAllNotePaths();

    assertEquals(2, all.size());
    assertTrue(all.contains(VaultRelativePath.of("private.md")));
}
```

```java
// NullVaultReaderTest.java — new test, alongside the existing listPublishCandidates() coverage
@Test
void listAllNotePathsIncludesPrivateNotesThatListPublishCandidatesExcludes() {
    VaultRelativePath publicPath = VaultRelativePath.of("public.md");
    VaultRelativePath privatePath = VaultRelativePath.of("private.md");
    VaultReader reader = VaultReader.createNull(Map.of(
            publicPath, "---\npublish: true\n---\nPublic.",
            privatePath, "---\npublish: false\n---\nPrivate."));

    List<VaultRelativePath> all = reader.listAllNotePaths();

    assertEquals(List.of(privatePath, publicPath), all);
}
```

  Adjust `temporaryRoot`/`@TempDir` field names to match whatever `FilesystemVaultReaderTest.java` already
  uses if different from this sketch — read the file first per this section's header instruction.

- [ ] 2.5 Run the full suite and confirm nothing regresses (the three section-1 tests are still red, for
      the same reason as before — nothing yet calls `listAllNotePaths()` from `prepare`).

```bash
cd publication-exporter && mvn -q test 2>&1 | tail -150
```

- [ ] 2.6 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultReader.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultReaderTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/NullVaultReaderTest.java
git commit -m "feat(exporter): add VaultReader.listAllNotePaths() for private-note listing"
```

## 3. Implement `PrivateNoteIdentityIndex`, extend `LinkResolver`, implement `DirectTargetIdentityCheck`, wire into `PrepareHandler` (GREEN)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndex.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityOutcome.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityCheck.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java`

- [ ] 3.1 Make `PublicNoteIndex.filenameStem` reusable by `PrivateNoteIdentityIndex` and by `PrepareHandler`
      (for computing the source note's own stem): drop its `private` modifier so it is package-private.
      Read the current file first — only this one modifier changes:

```java
    static String filenameStem(VaultRelativePath path) {
```

  (was `private static String filenameStem(...)` — everything else in `PublicNoteIndex.java` is unchanged.)

- [ ] 3.2 Create `PrivateNoteIdentityIndex.java`. It is built once per `prepare()` call from
      `vaultReader.listAllNotePaths()`, exactly mirroring `PublicNoteIndex.from(...)`'s shape and its
      ambiguous-stem-drops-silently precedent (two files sharing a stem resolve to "not found" here too,
      not a new blocking case — scope-pins.md is explicit this is deliberate, not an oversight):

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PrivateNoteIdentityIndex {

    private final Map<String, TargetIdentity> identitiesByFilenameStem;

    PrivateNoteIdentityIndex(Map<String, TargetIdentity> identitiesByFilenameStem) {
        this.identitiesByFilenameStem =
                Map.copyOf(Objects.requireNonNull(identitiesByFilenameStem, "identitiesByFilenameStem"));
    }

    static PrivateNoteIdentityIndex from(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Map<String, TargetIdentity> identities = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath path : vaultReader.listAllNotePaths()) {
            registerOrMarkAmbiguous(vaultReader, path, identities, ambiguousStems);
        }
        ambiguousStems.forEach(identities::remove);
        return new PrivateNoteIdentityIndex(identities);
    }

    Optional<TargetIdentity> identityFor(String filenameStem) {
        return Optional.ofNullable(identitiesByFilenameStem.get(filenameStem));
    }

    private static void registerOrMarkAmbiguous(
            VaultReader vaultReader, VaultRelativePath path,
            Map<String, TargetIdentity> identities, Set<String> ambiguousStems) {
        String stem = PublicNoteIndex.filenameStem(path);
        if (identities.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        Optional<String> sourceId = MarkdownNote.parse(vaultReader.readSource(path)).string("id");
        identities.put(stem, new TargetIdentity(sourceId));
    }
}

record TargetIdentity(Optional<String> sourceId) {
}
```

  `TargetIdentity` is a top-level package-private record in the same file, matching how
  `LinkResolutionOutcome.java` already holds `ResolvedLinks`/`BlockedTransclusion` alongside its interface.

- [ ] 3.3 Widen `LinkResolutionOutcome.java` to also carry the private-target stems a resolution collected.
      Read the current file first (reproduced in full below as of this task list's writing — confirm it
      still matches before editing):

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body, Set<String> privateTargetStems) {
        return new ResolvedLinks(body, privateTargetStems);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            BiFunction<String, Set<String>, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;
    private final Set<String> privateTargetStems;

    ResolvedLinks(String body, Set<String> privateTargetStems) {
        this.body = Objects.requireNonNull(body, "body");
        this.privateTargetStems = Set.copyOf(Objects.requireNonNull(privateTargetStems, "privateTargetStems"));
    }

    @Override
    public <T> T resolve(BiFunction<String, Set<String>, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body, privateTargetStems);
    }
}

final class BlockedTransclusion implements LinkResolutionOutcome {

    private final String target;

    BlockedTransclusion(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(BiFunction<String, Set<String>, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
```

- [ ] 3.4 Update `LinkResolver.java` to collect the stem of every *plain* (non-embed) link whose target is
      not in `knownNotes` — the existing safe-label branch — into a `Set<String>` threaded through the walk,
      and pass it to `LinkResolutionOutcome.resolved(...)`. Read the current file first (reproduced in full
      below; confirm it still matches before editing):

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.AssetTargets;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkResolver {

    private static final Pattern WIKILINK =
            Pattern.compile("(!?)\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]");
    private LinkResolver() {
    }

    public static LinkResolutionOutcome resolve(String body, PublicNoteIndex knownNotes) {
        StringBuilder output = new StringBuilder(body.length());
        Set<String> privateTargetStems = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher link = nextLink(body, cursor);
            if (protectedSpanBeforeLink(protectedSpan, link)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (link != null) {
                Optional<String> blockedTarget = appendLink(body, output, cursor, link, knownNotes, privateTargetStems);
                if (blockedTarget.isPresent()) {
                    return LinkResolutionOutcome.blockedTransclusion(blockedTarget.get());
                }
                cursor = link.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return LinkResolutionOutcome.resolved(output.toString(), privateTargetStems);
    }

    private static Matcher nextLink(String body, int cursor) {
        Matcher matcher = WIKILINK.matcher(body);
        return matcher.find(cursor) ? matcher : null;
    }

    private static boolean protectedSpanBeforeLink(ProtectedRegionScanner.ProtectedSpan protectedSpan, Matcher link) {
        return protectedSpan != null && (link == null || protectedSpan.start() <= link.start());
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<String> appendLink(
            String body, StringBuilder output, int cursor, Matcher link, PublicNoteIndex knownNotes,
            Set<String> privateTargetStems) {
        output.append(body, cursor, link.start());
        boolean isEmbed = !link.group(1).isEmpty();
        String target = link.group(2).strip();
        String label = labelFor(link, target);
        if (isEmbed && AssetTargets.isAssetTarget(target)) {
            output.append(link.group());
            return Optional.empty();
        }
        Optional<String> route = knownNotes.routeFor(target);
        if (route.isPresent()) {
            output.append('[').append(label).append("](").append(route.get()).append(')');
            return Optional.empty();
        }
        if (isEmbed) {
            return Optional.of(lastPathSegment(target));
        }
        privateTargetStems.add(target);
        output.append(label);
        return Optional.empty();
    }

    private static String labelFor(Matcher link, String target) {
        String alias = link.group(3);
        return alias != null ? alias.strip() : lastPathSegment(target);
    }

    private static String lastPathSegment(String target) {
        int lastSlash = target.lastIndexOf('/');
        return lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
    }

}
```

  Only `appendLink` changed (gains the `privateTargetStems` parameter and the `privateTargetStems.add(target)`
  line right before the existing safe-label `output.append(label)`), plus `resolve`'s new
  `Set<String> privateTargetStems` local and its use in the final `resolved(...)` call. An embed target that
  falls through to `isEmbed` still returns `Optional.of(lastPathSegment(target))` unchanged — it is never
  added to `privateTargetStems`, matching design.md's note that unresolved embeds already fail closed via
  `blockedTransclusion` before this check could run.

- [ ] 3.5 Create `DirectTargetIdentityOutcome.java`, the same sealed-outcome idiom as
      `LinkResolutionOutcome`/`SourceFreshnessOutcome`:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface DirectTargetIdentityOutcome permits IdentityAdmitted, IdentityBlocked {

    static DirectTargetIdentityOutcome admitted() {
        return new IdentityAdmitted();
    }

    static DirectTargetIdentityOutcome blocked(String reason) {
        return new IdentityBlocked(reason);
    }

    <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked);
}

final class IdentityAdmitted implements DirectTargetIdentityOutcome {

    @Override
    public <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked) {
        Objects.requireNonNull(onAdmitted, "onAdmitted");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onAdmitted.get();
    }
}

final class IdentityBlocked implements DirectTargetIdentityOutcome {

    private final String reason;

    IdentityBlocked(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked) {
        Objects.requireNonNull(onAdmitted, "onAdmitted");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onBlocked.apply(reason);
    }
}
```

- [ ] 3.6 Create `DirectTargetIdentityCheck.java` — a pure function (design.md's cohesion call: it owns
      exactly one responsibility, comparing already-resolved identities, and depends on nothing but its
      arguments, matching `LinkResolver`/`AssetResolver`'s existing static-utility shape in this package):

```java
package dev.eugene.publicationexporter.prepare;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class DirectTargetIdentityCheck {

    private DirectTargetIdentityCheck() {
    }

    static DirectTargetIdentityOutcome verify(
            String sourceStem, String sourceId, Set<String> targetStems, PrivateNoteIdentityIndex index) {
        Set<String> seenSourceIds = new HashSet<>();
        seenSourceIds.add(sourceId);
        for (String targetStem : targetStems) {
            if (targetStem.equals(sourceStem)) {
                continue;
            }
            Optional<DirectTargetIdentityOutcome> blocked = checkTarget(targetStem, index, seenSourceIds);
            if (blocked.isPresent()) {
                return blocked.get();
            }
        }
        return DirectTargetIdentityOutcome.admitted();
    }

    private static Optional<DirectTargetIdentityOutcome> checkTarget(
            String targetStem, PrivateNoteIdentityIndex index, Set<String> seenSourceIds) {
        Optional<TargetIdentity> identity = index.identityFor(targetStem);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> targetSourceId = identity.get().sourceId();
        if (targetSourceId.isEmpty()) {
            return Optional.of(DirectTargetIdentityOutcome.blocked(
                    "Direct target \"" + targetStem + "\" has no source ID."));
        }
        if (!seenSourceIds.add(targetSourceId.get())) {
            return Optional.of(DirectTargetIdentityOutcome.blocked(
                    "Direct target \"" + targetStem + "\" shares a source ID with another note."));
        }
        return Optional.empty();
    }
}
```

  The `targetStem.equals(sourceStem)` guard is the self-link exclusion scope-pins.md names: a self-link
  never reaches identity comparison at all, so it can never spuriously collide with the `sourceId` already
  seeded into `seenSourceIds`.

- [ ] 3.7 Wire everything into `PrepareHandler.java`. Read the current file in full first (its exact shape
      may have shifted since this task list was written). Three things change: `prepare(...)` computes the
      source's own stem/id and gains the new check right after `LinkResolver.resolve` succeeds;
      `sourceFreshness(...)`'s `LinkResolver.resolve(...).resolve(...)` call gains the now-required second
      lambda parameter (ignored, per design.md's decision that freshness re-checks do not re-verify
      identity); and one new private failure-building helper is added.

  `prepare(...)`:
```java
    public BridgeResponse prepare(
            VaultRelativePath notePath, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
        NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        PublicNoteIndex knownNotes;
        try {
            knownNotes = PublicNoteIndex.from(vaultReader, noteIntake);
        } catch (UncheckedIOException failure) {
            return knownNotesLookupFailure(failure);
        }
        String sourceStem = PublicNoteIndex.filenameStem(notePath);
        String sourceId = intake.frontmatterString("id").orElseThrow();
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        (resolvedBody, privateTargetStems) -> prepareAfterIdentityCheck(
                                notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader,
                                sourceStem, sourceId, privateTargetStems),
                        PrepareHandler::transclusionBlockedFailure),
                position -> unclosedCommentFailure(position));
    }

    private BridgeResponse prepareAfterIdentityCheck(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String resolvedBody, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            String sourceStem, String sourceId, java.util.Set<String> privateTargetStems) {
        PrivateNoteIdentityIndex identityIndex = PrivateNoteIdentityIndex.from(vaultReader);
        return DirectTargetIdentityCheck.verify(sourceStem, sourceId, privateTargetStems, identityIndex)
                .resolve(
                        () -> prepareAfterAssetResolution(
                                notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader),
                        PrepareHandler::directTargetIdentityBlockedFailure);
    }
```

  (`import java.util.Set;` at the top of the file instead of the inline `java.util.Set` qualifier above is
  preferred if `Set` is not already imported — check the current import block first; this file does not
  import `java.util.Set` today, so add `import java.util.Set;` alongside the existing `java.util.*` imports
  and use the plain `Set<String>` type in `prepareAfterIdentityCheck`'s signature instead of the
  fully-qualified form shown above.)

  `sourceFreshness(...)` — only the `LinkResolver.resolve(...)` lambda's arity changes, from
  `resolvedBody -> AssetResolver...` to `(resolvedBody, ignoredPrivateTargetStems) -> AssetResolver...`:
```java
    private static SourceFreshnessOutcome sourceFreshness(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity expectedIdentity, TranslationJob job, String expectedStructuredData,
            PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader, NoteIntake noteIntake) {
        NoteIntake.Result current = noteIntake.admit(notePath, vaultReader);
        if (!current.accepted() || !expectedIdentity.equals(current.identity())) {
            return SourceFreshnessOutcome.stale();
        }
        return MarkdownNormalizer.normalize(current.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        (resolvedBody, ignoredPrivateTargetStems) -> AssetResolver.resolve(resolvedBody, vaultAssetReader).resolve(
                                (assetResolvedBody, ignoredAssets) ->
                                        sourceFingerprintMatches(job, assetResolvedBody, fieldsOf(current))
                                                && expectedStructuredData.equals(current.structuredData())
                                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                                : SourceFreshnessOutcome.stale(),
                                SourceFreshnessOutcome::assetBlocked),
                        SourceFreshnessOutcome::blockedTransclusion),
                SourceFreshnessOutcome::unclosedComment);
    }
```

  New failure helper, next to the existing `transclusionBlockedFailure`:
```java
    private static BridgeResponse directTargetIdentityBlockedFailure(String reason) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("semantic-references", reason));
    }
```

- [ ] 3.8 Run `PrepareHandlerTest` and confirm every test passes, including the three written in section 1.

```bash
cd publication-exporter && mvn -q -Dtest=PrepareHandlerTest test 2>&1 | tail -150
```

- [ ] 3.9 Update `LinkResolverTest.java`'s `resolvedBodyOrFail` helper and the one direct `.resolve(...)`
      call site for the new two-argument `onResolved` callback. Read the current file first (its full
      current content is reproduced above in this plan's research, at the point design.md's "LinkResolver /
      LinkResolutionOutcome" section was written — confirm it still matches):

```java
    private static String resolvedBodyOrFail(String body, PublicNoteIndex knownNotes) {
        return LinkResolver.resolve(body, knownNotes).resolve(
                (resolved, privateTargetStems) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));
    }
```

  And in `linkToBlogNoteTargetResolvesToNotesRoute` (the one test that calls `outcome.resolve(...)`
  directly rather than through the helper):
```java
        assertEquals("See [My Note](/notes/my-note/).", outcome.resolve(
                (resolved, privateTargetStems) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
```

  Also add one new test proving the collection itself, since nothing in `PrepareHandlerTest` asserts the
  *set* directly (only its downstream blocking effect):

```java
    @Test
    void resolveCollectsTheStemOfEveryUnresolvedPlainLinkAsAPrivateTargetStem() {
        String body = "See [[Черновик]] and [[Заметка о времени]].";

        Set<String> privateTargetStems = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, stems) -> stems,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(Set.of("Черновик"), privateTargetStems);
    }
```

  Add `import java.util.Set;` to this test file's imports if not already present.

- [ ] 3.10 Run `LinkResolverTest` and `PrepareHandlerTest` together, confirm both green.

```bash
cd publication-exporter && mvn -q -Dtest=LinkResolverTest,PrepareHandlerTest test 2>&1 | tail -150
```

- [ ] 3.11 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndex.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityCheck.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java
git commit -m "feat(exporter): block prepare when a direct private target's source ID is missing or duplicated"
```

## 4. Narrow unit tests for genuinely combinatorial identity-index logic

Per this project's outside-in discipline, these are the "genuinely combinatorial" cases not already covered
at acceptance scope by section 1: the ambiguous-filename-stem-collision fallback (mirrors
`PublicNoteIndex`'s own precedent), a target note absent from the vault entirely, and a duplicate between
two *different* targets (not source-vs-target, already covered by 1.2).

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndexTest.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityCheckTest.java`

- [ ] 4.1 Write and verify all of the following tests in `PrivateNoteIdentityIndexTest.java`:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrivateNoteIdentityIndexTest {

    @Test
    void identityForReturnsEmptyWhenNoFileMatchesTheStem() {
        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(VaultReader.createNull());

        assertTrue(index.identityFor("Nonexistent").isEmpty());
    }

    @Test
    void identityForReturnsEmptyWhenTwoFilesShareTheSameStem() {
        String noteInBlog = "---\npublish: false\nid: one\n---\nFirst.";
        String noteInArchive = "---\npublish: false\nid: two\n---\nSecond.";
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), noteInBlog,
                VaultRelativePath.of("archive/Draft.md"), noteInArchive));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        assertTrue(index.identityFor("Draft").isEmpty());
    }

    @Test
    void presentIdentityHasAnEmptySourceIdWhenFrontmatterHasNoIdKey() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), "---\npublish: false\n---\nNo id."));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        TargetIdentity identity = index.identityFor("Draft").orElseThrow();
        assertEquals(Optional.empty(), identity.sourceId());
    }

    @Test
    void identityForReturnsTheFrontmatterSourceIdWhenPresent() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), "---\npublish: false\nid: 4c1b-draft\n---\nBody."));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        TargetIdentity identity = index.identityFor("Draft").orElseThrow();
        assertEquals(Optional.of("4c1b-draft"), identity.sourceId());
    }
}
```

- [ ] 4.2 Write and verify all of the following tests in `DirectTargetIdentityCheckTest.java`:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectTargetIdentityCheckTest {

    @Test
    void selfLinkIsExcludedFromComparisonAgainstTheSourcesOwnId() {
        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/My Essay.md"), "---\npublish: true\nid: 8f2c-my-essay\n---\nBody.")));

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("My Essay"), index);

        assertTrue(outcome.resolve(() -> true, reason -> false));
    }

    @Test
    void twoDistinctTargetsSharingAnIdWithEachOtherAreBlocked() {
        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Target One.md"), "---\npublish: false\nid: shared\n---\nOne.",
                VaultRelativePath.of("blog/Target Two.md"), "---\npublish: false\nid: shared\n---\nTwo.")));

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("Target One", "Target Two"), index);

        assertTrue(outcome.resolve(() -> false, reason -> true));
    }

    @Test
    void aTargetStemWithNoMatchingVaultFileIsSkippedRatherThanBlocked() {
        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(VaultReader.createNull());

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("Typo Target"), index);

        assertTrue(outcome.resolve(() -> true, reason -> false));
    }
}
```

- [ ] 4.3 Run the two new test classes together with `PrepareHandlerTest` and `LinkResolverTest`, confirm
      all green.

```bash
cd publication-exporter && mvn -q -Dtest=PrivateNoteIdentityIndexTest,DirectTargetIdentityCheckTest,PrepareHandlerTest,LinkResolverTest test 2>&1 | tail -150
```

- [ ] 4.4 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrivateNoteIdentityIndexTest.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/DirectTargetIdentityCheckTest.java
git commit -m "test(exporter): add narrow coverage for private identity index and direct-target check edge cases"
```

## 5. Full-suite verification

- [ ] 5.1 Run the complete `publication-exporter` test suite and confirm every test passes. Baseline going
      into this slice is 802 tests, all green (confirmed 2026-08-14 immediately before this slice began,
      after fixing an unrelated dash-vs-bash timeout-test portability bug — see commit `dbd818b`).

```bash
cd publication-exporter && mvn -q test 2>&1 | tail -150
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{tests+=$3; fail+=$5; err+=$7; skip+=$9} END {print "Tests run:", tests, "Failures:", fail, "Errors:", err, "Skipped:", skip}'
```

- [ ] 5.2 Run the OpenSpec strict validation for this change and confirm it passes.

```bash
cd /home/eugene/Dev/personal-site && openspec validate --changes 2026-08-14-s18-direct-target-source-id-admission --strict
```

- [ ] 5.3 Refresh the graphify code graph (project convention after any code change).

```bash
cd /home/eugene/Dev/personal-site/publication-exporter && graphify update .
```

Do not archive the OpenSpec change or touch Haft artifacts from this task list — those steps are owned by
the orchestrating session, not by an implementer subagent.
