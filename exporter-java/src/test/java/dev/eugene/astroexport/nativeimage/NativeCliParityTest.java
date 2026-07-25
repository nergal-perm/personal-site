package dev.eugene.astroexport.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.cli.AstroExportCommand;
import dev.eugene.astroexport.cli.CommandServices;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.manifest.ManifestBuilder;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.testsupport.CommandFixture;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCliParityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> MANAGED_ROOTS = List.of(
      "public/assets/vault",
      "src/content",
      "src/data/pages");

  @TempDir
  Path temp;

  @Test
  void frontmatterBodyMatchesPythonOracleBoundaryTrimming() {
    FrontmatterDocument document = FrontmatterDocument.parse(
        Path.of("Essay.md"),
        "Essay.md",
        """
        ---
        publish: true
        ---

        Text with boundary whitespace.

        """);

    assertEquals("Text with boundary whitespace.", document.body());
  }

  @Test
  void dryRunReportContainsOracleComparableCountsMappingsAndAssets() throws Exception {
    Path vault = writeFixtureVault(temp.resolve("vault"));
    Path review = writeFixtureReview(vault, temp.resolve("review"));
    Path report = temp.resolve("java-dry-run.md");

    CommandFixture.Result result = new CommandFixture().run(
        "--vault", vault.toString(),
        "--dry-run",
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    String text = Files.readString(report);
    assertEquals(text, result.stdout());
    assertEquals("1", summaryValue(text, "Files matched by `rg`"));
    assertEquals("1", summaryValue(text, "Confirmed `publish: true` frontmatter"));
    assertEquals("1", summaryValue(text, "Included by selector"));
    assertEquals("0", summaryValue(text, "Excluded by selector"));
    assertEquals("1", summaryValue(text, "Normalized RU records"));
    assertEquals("1", summaryValue(text, "Generated EN records"));
    assertEquals("0", summaryValue(text, "Translation blockers"));
    assertEquals("1", summaryValue(text, "Collected assets"));
    assertIterableEquals(List.of("- `anywhere/Essay.md` \u2192 `blog/essay` (`essay`)"),
        sectionBullets(text, "## Selected sources"));
    assertIterableEquals(List.of("- `anywhere/Essay.md` \u2192 `src/content/blog/ru/essay.md` \u2192 `/ru/essays/essay/`"),
        sectionBullets(text, "## Normalized manifest"));
    assertIterableEquals(List.of("- `anywhere/Essay.md` \u2192 `src/content/blog/en/essay.md` \u2192 `/en/essays/essay/`"),
        sectionBullets(text, "## Generated English manifest"));
    assertIterableEquals(List.of("- `media/cover.png`"), sectionBullets(text, "## Assets"));
    assertIterableEquals(List.of(), sectionBullets(text, "## Selector exclusions"));
  }

  @Test
  void writeModeReportContainsManagedTreeHashesWithoutNativeBuild() throws Exception {
    Path vault = writeFixtureVault(temp.resolve("vault"));
    Path review = writeFixtureReview(vault, temp.resolve("review"));
    Path out = writeAstroRoot(temp.resolve("astro"));
    Path report = temp.resolve("java-write.md");
    List<SiteWriter.GateInvocation> gateInvocations = new ArrayList<>();
    CommandServices services = CommandServices.defaults()
        .withGateRunner(invocation -> {
          gateInvocations.add(invocation);
          return new SiteWriter.GateResult(0, "fixture gate ok\n", "");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    String text = Files.readString(report);
    assertEquals(text, result.stdout());
    assertEquals("4", summaryValue(text, "Generated records"));
    assertEquals("3", summaryValue(text, "Managed trees"));
    assertEquals("1", summaryValue(text, "Resolved assets"));
    assertIterableEquals(MANAGED_ROOTS, sectionBullets(text, "## Managed tree hashes").stream()
        .map(line -> line.substring(3, line.indexOf("` \u2014 sha256")))
        .toList());
    assertTrue(sectionBullets(text, "## Resolved asset mappings").getFirst()
        .contains("Vault reference `media/cover.png`"));
    assertEquals(List.of(List.of("npm", "run", "check")),
        gateInvocations.stream().map(SiteWriter.GateInvocation::command).toList());
    assertEquals(out.toRealPath(), gateInvocations.getFirst().workingDirectory());
    assertEquals("1", gateInvocations.getFirst().environment().get("CI"));
    assertEquals("1", gateInvocations.getFirst().environment().get("NO_COLOR"));
    assertFalse(Files.exists(out.resolve("src/content/blog/ru/old.md")));
    assertTrue(Files.isRegularFile(out.resolve("src/content/blog/ru/essay.md")));
    assertTrue(Files.isRegularFile(out.resolve("src/content/blog/en/essay.md")));
    assertTrue(Files.isRegularFile(out.resolve("src/data/pages/ru/search.json")));
    assertTrue(Files.isRegularFile(out.resolve("src/data/pages/en/search.json")));
    assertEquals("keep me\n", Files.readString(out.resolve("unmanaged.txt")));
    assertTrue(Files.isRegularFile(out.resolve("public/assets/vault/" + fixtureAssetSha() + ".png")));
    assertEquals(Map.of(), temporarySiblings(out));
  }

  @Test
  void nativeReflectionMetadataCoversEveryPicocliCommandField() throws Exception {
    Path root = Path.of("").toAbsolutePath().normalize();
    Map<String, Set<String>> fieldsByType = nativeReflectionFields(root.resolve(
        "src/main/resources/META-INF/native-image/dev.eugene/astro-export/reachability-metadata.json"));

    assertEquals(Set.of("spec", "vault", "out", "dryRun", "report", "review"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand"));
    assertEquals(Set.of("spec", "parent", "vault", "out", "dryRun", "report", "review"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$BuildFromReviewCommand"));
    assertEquals(Set.of("parent", "overrides", "review"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$MigrateOverridesCommand"));
    assertEquals(Set.of("parent", "vault", "note", "review", "jobs", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$PrepareCommand"));
    assertEquals(Set.of("parent", "vault", "note", "review", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$InspectPublicationCommand"));
    assertEquals(Set.of("parent", "vault", "note", "review", "jobs", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$MarkReviewedCommand"));
    assertEquals(Set.of("parent", "vault", "review", "jobs", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$RefreshPublicationQueueCommand"));
    assertEquals(Set.of("parent", "out"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$WritePublicationContractCommand"));
  }

  @Test
  void nativeBuildSupportDocumentsAgentClasspathAndCutoverGates() throws Exception {
    Path root = Path.of("").toAbsolutePath().normalize();
    String pom = Files.readString(root.resolve("pom.xml"));
    assertTrue(pom.contains("<artifactId>maven-dependency-plugin</artifactId>"));
    assertTrue(pom.contains("<outputFile>${project.build.directory}/classpath.txt</outputFile>"));
    Path nativeMetadata = root.resolve("src/main/resources/META-INF/native-image/dev.eugene/astro-export");
    assertTrue(Files.isDirectory(nativeMetadata));
    assertTrue(Files.readString(nativeMetadata.resolve("resource-config.json"))
        .contains("templates/pages/ru/search"));

    String nativeBuild = Files.readString(root.resolve("docs/native-build.md"));
    assertTrue(nativeBuild.contains("mvn -Pnative native:compile"));
    assertTrue(nativeBuild.contains("/Users/eugene/.sdkman/candidates/java/25.0.4-graal"));
    assertTrue(nativeBuild.contains("target/astro-export --help"));
    assertTrue(nativeBuild.contains("/private/tmp/astro-export-java-dry-run.md"));

    String cutover = Files.readString(root.resolve("docs/cutover-checklist.md"));
    assertTrue(cutover.contains("- [x] Java `mvn test` passes."));
    assertTrue(cutover.contains("- [x] Java `mvn -Pnative native:compile` passes."));
    assertTrue(cutover.contains("- [x] Native dry-run report matches Python oracle counts and mappings."));
    assertTrue(cutover.contains("- [x] Native temp write produces managed-tree hashes matching Python oracle."));
    assertTrue(cutover.contains("- [x] No production Astro deployment is included in cutover."));
  }

  private static CommandFixture.Result run(AstroExportCommand command, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    var commandLine = AstroExportCommand.commandLine(command);
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(args);
    return new CommandFixture.Result(
        exitCode,
        out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private static Map<String, Set<String>> nativeReflectionFields(Path path) throws Exception {
    Map<String, Object> metadata = JSON.readValue(
        Files.readString(path, StandardCharsets.UTF_8),
        new TypeReference<LinkedHashMap<String, Object>>() { });
    List<?> reflection = (List<?>) metadata.get("reflection");
    LinkedHashMap<String, Set<String>> fieldsByType = new LinkedHashMap<>();
    for (Object item : reflection) {
      if (!(item instanceof Map<?, ?> entry)) {
        continue;
      }
      Object type = entry.get("type");
      if (!(type instanceof String typeName) || !typeName.startsWith("dev.eugene.astroexport.cli.")) {
        continue;
      }
      LinkedHashSet<String> fields = new LinkedHashSet<>();
      Object fieldEntries = entry.get("fields");
      if (fieldEntries instanceof List<?> list) {
        for (Object fieldItem : list) {
          if (fieldItem instanceof Map<?, ?> field && field.get("name") instanceof String name) {
            fields.add(name);
          }
        }
      }
      fieldsByType.put(typeName, fields);
    }
    return fieldsByType;
  }

  private static Path writeFixtureVault(Path vault) throws Exception {
    Path note = vault.resolve("anywhere/Essay.md");
    Files.createDirectories(note.getParent());
    Files.writeString(note, """
        ---
        id: essay-internal
        title: Essay
        publish: true
        publicId: essay
        publicCollection: blog
        publicContentType: essay
        description: Essay.
        ---
        Text with ![[media/cover.png|Cover]].
        """, StandardCharsets.UTF_8);
    Path asset = vault.resolve("media/cover.png");
    Files.createDirectories(asset.getParent());
    Files.write(asset, fixtureAssetBytes());
    return vault;
  }

  private static Path writeFixtureReview(Path vault, Path review) throws Exception {
    ManifestEntry entry = currentBlogEntry(vault);
    Path english = review.resolve("blog/essay/en.md");
    Files.createDirectories(english.getParent());
    Files.writeString(english, """
        ---
        sourceHash: %s
        translationStatus: generated
        translatedAt: 2026-07-17
        translationProfile: native-parity-fixture-v1
        title: English title
        description: English description.
        ---
        English text with ![[media/cover.png|Cover]].
        """.formatted(entry.translationSourceHash()), StandardCharsets.UTF_8);
    return review;
  }

  private static ManifestEntry currentBlogEntry(Path vault) {
    SelectionResult selection = CommandServices.defaults().select(vault);
    ManifestResult manifest = new ManifestBuilder().buildRussianManifest(selection);
    return manifest.entries().stream()
        .filter(entry -> entry.sourcePath().equals("anywhere/Essay.md"))
        .findFirst()
        .orElseThrow();
  }

  private static Path writeAstroRoot(Path root) throws Exception {
    Files.createDirectories(root.resolve("scripts"));
    Files.createDirectories(root.resolve("src/content/blog/ru"));
    Files.createDirectories(root.resolve("src/data/pages/ru"));
    Files.createDirectories(root.resolve("public/assets/vault"));
    Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"check\":\"true\"}}\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("src/content.config.ts"), "export default {};\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("scripts/check-content.mjs"), "console.log('check');\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("src/content/blog/ru/old.md"), "old\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("src/data/pages/ru/old.json"), "{}\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("public/assets/vault/old.png"), "old\n", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("unmanaged.txt"), "keep me\n", StandardCharsets.UTF_8);
    return root;
  }

  private static Map<String, ByteBuffer> temporarySiblings(Path root) throws Exception {
    LinkedHashMap<String, ByteBuffer> files = new LinkedHashMap<>();
    try (var paths = Files.list(root.getParent())) {
      for (Path path : paths
          .filter(item -> item.getFileName().toString().startsWith("." + root.getFileName() + ".astro-export-"))
          .sorted()
          .toList()) {
        files.put(path.getFileName().toString(), ByteBuffer.wrap(new byte[0]));
      }
    }
    return files;
  }

  private static String summaryValue(String report, String key) {
    return report.lines()
        .filter(line -> line.equals("- " + key) || line.startsWith("- " + key + ": "))
        .map(line -> line.substring(("- " + key + ": ").length()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> sectionBullets(String report, String headingPrefix) {
    List<String> lines = report.lines().toList();
    int start = -1;
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).startsWith(headingPrefix)) {
        start = index + 1;
        break;
      }
    }
    if (start < 0) {
      throw new AssertionError("missing section " + headingPrefix);
    }
    ArrayList<String> bullets = new ArrayList<>();
    for (int index = start; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.startsWith("## ")) {
        break;
      }
      if (line.startsWith("- ")) {
        bullets.add(line);
      }
    }
    return bullets;
  }

  private static byte[] fixtureAssetBytes() {
    return "native parity fixture asset\n".getBytes(StandardCharsets.UTF_8);
  }

  private static String fixtureAssetSha() throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(fixtureAssetBytes()));
  }
}
