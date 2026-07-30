package dev.eugene.astroexport.release;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.fs.TreeHasher;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Writes and verifies release provenance for the staged Astro payload. */
public final class ReleaseProvenanceWriter {
  public static final String MANIFEST_RELATIVE = ".astro-export/release-provenance.json";
  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final ManifestSink manifestSink;

  public ReleaseProvenanceWriter() {
    this(ManifestSink.files());
  }

  ReleaseProvenanceWriter(ManifestSink manifestSink) {
    this.manifestSink = Objects.requireNonNull(manifestSink, "manifestSink");
  }

  public ReleaseProvenance write(
      Path stagedRoot,
      ApprovedReleaseMaterializer.MaterializedRelease release) {
    Objects.requireNonNull(stagedRoot, "stagedRoot");
    Objects.requireNonNull(release, "release");
    ReleaseProvenance withoutDigest = build(stagedRoot, release, "");
    ReleaseProvenance provenance = withDigest(withoutDigest, payloadDigest(withoutDigest));
    Path manifest = stagedRoot.resolve(MANIFEST_RELATIVE);
    try {
      Files.createDirectories(manifest.getParent());
      manifestSink.writeAndForce(manifest, json(provenance));
    } catch (IOException error) {
      throw new SiteWriter.WriterException("cannot write release provenance: " + error.getMessage(), error);
    }
    return verify(stagedRoot, release);
  }

  public ReleaseProvenance verify(Path stagedRoot) {
    return verify(stagedRoot, stagedRoot.resolve(MANIFEST_RELATIVE));
  }

  public ReleaseProvenance verify(Path stagedRoot, Path manifest) {
    try {
      if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
        throw mismatch("missing manifest");
      }
      if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
        throw mismatch("manifest is not a regular file");
      }
      byte[] manifestBytes = Files.readAllBytes(manifest);
      ReleaseProvenance actual = JSON.readValue(manifestBytes, ReleaseProvenance.class);
      if (actual.schemaVersion() != ReleaseProvenance.SCHEMA_VERSION) {
        throw mismatch("unsupported schema version " + actual.schemaVersion());
      }
      ReleaseProvenance recomputed = new ReleaseProvenance(
          actual.schemaVersion(),
          actual.selectedPages(),
          TreeHasher.hashPayloadTrees(stagedRoot),
          TreeHasher.hashPayloadFiles(stagedRoot),
          actual.activationCount(),
          actual.deactivationCount(),
          "");
      recomputed = withDigest(recomputed, payloadDigest(recomputed));
      if (!actual.managedTrees().equals(recomputed.managedTrees())) {
        throw mismatch("managed tree hash mismatch");
      }
      if (!actual.managedFiles().equals(recomputed.managedFiles())) {
        throw mismatch("managed file hash mismatch");
      }
      if (!actual.payloadDigest().equals(recomputed.payloadDigest())) {
        throw mismatch("payload digest mismatch");
      }
      if (!java.util.Arrays.equals(manifestBytes, json(actual))) {
        throw mismatch("manifest is not canonical");
      }
      return actual;
    } catch (ReleaseProvenanceException error) {
      throw error;
    } catch (IOException error) {
      throw mismatch("cannot read manifest: " + error.getMessage(), error);
    }
  }

  public ReleaseProvenance verify(
      Path stagedRoot,
      ApprovedReleaseMaterializer.MaterializedRelease release) {
    return verify(stagedRoot, stagedRoot.resolve(MANIFEST_RELATIVE), release);
  }

  public ReleaseProvenance verify(
      Path stagedRoot,
      Path manifest,
      ApprovedReleaseMaterializer.MaterializedRelease release) {
    Objects.requireNonNull(release, "release");
    ReleaseProvenance actual = verify(stagedRoot, manifest);
    ReleaseProvenance expectedWithoutDigest = build(stagedRoot, release, "");
    ReleaseProvenance expected = withDigest(expectedWithoutDigest, payloadDigest(expectedWithoutDigest));
    if (!actual.equals(expected)) {
      throw mismatch("release claims mismatch");
    }
    return actual;
  }

  public byte[] serialize(ReleaseProvenance provenance) {
    return json(provenance);
  }

  private static ReleaseProvenance build(
      Path stagedRoot,
      ApprovedReleaseMaterializer.MaterializedRelease release,
      String payloadDigest) {
    Map<String, ManifestEntry> ru = byPublicId(release.manifest().entries());
    Map<String, ManifestEntry> en = byPublicId(release.manifest().englishEntries());
    List<ReleaseProvenance.SelectedPage> selected = release.selectedSnapshots().stream()
        .sorted(Comparator.comparing(ApprovedPageSnapshot::pageRef))
        .map(snapshot -> selectedPage(snapshot, ru.get(snapshot.publicId()), en.get(snapshot.publicId())))
        .toList();
    int activations = release.audit().byPageRef().values().stream().mapToInt(List::size).sum();
    return new ReleaseProvenance(
        ReleaseProvenance.SCHEMA_VERSION,
        selected,
        TreeHasher.hashPayloadTrees(stagedRoot),
        TreeHasher.hashPayloadFiles(stagedRoot),
        activations,
        release.ignoredDrafts().size(),
        payloadDigest);
  }

  private static ReleaseProvenance.SelectedPage selectedPage(
      ApprovedPageSnapshot snapshot,
      ManifestEntry ru,
      ManifestEntry en) {
    if (ru == null || en == null) {
      throw new SiteWriter.WriterException("release output is missing projected page: " + snapshot.publicId());
    }
    return new ReleaseProvenance.SelectedPage(
        snapshot.pageRef(),
        snapshot.publicId(),
        snapshot.sourcePath(),
        snapshot.hashes().russianSha256(),
        snapshot.hashes().englishSha256(),
        TreeHasher.sha256(PageReferenceMapCodec.write(snapshot.references())),
        TreeHasher.sha256(ru.body().getBytes(StandardCharsets.UTF_8)),
        TreeHasher.sha256(en.body().getBytes(StandardCharsets.UTF_8)));
  }

  private static Map<String, ManifestEntry> byPublicId(List<ManifestEntry> entries) {
    return entries.stream().collect(Collectors.toMap(
        entry -> String.valueOf(entry.metadata().get("id")),
        Function.identity(),
        (left, right) -> left,
        java.util.LinkedHashMap::new));
  }

  private static ReleaseProvenance withDigest(ReleaseProvenance provenance, String digest) {
    return new ReleaseProvenance(
        provenance.schemaVersion(),
        provenance.selectedPages(),
        provenance.managedTrees(),
        provenance.managedFiles(),
        provenance.activationCount(),
        provenance.deactivationCount(),
        digest);
  }

  private static String payloadDigest(ReleaseProvenance provenance) {
    return TreeHasher.sha256(json(new ReleaseProvenance(
        provenance.schemaVersion(),
        provenance.selectedPages(),
        provenance.managedTrees(),
        provenance.managedFiles(),
        provenance.activationCount(),
        provenance.deactivationCount(),
        "")));
  }

  private static byte[] json(Object value) {
    try {
      JsonNode tree = JSON.valueToTree(value);
      return JSON.writer().writeValueAsBytes(tree);
    } catch (IOException error) {
      throw new SiteWriter.WriterException("cannot serialize release provenance: " + error.getMessage(), error);
    }
  }

  private static ReleaseProvenanceException mismatch(String detail) {
    return new ReleaseProvenanceException("release-provenance-mismatch: " + detail);
  }

  private static ReleaseProvenanceException mismatch(String detail, Throwable cause) {
    return new ReleaseProvenanceException("release-provenance-mismatch: " + detail, cause);
  }

  public static final class ReleaseProvenanceException extends RuntimeException {
    public ReleaseProvenanceException(String detail) {
      super(detail);
    }

    public ReleaseProvenanceException(String detail, Throwable cause) {
      super(detail, cause);
    }
  }

  interface ManifestSink {
    void writeAndForce(Path path, byte[] bytes) throws IOException;

    static ManifestSink files() {
      return (path, bytes) -> {
        try (FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }
          channel.force(true);
        }
      };
    }
  }
}
