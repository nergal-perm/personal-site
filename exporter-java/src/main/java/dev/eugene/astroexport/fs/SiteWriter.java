package dev.eugene.astroexport.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.assets.AssetResolver;
import dev.eugene.astroexport.assets.AssetValidationException;
import dev.eugene.astroexport.assets.ResolvedAsset;
import dev.eugene.astroexport.frontmatter.FrontmatterCanonicalizer;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Writes a serialized Astro export through a staged managed-tree transaction. */
public final class SiteWriter {
  private static final List<String> COLLECTIONS = List.of("bibliography", "blog", "concepts", "music");
  private static final List<String> LOCALES = List.of("en", "ru");
  private static final List<String> REQUIRED_DIRECTORIES = List.of(
      "public/assets/vault",
      "src/content/bibliography/en",
      "src/content/bibliography/ru",
      "src/content/blog/en",
      "src/content/blog/ru",
      "src/content/concepts/en",
      "src/content/concepts/ru",
      "src/content/music/en",
      "src/content/music/ru",
      "src/data/pages/en",
      "src/data/pages/ru");
  private static final List<String> LIVE_ANCESTORS = List.of(
      "src",
      "src/data",
      "public",
      "public/assets");
  private static final Map<String, String> SEARCH_TARGETS = Map.of(
      "ru", "src/data/pages/ru/search.json",
      "en", "src/data/pages/en/search.json");
  private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*|^\\\\\\\\.*");
  private static final Pattern YAML_DATE_LIKE =
      Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[Tt ].*)?$");
  private static final Pattern YAML_INTEGER_LIKE =
      Pattern.compile("^[+-]?(?:[0-9][0-9_]*|0x[0-9A-Fa-f_]+|[0-9][0-9_]*(?::[0-5]?[0-9])+)$");
  private static final Pattern YAML_FLOAT_LIKE =
      Pattern.compile("^[+-]?(?:(?:[0-9][0-9_]*)?\\.[0-9_]+|[0-9][0-9_]*\\.)(?:[eE][-+]?[0-9]+)?$|^[+-]?\\.(?:inf|Inf|INF|nan|NaN|NAN)$");
  private static final Set<String> YAML_AMBIGUOUS_VALUES = Set.of(
      "true", "false", "yes", "no", "on", "off", "null", "~");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ConcurrentHashMap<String, StageBinding> STAGES = new ConcurrentHashMap<>();
  private static final AssetResolver ASSET_RESOLVER = new AssetResolver();

  private SiteWriter() { }

  public static StagedSite stageSite(Path siteRoot, ManifestResult manifest) {
    return stageSite(siteRoot, manifest, AssetCopier.filesCopy());
  }

  static StagedSite stageSite(Path siteRoot, ManifestResult manifest, AssetCopier assetCopier) {
    return stageSite(siteRoot, manifest, assetCopier, IdentityReader.filesIdentity());
  }

  static StagedSite stageSite(
      Path siteRoot,
      ManifestResult manifest,
      AssetCopier assetCopier,
      IdentityReader identityReader) {
    Path site = canonicalSiteRoot(siteRoot);
    List<RecordToWrite> records = records(manifest);
    Map<String, byte[]> templates = loadSearchTemplates();
    List<ResolvedAsset> assets = resolvedAssets(manifest.resolvedAssets());
    Path stagedRoot = null;
    OwnedPath owned = null;
    try {
      stagedRoot = Files.createTempDirectory(
          site.getParent(),
          "." + site.getFileName() + ".astro-export-stage-");
      owned = ownedPath(stagedRoot, "staging", identityReader);
      sameStore(owned.path(), site, "staging and site device mismatch");
      createRequiredDirectories(stagedRoot);
      for (RecordToWrite record : records) {
        ManifestEntry entry = record.entry();
        String body = entry.body() == null ? "" : entry.body();
        try {
          body = ASSET_RESOLVER.rewriteAssetEmbeds(body, assets);
        } catch (AssetValidationException error) {
          throw new WriterException(error.getMessage(), error);
        }
        byte[] payload = record.kind() == RecordKind.COLLECTION
            ? serializeMarkdown(entry, body)
            : serializeEditorial(entry);
        writeBytes(stagedRoot.resolve(record.target()), payload);
      }
      for (Map.Entry<String, byte[]> template : templates.entrySet()) {
        writeBytes(stagedRoot.resolve(SEARCH_TARGETS.get(template.getKey())), template.getValue());
      }
      copyAssets(stagedRoot, assets, assetCopier);
      List<TreeHasher.ManagedTreeHash> hashes = TreeHasher.hashManagedTrees(stagedRoot);
      String capability = UUID.randomUUID().toString() + UUID.randomUUID();
      StageBinding binding = new StageBinding(
          capability,
          owned,
          site,
          pathIdentity(site, identityReader),
          pathIdentity(site.getParent(), identityReader),
          records.size() + templates.size(),
          assets,
          hashes);
      STAGES.put(capability, binding);
      return new StagedSite(
          owned.path(),
          site,
          binding.writtenEntries(),
          binding.resolvedAssets(),
          binding.managedTreeHashes(),
          capability);
    } catch (Exception error) {
      WriterException primary = writerError(error, "staging failed: " + error.getMessage());
      if (owned != null) {
        throw cleanupWithPrimary(primary, List.of(owned), false, List.of());
      }
      if (stagedRoot != null) {
        deleteUnownedBestEffort(stagedRoot);
      }
      throw primary;
    }
  }

  public static WriteResult writeSiteAtomic(
      Path siteRoot,
      ManifestResult manifest,
      Consumer<Path> validator) {
    StagedSite staged = stageSite(siteRoot, manifest);
    return replaceManagedTrees(siteRoot, staged, validator);
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator) {
    return replaceManagedTrees(siteRoot, staged, validator, PathMover.filesMove());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover) {
    return replaceManagedTrees(siteRoot, staged, validator, mover, BackupMover.filesMove(), RollbackHook.noop());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover) {
    return replaceManagedTrees(siteRoot, staged, validator, mover, backupMover, RollbackHook.noop());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover,
      RollbackHook rollbackHook) {
    return replaceManagedTrees(
        siteRoot,
        staged,
        validator,
        mover,
        backupMover,
        rollbackHook,
        CleanupHook.deleteOwnedTemp());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover,
      RollbackHook rollbackHook,
      CleanupHook cleanupHook) {
    return replaceManagedTrees(
        siteRoot,
        staged,
        validator,
        mover,
        backupMover,
        rollbackHook,
        cleanupHook,
        StoreChecker.filesStore());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover,
      RollbackHook rollbackHook,
      CleanupHook cleanupHook,
      StoreChecker storeChecker) {
    return replaceManagedTrees(
        siteRoot,
        staged,
        validator,
        mover,
        backupMover,
        rollbackHook,
        cleanupHook,
        storeChecker,
        ForwardBoundaryHook.noop());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover,
      RollbackHook rollbackHook,
      CleanupHook cleanupHook,
      StoreChecker storeChecker,
      ForwardBoundaryHook forwardBoundaryHook) {
    return replaceManagedTrees(
        siteRoot,
        staged,
        validator,
        mover,
        backupMover,
        rollbackHook,
        cleanupHook,
        storeChecker,
        forwardBoundaryHook,
        IdentityReader.filesIdentity());
  }

  static WriteResult replaceManagedTrees(
      Path siteRoot,
      StagedSite staged,
      Consumer<Path> validator,
      PathMover mover,
      BackupMover backupMover,
      RollbackHook rollbackHook,
      CleanupHook cleanupHook,
      StoreChecker storeChecker,
      ForwardBoundaryHook forwardBoundaryHook,
      IdentityReader identityReader) {
    StageBinding binding = claimStage(staged);
    Path site;
    LiveLayoutBinding liveLayout;
    try {
      site = canonicalSiteRoot(siteRoot, storeChecker);
      if (!site.equals(binding.siteRoot())) {
        throw new WriterException("staged site belongs to " + binding.siteRoot() + ", not requested site " + site);
      }
      verifyStaged(binding, identityReader);
      liveLayout = bindLiveLayout(site, binding, storeChecker, identityReader);
      if (validator == null) {
        throw new WriterException("validate callback must be callable");
      }
      validator.accept(binding.temp().path());
      verifyStaged(binding, identityReader);
      verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
    } catch (Exception error) {
      WriterException primary = writerError(error, "staged validation failed: " + error.getMessage());
      throw cleanupWithPrimary(primary, List.of(binding.temp()), false, List.of(), cleanupHook);
    }

    List<Path> createdAncestors = new ArrayList<>();
    OwnedPath backupOwned = null;
    List<String> backedUp = new ArrayList<>();
    try {
      verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
      createdAncestors = createLiveAncestors(site);
      bindCreatedAncestors(site, createdAncestors, liveLayout, storeChecker, identityReader);
      verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
      Path backupRoot = Files.createTempDirectory(
          site.getParent(),
          "." + site.getFileName() + ".astro-export-backup-");
      backupOwned = ownedPath(backupRoot, "backup", identityReader);
      storeChecker.sameStore(backupRoot, site, "backup and live layout device mismatch");
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        Path source = site.resolve(relative);
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
          Path destination = backupRoot.resolve(relative);
          Files.createDirectories(destination.getParent());
          PathIdentity expectedSource = liveLayout.requiredIdentity(relative);
          if (backupMover.move(
              source,
              destination,
              relative,
              liveLayout.parentIdentity(relative),
              pathIdentity(destination.getParent(), identityReader))) {
            if (!identityMatches(destination, expectedSource, identityReader)) {
              throw new WriterException("backed-up managed tree ownership does not match live source: " + relative);
            }
            backedUp.add(relative);
            liveLayout.markAbsent(relative);
          }
        }
      }
    } catch (Exception error) {
      List<String> reconciled = backupOwned == null
          ? backedUp
          : backedUpRoots(backupOwned.path());
      List<String> rollbackErrors = backupOwned == null
          ? List.of()
          : rollback(site, backupOwned.path(), reconciled, false, rollbackHook, Map.of(), liveLayout, identityReader);
      List<String> ancestorErrors = rollbackErrors.isEmpty()
          ? cleanupAncestors(site, createdAncestors, liveLayout, identityReader)
          : List.of();
      List<Path> retained = new ArrayList<>(createdAncestors.stream()
          .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
          .toList());
      WriterException primary = new WriterException("cannot back up managed trees: " + error.getMessage());
      if (!rollbackErrors.isEmpty()) {
        primary = new WriterException(primary.detail() + "; rollback errors: " + String.join("; ", rollbackErrors));
        if (backupOwned != null) {
          retained.add(backupOwned.path());
        }
      }
      if (!ancestorErrors.isEmpty()) {
        primary = new WriterException(primary.detail() + "; ancestor cleanup errors: " + String.join("; ", ancestorErrors));
      }
      List<OwnedPath> cleanup = backupOwned == null || !rollbackErrors.isEmpty()
          ? List.of(binding.temp())
          : List.of(backupOwned, binding.temp());
      throw cleanupWithPrimary(primary, cleanup, false, retained, cleanupHook);
    }

    LinkedHashMap<String, PathIdentity> installedRoots = new LinkedHashMap<>();
    try {
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
        moveManagedTree(
            site,
            binding,
            liveLayout,
            relative,
            mover,
            storeChecker,
            forwardBoundaryHook,
            installedRoots,
            identityReader);
        verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
      }
      verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        if (!Files.isDirectory(site.resolve(relative), LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(site.resolve(relative))) {
          throw new WriterException("installed managed trees do not match staged evidence");
        }
      }
      if (!TreeHasher.hashManagedTrees(site).equals(binding.managedTreeHashes())) {
        throw new WriterException("installed managed trees do not match staged evidence");
      }
    } catch (Exception error) {
      List<String> rollbackErrors = rollback(
          site,
          backupOwned.path(),
          backedUpRoots(backupOwned.path()),
          true,
          rollbackHook,
          installedRoots,
          liveLayout,
          identityReader);
      List<String> ancestorErrors = rollbackErrors.isEmpty()
          ? cleanupAncestors(site, createdAncestors, liveLayout, identityReader)
          : List.of();
      List<Path> retained = new ArrayList<>(createdAncestors.stream()
          .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
          .toList());
      WriterException primary = new WriterException("cannot replace managed trees: " + error.getMessage());
      if (!rollbackErrors.isEmpty()) {
        primary = new WriterException(primary.detail() + "; rollback errors: " + String.join("; ", rollbackErrors));
        retained.add(backupOwned.path());
      }
      if (!ancestorErrors.isEmpty()) {
        primary = new WriterException(primary.detail() + "; ancestor cleanup errors: " + String.join("; ", ancestorErrors));
      }
      List<OwnedPath> cleanup = rollbackErrors.isEmpty()
          ? List.of(backupOwned, binding.temp())
          : List.of(binding.temp());
      throw cleanupWithPrimary(primary, cleanup, false, retained, cleanupHook);
    }

    List<String> cleanupErrors = new ArrayList<>();
    List<String> recoveryPaths = new ArrayList<>();
    for (OwnedPath owned : List.of(backupOwned, binding.temp())) {
      try {
        cleanupHook.cleanup(owned);
      } catch (Exception error) {
        cleanupErrors.add(error.getMessage());
        if (Files.exists(owned.path(), LinkOption.NOFOLLOW_LINKS)) {
          recoveryPaths.add(owned.path().toString());
        }
      }
    }
    if (!cleanupErrors.isEmpty()) {
      throw new WriterException(
          "committed managed trees but cleanup failed: " + String.join("; ", cleanupErrors),
          true,
          recoveryPaths);
    }
    return new WriteResult(
        binding.writtenEntries(),
        binding.resolvedAssets(),
        binding.managedTreeHashes());
  }

  public static Consumer<Path> astroContentGate(Path siteRoot) {
    return astroContentGate(siteRoot, SiteWriter::runGate);
  }

  public static Consumer<Path> astroContentGate(Path siteRoot, GateRunner runner) {
    Path workingDirectory = canonicalSiteRoot(siteRoot);
    return stagedRoot -> {
      GateInvocation invocation = new GateInvocation(
          workingDirectory,
          List.of("npm", "run", "check-content"),
          Map.of(
              "ASTRO_CONTENT_DIR", stagedRoot.resolve("src/content").toString(),
              "ASTRO_PAGES_DIR", stagedRoot.resolve("src/data/pages").toString()));
      GateResult result;
      try {
        result = runner.run(invocation);
      } catch (IOException error) {
        throw new WriterException("Astro content gate failed to start: " + error.getMessage(), error);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new WriterException("Astro content gate interrupted", error);
      }
      if (result.exitCode() != 0) {
        throw new WriterException("Astro content gate failed with exit code " + result.exitCode());
      }
    };
  }

  private static GateResult runGate(GateInvocation invocation) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(invocation.command());
    builder.directory(invocation.workingDirectory().toFile());
    builder.environment().putAll(invocation.environment());
    Process process = builder.start();
    CompletableFuture<String> stdout = read(process.getInputStream());
    CompletableFuture<String> stderr = read(process.getErrorStream());
    int exitCode = process.waitFor();
    return new GateResult(exitCode, stdout.join(), stderr.join());
  }

  private static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try (stream) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    });
  }

  private static StageBinding claimStage(StagedSite staged) {
    StageBinding binding = STAGES.remove(staged.capability());
    if (binding == null) {
      throw new WriterException("invalid or already claimed staged-site capability");
    }
    if (!binding.temp().path().equals(staged.root())
        || !binding.siteRoot().equals(staged.siteRoot())
        || binding.writtenEntries() != staged.writtenEntries()
        || !binding.resolvedAssets().equals(staged.resolvedAssets())
        || !binding.managedTreeHashes().equals(staged.managedTreeHashes())) {
      throw cleanupWithPrimary(
          new WriterException("staged-site capability evidence was altered"),
          List.of(binding.temp()),
          false,
          List.of());
    }
    return binding;
  }

  private static void verifyStaged(StageBinding binding, IdentityReader identityReader) {
    if (!identityMatches(binding.temp().path(), binding.temp().identity(), identityReader)) {
      throw new WriterException(
          "staging ownership no longer matches registered inode: " + binding.temp().path(),
          false,
          List.of(binding.temp().path().toString()));
    }
    for (String relative : REQUIRED_DIRECTORIES) {
      Path required = binding.temp().path().resolve(relative);
      if (!Files.isDirectory(required, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(required)) {
        throw new WriterException("staging is missing required directory '" + relative + "'");
      }
    }
    if (!TreeHasher.hashManagedTrees(binding.temp().path()).equals(binding.managedTreeHashes())) {
      throw new WriterException("staged managed trees changed after serialization");
    }
  }

  private static void preflightLiveLayout(
      Path site,
      StageBinding binding,
      StoreChecker storeChecker,
      IdentityReader identityReader) {
    if (!identityMatches(site, binding.siteIdentity(), identityReader)) {
      throw new WriterException("site ownership changed after staging: " + site);
    }
    if (!identityMatches(site.getParent(), binding.siteParentIdentity(), identityReader)) {
      throw new WriterException("site parent ownership changed after staging: " + site.getParent());
    }
    storeChecker.sameStore(site, site.getParent(), "site and parent device mismatch for " + site);
    storeChecker.sameStore(binding.temp().path(), site, "staging and live layout device mismatch");
    for (String relative : concat(LIVE_ANCESTORS, TreeHasher.MANAGED_ROOTS)) {
      Path path = site.resolve(relative);
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (Files.isSymbolicLink(path)) {
        throw new WriterException("live layout symlink is not allowed: " + path);
      }
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new WriterException("live layout ancestor is not a directory: " + path);
      }
      storeChecker.sameStore(path, site, "live layout device mismatch for " + path);
    }
  }

  private static LiveLayoutBinding bindLiveLayout(
      Path site,
      StageBinding binding,
      StoreChecker storeChecker,
      IdentityReader identityReader) {
    preflightLiveLayout(site, binding, storeChecker, identityReader);
    LinkedHashMap<String, PathIdentity> identities = new LinkedHashMap<>();
    LinkedHashSet<String> absent = new LinkedHashSet<>();
    for (String relative : concat(LIVE_ANCESTORS, TreeHasher.MANAGED_ROOTS)) {
      Path path = site.resolve(relative);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        identities.put(relative, pathIdentity(path, identityReader));
      } else {
        absent.add(relative);
      }
    }
    return new LiveLayoutBinding(binding.siteIdentity(), identities, absent);
  }

  private static void verifyLiveLayout(
      Path site,
      StageBinding binding,
      LiveLayoutBinding liveLayout,
      StoreChecker storeChecker,
      IdentityReader identityReader) {
    if (!identityMatches(site, binding.siteIdentity(), identityReader)) {
      throw new WriterException("site ownership changed after staging: " + site);
    }
    if (!identityMatches(site.getParent(), binding.siteParentIdentity(), identityReader)) {
      throw new WriterException("site parent ownership changed after staging: " + site.getParent());
    }
    storeChecker.sameStore(site, site.getParent(), "site and parent device mismatch for " + site);
    storeChecker.sameStore(binding.temp().path(), site, "staging and live layout device mismatch");
    for (String relative : concat(LIVE_ANCESTORS, TreeHasher.MANAGED_ROOTS)) {
      Path path = site.resolve(relative);
      PathIdentity expected = liveLayout.identity(relative);
      if (expected == null) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
          throw new WriterException("live layout ownership changed after validation: " + path);
        }
        continue;
      }
      if (!identityMatches(path, expected, identityReader)) {
        throw new WriterException("live layout ownership changed after validation: " + path);
      }
      if (Files.isSymbolicLink(path)) {
        throw new WriterException("live layout symlink is not allowed: " + path);
      }
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new WriterException("live layout ancestor is not a directory: " + path);
      }
      storeChecker.sameStore(path, site, "live layout device mismatch for " + path);
    }
  }

  private static void bindCreatedAncestors(
      Path site,
      List<Path> createdAncestors,
      LiveLayoutBinding liveLayout,
      StoreChecker storeChecker,
      IdentityReader identityReader) {
    for (Path path : createdAncestors) {
      String relative = site.relativize(path).toString().replace('\\', '/');
      if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new WriterException("created live layout ancestor is not a directory: " + path);
      }
      storeChecker.sameStore(path, site, "live layout device mismatch for " + path);
      liveLayout.bind(relative, pathIdentity(path, identityReader));
    }
  }

  private static List<String> concat(List<String> first, List<String> second) {
    ArrayList<String> combined = new ArrayList<>(first);
    combined.addAll(second);
    return combined;
  }

  private static String parentRelative(String relative) {
    int separator = relative.lastIndexOf('/');
    return separator < 0 ? "" : relative.substring(0, separator);
  }

  private static List<Path> createLiveAncestors(Path site) throws IOException {
    List<Path> created = new ArrayList<>();
    for (String relative : LIVE_ANCESTORS) {
      Path path = site.resolve(relative);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          throw new WriterException("live layout ancestor is not a directory: " + path);
        }
        continue;
      }
      Files.createDirectory(path);
      created.add(path);
    }
    return created;
  }

  private static void moveManagedTree(
      Path site,
      StageBinding binding,
      LiveLayoutBinding liveLayout,
      String relative,
      PathMover mover,
      StoreChecker storeChecker,
      ForwardBoundaryHook forwardBoundaryHook,
      Map<String, PathIdentity> installedRoots,
      IdentityReader identityReader) throws IOException {
    Path source = binding.temp().path().resolve(relative);
    Path destination = site.resolve(relative);
    PathIdentity sourceIdentity = pathIdentity(source, identityReader);
    PathIdentity sourceParentIdentity = pathIdentity(source.getParent(), identityReader);
    if (!sourceIdentity.directory() || sourceIdentity.symlink()) {
      throw new WriterException("staged managed tree is not a real directory: " + relative);
    }
    forwardBoundaryHook.beforeMove(relative, source, destination);
    verifyLiveLayout(site, binding, liveLayout, storeChecker, identityReader);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      throw new WriterException("managed target unexpectedly exists before install: " + destination);
    }
    try {
      mover.move(source, destination, sourceParentIdentity, liveLayout.parentIdentity(relative));
    } catch (IOException | RuntimeException error) {
      if (identityMatches(destination, sourceIdentity, identityReader)) {
        installedRoots.put(relative, sourceIdentity);
        liveLayout.bind(relative, sourceIdentity);
      }
      throw error;
    }
    if (!identityMatches(destination, sourceIdentity, identityReader)) {
      throw new WriterException("installed managed tree ownership does not match staged source: " + relative);
    }
    installedRoots.put(relative, sourceIdentity);
    liveLayout.bind(relative, sourceIdentity);
  }

  private static List<String> cleanupAncestors(
      Path site,
      List<Path> created,
      LiveLayoutBinding liveLayout,
      IdentityReader identityReader) {
    List<String> errors = new ArrayList<>();
    for (Path path : created.reversed()) {
      String relative = site.relativize(path).toString().replace('\\', '/');
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        liveLayout.markAbsent(relative);
        continue;
      }
      try {
        PathIdentity expected = liveLayout.identity(relative);
        if (expected == null) {
          throw new WriterException("created ancestor ownership is not bound before cleanup: " + path);
        }
        deleteDirectoryConfined(path, expected, liveLayout.parentIdentity(relative), identityReader);
        liveLayout.markAbsent(relative);
      } catch (IOException | WriterException error) {
        errors.add("cannot remove created ancestor " + path + ": " + error.getMessage());
      }
    }
    return errors;
  }

  private static List<String> rollback(
      Path site,
      Path backupRoot,
      List<String> backedUp,
      boolean removeAllLive,
      RollbackHook rollbackHook,
      Map<String, PathIdentity> removableTargets,
      LiveLayoutBinding liveLayout,
      IdentityReader identityReader) {
    List<String> errors = new ArrayList<>();
    if (removeAllLive) {
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        try {
          removeManagedTree(site, relative, removableTargets.get(relative), liveLayout, identityReader);
        } catch (Exception error) {
          errors.add("cannot remove partial target " + relative + ": " + error.getMessage());
        }
      }
    }
    for (String relative : backedUp.reversed()) {
      try {
        rollbackHook.beforeRestore(relative);
        Path source = backupRoot.resolve(relative);
        Path target = site.resolve(relative);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          PathIdentity expected = removableTargets.get(relative);
          if (expected == null) {
            throw new WriterException("managed target ownership changed before restore: " + relative);
          }
          removeManagedTree(site, relative, expected, liveLayout, identityReader);
        }
        PathIdentity sourceIdentity = pathIdentity(source, identityReader);
        movePathConfined(
            source,
            target,
            pathIdentity(source.getParent(), identityReader),
            liveLayout.parentIdentity(relative));
        if (!identityMatches(target, sourceIdentity, identityReader)) {
          throw new WriterException("restored managed tree ownership does not match backup: " + relative);
        }
        liveLayout.bind(relative, sourceIdentity);
      } catch (Exception error) {
        errors.add("cannot restore backup " + relative + ": " + error.getMessage());
      }
    }
    return errors;
  }

  private static List<String> backedUpRoots(Path backupRoot) {
    return TreeHasher.MANAGED_ROOTS.stream()
        .filter(relative -> Files.exists(backupRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS))
        .toList();
  }

  private static void removeManagedTree(
      Path site,
      String relative,
      PathIdentity expectedRootIdentity,
      LiveLayoutBinding liveLayout,
      IdentityReader identityReader) throws IOException {
    Path cursor = site;
    for (String part : relative.split("/")) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor)) {
        String cursorRelative = site.relativize(cursor).toString().replace('\\', '/');
        deletePathConfined(cursor, liveLayout.parentIdentity(cursorRelative), identityReader);
        return;
      }
      if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.equals(site.resolve(relative))) {
        String cursorRelative = site.relativize(cursor).toString().replace('\\', '/');
        deletePathConfined(cursor, liveLayout.parentIdentity(cursorRelative), identityReader);
        return;
      }
    }
    Path root = site.resolve(relative);
    if (expectedRootIdentity == null) {
      throw new WriterException("managed target ownership is unknown before removal: " + relative);
    }
    if (!identityMatches(root, expectedRootIdentity, identityReader)) {
      throw new WriterException("managed target ownership changed before removal: " + relative);
    }
    deleteTree(root, expectedRootIdentity, liveLayout.parentIdentity(relative), identityReader);
    liveLayout.markAbsent(relative);
  }

  private static Path canonicalSiteRoot(Path siteRoot) {
    return canonicalSiteRoot(siteRoot, StoreChecker.filesStore());
  }

  private static Path canonicalSiteRoot(Path siteRoot, StoreChecker storeChecker) {
    try {
      Path root = siteRoot.toRealPath();
      if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw new WriterException("site root is not a real directory: " + root);
      }
      Path parent = root.getParent();
      if (parent == null
          || Files.isSymbolicLink(parent)
          || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        throw new WriterException("site parent is not a real directory: " + parent);
      }
      storeChecker.sameStore(root, parent, "site and parent device mismatch for " + root);
      return root;
    } catch (IOException error) {
      throw new WriterException("cannot resolve site root " + siteRoot + ": " + error.getMessage(), error);
    }
  }

  private static OwnedPath ownedPath(Path path, String kind, IdentityReader identityReader) {
    PathIdentity identity = pathIdentity(path, identityReader);
    PathIdentity parentIdentity = pathIdentity(path.getParent(), identityReader);
    if (!identity.directory() || identity.symlink()) {
      throw new WriterException("owned " + kind + " path is not a real directory: " + path);
    }
    return new OwnedPath(path, kind, identity, parentIdentity);
  }

  private static PathIdentity pathIdentity(Path path) {
    return pathIdentity(path, IdentityReader.filesIdentity());
  }

  private static PathIdentity pathIdentity(Path path, IdentityReader identityReader) {
    try {
      PathEvidence evidence = identityReader.read(path);
      if (evidence == null || evidence.fileKey() == null) {
        throw new WriterException("stable path identity is unavailable for " + path);
      }
      return new PathIdentity(
          evidence.fileKey().toString(),
          evidence.directory(),
          evidence.symlink(),
          evidence.fileStoreName(),
          evidence.fileStoreType());
    } catch (IOException error) {
      throw new WriterException("cannot inspect path " + path + ": " + error.getMessage(), error);
    }
  }

  private static boolean identityMatches(Path path, PathIdentity expected) {
    return identityMatches(path, expected, IdentityReader.filesIdentity());
  }

  private static boolean identityMatches(Path path, PathIdentity expected, IdentityReader identityReader) {
    try {
      return pathIdentity(path, identityReader).equals(expected);
    } catch (WriterException error) {
      return false;
    }
  }

  private static PathEvidence pathEvidence(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path,
        BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    FileStore store = Files.getFileStore(path);
    return new PathEvidence(
        attributes.fileKey(),
        attributes.isDirectory(),
        attributes.isSymbolicLink(),
        store.name(),
        store.type());
  }

  private static void sameStore(Path first, Path second, String message) {
    try {
      FileStore firstStore = Files.getFileStore(first);
      FileStore secondStore = Files.getFileStore(second);
      if (!firstStore.equals(secondStore)) {
        throw new WriterException(message);
      }
    } catch (IOException error) {
      throw new WriterException("cannot inspect filesystem device: " + error.getMessage(), error);
    }
  }

  private static List<RecordToWrite> records(ManifestResult manifest) {
    ArrayList<RecordToWrite> records = new ArrayList<>();
    LinkedHashMap<String, String> seen = new LinkedHashMap<>();
    for (String target : SEARCH_TARGETS.values()) {
      seen.put(target.toLowerCase(Locale.ROOT), target);
    }
    addRecords(records, seen, "ru", manifest.entries());
    addRecords(records, seen, "en", manifest.englishEntries());
    records.sort(Comparator.comparing(RecordToWrite::target));
    return List.copyOf(records);
  }

  private static void addRecords(
      List<RecordToWrite> records,
      Map<String, String> seen,
      String locale,
      List<ManifestEntry> entries) {
    for (ManifestEntry entry : entries) {
      Target target = validatedTarget(entry, locale);
      String canonical = target.path();
      String key = canonical.toLowerCase(Locale.ROOT);
      if (seen.containsKey(key)) {
        throw new WriterException("duplicate target '" + canonical + "'; conflicts with '" + seen.get(key) + "'");
      }
      seen.put(key, canonical);
      records.add(new RecordToWrite(canonical, entry, target.kind()));
    }
  }

  private static Target validatedTarget(ManifestEntry entry, String expectedLocale) {
    String raw = entry.targetPath();
    if (raw == null || raw.isEmpty()) {
      throw new WriterException("manifest target must be a non-empty relative path");
    }
    if (raw.startsWith("/")
        || WINDOWS_ABSOLUTE.matcher(raw).matches()
        || raw.contains("\\")
        || raw.contains("//")
        || raw.contains("/../")
        || raw.startsWith("../")
        || raw.endsWith("/..")) {
      throw new WriterException("manifest target is not a canonical relative path: '" + raw + "'");
    }
    String[] parts = raw.split("/");
    if (parts.length == 0 || parts[parts.length - 1].startsWith(".")) {
      throw new WriterException("manifest target filename must not be hidden: '" + raw + "'");
    }
    for (String part : parts) {
      if (part.isEmpty() || part.equals("..")) {
        throw new WriterException("manifest target is not a canonical relative path: '" + raw + "'");
      }
    }
    if (parts.length == 5 && parts[0].equals("src") && parts[1].equals("content")) {
      String collection = parts[2];
      String locale = parts[3];
      String filename = parts[4];
      if (!COLLECTIONS.contains(collection)) {
        throw new WriterException("manifest target uses unsupported collection: '" + raw + "'");
      }
      if (!locale.equals(expectedLocale)) {
        throw new WriterException("manifest target locale must be '" + expectedLocale + "': '" + raw + "'");
      }
      if (!filename.endsWith(".md")) {
        throw new WriterException("collection manifest target must end in .md: '" + raw + "'");
      }
      return new Target(raw, RecordKind.COLLECTION);
    }
    if (parts.length == 5
        && parts[0].equals("src")
        && parts[1].equals("data")
        && parts[2].equals("pages")) {
      String locale = parts[3];
      String filename = parts[4];
      if (!locale.equals(expectedLocale)) {
        throw new WriterException("manifest target locale must be '" + expectedLocale + "': '" + raw + "'");
      }
      if (!filename.endsWith(".json")) {
        throw new WriterException("editorial manifest target must end in .json: '" + raw + "'");
      }
      return new Target(raw, RecordKind.EDITORIAL);
    }
    throw new WriterException("manifest target is outside the managed collection/editorial roots: '" + raw + "'");
  }

  private static void createRequiredDirectories(Path root) throws IOException {
    for (String collection : COLLECTIONS) {
      for (String locale : LOCALES) {
        Files.createDirectories(root.resolve("src/content").resolve(collection).resolve(locale));
      }
    }
    for (String locale : LOCALES) {
      Files.createDirectories(root.resolve("src/data/pages").resolve(locale));
    }
    Files.createDirectories(root.resolve("public/assets/vault"));
  }

  private static Map<String, byte[]> loadSearchTemplates() {
    LinkedHashMap<String, byte[]> templates = new LinkedHashMap<>();
    for (String locale : LOCALES.reversed()) {
      String resource = "/templates/pages/" + locale + "/search.json";
      try (InputStream stream = SiteWriter.class.getResourceAsStream(resource)) {
        if (stream == null) {
          throw new WriterException("cannot read search template " + resource);
        }
        byte[] payload = stream.readAllBytes();
        JsonNode node = JSON.readTree(payload);
        if (!node.isObject() || !node.path("id").asText("").equals("search")) {
          throw new WriterException("search template must be the search record: " + resource);
        }
        if (payload.length == 0 || payload[payload.length - 1] != '\n') {
          throw new WriterException("search template must end with a newline: " + resource);
        }
        templates.put(locale, payload);
      } catch (IOException error) {
        throw new WriterException("cannot read search template " + resource + ": " + error.getMessage(), error);
      }
    }
    return templates;
  }

  private static List<ResolvedAsset> resolvedAssets(List<ResolvedAsset> assets) {
    ArrayList<ResolvedAsset> ordered = new ArrayList<>(assets);
    ordered.sort(Comparator.comparing(ResolvedAsset::reference));
    LinkedHashSet<String> references = new LinkedHashSet<>();
    for (ResolvedAsset asset : ordered) {
      if (!references.add(asset.reference())) {
        throw new WriterException("duplicate resolved asset reference: '" + asset.reference() + "'");
      }
      if (asset.outputName().contains("/")
          || asset.outputName().contains("\\")
          || asset.outputName().startsWith(".")
          || !asset.publicUrl().equals("/assets/vault/" + asset.outputName())) {
        throw new WriterException("resolved asset has invalid destination: '" + asset.reference() + "'");
      }
      String digest;
      try {
        digest = sha256(Files.readAllBytes(asset.sourcePath()));
      } catch (IOException error) {
        throw new WriterException("cannot read resolved asset '" + asset.reference() + "': " + error.getMessage(), error);
      }
      if (!digest.equals(asset.sha256())) {
        throw new WriterException("resolved asset source hash changed for '" + asset.reference()
            + "': expected " + asset.sha256() + ", got " + digest);
      }
    }
    return List.copyOf(ordered);
  }

  private static void copyAssets(Path stagedRoot, List<ResolvedAsset> assets, AssetCopier assetCopier) {
    LinkedHashMap<String, ResolvedAsset> byOutput = new LinkedHashMap<>();
    for (ResolvedAsset asset : assets) {
      ResolvedAsset existing = byOutput.putIfAbsent(asset.outputName(), asset);
      if (existing != null && !existing.sha256().equals(asset.sha256())) {
        throw new WriterException("resolved assets disagree on destination '" + asset.outputName() + "'");
      }
    }
    byOutput.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> {
          ResolvedAsset asset = entry.getValue();
          Path destination = stagedRoot.resolve("public/assets/vault").resolve(asset.outputName());
          try {
            assetCopier.copy(asset.sourcePath(), destination);
            String digest = sha256(Files.readAllBytes(destination));
            if (!digest.equals(asset.sha256())) {
              throw new WriterException("copied asset hash mismatch for '" + asset.reference()
                  + "': expected " + asset.sha256() + ", got " + digest);
            }
          } catch (IOException error) {
            throw new WriterException("cannot copy resolved asset '" + asset.reference() + "': " + error.getMessage(), error);
          }
        });
  }

  private static byte[] serializeMarkdown(ManifestEntry entry, String body) {
    String metadata = yaml(FrontmatterCanonicalizer.canonicalize(entry.metadata())).stripTrailing();
    String canonicalBody = canonicalBody(body);
    String result = canonicalBody.isBlank()
        ? "---\n" + metadata + "\n---\n"
        : "---\n" + metadata + "\n---\n\n" + canonicalBody + "\n";
    return result.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] serializeEditorial(ManifestEntry entry) {
    return (json(FrontmatterCanonicalizer.canonicalize(entry.metadata()), 0) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String canonicalBody(String body) {
    String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
    ArrayList<String> lines = new ArrayList<>();
    for (String line : normalized.split("\n", -1)) {
      int end = line.length();
      while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
        end--;
      }
      String content = line.substring(0, end);
      String trailing = line.substring(end);
      if (!content.isEmpty() && trailing.startsWith("  ") && !content.endsWith("\\")) {
        content += "\\";
      }
      lines.add(content);
    }
    while (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return String.join("\n", lines);
  }

  private static String yaml(Map<String, Object> metadata) {
    StringBuilder builder = new StringBuilder();
    yamlMap(metadata, 0, builder);
    return builder.toString();
  }

  private static void yamlMap(Map<String, Object> map, int indent, StringBuilder builder) {
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      indent(builder, indent);
      yamlEntry(builder, entry.getKey(), entry.getValue(), indent);
    }
  }

  private static void yamlList(List<?> list, int indent, StringBuilder builder) {
    for (Object value : list) {
      if (value instanceof Map<?, ?> nested) {
        Map<String, Object> map = FrontmatterCanonicalizer.canonicalize(nested);
        if (map.isEmpty()) {
          indent(builder, indent).append("- {}\n");
          continue;
        }
        int index = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
          if (index == 0) {
            indent(builder, indent).append("- ");
          } else {
            indent(builder, indent + 2);
          }
          yamlEntry(builder, entry.getKey(), entry.getValue(), indent + 2);
          index++;
        }
      } else if (value instanceof List<?> nestedList) {
        if (nestedList.isEmpty()) {
          indent(builder, indent).append("- []\n");
          continue;
        }
        indent(builder, indent).append("-\n");
        yamlList(nestedList, indent + 2, builder);
      } else {
        indent(builder, indent).append("- ").append(yamlScalar(value, indent)).append('\n');
      }
    }
  }

  private static void yamlEntry(StringBuilder builder, String key, Object value, int keyIndent) {
    builder.append(key).append(":");
    if (value instanceof Map<?, ?> nested) {
      if (nested.isEmpty()) {
        builder.append(" {}\n");
        return;
      }
      builder.append('\n');
      yamlMap(FrontmatterCanonicalizer.canonicalize(nested), keyIndent + 2, builder);
    } else if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        builder.append(" []\n");
        return;
      }
      builder.append('\n');
      yamlList(list, keyIndent, builder);
    } else {
      builder.append(' ').append(yamlScalar(value, keyIndent)).append('\n');
    }
  }

  private static String yamlScalar(Object value, int indent) {
    if (value == null) {
      return "null";
    }
    if (value instanceof TemporalAccessor temporal) {
      return temporal.toString();
    }
    if (value instanceof Boolean bool) {
      return bool ? "true" : "false";
    }
    if (value instanceof Number number) {
      return number.toString();
    }
    String text = value.toString();
    if (text.isEmpty()) {
      return "''";
    }
    if (text.contains("\n")) {
      return yamlMultilineSingleQuoted(text, indent + 2);
    }
    return shouldQuoteYamlString(text) ? yamlSingleQuoted(text) : text;
  }

  private static boolean shouldQuoteYamlString(String text) {
    if (!text.equals(text.strip())) {
      return true;
    }
    if (text.contains(": ") || text.contains(" #")) {
      return true;
    }
    if (text.startsWith("- ") || text.startsWith("? ") || text.startsWith(": ")) {
      return true;
    }
    char first = text.charAt(0);
    if ("[]{}#,&*!|>'\"%@`".indexOf(first) >= 0) {
      return true;
    }
    String normalized = text.toLowerCase(Locale.ROOT);
    return YAML_AMBIGUOUS_VALUES.contains(normalized)
        || YAML_DATE_LIKE.matcher(text).matches()
        || YAML_INTEGER_LIKE.matcher(text).matches()
        || YAML_FLOAT_LIKE.matcher(text).matches();
  }

  private static String yamlSingleQuoted(String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  private static String yamlMultilineSingleQuoted(String text, int continuationIndent) {
    String[] parts = text.split("\n", -1);
    StringBuilder builder = new StringBuilder("'");
    builder.append(parts[0].replace("'", "''"));
    String continuation = " ".repeat(continuationIndent);
    for (int index = 1; index < parts.length; index++) {
      builder.append("\n\n").append(continuation).append(parts[index].replace("'", "''"));
    }
    return builder.append("'").toString();
  }

  private static String json(Object value, int indent) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String string) {
      try {
        return JSON.writeValueAsString(string);
      } catch (IOException error) {
        throw new WriterException("cannot serialize JSON string: " + error.getMessage(), error);
      }
    }
    if (value instanceof Boolean || value instanceof Integer || value instanceof Long
        || value instanceof Short || value instanceof Byte || value instanceof java.math.BigInteger) {
      return value.toString();
    }
    if (value instanceof Float floating) {
      if (!Float.isFinite(floating)) {
        throw new WriterException("cannot serialize non-finite JSON number");
      }
      return floating.toString();
    }
    if (value instanceof Double floating) {
      if (!Double.isFinite(floating)) {
        throw new WriterException("cannot serialize non-finite JSON number");
      }
      return floating.toString();
    }
    if (value instanceof Number number) {
      return number.toString();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> canonical = FrontmatterCanonicalizer.canonicalize(map);
      if (canonical.isEmpty()) {
        return "{}";
      }
      StringBuilder builder = new StringBuilder("{\n");
      int index = 0;
      for (Map.Entry<String, Object> entry : canonical.entrySet()) {
        indent(builder, indent + 2)
            .append(json(entry.getKey(), indent + 2))
            .append(": ")
            .append(json(entry.getValue(), indent + 2));
        if (++index < canonical.size()) {
          builder.append(',');
        }
        builder.append('\n');
      }
      return indent(builder, indent).append('}').toString();
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        return "[]";
      }
      StringBuilder builder = new StringBuilder("[\n");
      for (int index = 0; index < list.size(); index++) {
        indent(builder, indent + 2).append(json(list.get(index), indent + 2));
        if (index + 1 < list.size()) {
          builder.append(',');
        }
        builder.append('\n');
      }
      return indent(builder, indent).append(']').toString();
    }
    return json(value.toString(), indent);
  }

  private static StringBuilder indent(StringBuilder builder, int count) {
    return builder.append(" ".repeat(count));
  }

  private static void writeBytes(Path target, byte[] payload) throws IOException {
    Files.createDirectories(target.getParent());
    Files.write(target, payload);
  }

  private static WriterException cleanupWithPrimary(
      WriterException primary,
      List<OwnedPath> ownedPaths,
      boolean committed,
      List<Path> retainedPaths) {
    return cleanupWithPrimary(primary, ownedPaths, committed, retainedPaths, CleanupHook.deleteOwnedTemp());
  }

  private static WriterException cleanupWithPrimary(
      WriterException primary,
      List<OwnedPath> ownedPaths,
      boolean committed,
      List<Path> retainedPaths,
      CleanupHook cleanupHook) {
    ArrayList<String> cleanupErrors = new ArrayList<>();
    LinkedHashSet<String> recoveryPaths = new LinkedHashSet<>(primary.recoveryPaths());
    for (Path path : retainedPaths) {
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        recoveryPaths.add(path.toString());
      }
    }
    for (OwnedPath owned : ownedPaths) {
      try {
        cleanupHook.cleanup(owned);
      } catch (Exception error) {
        cleanupErrors.add(error.getMessage());
        if (Files.exists(owned.path(), LinkOption.NOFOLLOW_LINKS)) {
          recoveryPaths.add(owned.path().toString());
        }
      }
    }
    String detail = primary.detail();
    if (!cleanupErrors.isEmpty()) {
      detail += "; cleanup failures: " + String.join("; ", cleanupErrors);
    }
    return new WriterException(detail, primary.committed() || committed, List.copyOf(recoveryPaths));
  }

  private static void deleteOwnedTemp(OwnedPath owned) throws IOException {
    deleteOwnedTemp(owned, CleanupBoundaryHook.noop());
  }

  private static void deleteOwnedTemp(OwnedPath owned, CleanupBoundaryHook cleanupBoundaryHook) throws IOException {
    if (!identityMatches(owned.path().getParent(), owned.parentIdentity())) {
      throw new WriterException("owned " + owned.kind() + " parent ownership no longer matches: " + owned.path().getParent());
    }
    if (!identityMatches(owned.path(), owned.identity())) {
      throw new WriterException("owned " + owned.kind() + " path ownership no longer matches: " + owned.path());
    }
    cleanupBoundaryHook.beforeDelete(owned);
    if (!identityMatches(owned.path().getParent(), owned.parentIdentity())) {
      throw new WriterException("owned " + owned.kind() + " parent ownership no longer matches: " + owned.path().getParent());
    }
    if (!identityMatches(owned.path(), owned.identity())) {
      throw new WriterException("owned " + owned.kind() + " path ownership no longer matches: " + owned.path());
    }
    deleteTree(owned.path(), owned.identity(), owned.parentIdentity());
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    deleteTree(root, pathIdentity(root), pathIdentity(root.getParent()));
  }

  private static void deleteTree(Path root, PathIdentity expectedRoot, PathIdentity expectedParent) throws IOException {
    deleteTree(root, expectedRoot, expectedParent, IdentityReader.filesIdentity());
  }

  private static void deleteTree(
      Path root,
      PathIdentity expectedRoot,
      PathIdentity expectedParent,
      IdentityReader identityReader) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (!identityMatches(root.getParent(), expectedParent, identityReader)) {
      throw new WriterException("tree parent ownership changed before deletion: " + root.getParent());
    }
    if (!identityMatches(root, expectedRoot, identityReader)) {
      throw new WriterException("tree root ownership changed before deletion: " + root);
    }
    try (SecureDirectoryStream<Path> parent = openSecureDirectory(root.getParent(), expectedParent)) {
      deleteEntryRecursive(parent, root.getFileName(), expectedRoot, root);
    }
  }

  private static void deletePathConfined(Path path) throws IOException {
    deletePathConfined(path, pathIdentity(path.getParent()));
  }

  private static void deletePathConfined(Path path, PathIdentity expectedParentIdentity) throws IOException {
    deletePathConfined(path, expectedParentIdentity, IdentityReader.filesIdentity());
  }

  private static void deletePathConfined(
      Path path,
      PathIdentity expectedParentIdentity,
      IdentityReader identityReader) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (!identityMatches(path.getParent(), expectedParentIdentity, identityReader)) {
      throw new WriterException("path parent ownership changed before deletion: " + path.getParent());
    }
    try (SecureDirectoryStream<Path> parent = openSecureDirectory(path.getParent(), expectedParentIdentity)) {
      BasicFileAttributes attributes = attributes(parent, path.getFileName());
      if (attributes.isDirectory() && !attributes.isSymbolicLink()) {
        parent.deleteDirectory(path.getFileName());
      } else {
        parent.deleteFile(path.getFileName());
      }
    }
  }

  private static void deleteDirectoryConfined(
      Path path,
      PathIdentity expectedRootIdentity,
      PathIdentity expectedParentIdentity,
      IdentityReader identityReader) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (!identityMatches(path.getParent(), expectedParentIdentity, identityReader)) {
      throw new WriterException("created ancestor parent ownership changed before cleanup: " + path.getParent());
    }
    if (!identityMatches(path, expectedRootIdentity, identityReader)) {
      throw new WriterException("created ancestor ownership changed before cleanup: " + path);
    }
    try (SecureDirectoryStream<Path> parent = openSecureDirectory(path.getParent(), expectedParentIdentity)) {
      BasicFileAttributes attributes = attributes(parent, path.getFileName());
      if (!attributesMatch(expectedRootIdentity, attributes, path)) {
        throw new WriterException("created ancestor ownership changed before cleanup: " + path);
      }
      if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
        throw new WriterException("created ancestor is not a real directory before cleanup: " + path);
      }
      parent.deleteDirectory(path.getFileName());
    }
  }

  private static void deleteEntryRecursive(
      SecureDirectoryStream<Path> parent,
      Path name,
      PathIdentity expectedIdentity,
      Path displayPath) throws IOException {
    BasicFileAttributes attributes = attributes(parent, name);
    if (expectedIdentity != null && !attributesMatch(expectedIdentity, attributes, displayPath)) {
      throw new WriterException("tree root ownership changed before deletion: " + displayPath);
    }
    if (attributes.isDirectory() && !attributes.isSymbolicLink()) {
      try (SecureDirectoryStream<Path> child = openSecureChild(parent, name, displayPath)) {
        for (Path entry : child) {
          Path entryName = entry.getFileName();
          deleteEntryRecursive(child, entryName, null, displayPath.resolve(entryName));
        }
      }
      parent.deleteDirectory(name);
    } else {
      parent.deleteFile(name);
    }
  }

  private static void deleteUnownedBestEffort(Path root) {
    try {
      deleteTree(root);
    } catch (Exception ignored) {
      // Best effort for a partially-created stage before ownership evidence exists.
    }
  }

  private static void movePathConfined(Path source, Path destination) throws IOException {
    movePathConfined(source, destination, pathIdentity(source.getParent()), pathIdentity(destination.getParent()));
  }

  private static void movePathConfined(
      Path source,
      Path destination,
      PathIdentity sourceParentIdentity,
      PathIdentity destinationParentIdentity) throws IOException {
    try (SecureDirectoryStream<Path> sourceParent = openSecureDirectory(source.getParent(), sourceParentIdentity);
        SecureDirectoryStream<Path> destinationParent = openSecureDirectory(destination.getParent(), destinationParentIdentity)) {
      sourceParent.move(source.getFileName(), destinationParent, destination.getFileName());
    }
  }

  @SuppressWarnings("unchecked")
  private static SecureDirectoryStream<Path> openSecureDirectory(Path path, PathIdentity expectedIdentity) throws IOException {
    DirectoryStream<Path> stream = Files.newDirectoryStream(path);
    if (stream instanceof SecureDirectoryStream<?> secure) {
      SecureDirectoryStream<Path> typed = (SecureDirectoryStream<Path>) secure;
      if (!attributesMatch(expectedIdentity, typed
          .getFileAttributeView(BasicFileAttributeView.class)
          .readAttributes(), path)) {
        try {
          typed.close();
        } catch (IOException ignored) {
          // Preserve the ownership failure as the primary error.
        }
        throw new WriterException("directory ownership changed while opening: " + path);
      }
      return typed;
    }
    try {
      stream.close();
    } catch (IOException ignored) {
      // Preserve the fail-closed secure stream error as the primary error.
    }
    throw new WriterException("secure directory stream is unavailable for " + path);
  }

  private static SecureDirectoryStream<Path> openSecureChild(
      SecureDirectoryStream<Path> parent,
      Path name,
      Path displayPath) throws IOException {
    SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
    BasicFileAttributes attributes = child
        .getFileAttributeView(BasicFileAttributeView.class)
        .readAttributes();
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      try {
        child.close();
      } catch (IOException ignored) {
        // Preserve the directory ownership failure as the primary error.
      }
      throw new WriterException("secure child directory changed while opening: " + displayPath);
    }
    return child;
  }

  private static BasicFileAttributes attributes(SecureDirectoryStream<Path> parent, Path name) throws IOException {
    return parent
        .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private static boolean attributesMatch(PathIdentity expected, BasicFileAttributes attributes, Path fallbackPath) {
    Object fileKey = attributes.fileKey();
    return fileKey != null
        && expected.fileKey().equals(fileKey.toString())
        && expected.directory() == attributes.isDirectory()
        && expected.symlink() == attributes.isSymbolicLink();
  }

  private static WriterException writerError(Exception error, String fallback) {
    if (error instanceof WriterException writerException) {
      return writerException;
    }
    return new WriterException(fallback, error);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  enum RecordKind { COLLECTION, EDITORIAL }

  record Target(String path, RecordKind kind) { }

  record RecordToWrite(String target, ManifestEntry entry, RecordKind kind) { }

  record PathIdentity(
      String fileKey,
      boolean directory,
      boolean symlink,
      String fileStoreName,
      String fileStoreType) { }

  record PathEvidence(
      Object fileKey,
      boolean directory,
      boolean symlink,
      String fileStoreName,
      String fileStoreType) { }

  record OwnedPath(Path path, String kind, PathIdentity identity, PathIdentity parentIdentity) { }

  record LiveLayoutBinding(
      PathIdentity siteIdentity,
      LinkedHashMap<String, PathIdentity> identities,
      LinkedHashSet<String> absent) {
    PathIdentity identity(String relative) {
      return identities.get(relative);
    }

    PathIdentity requiredIdentity(String relative) {
      PathIdentity identity = identities.get(relative);
      if (identity == null) {
        throw new WriterException("live layout ownership is not bound: " + relative);
      }
      return identity;
    }

    PathIdentity parentIdentity(String relative) {
      String parent = parentRelative(relative);
      return parent.isEmpty() ? siteIdentity : requiredIdentity(parent);
    }

    void bind(String relative, PathIdentity identity) {
      absent.remove(relative);
      identities.put(relative, identity);
    }

    void markAbsent(String relative) {
      identities.remove(relative);
      absent.add(relative);
    }
  }

  record StageBinding(
      String capability,
      OwnedPath temp,
      Path siteRoot,
      PathIdentity siteIdentity,
      PathIdentity siteParentIdentity,
      int writtenEntries,
      List<ResolvedAsset> resolvedAssets,
      List<TreeHasher.ManagedTreeHash> managedTreeHashes) { }

  public record StagedSite(
      Path root,
      Path siteRoot,
      int writtenEntries,
      List<ResolvedAsset> resolvedAssets,
      List<TreeHasher.ManagedTreeHash> managedTreeHashes,
      String capability) {
    public StagedSite {
      resolvedAssets = List.copyOf(resolvedAssets);
      managedTreeHashes = List.copyOf(managedTreeHashes);
    }
  }

  public record WriteResult(
      int writtenEntries,
      List<ResolvedAsset> resolvedAssets,
      List<TreeHasher.ManagedTreeHash> managedTreeHashes) {
    public WriteResult {
      resolvedAssets = List.copyOf(resolvedAssets);
      managedTreeHashes = List.copyOf(managedTreeHashes);
    }
  }

  public static final class WriterException extends RuntimeException {
    private final String detail;
    private final boolean committed;
    private final List<String> recoveryPaths;

    public WriterException(String detail) {
      this(detail, false, List.of(), null);
    }

    public WriterException(String detail, Throwable cause) {
      this(detail, false, List.of(), cause);
    }

    public WriterException(String detail, boolean committed, List<String> recoveryPaths) {
      this(detail, committed, recoveryPaths, null);
    }

    private WriterException(
        String detail,
        boolean committed,
        List<String> recoveryPaths,
        Throwable cause) {
      super(message(detail, committed, recoveryPaths), cause);
      this.detail = detail;
      this.committed = committed;
      this.recoveryPaths = List.copyOf(recoveryPaths);
    }

    public String detail() {
      return detail;
    }

    public boolean committed() {
      return committed;
    }

    public List<String> recoveryPaths() {
      return recoveryPaths;
    }

    private static String message(String detail, boolean committed, List<String> recoveryPaths) {
      String suffix = "; committed=" + committed;
      if (!recoveryPaths.isEmpty()) {
        suffix += "; retained recovery paths: " + String.join(", ", recoveryPaths);
      }
      return detail + suffix;
    }
  }

  @FunctionalInterface
  interface AssetCopier {
    void copy(Path source, Path destination) throws IOException;

    static AssetCopier filesCopy() {
      return (source, destination) -> Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @FunctionalInterface
  interface PathMover {
    void move(Path source, Path destination) throws IOException;

    default void move(
        Path source,
        Path destination,
        PathIdentity sourceParentIdentity,
        PathIdentity destinationParentIdentity) throws IOException {
      move(source, destination);
    }

    static PathMover filesMove() {
      return new PathMover() {
        @Override
        public void move(Path source, Path destination) throws IOException {
          movePathConfined(source, destination);
        }

        @Override
        public void move(
            Path source,
            Path destination,
            PathIdentity sourceParentIdentity,
            PathIdentity destinationParentIdentity) throws IOException {
          movePathConfined(source, destination, sourceParentIdentity, destinationParentIdentity);
        }
      };
    }
  }

  @FunctionalInterface
  interface BackupMover {
    boolean move(Path source, Path destination, String relative) throws IOException;

    default boolean move(
        Path source,
        Path destination,
        String relative,
        PathIdentity sourceParentIdentity,
        PathIdentity destinationParentIdentity) throws IOException {
      return move(source, destination, relative);
    }

    static BackupMover filesMove() {
      return new BackupMover() {
        @Override
        public boolean move(Path source, Path destination, String relative) throws IOException {
          movePathConfined(source, destination);
          return true;
        }

        @Override
        public boolean move(
            Path source,
            Path destination,
            String relative,
            PathIdentity sourceParentIdentity,
            PathIdentity destinationParentIdentity) throws IOException {
          movePathConfined(source, destination, sourceParentIdentity, destinationParentIdentity);
          return true;
        }
      };
    }
  }

  @FunctionalInterface
  interface RollbackHook {
    void beforeRestore(String relative) throws IOException;

    static RollbackHook noop() {
      return relative -> { };
    }
  }

  @FunctionalInterface
  interface ForwardBoundaryHook {
    void beforeMove(String relative, Path source, Path destination) throws IOException;

    static ForwardBoundaryHook noop() {
      return (relative, source, destination) -> { };
    }
  }

  @FunctionalInterface
  interface CleanupBoundaryHook {
    void beforeDelete(OwnedPath owned) throws IOException;

    static CleanupBoundaryHook noop() {
      return owned -> { };
    }
  }

  @FunctionalInterface
  interface CleanupHook {
    void cleanup(OwnedPath owned) throws IOException;

    static CleanupHook deleteOwnedTemp() {
      return SiteWriter::deleteOwnedTemp;
    }

    static CleanupHook deleteOwnedTemp(CleanupBoundaryHook cleanupBoundaryHook) {
      return owned -> SiteWriter.deleteOwnedTemp(owned, cleanupBoundaryHook);
    }
  }

  @FunctionalInterface
  interface StoreChecker {
    void sameStore(Path first, Path second, String message);

    static StoreChecker filesStore() {
      return SiteWriter::sameStore;
    }
  }

  @FunctionalInterface
  interface IdentityReader {
    PathEvidence read(Path path) throws IOException;

    static IdentityReader filesIdentity() {
      return SiteWriter::pathEvidence;
    }
  }

  @FunctionalInterface
  public interface GateRunner {
    GateResult run(GateInvocation invocation) throws IOException, InterruptedException;
  }

  public record GateInvocation(Path workingDirectory, List<String> command, Map<String, String> environment) {
    public GateInvocation {
      command = List.copyOf(command);
      environment = Map.copyOf(environment);
    }
  }

  public record GateResult(int exitCode, String stdout, String stderr) { }
}
