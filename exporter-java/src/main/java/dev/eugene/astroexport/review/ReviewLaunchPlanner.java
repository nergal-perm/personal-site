package dev.eugene.astroexport.review;

import dev.eugene.astroexport.fs.JnaFileDescriptor;
import dev.eugene.astroexport.migration.SemanticSchemaState;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/** Builds safe, editor-neutral file targets for one translation review. */
public final class ReviewLaunchPlanner {
  private final SafeReader safeReader;
  private final AttributeProbe attributeProbe;

  public ReviewLaunchPlanner() {
    this(ReviewLaunchPlanner::readSafeUtf8, ReviewLaunchPlanner::readAttributesNoFollow);
  }

  ReviewLaunchPlanner(SafeReader safeReader) {
    this(safeReader, ReviewLaunchPlanner::readAttributesNoFollow);
  }

  ReviewLaunchPlanner(SafeReader safeReader, AttributeProbe attributeProbe) {
    this.safeReader = Objects.requireNonNull(safeReader, "safeReader");
    this.attributeProbe = Objects.requireNonNull(attributeProbe, "attributeProbe");
  }

  public ReviewPlan plan(
      Path reviewRoot,
      Path reviewDirectory,
      ManifestEntry entry,
      byte[] validatedEnglish) {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(validatedEnglish, "validatedEnglish");
    Path root = realDirectory(reviewRoot, "review root");
    Path page = realDirectory(reviewDirectory, "review directory");
    if (!page.startsWith(root)) {
      throw proposalFailure("Review directory escapes the review root.");
    }

    Path candidateDirectory = page.resolve("candidate");
    SemanticSchemaState.Mode schemaMode = SemanticSchemaState.mode(root);
    if (schemaMode == SemanticSchemaState.Mode.MIGRATION_INCOMPLETE) {
      throw proposalFailure(
          "Semantic link migration is incomplete; recover it before launching review.");
    }
    boolean semantic = schemaMode == SemanticSchemaState.Mode.SEMANTIC;
    if (semantic && !Files.isDirectory(candidateDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw proposalFailure(
          "Semantic review requires a complete candidate/ proposal; run prepare again.");
    }
    Path proposalDirectory = Files.isDirectory(candidateDirectory, LinkOption.NOFOLLOW_LINKS)
        ? candidateDirectory
        : page;
    Path proposedRu = proposalDirectory.resolve("ru.md");
    Path proposedEn = proposalDirectory.resolve("en.md");
    byte[] russian = readProposal(proposedRu, "Russian proposal");
    byte[] english = readProposal(proposedEn, "English proposal");
    if (proposalDirectory.endsWith("candidate")) {
      validateCandidateReferences(proposalDirectory, russian, english);
    }
    byte[] expectedRussian =
        ReviewWorkspace.renderRuReview(entry).getBytes(StandardCharsets.UTF_8);
    if (!Arrays.equals(expectedRussian, russian)) {
      throw proposalFailure(
          "Russian review does not match the current normalized source; run prepare again.");
    }
    if (!Arrays.equals(validatedEnglish, english)) {
      throw proposalFailure(
          "English review changed after freshness validation; inspect it again.");
    }

    Path publishedDirectory = page.resolve("published");
    ProbeResult directoryProbe =
        probePublishedPath(publishedDirectory, "Published snapshot directory");
    if (directoryProbe.missing()) {
      return planMissingPublishedDirectory(
          publishedDirectory, proposedRu, proposedEn);
    }
    BasicFileAttributes directoryAttributes = directoryProbe.attributes();
    if (directoryAttributes.isSymbolicLink() || !directoryAttributes.isDirectory()) {
      throw publishedFailure("Published snapshot path must be a non-symbolic directory.");
    }
    Path realPublished;
    try {
      realPublished = publishedDirectory.toRealPath();
    } catch (IOException error) {
      throw publishedFailure("Published snapshot directory is unreadable.", error);
    }
    if (!realPublished.startsWith(page)) {
      throw publishedFailure("Published snapshot directory escapes the review page.");
    }
    Path publishedRu = realPublished.resolve("ru.md");
    Path publishedEn = realPublished.resolve("en.md");
    Path publishedReferences = realPublished.resolve("references.json");
    ProbeResult ruProbe = probePublishedPath(publishedRu, "Published Russian snapshot");
    ProbeResult enProbe = probePublishedPath(publishedEn, "Published English snapshot");
    ProbeResult referencesProbe = semantic
        ? probePublishedPath(publishedReferences, "Published reference map")
        : null;
    if (ruProbe.missing() && enProbe.missing() && (!semantic || referencesProbe.missing())) {
      return new ReviewPlan("absent", List.of(
          new ReviewTarget("ru", proposedRu, null),
          new ReviewTarget("en", proposedEn, null)));
    }
    if (ruProbe.missing() || enProbe.missing() || (semantic && referencesProbe.missing())) {
      throw publishedFailure(
          "published snapshot is incomplete: ru.md, en.md, and references.json must exist as one triple.");
    }
    validatePublishedEntries(realPublished, semantic);
    byte[] publishedRussian = readPublished(publishedRu, "Published Russian snapshot");
    byte[] publishedEnglish = readPublished(publishedEn, "Published English snapshot");
    if (semantic) {
      validatePublishedReferences(publishedReferences, publishedRussian, publishedEnglish);
    }
    return new ReviewPlan("complete", List.of(
        new ReviewTarget("ru", proposedRu, publishedRu),
        new ReviewTarget("en", proposedEn, publishedEn)));
  }

  private ReviewPlan planMissingPublishedDirectory(
      Path publishedDirectory,
      Path proposedRu,
      Path proposedEn) {
    ProbeResult ruProbe = probePublishedPath(
        publishedDirectory.resolve("ru.md"), "Published Russian snapshot");
    ProbeResult enProbe = probePublishedPath(
        publishedDirectory.resolve("en.md"), "Published English snapshot");
    ProbeResult referencesProbe = probePublishedPath(
        publishedDirectory.resolve("references.json"), "Published reference map");
    if (!ruProbe.missing() || !enProbe.missing() || !referencesProbe.missing()) {
      throw publishedFailure(
          "published snapshot changed during inspection; run the check again.");
    }
    return new ReviewPlan("absent", List.of(
        new ReviewTarget("ru", proposedRu, null),
        new ReviewTarget("en", proposedEn, null)));
  }

  private ProbeResult probePublishedPath(Path path, String label) {
    try {
      return ProbeResult.present(attributeProbe.read(path));
    } catch (NoSuchFileException error) {
      return ProbeResult.missingResult();
    } catch (IOException | SecurityException error) {
      throw publishedFailure(
          label + " presence cannot be determined.", error);
    }
  }

  private byte[] readProposal(Path path, String label) {
    try {
      return safeReader.read(path, label);
    } catch (IOException | IllegalArgumentException error) {
      throw proposalFailure(label + " is unavailable: " + error.getMessage(), error);
    }
  }

  private byte[] readPublished(Path path, String label) {
    try {
      return safeReader.read(path, label);
    } catch (IOException | IllegalArgumentException error) {
      throw publishedFailure(label + " is unsafe or unreadable: " + error.getMessage(), error);
    }
  }

  private void validatePublishedReferences(
      Path references,
      byte[] russian,
      byte[] english) {
    try {
      byte[] content = safeReader.read(references, "Published reference map");
      PageReferenceMap map = PageReferenceMapCodec.read(content, "published/references.json");
      PageReferenceMapCodec.validate(map, russian, english);
    } catch (IOException | RuntimeException error) {
      throw publishedFailure(
          "Published reference map is unsafe or invalid: " + error.getMessage(), error);
    }
  }

  private void validatePublishedEntries(Path published, boolean semantic) {
    Set<String> expected = semantic
        ? Set.of("ru.md", "en.md", "references.json")
        : Set.of("ru.md", "en.md");
    Set<String> entries;
    try (var paths = Files.list(published)) {
      entries = paths
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    } catch (IOException | RuntimeException error) {
      throw publishedFailure("Published snapshot directory is unreadable.", error);
    }
    if (!entries.equals(expected)) {
      throw publishedFailure(
          "published snapshot layout is invalid for the active schema.");
    }
  }

  private void validateCandidateReferences(Path candidate, byte[] russian, byte[] english) {
    try {
      byte[] references = safeReader.read(
          candidate.resolve("references.json"), "Candidate reference map");
      PageReferenceMap map = PageReferenceMapCodec.read(references, "candidate/references.json");
      PageReferenceMapCodec.validate(map, russian, english);
    } catch (IOException | RuntimeException error) {
      throw proposalFailure(
          "Candidate reference map is unavailable: " + error.getMessage(), error);
    }
  }

  private static Path realDirectory(Path path, String label) {
    try {
      Path absolute = Objects.requireNonNull(path, label).toAbsolutePath().normalize();
      if (Files.isSymbolicLink(absolute)
          || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(label + " must be a non-symbolic directory.");
      }
      return absolute.toRealPath();
    } catch (IOException | IllegalArgumentException error) {
      throw proposalFailure(label + " is unavailable: " + error.getMessage(), error);
    }
  }

  private static byte[] readSafeUtf8(Path path, String label) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(label + " is missing.");
    }
    try (JnaFileDescriptor descriptor = JnaFileDescriptor.openReadNoFollow(path)) {
      JnaFileDescriptor.Snapshot snapshot = descriptor.snapshot();
      if (!snapshot.attributes().isRegularFile()) {
        throw new IllegalArgumentException(label + " must be a regular file.");
      }
      if (snapshot.linkCount() != 1) {
        throw new IllegalArgumentException(label + " must have exactly one hard link.");
      }
      byte[] bytes = descriptor.readAllBytes();
      decodeUtf8(bytes, label);
      return bytes;
    }
  }

  private static BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
    return Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static void decodeUtf8(byte[] bytes, String label) {
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(label + " must be valid UTF-8.", error);
    }
  }

  private static ReviewLaunchException proposalFailure(String message) {
    return proposalFailure(message, null);
  }

  private static ReviewLaunchException proposalFailure(String message, Throwable cause) {
    return new ReviewLaunchException("stale", "translation", message, cause);
  }

  private static ReviewLaunchException publishedFailure(String message) {
    return publishedFailure(message, null);
  }

  private static ReviewLaunchException publishedFailure(String message, Throwable cause) {
    return new ReviewLaunchException(
        "published_snapshot_inconsistent", "published-snapshot", message, cause);
  }

  @FunctionalInterface
  interface SafeReader {
    byte[] read(Path path, String label) throws IOException;
  }

  @FunctionalInterface
  interface AttributeProbe {
    BasicFileAttributes read(Path path) throws IOException;
  }

  private record ProbeResult(BasicFileAttributes attributes) {
    private static ProbeResult present(BasicFileAttributes attributes) {
      return new ProbeResult(Objects.requireNonNull(attributes, "attributes"));
    }

    private static ProbeResult missingResult() {
      return new ProbeResult(null);
    }

    private boolean missing() {
      return attributes == null;
    }
  }

  public record ReviewPlan(String baselineState, List<ReviewTarget> targets) {
    public ReviewPlan {
      baselineState = Objects.requireNonNull(baselineState, "baselineState");
      targets = List.copyOf(targets);
    }
  }

  public record ReviewTarget(
      String language,
      Path proposedPath,
      Path publishedPath) {
    public ReviewTarget {
      language = Objects.requireNonNull(language, "language");
      proposedPath = Objects.requireNonNull(proposedPath, "proposedPath");
    }
  }

  public static final class ReviewLaunchException extends IllegalArgumentException {
    private final String status;
    private final String field;

    ReviewLaunchException(
        String status,
        String field,
        String message,
        Throwable cause) {
      super(message, cause);
      this.status = status;
      this.field = field;
    }

    public String status() {
      return status;
    }

    public String field() {
      return field;
    }
  }
}
