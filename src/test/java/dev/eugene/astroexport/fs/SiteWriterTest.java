package dev.eugene.astroexport.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.eugene.astroexport.assets.ResolvedAsset;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SiteWriterTest {
  private static final List<String> MANAGED_ROOTS = List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages");
  private static final Map<String, String> SEARCH_TEMPLATE_HASHES = Map.of(
      "ru", "29fac5e1900763c1fad16e1ede73789fa371dc1b01007cdb5efc44cd4fdaa591",
      "en", "2c0e2e56b306532f6facbb7cdf2e80005c1041c8b34ee67131b0f520a0cf1d8e");

  @TempDir
  Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"ru", "en"})
  void searchTemplatesAreExactCodeOwnedRecords(String locale) throws Exception {
    byte[] payload;
    try (InputStream stream = SiteWriter.class.getResourceAsStream(
        "/templates/pages/" + locale + "/search.json")) {
      payload = stream.readAllBytes();
    }

    assertEquals(SEARCH_TEMPLATE_HASHES.get(locale), sha256(payload));
    assertTrue(new String(payload, StandardCharsets.UTF_8).startsWith("{\n  \"id\": \"search\""));
    assertEquals((byte) '\n', payload[payload.length - 1]);
  }

  @Test
  void stageSiteSerializesPublicFieldsRequiredDirectoriesTemplatesAndAssets() throws Exception {
    Sample sample = sampleExport();

    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);

    assertEquals(sample.site.toRealPath(), staged.siteRoot());
    assertEquals(sample.site.toRealPath().getParent(), staged.root().getParent());
    assertNotEquals(sample.site, staged.root());
    assertTrue(staged.root().getFileName().toString().startsWith(
        "." + sample.site.getFileName() + ".astro-export-stage-"));
    assertFalse(Files.exists(sample.site.resolve("src")));
    assertEquals(6, staged.writtenEntries());
    assertEquals(sample.resolvedAsset, staged.resolvedAssets().getFirst());
    assertEquals(MANAGED_ROOTS, staged.managedTreeHashes().stream()
        .map(TreeHasher.ManagedTreeHash::relative)
        .toList());

    for (String collection : List.of("blog", "bibliography", "music", "concepts")) {
      for (String locale : List.of("ru", "en")) {
        assertTrue(Files.isDirectory(staged.root().resolve("src/content").resolve(collection).resolve(locale)));
      }
    }

    assertEquals(List.of(
        "public/assets/vault/" + sample.digest + ".png",
        "src/content/blog/en/en-note.md",
        "src/content/blog/ru/ru-note.md",
        "src/data/pages/en/home.json",
        "src/data/pages/en/search.json",
        "src/data/pages/ru/home.json",
        "src/data/pages/ru/search.json"), files(staged.root()).keySet().stream().toList());

    assertEquals("""
        ---
        date: 2026-07-15
        id: ru-note
        title: Русская запись
        ---

        Текст с ![Обложка](/assets/vault/%s.png).
        """.formatted(sample.digest), Files.readString(staged.root().resolve("src/content/blog/ru/ru-note.md")));
    assertEquals("""
        ---
        date: 2026-07-15
        id: en-note
        title: English entry
        ---

        Text with ![Cover](/assets/vault/%s.png).
        """.formatted(sample.digest), Files.readString(staged.root().resolve("src/content/blog/en/en-note.md")));
    assertEquals("""
        {
          "id": "home",
          "nested": {
            "a": 1,
            "z": 2
          },
          "title": "Главная"
        }
        """, Files.readString(staged.root().resolve("src/data/pages/ru/home.json")));
    assertArrayEquals(sample.assetPathBytes(), Files.readAllBytes(
        staged.root().resolve("public/assets/vault/" + sample.digest + ".png")));
    String emitted = String.join("\n", files(staged.root()).values().stream()
        .map(bytes -> StandardCharsets.UTF_8.decode(bytes.duplicate()).toString())
        .toList());
    for (String internal : List.of(
        "private/source-only",
        "source-only-route",
        "internal-translation-hash",
        "translation metadata",
        "EDITORIAL-BODY-MUST-NOT-LEAK")) {
      assertFalse(emitted.contains(internal));
    }
    discard(staged);
  }

  @Test
  void emptyCollectionBodyEndsAtFrontmatterWithoutBlankLines() throws Exception {
    Path site = site();
    ManifestEntry entry = entry(
        "src/content/music/en/empty.md",
        "empty",
        "Empty body",
        "",
        Map.of("id", "empty", "title", "Empty body"));

    SiteWriter.StagedSite staged = SiteWriter.stageSite(site,
        manifest(List.of(), List.of(entry), List.of()));

    assertEquals("---\nid: empty\ntitle: Empty body\n---\n",
        Files.readString(staged.root().resolve("src/content/music/en/empty.md")));
    discard(staged);
  }

  @Test
  void collectionBodyCanonicalizesTrailingWhitespaceAndPreservesHardBreaks() throws Exception {
    Path site = site();
    ManifestEntry entry = entry(
        "src/content/blog/ru/canonical.md",
        "canonical",
        "Canonical body",
        "Accidental space \nAccidental tab\t\nIntentional break  \nNext line\n",
        Map.of("id", "canonical", "title", "Canonical body"));

    SiteWriter.StagedSite staged = SiteWriter.stageSite(site,
        manifest(List.of(entry), List.of(), List.of()));

    String payload = Files.readString(staged.root().resolve("src/content/blog/ru/canonical.md"));
    assertTrue(payload.endsWith("\n\nAccidental space\nAccidental tab\nIntentional break\\\nNext line\n"));
    assertTrue(payload.lines().noneMatch(line -> line.endsWith(" ") || line.endsWith("\t")));
    discard(staged);
  }

  @Test
  void repeatedStagingIsByteIdenticalAndIgnoresAbsolutePathsAndMtime() throws Exception {
    Sample sample = sampleExport();

    SiteWriter.StagedSite first = SiteWriter.stageSite(sample.site, sample.manifest);
    SiteWriter.StagedSite second = SiteWriter.stageSite(sample.site, sample.manifest);

    assertNotEquals(first.root(), second.root());
    assertEquals(files(first.root()), files(second.root()));
    assertEquals(first.managedTreeHashes(), second.managedTreeHashes());
    assertEquals(first.resolvedAssets(), second.resolvedAssets());
    discard(first);
    discard(second);
  }

  @Test
  void enOnlyAssetEmbedBlocksStagingAndCleansPartialTree() throws Exception {
    Sample sample = sampleExport();
    ManifestEntry en = sample.manifest.englishEntries().getFirst();
    ManifestResult manifest = manifest(sample.manifest.entries(), List.of(
        new ManifestEntry(en.sourcePath(), en.targetPath(), en.route(), en.metadata(),
            en.body() + "EN only ![[media/en-only.png]].")),
        sample.manifest.resolvedAssets());

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.stageSite(sample.site, manifest));

    assertTrue(error.getMessage().contains("RU asset allowlist"));
    assertEquals(List.of(), temporarySiblings(sample.site));
    assertFalse(Files.exists(sample.site.resolve("src")));
  }

  @ParameterizedTest
  @MethodSource("invalidTargets")
  void manifestTargetsAreConfinedToManagedRecordRoots(String targetPath) throws Exception {
    Path site = site();
    ManifestEntry entry = entry(targetPath, "bad-target", "Bad target", "Body", Map.of("id", "bad-target"));

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.stageSite(site, manifest(List.of(entry), List.of(), List.of())));

    assertTrue(error.getMessage().toLowerCase().contains("target"));
    assertEquals(List.of(), temporarySiblings(site));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void duplicateManifestTargetsAreRejectedBeforeAnyRecordIsWritten(boolean duplicateSearch) throws Exception {
    Path site = site();
    String target = duplicateSearch
        ? "src/data/pages/ru/search.json"
        : "src/content/blog/ru/duplicate.md";
    List<ManifestEntry> entries = duplicateSearch
        ? List.of(entry(target, "first", "First", "First", Map.of("id", "first")))
        : List.of(
            entry(target, "first", "First", "First", Map.of("id", "first")),
            entry(target, "second", "Second", "Second", Map.of("id", "second")));

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.stageSite(site, manifest(entries, List.of(), List.of())));

    assertTrue(error.getMessage().toLowerCase().contains("duplicate target"));
    assertEquals(List.of(), temporarySiblings(site));
  }

  @Test
  void assetSourceHashIsRecheckedBeforeStagingSucceeds() throws Exception {
    Sample sample = sampleExport();
    ResolvedAsset lied = new ResolvedAsset(
        "media/cover.png",
        sample.assetPath,
        "0".repeat(64) + ".png",
        "/assets/vault/" + "0".repeat(64) + ".png",
        "0".repeat(64));
    ManifestResult manifest = manifest(sample.manifest.entries(), sample.manifest.englishEntries(), List.of(lied));

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.stageSite(sample.site, manifest));

    assertTrue(error.getMessage().toLowerCase().contains("hash"));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void failedValidatorPerformsZeroLiveMovesAndCleansStaging() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedSnapshot(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    List<String> forwardMoves = new java.util.ArrayList<>();

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged, root -> {
          assertEquals(staged.root(), root);
          assertEquals(before, uncheckedManagedSnapshot(sample.site));
          throw new RuntimeException("validation failed");
        }, (source, destination) -> {
          forwardMoves.add(destination.toString());
          Files.move(source, destination);
        }));

    assertTrue(error.getMessage().contains("validation failed"));
    assertEquals(List.of(), forwardMoves);
    assertEquals(before, managedSnapshot(sample.site));
    assertEquals("keep me\n", Files.readString(sample.site.resolve("unmanaged.txt")));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void validatorCannotRemoveAnEmptyRequiredDirectory() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedSnapshot(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged,
            root -> uncheckedDeleteTree(root.resolve("src/content/music/en")),
            (source, destination) -> Files.move(source, destination)));

    assertTrue(error.getMessage().toLowerCase().contains("directory"));
    assertEquals(before, managedSnapshot(sample.site));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void randomCapabilityForgedSiblingSurvivesAndIsNeverValidated() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    Path forgedRoot = sample.site.getParent().resolve(
        "." + sample.site.getFileName() + ".astro-export-stage-forged");
    copyTree(staged.root(), forgedRoot);
    Files.writeString(forgedRoot.resolve("unrelated-sentinel.txt"), "must survive\n");
    SiteWriter.StagedSite forged = new SiteWriter.StagedSite(
        forgedRoot,
        staged.siteRoot(),
        staged.writtenEntries(),
        staged.resolvedAssets(),
        staged.managedTreeHashes(),
        "random-token-not-in-registry");
    List<Path> validated = new java.util.ArrayList<>();

    try {
      SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
          () -> SiteWriter.replaceManagedTrees(sample.site, forged, validated::add));

      assertTrue(error.getMessage().toLowerCase().contains("capability"));
      assertEquals(List.of(), validated);
      assertEquals("must survive\n", Files.readString(forgedRoot.resolve("unrelated-sentinel.txt")));
      assertTrue(Files.isDirectory(staged.root()));
    } finally {
      discard(staged);
      deleteTree(forgedRoot);
    }
  }

  @Test
  void alteredStagedEvidenceIsRejectedAndCannotChangeResult() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedState(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    SiteWriter.StagedSite altered = new SiteWriter.StagedSite(
        staged.root(),
        staged.siteRoot(),
        999_999,
        staged.resolvedAssets(),
        staged.managedTreeHashes(),
        staged.capability());

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, altered, root -> { }));

    assertTrue(error.getMessage().toLowerCase().contains("evidence"));
    assertEquals(before, managedState(sample.site));
    assertFalse(Files.exists(staged.root()));
  }

  @Test
  void stagedSiteCapabilityIsOneUseEvenAfterSuccess() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);

    SiteWriter.WriteResult result = SiteWriter.replaceManagedTrees(sample.site, staged, root -> { });

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged, root -> { }));
    assertEquals(6, result.writtenEntries());
    assertTrue(error.getMessage().contains("already claimed"));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @ParameterizedTest
  @MethodSource("liveLayoutPaths")
  void preexistingLiveSymlinkBlocksBeforeValidationAndPreservesOutside(String relative) throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Files.writeString(outside.resolve("sentinel.txt"), "outside\n");
    Path target = sample.site.resolve(relative);
    Path original = target.resolveSibling(target.getFileName() + "-original");
    Files.move(target, original);
    Files.createSymbolicLink(target, outside);
    List<Path> validated = new java.util.ArrayList<>();

    try {
      SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
          () -> SiteWriter.replaceManagedTrees(sample.site, staged, validated::add));

      assertTrue(error.getMessage().toLowerCase().contains("symlink"));
      assertEquals(List.of(), validated);
      assertEquals(List.of("sentinel.txt"), Files.list(outside).map(path -> path.getFileName().toString()).toList());
      assertFalse(Files.exists(staged.root()));
    } finally {
      Files.deleteIfExists(target);
      if (Files.exists(original) && !Files.exists(target)) {
        Files.move(original, target);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("liveLayoutPaths")
  void preexistingLiveNonDirectoryBlocksBeforeValidation(String relative) throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    Path target = sample.site.resolve(relative);
    Path original = target.resolveSibling(target.getFileName() + "-original");
    Files.move(target, original);
    Files.writeString(target, "not a directory\n");
    List<Path> validated = new java.util.ArrayList<>();

    try {
      SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
          () -> SiteWriter.replaceManagedTrees(sample.site, staged, validated::add));

      assertTrue(error.getMessage().toLowerCase().contains("not a directory"));
      assertEquals(List.of(), validated);
      assertFalse(Files.exists(staged.root()));
    } finally {
      Files.deleteIfExists(target);
      if (Files.exists(original) && !Files.exists(target)) {
        Files.move(original, target);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void eachOneShotBackupFailureRestoresExactLiveState(int failAt) throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedState(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    List<String> backupAttempts = new java.util.ArrayList<>();

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged, root -> { },
            SiteWriter.PathMover.filesMove(),
            (source, destination, relative) -> {
              backupAttempts.add(relative);
              if (backupAttempts.size() == failAt) {
                throw new IOException("injected backup move " + failAt);
              }
              Files.createDirectories(destination.getParent());
              Files.move(source, destination);
              return true;
            }));

    assertTrue(error.getMessage().contains("injected backup move " + failAt));
    assertEquals(failAt, backupAttempts.size());
    assertEquals(before, managedState(sample.site));
    assertEquals("keep me\n", Files.readString(sample.site.resolve("unmanaged.txt")));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void failedRollbackRetainsBackupAndReportsRecoveryPath() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> expectedOld = managedState(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged, root -> { },
            (source, destination) -> {
              throw new IOException("injected forward failure before install");
            },
            SiteWriter.BackupMover.filesMove(),
            relative -> {
              throw new IOException("injected rollback failure");
            }));

    assertFalse(error.committed());
    assertTrue(error.getMessage().contains("rollback failure"));
    assertEquals(1, error.recoveryPaths().size());
    Path recovery = Path.of(error.recoveryPaths().getFirst());
    assertTrue(recovery.getFileName().toString().startsWith(
        "." + sample.site.getFileName() + ".astro-export-backup-"));
    assertEquals(expectedOld, managedState(recovery));
    assertFalse(Files.exists(staged.root()));
    deleteTree(recovery);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3})
  void forwardMoveFailureRollsBackAllOldTreesAndCleansTemporaryState(int failAt) throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedSnapshot(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    List<String> forwardMoves = new java.util.ArrayList<>();

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged, root -> { }, (source, destination) -> {
          forwardMoves.add(sample.site.relativize(destination).toString().replace('\\', '/'));
          if (forwardMoves.size() == failAt) {
            throw new IOException("injected forward move " + failAt);
          }
          Files.move(source, destination);
        }));

    assertTrue(error.getMessage().contains("injected forward move " + failAt));
    assertEquals(failAt, forwardMoves.size());
    assertEquals(before, managedSnapshot(sample.site));
    assertEquals("keep me\n", Files.readString(sample.site.resolve("unmanaged.txt")));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void writeSiteAtomicIsIdempotentAndReplacesOnlyManagedTrees() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    List<Path> validatedRoots = new java.util.ArrayList<>();
    Path expectedStageParent = sample.site.toRealPath().getParent();

    SiteWriter.WriteResult first = SiteWriter.writeSiteAtomic(sample.site, sample.manifest, root -> {
      validatedRoots.add(root);
      assertEquals(expectedStageParent, root.getParent());
      assertTrue(Files.isRegularFile(root.resolve("src/data/pages/ru/search.json")));
    });
    Map<String, Map<String, ByteBuffer>> firstSnapshot = managedSnapshot(sample.site);
    SiteWriter.WriteResult second = SiteWriter.writeSiteAtomic(sample.site, sample.manifest, validatedRoots::add);

    assertEquals(first, second);
    assertEquals(6, first.writtenEntries());
    assertEquals(1, first.resolvedAssets().size());
    assertEquals(MANAGED_ROOTS, first.managedTreeHashes().stream().map(TreeHasher.ManagedTreeHash::relative).toList());
    assertEquals(firstSnapshot, managedSnapshot(sample.site));
    assertEquals(2, validatedRoots.size());
    assertEquals("keep me\n", Files.readString(sample.site.resolve("unmanaged.txt")));
    assertTrue(MANAGED_ROOTS.stream()
        .flatMap(relative -> {
          try {
            return Files.walk(sample.site.resolve(relative));
          } catch (IOException exception) {
            throw new RuntimeException(exception);
          }
        })
        .noneMatch(path -> path.getFileName().toString().startsWith("old-")));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  @Test
  void astroContentGateRunsNpmWithStagedContentEnvironmentAndBlocksOnFailure() throws Exception {
    Sample sample = sampleExport();
    seedLiveTrees(sample.site);
    Map<String, Map<String, ByteBuffer>> before = managedSnapshot(sample.site);
    SiteWriter.StagedSite staged = SiteWriter.stageSite(sample.site, sample.manifest);
    List<SiteWriter.GateInvocation> invocations = new java.util.ArrayList<>();

    SiteWriter.WriterException error = assertThrows(SiteWriter.WriterException.class,
        () -> SiteWriter.replaceManagedTrees(sample.site, staged,
            SiteWriter.astroContentGate(sample.site, invocation -> {
              invocations.add(invocation);
              return new SiteWriter.GateResult(7, "", "content invalid");
            }),
            (source, destination) -> {
              throw new AssertionError("live move must not run after gate failure");
            }));

    assertTrue(error.getMessage().contains("Astro content gate failed with exit code 7"));
    assertEquals(List.of(new SiteWriter.GateInvocation(
        sample.site.toRealPath(),
        List.of("npm", "run", "check-content"),
        Map.of(
            "ASTRO_CONTENT_DIR", staged.root().resolve("src/content").toString(),
            "ASTRO_PAGES_DIR", staged.root().resolve("src/data/pages").toString()))),
        invocations);
    assertEquals(before, managedSnapshot(sample.site));
    assertEquals(List.of(), temporarySiblings(sample.site));
  }

  private Sample sampleExport() throws Exception {
    Path site = site();
    Path vault = Files.createDirectory(temp.resolve("vault"));
    Path assetPath = vault.resolve("media/cover.png");
    Files.createDirectories(assetPath.getParent());
    byte[] assetBytes = "deterministic png bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(assetPath, assetBytes);
    String digest = sha256(assetBytes);
    ResolvedAsset resolvedAsset = new ResolvedAsset(
        "media/cover.png",
        assetPath.toRealPath(),
        digest + ".png",
        "/assets/vault/" + digest + ".png",
        digest);
    ManifestEntry ruMarkdown = entry(
        "src/content/blog/ru/ru-note.md",
        "ru-note",
        "Русская запись",
        "Текст с ![[media/cover.png|Обложка]].\n\n",
        orderedMap(
            "title", "Русская запись",
            "id", "ru-note",
            "date", LocalDate.of(2026, 7, 15)));
    ManifestEntry enMarkdown = entry(
        "src/content/blog/en/en-note.md",
        "en-note",
        "English entry",
        "Text with ![[media/cover.png|Cover]].\n",
        orderedMap(
            "title", "English entry",
            "id", "en-note",
            "date", LocalDate.of(2026, 7, 15)));
    ManifestEntry ruPage = entry(
        "src/data/pages/ru/home.json",
        "home",
        "Главная",
        "EDITORIAL-BODY-MUST-NOT-LEAK",
        orderedMap("title", "Главная", "nested", orderedMap("z", 2, "a", 1), "id", "home"));
    ManifestEntry enPage = entry(
        "src/data/pages/en/home.json",
        "home-en",
        "Home",
        "EN-EDITORIAL-BODY-MUST-NOT-LEAK",
        orderedMap("title", "Home", "nested", orderedMap("z", 4, "a", 3), "id", "home"));
    return new Sample(
        site,
        manifest(List.of(ruPage, ruMarkdown), List.of(enMarkdown, enPage), List.of(resolvedAsset)),
        resolvedAsset,
        assetPath,
        digest);
  }

  private static Stream<Arguments> invalidTargets() {
    return Stream.of(
        Arguments.of("/absolute.md"),
        Arguments.of("../src/content/blog/ru/escape.md"),
        Arguments.of("src\\content\\blog\\ru\\windows.md"),
        Arguments.of("src/content/blog/en/wrong-locale.md"),
        Arguments.of("src/content/unknown/ru/wrong-collection.md"),
        Arguments.of("src/content/blog/ru/nested/wrong-depth.md"),
        Arguments.of("src/content/blog/ru/wrong-extension.json"),
        Arguments.of("src/data/pages/ru/wrong-extension.md"),
        Arguments.of("public/assets/vault/injected.md"));
  }

  private static Stream<Arguments> liveLayoutPaths() {
    return Stream.of(
        Arguments.of("src"),
        Arguments.of("src/data"),
        Arguments.of("public"),
        Arguments.of("public/assets"),
        Arguments.of("public/assets/vault"),
        Arguments.of("src/content"),
        Arguments.of("src/data/pages"));
  }

  private Path site() throws IOException {
    return Files.createDirectory(temp.resolve("astro-site-" + System.nanoTime()));
  }

  private static ManifestEntry entry(
      String targetPath,
      String publicId,
      String title,
      String body,
      Map<String, Object> metadata) {
    return new ManifestEntry(
        "private/source-only/" + publicId + ".md",
        targetPath,
        "/source-only-route/" + publicId + "/",
        metadata,
        body,
        "internal-translation-hash",
        Map.of("internal", "translation metadata"));
  }

  private static ManifestResult manifest(
      List<ManifestEntry> entries,
      List<ManifestEntry> englishEntries,
      List<ResolvedAsset> resolvedAssets) {
    return new ManifestResult(entries, englishEntries, List.of(), List.of(),
        resolvedAssets.stream().map(ResolvedAsset::reference).toList(), resolvedAssets);
  }

  private static Map<String, Object> orderedMap(Object... entries) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      map.put((String) entries[index], entries[index + 1]);
    }
    return map;
  }

  private static Map<String, ByteBuffer> files(Path root) throws IOException {
    Map<String, ByteBuffer> files = new java.util.TreeMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        files.put(root.relativize(path).toString().replace('\\', '/'), ByteBuffer.wrap(Files.readAllBytes(path)));
      }
    }
    return files;
  }

  private static Map<String, Map<String, ByteBuffer>> managedSnapshot(Path site) throws IOException {
    Map<String, Map<String, ByteBuffer>> snapshot = new java.util.TreeMap<>();
    for (String relative : MANAGED_ROOTS) {
      Path root = site.resolve(relative);
      if (Files.exists(root)) {
        snapshot.put(relative, files(root));
      }
    }
    return snapshot;
  }

  private static Map<String, Map<String, ByteBuffer>> managedState(Path site) throws IOException {
    Map<String, Map<String, ByteBuffer>> snapshot = new java.util.TreeMap<>();
    for (String relative : MANAGED_ROOTS) {
      Path root = site.resolve(relative);
      snapshot.put(relative, Files.exists(root) ? files(root) : null);
    }
    return snapshot;
  }

  private static Map<String, Map<String, ByteBuffer>> uncheckedManagedSnapshot(Path site) {
    try {
      return managedSnapshot(site);
    } catch (IOException error) {
      throw new RuntimeException(error);
    }
  }

  private static void uncheckedDeleteTree(Path root) {
    try {
      deleteTree(root);
    } catch (IOException error) {
      throw new RuntimeException(error);
    }
  }

  private static void seedLiveTrees(Path site) throws IOException {
    int index = 1;
    for (String relative : MANAGED_ROOTS) {
      Path target = site.resolve(relative);
      Files.createDirectories(target);
      Files.writeString(target.resolve("old-" + index + ".txt"), "old tree " + index + "\n");
      index++;
    }
    Files.createDirectories(site.resolve("src/data/pages/ru"));
    Files.writeString(site.resolve("src/data/pages/ru/search.json"),
        "{\"old\": \"must be replaced, not preserved\"}\n");
    Files.writeString(site.resolve("unmanaged.txt"), "keep me\n");
  }

  private static void discard(SiteWriter.StagedSite staged) throws IOException {
    deleteTree(staged.root());
  }

  private static List<Path> temporarySiblings(Path site) throws IOException {
    try (Stream<Path> paths = Files.list(site.getParent())) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith("." + site.getFileName() + ".astro-export-"))
          .sorted()
          .toList();
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path path : paths.sorted().toList()) {
        Path destination = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else if (Files.isRegularFile(path)) {
          Files.createDirectories(destination.getParent());
          Files.copy(path, destination);
        }
      }
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record Sample(
      Path site,
      ManifestResult manifest,
      ResolvedAsset resolvedAsset,
      Path assetPath,
      String digest) {
    byte[] assetPathBytes() throws IOException {
      return Files.readAllBytes(assetPath);
    }
  }
}
