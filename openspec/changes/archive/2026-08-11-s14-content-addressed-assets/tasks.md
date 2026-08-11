<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance, explicit sealed outcome types over exceptions for expected business
  outcomes, guard clauses over nested conditionals, Composed Method (small, single-purpose private
  methods) throughout, package-private visibility by default (public only where a different package needs
  the type), and never a null return — every "maybe absent" result is Optional or a sealed outcome. No
  comments in production code beyond what non-obvious rationale demands — this file's own comments are
  plan scaffolding, not a model for the code you write.
- One new production boundary adapter this slice: `FilesystemVaultAssetReader` (sections 6-7). Everything
  else is pure in-process or an extension of an already-shipped adapter (`FilesystemCandidateWorkspace`).
- Full reference documents (read before starting any task): proposal.md, specs/public-content-model/spec.md,
  design.md — all in openspec/changes/s14-content-addressed-assets/. design.md's Decisions 1-5 map directly
  onto the classes this file creates; read it first if anything below is unclear on *why*, not just *what*.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Never modify ApprovedSnapshotWorkspace, ReleaseOutputStore, or BuildFromReviewHandler — confirmed
  asset-free today and explicitly out of scope this slice (design.md Context/Non-Goals). `CandidateSnapshot`
  stays exactly as it is today — do not add an `assets` field to it; it is shared with
  `ApprovedSnapshotWorkspace.read()` and must stay asset-free (design.md Decision 3).
- Naming: `AssetResolver`, `VaultAssetReader` follow this project's existing `-er`-suffixed naming for
  transform/port collaborators (`LinkResolver`, `VaultReader`, `MarkdownNormalizer`). Elegant Objects 1.1
  flags `-er` names as a default smell, but this is a deliberate, already-established project convention —
  do not rename these or any existing sibling class to chase the heuristic.
-->

## 1. Failing acceptance tests through `prepare` (RED)

All tests in this group go into the existing file
`publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
(package `dev.eugene.publicationexporter.prepare`). Read the existing file fully before adding to it — it
already has the imports, fixtures, and helper types (`NullCandidateWorkspace`, `NullWorkflowStatusEditor`,
`NullTranslationWorker`) these tests reuse. `PrepareHandler.prepare(...)` currently takes two parameters
(`VaultRelativePath notePath, VaultReader vaultReader`); this slice adds a third, `VaultAssetReader
vaultAssetReader` (section 3 makes the signature change — these tests are written against the *new*
three-parameter signature and will not compile until section 3 lands, which is the expected RED state).

- [x] 1.1 Write a failing test: preparing an essay that embeds one image resolves it to a content-addressed
      public reference and the candidate body rewrites to a Markdown image link.

```java
@Test
void assetEmbedWithAnExactVaultRelativeMatchResolvesToAContentAddressedReference() {
    byte[] imageBytes = "pretend-png-bytes".getBytes(StandardCharsets.UTF_8);
    String essay = """
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

            ![[diagram.png]]

            More prose.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("diagram.png", imageBytes));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

    assertTrue(response.ok());
    String expectedDigest = ContentHash.sha256Hex(imageBytes);
    assertEquals(
            "# My Essay\n\n![diagram](/assets/vault/" + expectedDigest + ".png)\n\nMore prose.",
            workspace.installed().get(0).ruBody());
}
```

  `diagram.png` is resolved from the fake's map key `"diagram.png"` — the fake's exact-match lookup uses
  the same string the embed target text carries (design.md Decision 2: reference resolution and extension
  both come from the reference/target text, never sniffed from content).

- [x] 1.2 Write a failing test: an ambiguous basename (no exact match, two files share the basename) blocks
      preparation with an asset diagnostic before any candidate is installed or workflow status is written —
      same shape as the existing S13 transclusion-blocked test in this file.

```java
@Test
void ambiguousAssetBasenameBlocksPreparationWithoutInstallingACandidate() {
    String essay = """
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

            ![[logo.png]]""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
            "assets/logo.png", "a".getBytes(StandardCharsets.UTF_8),
            "archive/logo.png", "b".getBytes(StandardCharsets.UTF_8)));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, essay));
    NullTranslationWorker worker = new NullTranslationWorker(
            TranslationOutcome.success("EN", "EN title", "EN description."));
    PrepareHandler handler = new PrepareHandler(
            worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

    assertFalse(response.ok());
    assertEquals("translation_failed", response.status());
    assertEquals("candidate", response.diagnostics().get(0).field());
    assertTrue(worker.requested().isEmpty());
    assertTrue(workspace.installed().isEmpty());
    assertEquals(null, editor.currentValue(path, "workflowStatus"));
}
```

  `worker.requested().isEmpty()` proves asset resolution runs, and blocks, before translation — the same
  ordering guarantee S13's transclusion test proved for links (design.md Decision 1's composition order:
  `AssetResolver` sits between `LinkResolver` and `prepareNormalizedEssay`/`TranslationJob`).

- [x] 1.3 Write a failing test: two embeds referencing identical bytes at different vault paths materialize
      as exactly one public asset, and both references use it.

```java
@Test
void identicalAssetBytesReferencedTwiceMaterializeAsOnePublicAsset() {
    byte[] sharedBytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
    String essay = """
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

            ![[a/cover.png]] and ![[b/cover.png]]""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
            "a/cover.png", sharedBytes, "b/cover.png", sharedBytes));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

    assertTrue(response.ok());
    String expectedDigest = ContentHash.sha256Hex(sharedBytes);
    String expectedReference = "/assets/vault/" + expectedDigest + ".png";
    assertEquals(
            "# My Essay\n\n![cover](" + expectedReference + ") and ![cover](" + expectedReference + ")",
            workspace.installed().get(0).ruBody());
}
```

- [x] 1.4 Run the new tests and confirm they fail to compile for the expected reason (`VaultAssetReader`,
      `ContentHash.sha256Hex(byte[])`, and the three-parameter `prepare(...)` overload do not exist yet —
      not an unrelated failure).

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -100`

Do not proceed to section 2 until you can see exactly why the build fails.

## 2. Extract `AssetTargets` from `LinkResolver` (REFACTOR — stays green throughout)

`LinkResolver` and the new `AssetResolver` (section 3) both need to recognize a target string as
asset-like by extension. Extract that one-line classification into a shared, package-private primitive
*before* writing `AssetResolver` — mirroring how S13 extracted `ProtectedRegionScanner` before writing
`LinkResolver`. This step changes no observable behavior — run the full suite before and after to prove it.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetTargets.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`

- [x] 2.1 Read the current `LinkResolver.java` in full first — it may have evolved since this task list was
      written; match the refactor against its actual current content. Create `AssetTargets.java` by moving
      the `ASSET_EXTENSIONS` set and `isAssetTarget` method out of `LinkResolver` verbatim:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Locale;
import java.util.Set;

final class AssetTargets {

    private static final Set<String> ASSET_EXTENSIONS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".mp3", ".mp4");

    private AssetTargets() {
    }

    static boolean isAssetTarget(String target) {
        String lowercaseTarget = target.toLowerCase(Locale.ROOT);
        return ASSET_EXTENSIONS.stream().anyMatch(lowercaseTarget::endsWith);
    }
}
```

- [x] 2.2 Update `LinkResolver.java` to call `AssetTargets.isAssetTarget(target)` instead of its own removed
      copy — delete the `ASSET_EXTENSIONS` constant, the `isAssetTarget` private method, and the now-unused
      `java.util.Locale`/`java.util.Set` imports from `LinkResolver.java`. Everything else in the file
      (`WIKILINK`, `resolve`, `appendLink`, `labelFor`, `lastPathSegment`, etc.) stays exactly as it is
      today — only the one call site (`isAssetTarget(target)` inside `appendLink`) and the file's imports
      change. No import of `AssetTargets` is needed — it is in the same package.

- [x] 2.3 Run the full test suite and confirm it is exactly as green as it was before this refactor (the
      three new tests from section 1 still fail to compile, for the same reason as before — everything else
      must be unchanged).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

- [x] 2.4 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetTargets.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java
git commit -m "refactor(exporter): extract AssetTargets from LinkResolver"
```

## 3. Implement in-memory asset resolution and wire into `PrepareHandler` (GREEN)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/AssetLookup.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultAssetReader.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultAssetReader.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateAsset.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetResolutionOutcome.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetResolver.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/SourceFreshnessOutcome.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`

- [x] 3.1 Add a `byte[]` overload to `ContentHash.java` (read the current file first — it is four lines of
      real logic, reproduced here for reference). The existing `sha256Hex(String)` delegates to the new
      overload rather than duplicating the digest logic:

```java
package dev.eugene.publicationexporter.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    public static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", impossible);
        }
    }
}
```

- [x] 3.2 Create `AssetLookup.java` (design.md Decision 2) — a sealed outcome for one asset resolution
      attempt. Defensively clones the byte array on the way in and out (Elegant Objects 2.6: be immutable —
      `byte[]` is mutable, so a stored/returned reference must never be shared with caller-owned arrays):

```java
package dev.eugene.publicationexporter.vault;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface AssetLookup permits FoundAsset, AmbiguousAsset, UnsafeAsset, AssetNotFound {

    static AssetLookup found(byte[] content) {
        return new FoundAsset(content);
    }

    static AssetLookup ambiguous() {
        return new AmbiguousAsset();
    }

    static AssetLookup unsafe() {
        return new UnsafeAsset();
    }

    static AssetLookup notFound() {
        return new AssetNotFound();
    }

    <T> T resolve(
            Function<byte[], T> onFound,
            Supplier<T> onAmbiguous,
            Supplier<T> onUnsafe,
            Supplier<T> onNotFound);
}

final class FoundAsset implements AssetLookup {

    private final byte[] content;

    FoundAsset(byte[] content) {
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onFound.apply(content.clone());
    }
}

final class AmbiguousAsset implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onAmbiguous.get();
    }
}

final class UnsafeAsset implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onUnsafe.get();
    }
}

final class AssetNotFound implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onNotFound.get();
    }
}
```

- [x] 3.3 Create `VaultAssetReader.java` (design.md Decision 2 — a dedicated port, not an extension of
      `VaultReader`):

```java
package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;
import java.util.Map;

public interface VaultAssetReader {

    AssetLookup resolve(String reference);

    static VaultAssetReader create(Path vaultRoot) {
        return new FilesystemVaultAssetReader(vaultRoot);
    }

    static VaultAssetReader createNull() {
        return new NullVaultAssetReader(Map.of());
    }

    static VaultAssetReader createNull(Map<String, byte[]> assetsByVaultRelativePath) {
        return new NullVaultAssetReader(assetsByVaultRelativePath);
    }
}
```

  This references `FilesystemVaultAssetReader`, which section 6 creates. Section 3 does not compile until
  section 6 exists — either write a temporary throwing stub for `FilesystemVaultAssetReader` now and replace
  it in section 6, or do section 6 immediately after section 3 before running the full suite. The task order
  in this file assumes the former: create a minimal placeholder now so sections 3-5 compile and their tests
  run (the placeholder is never exercised by any in-memory test in sections 1-5, only by section 6's own
  contract tests):

```java
package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;
import java.util.Objects;

final class FilesystemVaultAssetReader implements VaultAssetReader {

    private final Path vaultRoot;

    FilesystemVaultAssetReader(Path vaultRoot) {
        this.vaultRoot = Objects.requireNonNull(vaultRoot, "vaultRoot");
    }

    @Override
    public AssetLookup resolve(String reference) {
        throw new UnsupportedOperationException("Implemented in section 6");
    }
}
```

  Section 6 replaces this whole file with the real implementation — do not leave the placeholder in place
  once section 6 lands.

- [x] 3.4 Create `NullVaultAssetReader.java` — exact-path-first, unique-basename-fallback, ambiguous, and
      (syntactic) unsafe-target resolution, all provable without real filesystem I/O. Reuses
      `VaultRelativePath.isWithinVault()` (already proven pure logic) for the traversal/absolute-path half of
      "unsafe" — symlink escape specifically cannot be modeled by a map and is proven only by section 6's
      real-adapter contract test, per design.md's sequencing (Decision 5):

```java
package dev.eugene.publicationexporter.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class NullVaultAssetReader implements VaultAssetReader {

    private final Map<String, byte[]> contentByPath;

    NullVaultAssetReader(Map<String, byte[]> assetsByVaultRelativePath) {
        Objects.requireNonNull(assetsByVaultRelativePath, "assetsByVaultRelativePath");
        this.contentByPath = Map.copyOf(assetsByVaultRelativePath);
    }

    @Override
    public AssetLookup resolve(String reference) {
        Objects.requireNonNull(reference, "reference");
        if (!VaultRelativePath.of(reference).isWithinVault()) {
            return AssetLookup.unsafe();
        }
        byte[] exact = contentByPath.get(reference);
        if (exact != null) {
            return AssetLookup.found(exact);
        }
        return resolveByBasename(basename(reference));
    }

    private AssetLookup resolveByBasename(String basename) {
        List<byte[]> matches = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : contentByPath.entrySet()) {
            if (basename(entry.getKey()).equals(basename)) {
                matches.add(entry.getValue());
            }
        }
        if (matches.isEmpty()) {
            return AssetLookup.notFound();
        }
        if (matches.size() > 1) {
            return AssetLookup.ambiguous();
        }
        return AssetLookup.found(matches.get(0));
    }

    private static String basename(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
    }
}
```

- [x] 3.5 Create `CandidateAsset.java` (design.md Decision 3 — the write-side value type threaded through
      `CandidateWorkspace.install(...)` as a parameter separate from `CandidateSnapshot`):

```java
package dev.eugene.publicationexporter.candidate;

import java.util.Arrays;
import java.util.Objects;

public final class CandidateAsset {

    private final String publicName;
    private final byte[] content;

    private CandidateAsset(String publicName, byte[] content) {
        this.publicName = Objects.requireNonNull(publicName, "publicName");
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    public static CandidateAsset of(String publicName, byte[] content) {
        return new CandidateAsset(publicName, content);
    }

    public String publicName() {
        return publicName;
    }

    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidateAsset that)) {
            return false;
        }
        return publicName.equals(that.publicName) && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicName, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "CandidateAsset[publicName=" + publicName + ", content=" + content.length + " bytes]";
    }
}
```

- [x] 3.6 Widen `CandidateWorkspace.java`'s `install(...)` to take a `CandidateSnapshot` plus a separate
      `List<CandidateAsset>` (design.md Decision 3 — `CandidateSnapshot` itself is untouched):

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

    static CandidateWorkspace create(Path reviewRoot) {
        return new FilesystemCandidateWorkspace(reviewRoot);
    }

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
```

- [x] 3.7 Update `NullCandidateWorkspace.java` to the new `install(...)` signature. Read the current file in
      full first (reproduced in full below for reference — confirm it still matches before editing).
      `InstalledCandidate` gains an `assets()` accessor; `read(...)` stays asset-free (matches
      `CandidateSnapshot`, which never carries assets):

```java
package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NullCandidateWorkspace implements CandidateWorkspace {

    private final List<InstalledCandidate> installed = new ArrayList<>();

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(assets, "assets");
        installed.add(InstalledCandidate.of(identity, content, assets));
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity).map(NullCandidateWorkspace::syntheticPaths);
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity)
                .filter(candidate -> candidate.content().referenceMap().identity().equals(identity))
                .map(InstalledCandidate::content);
    }

    private Optional<InstalledCandidate> lastInstalledMatching(PublicationIdentity identity) {
        InstalledCandidate match = null;
        for (InstalledCandidate candidate : installed) {
            if (candidate.identity().equals(identity)) {
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    private static CandidatePaths syntheticPaths(InstalledCandidate candidate) {
        Path candidateDirectory = Path.of("/candidate", candidate.identity().publicCollection(),
                candidate.identity().publicId(), "candidate");
        return CandidatePaths.of(candidateDirectory.resolve("ru.md"), candidateDirectory.resolve("en.md"));
    }

    public List<InstalledCandidate> installed() {
        return List.copyOf(installed);
    }

    public static final class InstalledCandidate {

        private final PublicationIdentity identity;
        private final CandidateSnapshot content;
        private final List<CandidateAsset> assets;

        private InstalledCandidate(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.content = Objects.requireNonNull(content, "content");
            this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        }

        public static InstalledCandidate of(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
            return new InstalledCandidate(identity, content, assets);
        }

        public PublicationIdentity identity() {
            return identity;
        }

        public CandidateSnapshot content() {
            return content;
        }

        public List<CandidateAsset> assets() {
            return assets;
        }

        public String ruBody() {
            return content.ruBody();
        }

        public String enBody() {
            return content.enBody();
        }

        public ReferenceMap referenceMap() {
            return content.referenceMap();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstalledCandidate that)) {
                return false;
            }
            return identity.equals(that.identity) && content.equals(that.content) && assets.equals(that.assets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity, content, assets);
        }

        @Override
        public String toString() {
            return "InstalledCandidate[identity=" + identity + ", content=" + content + ", assets=" + assets + "]";
        }
    }
}
```

  `ruBody()`/`enBody()`/`referenceMap()` convenience delegates are kept on `InstalledCandidate` so section 1's
  tests (`workspace.installed().get(0).ruBody()`) and every pre-existing test using that same call shape
  keep compiling unchanged.

- [x] 3.8 Search the test tree for every other `CandidateWorkspace.install(...)` caller and fix its call
      shape to the new signature. Confirmed callers as of this writing (re-verify — the codebase may have
      changed): `FilesystemCandidateWorkspaceTest.java` (production adapter — leave until section 7, which
      rewrites `FilesystemCandidateWorkspace` itself and its test together). No other test file calls
      `CandidateWorkspace.install(...)` directly today (`MarkReviewedHandlerTest`,
      `InspectPublicationHandlerTest`, etc. call `ApprovedSnapshotWorkspace.install(...)`, a different,
      unchanged interface — do not confuse the two). Run this to confirm before proceeding:

```bash
cd publication-exporter && grep -rn "CandidateWorkspace\b" src/test --include="*.java" -l
```

- [x] 3.9 Create `AssetResolutionOutcome.java` (design.md Decision 1 — mirrors `LinkResolutionOutcome`'s
      shape: a successful resolution carries the rewritten body plus the accepted assets; a block carries
      only the offending reference):

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.candidate.CandidateAsset;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface AssetResolutionOutcome permits ResolvedAssets, BlockedAsset {

    static AssetResolutionOutcome resolved(String body, List<CandidateAsset> assets) {
        return new ResolvedAssets(body, assets);
    }

    static AssetResolutionOutcome blocked(String reference) {
        return new BlockedAsset(reference);
    }

    <T> T resolve(
            BiFunction<String, List<CandidateAsset>, T> onResolved,
            Function<String, T> onBlocked);
}

final class ResolvedAssets implements AssetResolutionOutcome {

    private final String body;
    private final List<CandidateAsset> assets;

    ResolvedAssets(String body, List<CandidateAsset> assets) {
        this.body = Objects.requireNonNull(body, "body");
        this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
    }

    @Override
    public <T> T resolve(BiFunction<String, List<CandidateAsset>, T> onResolved, Function<String, T> onBlocked) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onResolved.apply(body, assets);
    }
}

final class BlockedAsset implements AssetResolutionOutcome {

    private final String reference;

    BlockedAsset(String reference) {
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public <T> T resolve(BiFunction<String, List<CandidateAsset>, T> onResolved, Function<String, T> onBlocked) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onBlocked.apply(reference);
    }
}
```

- [x] 3.10 Create `AssetResolver.java` (design.md Decisions 1, 2, 4 — the second pipeline stage, composed
      after `LinkResolver`). It re-scans for `![[...]]` embeds using the same `ProtectedRegionScanner` and
      leftmost-match-among-candidates shape `LinkResolver`/`MarkdownNormalizer` already use. Because
      `LinkResolver` has already resolved or blocked every *other* `![[...]]` shape (public-note embed →
      Markdown link; private/unresolved embed → blocked transclusion), the only `![[...]]` text that can
      possibly survive into this scanner's input is asset-like — the `AssetTargets.isAssetTarget` check
      inside `nextAssetEmbed` asserts that invariant defensively rather than relying on it silently. The
      extension used to build a public asset's name comes from the *reference text*, never from sniffing
      resolved bytes — basename-fallback resolution matches on the full filename including extension, so the
      reference's extension and the resolved file's actual extension are always the same string:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.candidate.CandidateAsset;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultAssetReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AssetResolver {

    private static final Pattern ASSET_EMBED =
            Pattern.compile("!\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]");

    private AssetResolver() {
    }

    public static AssetResolutionOutcome resolve(String body, VaultAssetReader vaultAssetReader) {
        StringBuilder output = new StringBuilder(body.length());
        Map<String, CandidateAsset> assetsByDigest = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher embed = nextAssetEmbed(body, cursor);
            if (protectedSpanBeforeEmbed(protectedSpan, embed)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (embed != null) {
                Optional<String> blockedReference =
                        appendAsset(body, output, cursor, embed, vaultAssetReader, assetsByDigest);
                if (blockedReference.isPresent()) {
                    return AssetResolutionOutcome.blocked(blockedReference.get());
                }
                cursor = embed.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return AssetResolutionOutcome.resolved(output.toString(), List.copyOf(assetsByDigest.values()));
    }

    private static Matcher nextAssetEmbed(String body, int cursor) {
        Matcher matcher = ASSET_EMBED.matcher(body);
        int searchFrom = cursor;
        while (matcher.find(searchFrom)) {
            if (AssetTargets.isAssetTarget(matcher.group(1).strip())) {
                return matcher;
            }
            searchFrom = matcher.end();
        }
        return null;
    }

    private static boolean protectedSpanBeforeEmbed(ProtectedRegionScanner.ProtectedSpan protectedSpan, Matcher embed) {
        return protectedSpan != null && (embed == null || protectedSpan.start() <= embed.start());
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<String> appendAsset(
            String body, StringBuilder output, int cursor, Matcher embed,
            VaultAssetReader vaultAssetReader, Map<String, CandidateAsset> assetsByDigest) {
        output.append(body, cursor, embed.start());
        String reference = embed.group(1).strip();
        String label = labelFor(embed, reference);
        return vaultAssetReader.resolve(reference).resolve(
                content -> appendMaterialized(output, reference, label, content, assetsByDigest),
                () -> Optional.of(reference),
                () -> Optional.of(reference),
                () -> Optional.of(reference));
    }

    private static Optional<String> appendMaterialized(
            StringBuilder output, String reference, String label, byte[] content,
            Map<String, CandidateAsset> assetsByDigest) {
        String digest = ContentHash.sha256Hex(content);
        String extension = extensionOf(reference);
        CandidateAsset existing = assetsByDigest.get(digest);
        if (existing != null && !existing.publicName().endsWith(extension)) {
            return Optional.of(reference);
        }
        CandidateAsset asset = existing != null
                ? existing
                : assetsByDigest.computeIfAbsent(digest, ignored -> CandidateAsset.of(digest + extension, content));
        output.append("![").append(label).append("](/assets/vault/").append(asset.publicName()).append(')');
        return Optional.empty();
    }

    private static String labelFor(Matcher embed, String reference) {
        String alias = embed.group(2);
        return alias != null ? alias.strip() : lastPathSegment(reference);
    }

    private static String lastPathSegment(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
    }

    private static String extensionOf(String reference) {
        int dot = reference.lastIndexOf('.');
        String extension = dot < 0 ? "" : reference.substring(dot).toLowerCase(Locale.ROOT);
        return extension.equals(".jpeg") ? ".jpg" : extension;
    }
}
```

  `appendMaterialized`'s suffix-family guard (`existing != null && !existing.publicName().endsWith(extension)`)
  blocks the pathological case where two *different*-extension references happen to have byte-identical
  content — mirroring `exporter-java`'s `AssetResolver` suffix-family check (evidence only). This has its own
  dedicated unit test in section 5; do not delete this guard as "unreachable" without that test present.

- [x] 3.11 Add a fifth branch to `SourceFreshnessOutcome.java` (`assetBlocked`) so the freshness re-check
      (which re-runs `MarkdownNormalizer` and `LinkResolver` today) can also re-run `AssetResolver` and
      report a newly-discovered asset block with its own diagnostic — the same "give a re-discovered
      terminal problem its own branch" precedent `blockedTransclusion` already set in S13. Read the current
      file in full first (reproduced below as of this task list's writing — confirm it still matches before
      editing) and add the `assetBlocked` static factory, the `BlockedAssetSource` implementation, and the
      fifth `Function<String, T> onAssetBlocked` parameter on every existing branch's `resolve(...)`
      override:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface SourceFreshnessOutcome
        permits MatchingSource, StaleSource, UnclosedSourceComment, BlockedTransclusionSource, BlockedAssetSource {

    static SourceFreshnessOutcome matches(String sourceHash) {
        return new MatchingSource(sourceHash);
    }

    static SourceFreshnessOutcome stale() {
        return new StaleSource();
    }

    static SourceFreshnessOutcome unclosedComment(int position) {
        return new UnclosedSourceComment(position);
    }

    static SourceFreshnessOutcome blockedTransclusion(String target) {
        return new BlockedTransclusionSource(target);
    }

    static SourceFreshnessOutcome assetBlocked(String reference) {
        return new BlockedAssetSource(reference);
    }

    <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion,
            Function<String, T> onAssetBlocked);
}

final class MatchingSource implements SourceFreshnessOutcome {

    private final String sourceHash;

    MatchingSource(String sourceHash) {
        this.sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onMatches.apply(sourceHash);
    }
}

final class StaleSource implements SourceFreshnessOutcome {

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onStale.get();
    }
}

final class UnclosedSourceComment implements SourceFreshnessOutcome {

    private final int position;

    UnclosedSourceComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onUnclosedComment.apply(position);
    }
}

final class BlockedTransclusionSource implements SourceFreshnessOutcome {

    private final String target;

    BlockedTransclusionSource(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onBlockedTransclusion.apply(target);
    }
}

final class BlockedAssetSource implements SourceFreshnessOutcome {

    private final String reference;

    BlockedAssetSource(String reference) {
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onAssetBlocked.apply(reference);
    }
}
```

- [x] 3.12 Wire everything into `PrepareHandler.java`. Read the current file in full first (its exact shape
      may have shifted since this task list was written). `AssetResolver` is threaded in as the pipeline
      stage right after `LinkResolver` succeeds; `vaultAssetReader` is threaded alongside `knownNotes`
      through every method between `prepare(...)` and `sourceFreshness(...)`, the same way S13 threaded
      `knownNotes`; the resolved `assets` list is threaded alongside `ruBody` from `prepare(...)` down to
      `installCandidate(...)`.

  **`prepare(...)`** — third parameter `VaultAssetReader vaultAssetReader`; `AssetResolver.resolve(...)`
  nests inside the existing `LinkResolver.resolve(...)` continuation:
```java
    public BridgeResponse prepare(
            VaultRelativePath notePath, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        PublicNoteIndex knownNotes;
        try {
            knownNotes = PublicNoteIndex.from(vaultReader);
        } catch (UncheckedIOException failure) {
            return knownNotesLookupFailure(failure);
        }
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> AssetResolver.resolve(resolvedBody, vaultAssetReader).resolve(
                                (assetResolvedBody, assets) -> prepareNormalizedEssay(notePath, vaultReader,
                                        intake, assetResolvedBody, assets, knownNotes, vaultAssetReader),
                                PrepareHandler::assetBlockedFailure),
                        PrepareHandler::transclusionBlockedFailure),
                position -> unclosedCommentFailure(position));
    }
```

  **`prepareNormalizedEssay(...)`** — gains `List<CandidateAsset> assets` and `VaultAssetReader
  vaultAssetReader`:
```java
    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, List<CandidateAsset> assets, PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader) {
        ApprovedBaselineLookup approved = lookupApprovedBaseline(intake, normalizedBody);
        if (approved.failed()) {
            return approved.failureResponse();
        }
        if (approved.snapshot().isPresent()) {
            return mirrorApprovedCandidate(intake.identity(), approved.snapshot().get());
        }
        return prepareWithInstallLock(
                notePath, vaultReader, intake, normalizedBody, assets, knownNotes, vaultAssetReader);
    }
```

  **`mirrorApprovedCandidate(...)`/`ensureCandidateMirrorsApproved(...)`** — unchanged signature; the mirror
  path installs zero assets, since `ApprovedSnapshotWorkspace` carries none this slice (design.md Decision
  3), and reuses the approved `CandidateSnapshot` directly instead of unpacking its fields:
```java
    private BridgeResponse mirrorApprovedCandidate(PublicationIdentity identity, CandidateSnapshot approved) {
        try {
            ensureCandidateMirrorsApproved(identity, approved);
        } catch (UncheckedIOException failure) {
            return candidateFailure(
                    IoFailureMessages.describe("Candidate mirror of approved snapshot failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate mirror of approved snapshot failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private void ensureCandidateMirrorsApproved(PublicationIdentity identity, CandidateSnapshot approved) {
        if (candidateWorkspace.find(identity).isPresent()) {
            return;
        }
        candidateWorkspace.install(identity, approved, List.of());
    }
```

  **`prepareWithInstallLock(...)`** — gains `List<CandidateAsset> assets` and `VaultAssetReader
  vaultAssetReader`:
```java
    private BridgeResponse prepareWithInstallLock(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake, String normalizedBody,
            List<CandidateAsset> assets, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader) {
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
        installLock.lock();
        try {
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(), intake.sourceHash(),
                    normalizedBody, intake.title(), intake.description(), assets, knownNotes, vaultAssetReader);
        } finally {
            installLock.unlock();
        }
    }
```

  **`prepareAdmittedEssay(...)`** — gains `List<CandidateAsset> assets` and `VaultAssetReader
  vaultAssetReader`:
```java
    private BridgeResponse prepareAdmittedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription, List<CandidateAsset> assets,
            PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
        return translateCandidate(job, ruBody, ruTitle, ruDescription).resolve(
                translation -> prepareTranslatedEssay(notePath, vaultReader, identity, sourceHash,
                        ruBody, ruTitle, ruDescription, assets, job, translation, knownNotes, vaultAssetReader),
                failure -> {
                    recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                    return translationFailure(failure);
                });
    }
```

  **`prepareTranslatedEssay(...)`** — gains `List<CandidateAsset> assets` and `VaultAssetReader
  vaultAssetReader`, passes both to `sourceFreshness`/`installCandidate`, and adds the fifth
  `.resolve(...)` arm:
```java
    private BridgeResponse prepareTranslatedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription, List<CandidateAsset> assets,
            TranslationJob job, EnglishTranslation translation, PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader) {
        String enBody = translation.body();
        String enTitle = translation.title();
        String enDescription = translation.description();

        EnglishCandidateValidator.Result validation = validateEnglishCandidate(
                ruBody, enBody, enTitle, enDescription);
        if (!validation.valid()) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }
        return sourceFreshness(notePath, vaultReader, identity, job, knownNotes, vaultAssetReader).resolve(
                currentSourceHash -> {
                    ReferenceMap referenceMap = buildReferenceMap(
                            identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription);
                    BridgeResponse response = installCandidate(identity, ruBody, enBody, ruTitle, enTitle,
                            ruDescription, enDescription, referenceMap, assets);
                    if (response.ok()) {
                        recordWorkflowStatus(notePath, currentSourceHash, WorkflowState.READY_FOR_REVIEW);
                    }
                    return response;
                },
                () -> {
                    recordStaleWorkflowStatus(notePath, vaultReader);
                    return BridgeResponse.stale(COMMAND,
                            Diagnostic.blocking(
                                    "candidate", "Source note changed while translation was in progress."));
                },
                PrepareHandler::unclosedCommentFailure,
                PrepareHandler::transclusionBlockedFailure,
                PrepareHandler::assetBlockedFailure);
    }
```

  **`sourceFreshness(...)`** (static) — gains `VaultAssetReader vaultAssetReader`, nests
  `AssetResolver.resolve` the same way `prepare()` does, discarding the re-resolved assets list (only the
  body text feeds the fingerprint comparison — design.md Decision 4):
```java
    private static SourceFreshnessOutcome sourceFreshness(
            VaultRelativePath notePath, VaultReader vaultReader, PublicationIdentity expectedIdentity,
            TranslationJob job, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader) {
        NoteIntake.Result current = new NoteIntake().admit(notePath, vaultReader);
        if (!current.accepted() || !expectedIdentity.equals(current.identity())) {
            return SourceFreshnessOutcome.stale();
        }
        return MarkdownNormalizer.normalize(current.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> AssetResolver.resolve(resolvedBody, vaultAssetReader).resolve(
                                (assetResolvedBody, ignoredAssets) ->
                                        sourceFingerprintMatches(job, assetResolvedBody, current.title(), current.description())
                                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                                : SourceFreshnessOutcome.stale(),
                                SourceFreshnessOutcome::assetBlocked),
                        SourceFreshnessOutcome::blockedTransclusion),
                SourceFreshnessOutcome::unclosedComment);
    }
```

  **`installCandidate(...)`** — builds a `CandidateSnapshot` and calls the new two-argument-plus-identity
  `install(...)`:
```java
    private BridgeResponse installCandidate(
            PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription,
            ReferenceMap referenceMap, List<CandidateAsset> assets) {
        try {
            candidateWorkspace.install(identity,
                    CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap),
                    assets);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }
```

  Add one new private static helper next to `transclusionBlockedFailure`:
```java
    private static BridgeResponse assetBlockedFailure(String reference) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Asset reference \"" + reference + "\" could not be resolved."));
    }
```

  New imports needed in `PrepareHandler.java`: `dev.eugene.publicationexporter.candidate.CandidateAsset`,
  `dev.eugene.publicationexporter.vault.VaultAssetReader`. `AssetResolver` and `AssetResolutionOutcome` are
  in the same package (`prepare`) — no import needed.

- [x] 3.13 Update `PrepareCommand.java`'s `call()` to construct and pass a real `VaultAssetReader`:

```java
    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        VaultAssetReader vaultAssetReader = VaultAssetReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.create(vaultRoot);
        TranslationWorker translationWorker = translationWorkerForJobRoot.apply(jobsDirectory);
        BridgeResponse response = new PrepareHandler(
                translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
                .prepare(VaultRelativePath.of(notePath), vaultReader, vaultAssetReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
```

  Add the import `dev.eugene.publicationexporter.vault.VaultAssetReader` alongside the existing
  `dev.eugene.publicationexporter.vault.VaultReader` import.

- [x] 3.14 Every other existing call to `PrepareHandler.prepare(path, vaultReader)` in
      `PrepareHandlerTest.java` must add a third argument. For every test that has no asset embeds in its
      fixture (the overwhelming majority), pass `VaultAssetReader.createNull()` (the empty default). Search
      and fix each call site:

```bash
cd publication-exporter && grep -n "\.prepare(" src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
```

  For each match, add `, VaultAssetReader.createNull()` before the closing parenthesis, and add the import
  `dev.eugene.publicationexporter.vault.VaultAssetReader` to the test file if not already present (it will
  already be present after task 1.1-1.3 are added, since those tests use `VaultAssetReader` directly).

- [x] 3.15 Run `PrepareHandlerTest` and confirm every test passes, including the three written in section 1.

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -200`

- [x] 3.16 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/hash/ContentHash.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/AssetLookup.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultAssetReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/NullVaultAssetReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultAssetReader.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateAsset.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetResolutionOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/AssetResolver.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/SourceFreshnessOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(exporter): resolve and content-address publishable assets in prepare"
```

## 4. `EnglishCandidateValidator` asset-route preservation

design.md Decision 4: the translation worker must preserve a resolved `/assets/vault/...` reference
verbatim, the same way it must already preserve external URLs. Add a check mirroring the existing
`droppedExternalUrls` logic.

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java`
- Modify (tests): `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidatorTest.java`
  (create this file if it does not already exist; check first — `PrepareHandlerTest` may be the only test
  coverage for this class today, in which case add the new case there following its existing test-naming
  convention instead)

- [x] 4.1 Read the current `EnglishCandidateValidator.java` in full first — reproduced above in this
      slice's design.md Context section for reference; confirm it still matches before editing. Add an
      asset-route-dropped diagnostic, following the exact shape of `droppedExternalUrls`/`extractUrls`:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnglishCandidateValidator {

    private static final Pattern EXTERNAL_URL =
            Pattern.compile("https?://[^\\s)\\]]+");
    private static final Pattern INTERNAL_RU_ROUTE =
            Pattern.compile("/ru/");
    private static final Pattern ASSET_REFERENCE =
            Pattern.compile("/assets/vault/[^\\s)\\]]+");

    private EnglishCandidateValidator() {
    }

    public static Result validate(String ruBody, String enBody, String enTitle, String enDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(enDescription, "enDescription");

        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(blankFieldDiagnostics(enBody, enTitle, enDescription));
        diagnostics.addAll(internalRouteDiagnostics(enBody, enTitle, enDescription));
        diagnostics.addAll(droppedUrlDiagnostics(ruBody, enBody));
        diagnostics.addAll(droppedAssetReferenceDiagnostics(ruBody, enBody));
        return diagnostics.isEmpty() ? Result.ok() : Result.invalid(diagnostics);
    }

    private static List<String> blankFieldDiagnostics(String enBody, String enTitle, String enDescription) {
        List<String> diagnostics = new ArrayList<>();
        if (enBody.isBlank()) {
            diagnostics.add("Translation worker produced a blank body.");
        }
        if (enTitle.isBlank()) {
            diagnostics.add("Translation worker produced a blank title.");
        }
        if (enDescription.isBlank()) {
            diagnostics.add("Translation worker produced a blank description.");
        }
        return diagnostics;
    }

    private static List<String> internalRouteDiagnostics(
            String enBody, String enTitle, String enDescription) {
        boolean internalRoutePresent = List.of(enBody, enTitle, enDescription).stream()
                .anyMatch(EnglishCandidateValidator::containsInternalRuRoute);
        return internalRoutePresent
                ? List.of("English candidate contains an internal /ru/ route.")
                : List.of();
    }

    private static boolean containsInternalRuRoute(String text) {
        Matcher urlMatcher = EXTERNAL_URL.matcher(text);
        StringBuilder withUrlsRemoved = new StringBuilder();
        int lastEnd = 0;
        while (urlMatcher.find()) {
            withUrlsRemoved.append(text, lastEnd, urlMatcher.start());
            lastEnd = urlMatcher.end();
        }
        withUrlsRemoved.append(text, lastEnd, text.length());
        return INTERNAL_RU_ROUTE.matcher(withUrlsRemoved).find();
    }

    private static List<String> droppedUrlDiagnostics(String ruBody, String enBody) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedUrl : droppedMatches(ruBody, enBody, EXTERNAL_URL)) {
            diagnostics.add("English candidate dropped external URL " + droppedUrl + ".");
        }
        return diagnostics;
    }

    private static List<String> droppedAssetReferenceDiagnostics(String ruBody, String enBody) {
        List<String> diagnostics = new ArrayList<>();
        for (String droppedReference : droppedMatches(ruBody, enBody, ASSET_REFERENCE)) {
            diagnostics.add("English candidate dropped asset reference " + droppedReference + ".");
        }
        return diagnostics;
    }

    private static Set<String> droppedMatches(String ruBody, String enBody, Pattern pattern) {
        Set<String> ruMatches = extractMatches(ruBody, pattern);
        Set<String> enMatches = extractMatches(enBody, pattern);
        Set<String> dropped = new LinkedHashSet<>(ruMatches);
        dropped.removeAll(enMatches);
        return dropped;
    }

    private static Set<String> extractMatches(String text, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    public static final class Result {
        private final boolean valid;
        private final List<String> diagnostics;

        private Result(boolean valid, List<String> diagnostics) {
            this.valid = valid;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result ok() {
            return new Result(true, List.of());
        }

        static Result invalid(List<String> diagnostics) {
            return new Result(false, diagnostics);
        }

        public boolean valid() {
            return valid;
        }

        public List<String> diagnostics() {
            return diagnostics;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result that)) {
                return false;
            }
            return valid == that.valid && diagnostics.equals(that.diagnostics);
        }

        @Override
        public int hashCode() {
            return Objects.hash(valid, diagnostics);
        }
    }
}
```

  `droppedUrlDiagnostics`/`droppedAssetReferenceDiagnostics` both now delegate to the generalized
  `droppedMatches`/`extractMatches` (Composed Method, SBPP-BEH-01) instead of duplicating the same
  set-difference logic twice — this is the DRY move the applying-sbpp skill's Composed Method pattern
  calls for once a second, structurally identical case appears.

- [x] 4.2 Write a failing-then-passing test proving the new check. Find the existing test file for this
      class first (`grep -rl "EnglishCandidateValidator" publication-exporter/src/test` — if a dedicated
      `EnglishCandidateValidatorTest.java` exists, add to it following its existing style; otherwise add
      alongside wherever this class is currently tested):

```java
@Test
void droppedAssetReferenceIsReportedAsInvalid() {
    String ruBody = "See ![cover](/assets/vault/abc123.png).";
    String enBody = "See the cover image.";

    EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
            ruBody, enBody, "Title", "Description.");

    assertFalse(result.valid());
    assertTrue(result.diagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.contains("dropped asset reference")));
}

@Test
void preservedAssetReferenceIsValid() {
    String ruBody = "See ![cover](/assets/vault/abc123.png).";
    String enBody = "See ![cover](/assets/vault/abc123.png).";

    EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
            ruBody, enBody, "Title", "Description.");

    assertTrue(result.valid());
}
```

- [x] 4.3 Run this class's tests and confirm they pass, plus a full-module compile check (this class's
      callers, e.g. `PrepareHandler`, must still compile against the unchanged public `Result`/`validate`
      signature).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -200`

- [x] 4.4 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/
git commit -m "feat(exporter): require English candidates to preserve resolved asset references"
```

## 5. Narrow unit tests for genuinely combinatorial `AssetResolver` logic

Per this project's outside-in discipline, these are the "genuinely combinatorial" cases not already covered
at acceptance scope by section 1: alias-vs-basename label choice, extension case-insensitivity and `.jpeg`
canonicalization, the suffix-family-conflict guard (task 3.10's dedicated note), and a protected-region
regression check independent of `PrepareHandlerTest`'s own guard.

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/AssetResolverTest.java`

- [x] 5.1 Write and verify all of the following tests in one new file:

```java
package dev.eugene.publicationexporter.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.eugene.publicationexporter.vault.VaultAssetReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AssetResolverTest {

    private static String resolvedBodyOrFail(String body, VaultAssetReader vaultAssetReader) {
        return AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> resolvedBody,
                reference -> fail("Expected a resolved result but \"" + reference + "\" was blocked."));
    }

    @Test
    void aliasWinsOverBasenameAsTheLabel() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("a/diagram.png", bytes));
        String digest = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(bytes);

        assertEquals("![a great diagram](/assets/vault/" + digest + ".png)",
                resolvedBodyOrFail("![[a/diagram.png|a great diagram]]", vaultAssetReader));
    }

    @Test
    void extensionMatchingAndOutputNamingAreCaseInsensitiveWithJpegCanonicalizedToJpg() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("Photo.JPEG", bytes));
        String digest = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(bytes);

        assertEquals("![Photo](/assets/vault/" + digest + ".jpg)",
                resolvedBodyOrFail("![[Photo.JPEG]]", vaultAssetReader));
    }

    @Test
    void twoDifferentExtensionReferencesWithIdenticalBytesBlockOnTheSecondOne() {
        byte[] sharedBytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "cover.png", sharedBytes, "cover.gif", sharedBytes));
        String body = "![[cover.png]] then ![[cover.gif]]";

        String blockedReference = AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> fail("Expected a block but resolution succeeded: " + resolvedBody),
                reference -> reference);

        assertEquals("cover.gif", blockedReference);
    }

    @Test
    void assetEmbedLikeTextInsideInlineCodeIsNeverResolved() {
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        String body = "Example: `![[diagram.png]]` is embed syntax.";

        assertEquals(body, resolvedBodyOrFail(body, vaultAssetReader));
    }

    @Test
    void ambiguousBasenameReportsTheOffendingReferenceText() {
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "a/logo.png", new byte[] {1}, "b/logo.png", new byte[] {2}));
        String body = "![[logo.png]]";

        String blockedReference = AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> fail("Expected a block but resolution succeeded: " + resolvedBody),
                reference -> reference);

        assertEquals("logo.png", blockedReference);
    }
}
```

  `assetEmbedLikeTextInsideInlineCodeIsNeverResolved` is `AssetResolver`'s own independent confirmation of
  protected-region correctness — a regression here would mean `ProtectedRegionScanner` composition broke,
  not a duplicate of `PrepareHandlerTest`'s guard for the same concern in `LinkResolver`.

- [x] 5.2 Run the new test class together with `LinkResolverTest` and `PrepareHandlerTest`, confirm all
      green.

Run: `cd publication-exporter && mvn -q -o test -Dtest=AssetResolverTest,LinkResolverTest,PrepareHandlerTest 2>&1 | tail -150`

- [x] 5.3 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/AssetResolverTest.java
git commit -m "test(exporter): add narrow AssetResolver coverage for labels, extensions, and suffix conflicts"
```

## 6. Real `FilesystemVaultAssetReader` adapter

The one new production boundary adapter this slice budgets for (design.md Decision 2, proposal.md).
Behaviorally mirrors `exporter-java`'s `AssetResolver.resolveSource` (evidence only, not a code donor) and
this project's own `FilesystemVaultReader` confinement idiom (`candidateFor`/`realPathOf`/`isInsideVault`/
`canonicalize` — read `FilesystemVaultReader.java` in full first for the exact pattern being mirrored).

**Files:**
- Replace: `publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultAssetReader.java`
  (the section 3.3 placeholder)
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultAssetReaderTest.java`

- [x] 6.1 Write failing tests first, in a new `FilesystemVaultAssetReaderTest.java`, against a real temp
      directory (`@TempDir`) — exact-path match preferred over basename fallback, unique basename fallback,
      ambiguous basename block, traversal-escape block, symlink-escape block, and not-found:

```java
package dev.eugene.publicationexporter.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilesystemVaultAssetReaderTest {

    @TempDir
    Path vaultRoot;

    private static byte[] foundOrFail(AssetLookup lookup) {
        return lookup.resolve(
                content -> content,
                () -> fail("Expected found but got ambiguous"),
                () -> fail("Expected found but got unsafe"),
                () -> fail("Expected found but got notFound"));
    }

    @Test
    void exactVaultRelativePathIsPreferredOverAnotherFileWithTheSameBasename() throws IOException {
        Files.createDirectories(vaultRoot.resolve("assets"));
        Files.writeString(vaultRoot.resolve("assets/logo.png"), "assets-copy", StandardCharsets.UTF_8);
        Files.createDirectories(vaultRoot.resolve("archive"));
        Files.writeString(vaultRoot.resolve("archive/logo.png"), "archive-copy", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("assets/logo.png");

        assertArrayEquals("assets-copy".getBytes(StandardCharsets.UTF_8), foundOrFail(lookup));
    }

    @Test
    void uniqueBasenameFallsBackWhenNoExactMatchExists() throws IOException {
        Files.createDirectories(vaultRoot.resolve("nested"));
        Files.writeString(vaultRoot.resolve("nested/only-copy.png"), "content", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("only-copy.png");

        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), foundOrFail(lookup));
    }

    @Test
    void ambiguousBasenameIsBlockedWhenNoExactMatchExists() throws IOException {
        Files.createDirectories(vaultRoot.resolve("a"));
        Files.createDirectories(vaultRoot.resolve("b"));
        Files.writeString(vaultRoot.resolve("a/dup.png"), "one", StandardCharsets.UTF_8);
        Files.writeString(vaultRoot.resolve("b/dup.png"), "two", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("dup.png");

        String outcome = lookup.resolve(
                content -> "found",
                () -> "ambiguous",
                () -> "unsafe",
                () -> "notFound");
        assertEquals("ambiguous", outcome);
    }

    @Test
    void traversalOutsideTheVaultIsBlockedAsUnsafe() {
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("../outside.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("unsafe", outcome);
    }

    @Test
    void symlinkEscapingTheVaultIsBlockedAsUnsafe() throws IOException {
        Path outside = Files.createTempDirectory("outside-vault");
        Path outsideFile = Files.writeString(outside.resolve("secret.png"), "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(vaultRoot.resolve("escape.png"), outsideFile);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("escape.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("notFound", outcome);
    }

    @Test
    void missingReferenceIsNotFound() {
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("nowhere.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("notFound", outcome);
    }
}
```

  `symlinkEscapingTheVaultIsBlockedAsUnsafe` expects `notFound`, not `unsafe`: an exact-path symlink whose
  real target resolves outside the vault fails `isInsideVault` in `resolveWithinVault`, so exact-path
  resolution falls through; basename fallback then walks the vault filtering matches through the same
  `isInsideVault` check, which also excludes the symlink (its real path is outside), leaving zero visible
  matches — the same "symlink escape is invisible to basename fallback, not a distinct branch" behavior
  `FilesystemVaultReader` already has for note paths. This is a deliberate outcome, not a gap: the reference
  syntactically looked safe (no `..`), so it is not rejected by the eager `isWithinVault()` check either —
  it simply resolves to nothing visible. If this assertion fails with `found`, the confinement filter in
  task 6.2 is missing or wrong; do not change the assertion to `found` to make the test pass.

- [x] 6.2 Run the new tests and confirm they fail for the expected reason (the section 3.3 placeholder
      throws `UnsupportedOperationException`).

Run: `cd publication-exporter && mvn -q -o test -Dtest=FilesystemVaultAssetReaderTest 2>&1 | tail -100`

- [x] 6.3 Replace the `FilesystemVaultAssetReader.java` placeholder with the real implementation, mirroring
      `FilesystemVaultReader`'s confinement idiom exactly:

```java
package dev.eugene.publicationexporter.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class FilesystemVaultAssetReader implements VaultAssetReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultAssetReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    @Override
    public AssetLookup resolve(String reference) {
        Objects.requireNonNull(reference, "reference");
        if (!VaultRelativePath.of(reference).isWithinVault()) {
            return AssetLookup.unsafe();
        }
        Optional<Path> exact = resolveWithinVault(reference);
        if (exact.isPresent()) {
            return readAsset(exact.get());
        }
        return resolveByBasename(basename(reference));
    }

    private AssetLookup resolveByBasename(String basename) {
        List<Path> matches = visibleBasenameMatches(basename);
        if (matches.isEmpty()) {
            return AssetLookup.notFound();
        }
        if (matches.size() > 1) {
            return AssetLookup.ambiguous();
        }
        return readAsset(matches.get(0));
    }

    private List<Path> visibleBasenameMatches(String basename) {
        try (var paths = Files.walk(canonicalVaultRoot)) {
            List<Path> matches = new ArrayList<>();
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(basename))
                    .filter(path -> realPathOf(path).filter(this::isInsideVault).isPresent())
                    .forEach(matches::add);
            return matches;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private AssetLookup readAsset(Path file) {
        try {
            return AssetLookup.found(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> resolveWithinVault(String reference) {
        return candidateFor(reference)
                .flatMap(FilesystemVaultAssetReader::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
    }

    private Optional<Path> candidateFor(String reference) {
        try {
            return Optional.of(canonicalVaultRoot.resolve(reference));
        } catch (InvalidPathException unrepresentable) {
            return Optional.empty();
        }
    }

    private boolean isInsideVault(Path realPath) {
        return realPath.startsWith(canonicalVaultRoot);
    }

    private static String basename(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
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

- [x] 6.4 Run `FilesystemVaultAssetReaderTest` and confirm every test passes.

Run: `cd publication-exporter && mvn -q -o test -Dtest=FilesystemVaultAssetReaderTest 2>&1 | tail -150`

- [x] 6.5 Run the full suite to confirm nothing else regressed (the placeholder is gone; every earlier
      section's tests must still be green).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -200`

- [x] 6.6 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/FilesystemVaultAssetReader.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/vault/FilesystemVaultAssetReaderTest.java
git commit -m "feat(exporter): add FilesystemVaultAssetReader with exact-path, basename-fallback, and escape safety"
```

## 7. `FilesystemCandidateWorkspace` asset materialization

Extend the already-shipped candidate adapter (not a new one) to write accepted assets into the same staged
directory as the RU/EN bodies, so a candidate and its referenced assets replace atomically (design.md
Decision 3).

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java`

- [x] 7.1 Read both current files in full first — `FilesystemCandidateWorkspace.java` is reproduced above in
      this slice's design.md Context discussion; confirm it still matches before editing. Find its existing
      `install(...)`-exercising tests (`grep -n "void.*[Ii]nstall" FilesystemCandidateWorkspaceTest.java`)
      and update every call site to the new `install(identity, CandidateSnapshot, List<CandidateAsset>)`
      shape (pass `List.of()` for every existing test that has no assets in scope).

- [x] 7.2 Write a new failing test: installing a candidate with one asset writes the asset's bytes under
      `assets/{publicName}` inside the candidate directory, and it is still present after a second install
      replaces the candidate (atomic swap doesn't leave the old asset behind or drop the new one):

```java
@Test
void installWritesAssetBytesUnderTheCandidateDirectoryAndTheyStillReadCorrectlyAfterReplacement() throws IOException {
    Path reviewRoot = tempDirectory();
    CandidateWorkspace workspace = CandidateWorkspace.create(reviewRoot);
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    CandidateAsset asset = CandidateAsset.of("abc123.png", "image-bytes".getBytes(StandardCharsets.UTF_8));

    workspace.install(identity,
            CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title", "RU desc.", "EN desc.",
                    ReferenceMap.empty(identity, "h1", "h2", "h3", "h4", "h5", "h6")),
            List.of(asset));

    Path assetPath = reviewRoot.resolve("blog/my-essay/candidate/assets/abc123.png");
    assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(assetPath));

    workspace.install(identity,
            CandidateSnapshot.of("RU body v2", "EN body v2", "RU title", "EN title", "RU desc.", "EN desc.",
                    ReferenceMap.empty(identity, "h1", "h2", "h3", "h4", "h5", "h6")),
            List.of(asset));

    assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(assetPath));
}
```

  Adjust the exact `PublicationIdentity.of(...)`/`ReferenceMap.empty(...)` construction and the expected
  `assetPath` prefix to match this test file's own existing fixture-building conventions and
  `candidateDirectory(identity)`'s real layout — read the surrounding existing tests in this file first and
  match their style; the shape above is illustrative of what to assert, not a literal drop-in if the
  existing file's helpers differ.

- [x] 7.3 Run the new test and confirm it fails to compile (no asset-writing behavior yet — `install(...)`
      ignores its third argument).

Run: `cd publication-exporter && mvn -q -o test -Dtest=FilesystemCandidateWorkspaceTest 2>&1 | tail -100`

- [x] 7.4 Update `FilesystemCandidateWorkspace.java`'s `install(...)` to the new signature and write each
      asset into the staged directory before the atomic move:

```java
    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(assets, "assets");

        Path destination = candidateDirectory(identity);
        Path staging = createStagingDirectory();
        try {
            writeSnapshot(staging, content);
            writeAssets(staging, assets);
            requireWithinReviewRoot(destination);
            stagedInstall.createParentDirectories(destination);
            requireWithinReviewRoot(destination);
            replaceCandidate(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }
```

  `writeSnapshot(...)`'s parameter list collapses from seven strings-plus-map to one `CandidateSnapshot`:

```java
    private void writeSnapshot(Path staging, CandidateSnapshot content) throws IOException {
        Files.writeString(candidateFile(staging, "ru.md"), content.ruBody(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.md"), content.enBody(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.title"), content.ruTitle(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.title"), content.enTitle(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.description"), content.ruDescription(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.description"), content.enDescription(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "references.json"),
                ReferenceMapCodec.write(content.referenceMap()), StandardCharsets.UTF_8);
    }

    private void writeAssets(Path staging, List<CandidateAsset> assets) throws IOException {
        for (CandidateAsset asset : assets) {
            Path assetFile = candidateFile(staging, "assets/" + asset.publicName());
            Files.createDirectories(assetFile.getParent());
            Files.write(assetFile, asset.content());
        }
    }
```

  `snapshotFrom(...)` (the `read(...)` path) is unchanged — it never reads assets back, matching design.md
  Decision 3's explicit "does not need to expose assets back out this slice" boundary. Add
  `import dev.eugene.publicationexporter.candidate.CandidateAsset;` — wait, this class is already in the
  `candidate` package, so no import is needed for `CandidateAsset`; add `import java.util.List;` if not
  already present (it is — `find(...)`/other methods likely already reference collection types; check
  before adding a duplicate import).

- [x] 7.5 Run `FilesystemCandidateWorkspaceTest` and confirm every test passes, including the new one.

Run: `cd publication-exporter && mvn -q -o test -Dtest=FilesystemCandidateWorkspaceTest 2>&1 | tail -150`

- [x] 7.6 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java
git commit -m "feat(exporter): materialize candidate assets atomically alongside RU/EN bodies"
```

## 8. Full-suite verification

- [x] 8.1 Run the complete `publication-exporter` test suite and confirm every test passes. Baseline going
      into this slice was 504 tests (one known order-dependent flaky test unrelated to this slice — see
      Haft note `note-20260810-2ea5406b` — expected green in isolation or most full-suite runs; if it alone
      fails, re-run once before treating anything as a regression).

```bash
cd publication-exporter && mvn -q -o test 2>&1 | tail -150
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{tests+=$3; fail+=$5; err+=$7; skip+=$9} END {print "Tests run:", tests, "Failures:", fail, "Errors:", err, "Skipped:", skip}'
```

- [x] 8.2 Run the OpenSpec strict validation for this change and confirm it passes.

```bash
cd /Users/eugene/Dev/personal-site && openspec validate "s14-content-addressed-assets" --strict
```

- [x] 8.3 Refresh the graphify code graph (project convention after any code change).

```bash
graphify update .
```

Do not archive the OpenSpec change or touch Haft artifacts from this task list — those steps are owned by
the orchestrating session, not by an implementer subagent.
