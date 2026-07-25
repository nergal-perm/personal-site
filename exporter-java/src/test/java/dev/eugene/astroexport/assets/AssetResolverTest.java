package dev.eugene.astroexport.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

final class AssetResolverTest {
  private final AssetResolver resolver = new AssetResolver();

  @TempDir Path temporaryDirectory;

  @Test
  void exposesExplainableValidationErrorsAndImmutableAssets() {
    AssetValidationException error = new AssetValidationException("cover.png", "missing file");
    ResolvedAsset asset = resolved("cover.png");

    assertEquals("cover.png", error.reference());
    assertEquals("missing file", error.reason());
    assertEquals("cover.png: missing file", error.getMessage());
    assertEquals("cover.png", asset.reference());
  }

  @Test
  void exactVaultRelativeReferenceWinsOverDuplicateBasename() throws IOException {
    Path exact = write("public/Cover.PNG", "exact");
    write("other/Cover.PNG", "duplicate");

    ResolvedAsset resolved = resolver.resolveAssets(temporaryDirectory, List.of("public/Cover.PNG")).getFirst();

    String digest = sha256("exact");
    assertEquals("public/Cover.PNG", resolved.reference());
    assertEquals(exact.toRealPath(), resolved.sourcePath());
    assertEquals(digest, resolved.sha256());
    assertEquals(digest + ".png", resolved.outputName());
    assertEquals("/assets/vault/" + digest + ".png", resolved.publicUrl());
  }

  @Test
  void allowsUniqueNonHiddenBasenameFallback() throws IOException {
    Path target = write("bibliography/images/cover.png", "png");
    write(".obsidian/cover.png", "hidden");

    ResolvedAsset resolved = resolver.resolveAssets(temporaryDirectory, List.of("cover.png")).getFirst();

    assertEquals(target.toRealPath(), resolved.sourcePath());
  }

  @Test
  void blocksAmbiguousBasename() throws IOException {
    write("one/cover.png", "one");
    write("two/cover.png", "two");

    AssetValidationException error = assertThrows(AssetValidationException.class,
        () -> resolver.resolveAssets(temporaryDirectory, List.of("cover.png")));

    assertEquals("cover.png", error.reference());
    assertTrue(error.reason().contains("ambiguous"));
  }

  @ParameterizedTest
  @MethodSource("invalidReferences")
  void blocksInvalidAssetReferences(String reference, String reasonFragment) {
    assertInvalid(reference, reasonFragment);
  }

  @ParameterizedTest
  @MethodSource("windowsInvalidReferences")
  void blocksWindowsAbsoluteUncAndBackslashTraversal(String reference, String reasonFragment) {
    assertInvalid(reference, reasonFragment);
  }

  @Test
  void blocksSymlinkEscapesBeforeFileBytesAreRead() throws IOException {
    Path vault = Files.createDirectory(temporaryDirectory.resolve("vault"));
    Path outside = write("outside.png", "secret");
    Files.createSymbolicLink(vault.resolve("escape.png"), outside);

    AtomicInteger reads = new AtomicInteger();
    AssetResolver countingResolver = new AssetResolver(path -> {
      reads.incrementAndGet();
      return Files.readAllBytes(path);
    });
    AssetValidationException error = assertThrows(AssetValidationException.class,
        () -> countingResolver.resolveAssets(vault, List.of("escape.png")));

    assertEquals("escape.png", error.reference());
    assertTrue(error.reason().contains("outside vault"));
    assertEquals(0, reads.get());
  }

  @Test
  void hashesDeduplicateDestinationsAndOrderIsStable() throws IOException {
    write("z/Cover.PNG", "same bytes");
    write("a/copy.png", "same bytes");
    write("m/other.jpg", "other bytes");

    List<String> references = List.of("z/Cover.PNG", "a/copy.png", "m/other.jpg", "z/Cover.PNG");
    List<ResolvedAsset> resolved = resolver.resolveAssets(temporaryDirectory, references);
    List<ResolvedAsset> repeated = resolver.resolveAssets(temporaryDirectory, references.reversed());

    assertEquals(resolved, repeated);
    assertEquals(List.of("a/copy.png", "m/other.jpg", "z/Cover.PNG"),
        resolved.stream().map(ResolvedAsset::reference).toList());
    assertEquals(resolved.getFirst().outputName(), resolved.getLast().outputName());
    assertEquals(resolved.getFirst().publicUrl(), resolved.getLast().publicUrl());
    assertEquals(2, resolved.stream().map(ResolvedAsset::outputName).distinct().count());
  }

  @Test
  void canonicalizesIdenticalJpgAndJpegDestinations() throws IOException {
    write("a/cover.jpg", "same jpeg bytes");
    write("z/cover.JPEG", "same jpeg bytes");

    List<ResolvedAsset> resolved = resolver.resolveAssets(temporaryDirectory,
        List.of("z/cover.JPEG", "a/cover.jpg"));

    String outputName = sha256("same jpeg bytes") + ".jpg";
    assertEquals(List.of("a/cover.jpg", "z/cover.JPEG"),
        resolved.stream().map(ResolvedAsset::reference).toList());
    assertEquals(List.of(outputName, outputName), resolved.stream().map(ResolvedAsset::outputName).toList());
    assertEquals(List.of("/assets/vault/" + outputName, "/assets/vault/" + outputName),
        resolved.stream().map(ResolvedAsset::publicUrl).toList());
  }

  @ParameterizedTest
  @MethodSource("incompatibleSuffixes")
  void blocksIdenticalBytesWithIncompatibleSuffixFamilies(String firstName, String secondName) throws IOException {
    assertIncompatible(firstName, secondName);
  }

  @Test
  void readsMultipleReferencesToOneRealSourceOnlyOnce() throws IOException {
    write("assets/cover.jpg", "one source");

    AtomicInteger reads = new AtomicInteger();
    AssetResolver countingResolver = new AssetResolver(path -> {
      reads.incrementAndGet();
      return Files.readAllBytes(path);
    });
    List<ResolvedAsset> resolved = countingResolver.resolveAssets(temporaryDirectory,
        List.of("cover.jpg", "assets/cover.jpg"));

    assertEquals(2, resolved.size());
    assertEquals(1, reads.get());
  }

  @Test
  void rewritesImagesAudioVideoAndNumericWidthSafely() {
    ResolvedAsset image = resolved("images/Cover.PNG");
    ResolvedAsset widthImage = resolved("images/Cover & \"wide\".PNG", ".png", "/assets/vault/a&b\".png");
    ResolvedAsset audio = resolved("sound.mp3", ".mp3", "/assets/vault/sound&\".mp3");
    ResolvedAsset video = resolved("clip.mp4", ".mp4", "/assets/vault/clip&\".mp4");
    String body = "![[images/Cover.PNG]]\n![[images/Cover.PNG|Diagram alt]]\n"
        + "![[images/Cover & \"wide\".PNG|360]]\n![[sound.mp3]]\n![[clip.mp4]]";

    String rewritten = resolver.rewriteAssetEmbeds(body, List.of(image, widthImage, audio, video));

    assertEquals("![Cover](" + image.publicUrl() + ")\n"
        + "![Diagram alt](" + image.publicUrl() + ")\n"
        + "<img src=\"/assets/vault/a&amp;b&quot;.png\" alt=\"Cover &amp; &quot;wide&quot;\" width=\"360\">\n"
        + "<audio controls src=\"/assets/vault/sound&amp;&quot;.mp3\"></audio>\n"
        + "<video controls src=\"/assets/vault/clip&amp;&quot;.mp4\"></video>", rewritten);
  }

  @Test
  void preservesProtectedContextsAndRewritesRepeatedRenderedEmbeds() {
    ResolvedAsset asset = resolved("cover.png");
    String body = "```md\n![[cover.png]]\n```\n"
        + "Inline `![[cover.png]]`; <!-- ![[cover.png]] -->; \\![[cover.png]].\n"
        + "Rendered ![[cover.png]] and again ![[cover.png]].";

    String rewritten = resolver.rewriteAssetEmbeds(body, List.of(asset));

    assertEquals("```md\n![[cover.png]]\n```\n"
        + "Inline `![[cover.png]]`; <!-- ![[cover.png]] -->; \\![[cover.png]].\n"
        + "Rendered ![cover](" + asset.publicUrl() + ") and again ![cover](" + asset.publicUrl() + ").", rewritten);
  }

  @Test
  void blocksAssetAbsentFromRuAllowlist() {
    ResolvedAsset ruAsset = resolved("ru-cover.png");

    AssetValidationException error = assertThrows(AssetValidationException.class,
        () -> resolver.rewriteAssetEmbeds("Translated ![[ru-cover.png]] and ![[en-only.png]].", List.of(ruAsset)));

    assertEquals("en-only.png", error.reference());
    assertTrue(error.reason().contains("RU asset allowlist"));
  }

  private void assertInvalid(String reference, String reasonFragment) {
    AssetValidationException error = assertThrows(AssetValidationException.class,
        () -> resolver.resolveAssets(temporaryDirectory, List.of(reference)));
    assertEquals(reference, error.reference());
    assertTrue(error.reason().contains(reasonFragment));
  }

  private static Stream<Arguments> invalidReferences() {
    return Stream.of(
        Arguments.of("missing.png", "missing"),
        Arguments.of("/absolute.png", "absolute"),
        Arguments.of("../outside.png", "traversal"),
        Arguments.of(".private/hidden.png", "hidden"),
        Arguments.of("images/.hidden.png", "hidden"),
        Arguments.of("document.pdf", "unsupported extension"));
  }

  private static Stream<Arguments> windowsInvalidReferences() {
    return Stream.of(
        Arguments.of("C:\\vault\\cover.png", "absolute"),
        Arguments.of("\\\\server\\share\\cover.png", "absolute"),
        Arguments.of("..\\outside.png", "traversal"));
  }

  private static Stream<Arguments> incompatibleSuffixes() {
    return Stream.of(
        Arguments.of("a.png", "b.jpg"),
        Arguments.of("a.png", "b.mp3"),
        Arguments.of("a.mp3", "b.mp4"));
  }

  private void assertIncompatible(String firstName, String secondName) throws IOException {
    String directory = "incompatible-" + firstName.replace('.', '-') + "-" + secondName.replace('.', '-');
    String firstReference = directory + "/" + firstName;
    String secondReference = directory + "/" + secondName;
    write(firstReference, "ambiguous media bytes");
    write(secondReference, "ambiguous media bytes");
    List<String> references = List.of(secondReference, firstReference);

    AssetValidationException first = assertThrows(AssetValidationException.class,
        () -> resolver.resolveAssets(temporaryDirectory, references));
    AssetValidationException repeated = assertThrows(AssetValidationException.class,
        () -> resolver.resolveAssets(temporaryDirectory, references.reversed()));

    assertEquals(first.getMessage(), repeated.getMessage());
    assertTrue(first.reason().contains("incompatible suffix families"));
  }

  private Path write(String relativePath, String content) throws IOException {
    Path path = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(path.getParent());
    return Files.writeString(path, content);
  }

  private static ResolvedAsset resolved(String reference) { return resolved(reference, ".png", null); }

  private static ResolvedAsset resolved(String reference, String suffix, String publicUrl) {
    String digest = "a".repeat(64);
    String outputName = digest + suffix;
    return new ResolvedAsset(reference, Path.of("/private/vault").resolve(reference), outputName,
        publicUrl == null ? "/assets/vault/" + outputName : publicUrl, digest);
  }

  private static String sha256(String content) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
