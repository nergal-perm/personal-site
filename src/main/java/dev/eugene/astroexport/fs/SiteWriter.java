package dev.eugene.astroexport.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.assets.AssetResolver;
import dev.eugene.astroexport.assets.AssetValidationException;
import dev.eugene.astroexport.assets.ResolvedAsset;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ConcurrentHashMap<String, StageBinding> STAGES = new ConcurrentHashMap<>();
  private static final AssetResolver ASSET_RESOLVER = new AssetResolver();

  private SiteWriter() { }

  public static StagedSite stageSite(Path siteRoot, ManifestResult manifest) {
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
      owned = ownedPath(stagedRoot, "staging");
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
      copyAssets(stagedRoot, assets);
      List<TreeHasher.ManagedTreeHash> hashes = TreeHasher.hashManagedTrees(stagedRoot);
      String capability = UUID.randomUUID().toString() + UUID.randomUUID();
      StageBinding binding = new StageBinding(
          capability,
          owned,
          site,
          pathIdentity(site),
          pathIdentity(site.getParent()),
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
    StageBinding binding = claimStage(staged);
    try {
      Path site = canonicalSiteRoot(siteRoot);
      if (!site.equals(binding.siteRoot())) {
        throw new WriterException("staged site belongs to " + binding.siteRoot() + ", not requested site " + site);
      }
      verifyStaged(binding);
      preflightLiveLayout(site, binding);
      if (validator == null) {
        throw new WriterException("validate callback must be callable");
      }
      validator.accept(binding.temp().path());
      verifyStaged(binding);
      preflightLiveLayout(site, binding);
    } catch (Exception error) {
      WriterException primary = writerError(error, "staged validation failed: " + error.getMessage());
      throw cleanupWithPrimary(primary, List.of(binding.temp()), false, List.of());
    }

    Path site = binding.siteRoot();
    List<Path> createdAncestors = new ArrayList<>();
    OwnedPath backupOwned = null;
    List<String> backedUp = new ArrayList<>();
    try {
      createdAncestors = createLiveAncestors(site);
      preflightLiveLayout(site, binding);
      Path backupRoot = Files.createTempDirectory(
          site.getParent(),
          "." + site.getFileName() + ".astro-export-backup-");
      backupOwned = ownedPath(backupRoot, "backup");
      sameStore(backupRoot, site, "backup and live layout device mismatch");
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        Path source = site.resolve(relative);
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
          Path destination = backupRoot.resolve(relative);
          Files.createDirectories(destination.getParent());
          if (backupMover.move(source, destination, relative)) {
            backedUp.add(relative);
          }
        }
      }
    } catch (Exception error) {
      List<String> reconciled = backupOwned == null
          ? backedUp
          : backedUpRoots(backupOwned.path());
      List<String> rollbackErrors = backupOwned == null
          ? List.of()
          : rollback(site, backupOwned.path(), reconciled, false, rollbackHook);
      List<String> ancestorErrors = rollbackErrors.isEmpty()
          ? cleanupAncestors(createdAncestors)
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
      throw cleanupWithPrimary(primary, cleanup, false, retained);
    }

    try {
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        preflightLiveLayout(site, binding);
        mover.move(binding.temp().path().resolve(relative), site.resolve(relative));
        preflightLiveLayout(site, binding);
      }
      preflightLiveLayout(site, binding);
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
      List<String> rollbackErrors = rollback(site, backupOwned.path(), backedUpRoots(backupOwned.path()), true, rollbackHook);
      List<String> ancestorErrors = rollbackErrors.isEmpty()
          ? cleanupAncestors(createdAncestors)
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
      throw cleanupWithPrimary(primary, cleanup, false, retained);
    }

    List<String> cleanupErrors = new ArrayList<>();
    List<String> recoveryPaths = new ArrayList<>();
    for (OwnedPath owned : List.of(backupOwned, binding.temp())) {
      try {
        deleteOwnedTemp(owned);
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

  private static void verifyStaged(StageBinding binding) {
    if (!identityMatches(binding.temp().path(), binding.temp().identity())) {
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

  private static void preflightLiveLayout(Path site, StageBinding binding) {
    if (!identityMatches(site, binding.siteIdentity())) {
      throw new WriterException("site ownership changed after staging: " + site);
    }
    if (!identityMatches(site.getParent(), binding.siteParentIdentity())) {
      throw new WriterException("site parent ownership changed after staging: " + site.getParent());
    }
    sameStore(binding.temp().path(), site, "staging and live layout device mismatch");
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
      sameStore(path, site, "live layout device mismatch for " + path);
    }
  }

  private static List<String> concat(List<String> first, List<String> second) {
    ArrayList<String> combined = new ArrayList<>(first);
    combined.addAll(second);
    return combined;
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

  private static List<String> cleanupAncestors(List<Path> created) {
    List<String> errors = new ArrayList<>();
    for (Path path : created.reversed()) {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try {
        Files.delete(path);
      } catch (IOException error) {
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
      RollbackHook rollbackHook) {
    List<String> errors = new ArrayList<>();
    if (removeAllLive) {
      for (String relative : TreeHasher.MANAGED_ROOTS) {
        try {
          removeManagedTree(site, relative);
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
          removeManagedTree(site, relative);
        }
        Files.move(source, target);
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

  private static void removeManagedTree(Path site, String relative) throws IOException {
    Path cursor = site;
    for (String part : relative.split("/")) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor)) {
        Files.deleteIfExists(cursor);
        return;
      }
      if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.equals(site.resolve(relative))) {
        Files.deleteIfExists(cursor);
        return;
      }
    }
    deleteTree(site.resolve(relative));
  }

  private static Path canonicalSiteRoot(Path siteRoot) {
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
      sameStore(root, parent, "site and parent device mismatch for " + root);
      return root;
    } catch (IOException error) {
      throw new WriterException("cannot resolve site root " + siteRoot + ": " + error.getMessage(), error);
    }
  }

  private static OwnedPath ownedPath(Path path, String kind) {
    PathIdentity identity = pathIdentity(path);
    PathIdentity parentIdentity = pathIdentity(path.getParent());
    if (!identity.directory() || identity.symlink()) {
      throw new WriterException("owned " + kind + " path is not a real directory: " + path);
    }
    return new OwnedPath(path, kind, identity, parentIdentity);
  }

  private static PathIdentity pathIdentity(Path path) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(
          path,
          BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      FileStore store = Files.getFileStore(path);
      return new PathIdentity(
          Objects.toString(attributes.fileKey(), path.toAbsolutePath().normalize().toString()),
          attributes.isDirectory(),
          attributes.isSymbolicLink(),
          store.name(),
          store.type());
    } catch (IOException error) {
      throw new WriterException("cannot inspect path " + path + ": " + error.getMessage(), error);
    }
  }

  private static boolean identityMatches(Path path, PathIdentity expected) {
    try {
      return pathIdentity(path).equals(expected);
    } catch (WriterException error) {
      return false;
    }
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

  private static void copyAssets(Path stagedRoot, List<ResolvedAsset> assets) {
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
            Files.copy(asset.sourcePath(), destination, StandardCopyOption.REPLACE_EXISTING);
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
    String metadata = yaml(sorted(entry.metadata())).stripTrailing();
    String canonicalBody = canonicalBody(body);
    String result = canonicalBody.isBlank()
        ? "---\n" + metadata + "\n---\n"
        : "---\n" + metadata + "\n---\n\n" + canonicalBody + "\n";
    return result.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] serializeEditorial(ManifestEntry entry) {
    return (json(sorted(entry.metadata()), 0) + "\n").getBytes(StandardCharsets.UTF_8);
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

  private static Map<String, Object> sorted(Map<String, Object> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.keySet().stream().sorted().forEach(key -> result.put(key, sortedValue(source.get(key))));
    return result;
  }

  private static Object sortedValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> source = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        source.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return sorted(source);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(SiteWriter::sortedValue).toList();
    }
    if (value instanceof TemporalAccessor) {
      return value.toString();
    }
    return value;
  }

  private static String yaml(Map<String, Object> metadata) {
    StringBuilder builder = new StringBuilder();
    yamlMap(metadata, 0, builder);
    return builder.toString();
  }

  private static void yamlMap(Map<String, Object> map, int indent, StringBuilder builder) {
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      indent(builder, indent).append(entry.getKey()).append(":");
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> nested) {
        builder.append('\n');
        yamlMap(castMap(nested), indent + 2, builder);
      } else if (value instanceof List<?> list) {
        builder.append('\n');
        yamlList(list, indent + 2, builder);
      } else {
        builder.append(' ').append(yamlScalar(value)).append('\n');
      }
    }
  }

  private static void yamlList(List<?> list, int indent, StringBuilder builder) {
    for (Object value : list) {
      indent(builder, indent).append("-");
      if (value instanceof Map<?, ?> nested) {
        builder.append('\n');
        yamlMap(castMap(nested), indent + 2, builder);
      } else {
        builder.append(' ').append(yamlScalar(value)).append('\n');
      }
    }
  }

  private static String yamlScalar(Object value) {
    if (value == null) {
      return "null";
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
    if (text.matches("[\\p{L}\\p{N} _./@+-]+")) {
      return text;
    }
    return json(text, 0);
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
      Map<String, Object> sorted = sorted(castMap(map));
      if (sorted.isEmpty()) {
        return "{}";
      }
      StringBuilder builder = new StringBuilder("{\n");
      int index = 0;
      for (Map.Entry<String, Object> entry : sorted.entrySet()) {
        indent(builder, indent + 2)
            .append(json(entry.getKey(), indent + 2))
            .append(": ")
            .append(json(entry.getValue(), indent + 2));
        if (++index < sorted.size()) {
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

  private static Map<String, Object> castMap(Map<?, ?> map) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
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
    ArrayList<String> cleanupErrors = new ArrayList<>();
    LinkedHashSet<String> recoveryPaths = new LinkedHashSet<>(primary.recoveryPaths());
    for (Path path : retainedPaths) {
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        recoveryPaths.add(path.toString());
      }
    }
    for (OwnedPath owned : ownedPaths) {
      try {
        deleteOwnedTemp(owned);
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
    if (!identityMatches(owned.path().getParent(), owned.parentIdentity())) {
      throw new WriterException("owned " + owned.kind() + " parent ownership no longer matches: " + owned.path().getParent());
    }
    if (!identityMatches(owned.path(), owned.identity())) {
      throw new WriterException("owned " + owned.kind() + " path ownership no longer matches: " + owned.path());
    }
    deleteTree(owned.path());
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(root);
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void deleteUnownedBestEffort(Path root) {
    try {
      deleteTree(root);
    } catch (IOException ignored) {
      // Best effort for a partially-created stage before ownership evidence exists.
    }
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

  record OwnedPath(Path path, String kind, PathIdentity identity, PathIdentity parentIdentity) { }

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
  interface PathMover {
    void move(Path source, Path destination) throws IOException;

    static PathMover filesMove() {
      return (source, destination) -> Files.move(source, destination);
    }
  }

  @FunctionalInterface
  interface BackupMover {
    boolean move(Path source, Path destination, String relative) throws IOException;

    static BackupMover filesMove() {
      return (source, destination, relative) -> {
        Files.move(source, destination);
        return true;
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
