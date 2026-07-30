package dev.eugene.astroexport.review;

import dev.eugene.astroexport.migration.SemanticSchemaState;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.references.VaultReferenceCatalog.CatalogEntry;
import dev.eugene.astroexport.release.ApprovedReleaseException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Loads release input from approved semantic published snapshots only. */
public final class ApprovedSnapshotRepository {
  private static final Set<String> PUBLISHED_FILES =
      Set.of("ru.md", "en.md", "references.json");

  public List<ApprovedPageSnapshot> loadSelected(
      SelectionResult selection,
      Path reviewRoot,
      VaultReferenceCatalog catalog) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(reviewRoot, "reviewRoot");
    VaultReferenceCatalog checkedCatalog = catalog == null
        ? VaultReferenceCatalog.empty()
        : catalog;
    if (SemanticSchemaState.mode(reviewRoot) == SemanticSchemaState.Mode.MIGRATION_INCOMPLETE) {
      throw failure("migration-incomplete", null, "semantic link migration is incomplete");
    }
    List<String> selected = selectedSourcePaths(selection);
    List<SnapshotDirectory> approved = scanApproved(reviewRoot);
    List<ApprovedPageSnapshot> snapshots = new ArrayList<>();
    for (String sourcePath : selected) {
      snapshots.add(loadOne(sourcePath, approved, checkedCatalog, reviewRoot));
    }
    rejectDuplicates(snapshots);
    return List.copyOf(snapshots);
  }

  private ApprovedPageSnapshot loadOne(
      String selectedSourcePath,
      List<SnapshotDirectory> approved,
      VaultReferenceCatalog catalog,
      Path reviewRoot) {
    List<SnapshotDirectory> exact = approved.stream()
        .filter(snapshot -> selectedSourcePath.equals(snapshot.references().sourcePath()))
        .toList();
    if (exact.size() > 1) {
      throw failure(
          "ambiguous-approved-snapshot",
          selectedSourcePath,
          "multiple approved snapshots match " + selectedSourcePath);
    }
    if (exact.size() == 1) {
      return exact.getFirst().load(selectedSourcePath, null);
    }

    List<CatalogEntry> entries = catalog.entries().values().stream()
        .filter(entry -> VaultReferenceCatalog.STATE_ACTIVE.equals(entry.state()))
        .filter(entry -> selectedSourcePath.equals(entry.currentPath()))
        .toList();
    if (entries.size() > 1) {
      throw failure(
          "ambiguous-approved-snapshot",
          selectedSourcePath,
          "multiple catalog entries match " + selectedSourcePath);
    }
    if (entries.isEmpty()) {
      throw failure(
          "missing-approved-snapshot",
          selectedSourcePath,
          "no approved snapshot for " + selectedSourcePath);
    }
    CatalogEntry entry = entries.getFirst();
    List<SnapshotDirectory> reconciled = approved.stream()
        .filter(snapshot -> entry.pageRef().equals(snapshot.references().pageRef()))
        .filter(snapshot -> entry.previousPaths().contains(snapshot.references().sourcePath()))
        .toList();
    if (reconciled.size() > 1) {
      throw failure(
          "ambiguous-approved-snapshot",
          selectedSourcePath,
          "multiple approved snapshots reconcile to " + selectedSourcePath);
    }
    if (reconciled.isEmpty()) {
      throw failure(
          "missing-approved-snapshot",
          selectedSourcePath,
          "no approved snapshot reconciles to " + selectedSourcePath);
    }
    return reconciled.getFirst().load(selectedSourcePath, VaultReferenceCatalog.catalogPath(reviewRoot));
  }

  private static List<String> selectedSourcePaths(SelectionResult selection) {
    LinkedHashSet<String> selected = new LinkedHashSet<>();
    selection.included().stream()
        .map(note -> note.vaultPath())
        .filter(path -> path != null && !path.isBlank())
        .forEach(selected::add);
    selection.excluded().stream()
        .map(SelectionResult.Exclusion::vaultPath)
        .filter(path -> path != null && !path.isBlank())
        .forEach(selected::add);
    return List.copyOf(selected);
  }

  private static List<SnapshotDirectory> scanApproved(Path reviewRoot) {
    Path root = reviewRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<SnapshotDirectory> snapshots = new ArrayList<>();
    try (var collections = Files.list(root)) {
      for (Path collection : collections
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .sorted()
          .toList()) {
        try (var pages = Files.list(collection)) {
          for (Path page : pages
              .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
              .sorted()
              .toList()) {
            Path published = page.resolve("published");
            if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
              snapshots.add(SnapshotDirectory.probe(
                  collection.getFileName().toString(),
                  page.getFileName().toString(),
                  published));
            }
          }
        }
      }
    } catch (IOException error) {
      throw failure("invalid-approved-snapshot", null, "cannot scan approved snapshots", error);
    }
    return List.copyOf(snapshots);
  }

  private static void rejectDuplicates(List<ApprovedPageSnapshot> snapshots) {
    rejectDuplicate(snapshots, "duplicate-page-ref", ApprovedPageSnapshot::pageRef);
    rejectDuplicate(
        snapshots,
        "duplicate-public-id",
        snapshot -> snapshot.collection() + "/" + snapshot.publicId());
    rejectDuplicate(
        snapshots,
        "duplicate-target-path",
        snapshot -> snapshot.russian().targetPath());
    rejectDuplicate(
        snapshots,
        "duplicate-target-path",
        snapshot -> snapshot.english().targetPath());
    rejectDuplicate(snapshots, "duplicate-route", snapshot -> snapshot.russian().route());
    rejectDuplicate(snapshots, "duplicate-route", snapshot -> snapshot.english().route());
  }

  private static void rejectDuplicate(
      List<ApprovedPageSnapshot> snapshots,
      String code,
      java.util.function.Function<ApprovedPageSnapshot, String> keyExtractor) {
    Set<String> seen = new LinkedHashSet<>();
    for (ApprovedPageSnapshot snapshot : snapshots) {
      String key = keyExtractor.apply(snapshot);
      if (key == null || key.isBlank()) {
        continue;
      }
      if (!seen.add(key)) {
        throw failure(code, snapshot.sourcePath(), "duplicate approved release key: " + key);
      }
    }
  }

  private static byte[] readSafeLeaf(Path path, String selectedSourcePath) {
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        throw new IOException(path.getFileName() + " is missing or symbolic");
      }
      BasicFileAttributes attributes = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw new IOException(path.getFileName() + " must be a regular file");
      }
      try {
        Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (links instanceof Number count && count.longValue() != 1) {
          throw new IOException(path.getFileName() + " must have exactly one hard link");
        }
      } catch (UnsupportedOperationException ignored) {
        // Non-Unix test file systems still get no-follow and regular-file checks.
      }
      byte[] bytes = Files.readAllBytes(path);
      decodeUtf8(bytes);
      return bytes;
    } catch (IOException | RuntimeException error) {
      throw failure(
          "invalid-approved-snapshot",
          selectedSourcePath,
          "unsafe approved snapshot leaf " + path + ": " + error.getMessage(),
          error);
    }
  }

  private static void decodeUtf8(byte[] bytes) throws CharacterCodingException {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes));
  }

  private static ApprovedReleaseException failure(
      String code,
      String sourcePath,
      String message) {
    return new ApprovedReleaseException(code, sourcePath, message);
  }

  private static ApprovedReleaseException failure(
      String code,
      String sourcePath,
      String message,
      Throwable cause) {
    return new ApprovedReleaseException(code, sourcePath, message, cause);
  }

  private record SnapshotDirectory(
      String collection,
      String directoryPublicId,
      Path published,
      byte[] referencesBytes,
      PageReferenceMap references) {

    static SnapshotDirectory probe(String collection, String publicId, Path published) {
      try {
        BasicFileAttributes attributes = Files.readAttributes(
            published, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || Files.isSymbolicLink(published)) {
          throw new IOException("published must be a non-symbolic directory");
        }
        Set<String> entries;
        try (var paths = Files.list(published)) {
          entries = paths
              .map(path -> path.getFileName().toString())
              .collect(Collectors.toUnmodifiableSet());
        }
        if (!PUBLISHED_FILES.equals(entries)) {
          throw new IOException("published must contain exactly ru.md, en.md, and references.json");
        }
        byte[] references = readSafeLeaf(published.resolve("references.json"), null);
        PageReferenceMap map = PageReferenceMapCodec.read(
            references,
            published.resolve("references.json").toString());
        return new SnapshotDirectory(collection, publicId, published, references, map);
      } catch (IOException | RuntimeException error) {
        throw failure(
            "invalid-approved-snapshot",
            null,
            "invalid approved snapshot " + published + ": " + error.getMessage(),
            error);
      }
    }

    ApprovedPageSnapshot load(String selectedSourcePath, Path catalogPath) {
      byte[] russian = readSafeLeaf(published.resolve("ru.md"), selectedSourcePath);
      byte[] english = readSafeLeaf(published.resolve("en.md"), selectedSourcePath);
      try {
        PageReferenceMapCodec.validate(references, russian, english);
        ReviewWorkspace.ApprovedMarkdown ru = ReviewWorkspace.parseApprovedMarkdown(
            russian,
            collection,
            "ru",
            published.resolve("ru.md").toString());
        ReviewWorkspace.ApprovedMarkdown en = ReviewWorkspace.parseApprovedMarkdown(
            english,
            collection,
            "en",
            published.resolve("en.md").toString());
        if (!ru.publicId().equals(en.publicId())) {
          throw new IllegalArgumentException("approved RU and EN ids must match");
        }
        if (!ru.contentType().equals(en.contentType())) {
          throw new IllegalArgumentException("approved RU and EN content types must match");
        }
        ManifestEntry russianEntry = new ManifestEntry(
            references.sourcePath(),
            ru.targetPath(),
            ru.route(),
            new LinkedHashMap<>(ru.metadata()),
            ru.body());
        ManifestEntry englishEntry = new ManifestEntry(
            references.sourcePath(),
            en.targetPath(),
            en.route(),
            new LinkedHashMap<>(en.metadata()),
            en.body());
        return new ApprovedPageSnapshot(
            collection,
            ru.publicId(),
            references.pageRef(),
            references.sourcePath(),
            russianEntry,
            englishEntry,
            references,
            new SnapshotHashes(references.ruSha256(), references.enSha256()),
            new ApprovedPageSnapshot.InputFiles(
                new ApprovedPageSnapshot.InputFile(published.resolve("ru.md"), russian),
                new ApprovedPageSnapshot.InputFile(published.resolve("en.md"), english),
                new ApprovedPageSnapshot.InputFile(published.resolve("references.json"), referencesBytes),
                inputFileIfPresent(catalogPath, selectedSourcePath)));
      } catch (RuntimeException error) {
        if (error instanceof ApprovedReleaseException releaseError) {
          throw releaseError;
        }
        throw failure(
            "invalid-approved-snapshot",
            selectedSourcePath,
            "invalid approved snapshot " + published + ": " + error.getMessage(),
            error);
      }
    }

    private static ApprovedPageSnapshot.InputFile inputFileIfPresent(
        Path path,
        String selectedSourcePath) {
      if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return new ApprovedPageSnapshot.InputFile(path, readSafeLeaf(path, selectedSourcePath));
    }
  }
}
