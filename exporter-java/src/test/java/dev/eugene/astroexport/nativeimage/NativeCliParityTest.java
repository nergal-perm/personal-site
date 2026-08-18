package dev.eugene.astroexport.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.eugene.astroexport.references.PageReferenceMapCodec;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCliParityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> MANAGED_ROOTS = List.of(
      ".astro-export",
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
    assertEquals(String.valueOf(MANAGED_ROOTS.size()), summaryValue(text, "Managed trees"));
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
    assertEquals(Set.of("parent", "vault", "review", "astro", "report", "decisions",
            "draft", "validate", "apply", "rollForward", "rollBack", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$MigrateSemanticLinksCommand"));
    assertEquals(Set.of("parent", "vault", "review", "jobs", "json"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$RefreshPublicationQueueCommand"));
    assertEquals(Set.of("parent", "out"),
        fieldsByType.get("dev.eugene.astroexport.cli.AstroExportCommand$WritePublicationContractCommand"));
  }

  @Test
  void nativeExecutableExercisesSemanticSubcommandsBeyondHelp() throws Exception {
    Path nativeBinary = Path.of("target/astro-export").toAbsolutePath().normalize();
    assertTrue(Files.isExecutable(nativeBinary), "run mvn -Pnative native:compile before native parity");
    assertTrue(nativeRun(nativeBinary, "--help").stdout().contains("migrate-semantic-links"),
        "native binary predates semantic CLI subcommands");
    assertNativeMissingApproval(nativeBinary);
    assertNativeMigrationIncomplete(nativeBinary);
    assertNativeOrderMismatch(nativeBinary);

    Path vault = temp.resolve("native-vault");
    Path review = temp.resolve("native-review");
    Path astro = writeAstroRoot(temp.resolve("native-astro"));
    Path jobs = temp.resolve("native-jobs");
    Path inventory = temp.resolve("native-inventory.json");
    writePublishedSemanticFixture(vault, review, astro);

    NativeResult inventoryResult = nativeRun(nativeBinary,
        "migrate-semantic-links",
        "--vault", vault.toString(),
        "--review", review.toString(),
        "--astro", astro.toString(),
        "--report", inventory.toString(),
        "--json");
    assertEquals(1, inventoryResult.exitCode(), inventoryResult.stdout() + inventoryResult.stderr());
    Map<String, Object> inventoryPayload = json(inventoryResult.stdout());
    assertEquals(3, inventoryPayload.get("schemaVersion"));
    assertEquals("migrate-semantic-links", inventoryPayload.get("command"));
    assertEquals("decisions-required", inventoryPayload.get("status"));
    assertEquals(2, ((Map<?, ?>) inventoryPayload.get("summary")).get("occurrences"));

    Path decisions = temp.resolve("native-decisions.json");
    Map<String, Object> inventoryReport = json(Files.readString(inventory));
    Map<?, ?> firstPage = (Map<?, ?>) ((List<?>) inventoryReport.get("pages")).getFirst();
    List<?> pageOccurrences = (List<?>) firstPage.get("occurrences");
    assertEquals(2, pageOccurrences.size(), "native binary predates duplicate-occurrence confirmation");
    Map<String, Object> decisionEntries = new LinkedHashMap<>();
    for (Object rawOccurrence : pageOccurrences) {
      Map<?, ?> occurrence = (Map<?, ?>) rawOccurrence;
      Map<?, ?> span = (Map<?, ?>) occurrence.get("proposedEnSpan");
      assertNotNull(span, "native binary predates proposedEnSpan inventory output");
      decisionEntries.put((String) occurrence.get("occurrenceKey"), Map.of(
          "decision", "confirm",
          "enSpan", Map.of("start", span.get("start"), "end", span.get("end"))));
    }
    Files.writeString(decisions, JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "inventorySha256", inventoryReport.get("inventorySha256"),
        "decisions", decisionEntries)), StandardCharsets.UTF_8);
    NativeResult applyResult = nativeRun(nativeBinary,
        "migrate-semantic-links",
        "--vault", vault.toString(),
        "--review", review.toString(),
        "--astro", astro.toString(),
        "--report", inventory.toString(),
        "--decisions", decisions.toString(),
        "--apply",
        "--json");
    assertEquals(0, applyResult.exitCode(), applyResult.stdout() + applyResult.stderr());
    assertEquals("applied", json(applyResult.stdout()).get("status"));
    writePublicationSource(vault, "page.md", "page", "Page", "See [[Target|target]].");
    writePublicationSource(vault, "target.md", "target", "Target", "Target.");

    NativeResult inspectResult = nativeRun(nativeBinary,
        "inspect-publication",
        "--vault", vault.toString(),
        "--note", "page.md",
        "--review", review.toString(),
        "--json");
    assertEquals(1, inspectResult.exitCode(), inspectResult.stdout() + inspectResult.stderr());
    Map<String, Object> inspectPayload = json(inspectResult.stdout());
    assertEquals(3, inspectPayload.get("schemaVersion"));
    assertEquals("stale", inspectPayload.get("status"));
    assertEquals("missing", inspectPayload.get("pairFreshness"));
    assertEquals("valid", inspectPayload.get("approvedSnapshotState"));
    assertEquals("valid", inspectPayload.get("semanticReferencesState"));
    assertEquals("releasable", inspectPayload.get("releaseState"));

    NativeResult buildResult = nativeRun(nativeBinary,
        "build-from-review",
        "--vault", vault.toString(),
        "--out", astro.toString(),
        "--review", review.toString(),
        "--report", temp.resolve("native-build.md").toString());
    assertEquals(0, buildResult.exitCode(), buildResult.stdout() + buildResult.stderr());
    assertTrue(Files.readString(astro.resolve("src/content/blog/ru/page.md"))
        .contains("[target](/ru/essays/target/)"));
    assertFalse(Files.readString(astro.resolve("src/content/blog/ru/page.md")).contains("ref:"));

    Path classicVault = temp.resolve("classic-vault");
    Path classicReview = temp.resolve("classic-review");
    writeBlogNote(classicVault);
    ManifestEntry classicEntry = currentBlogEntry(classicVault);
    writeBlogReviewEn(classicReview, classicEntry.translationSourceHash(), "generated");
    NativeResult markReviewed = nativeRun(nativeBinary,
        "mark-reviewed",
        "--vault", classicVault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", classicReview.toString(),
        "--jobs", jobs.toString(),
        "--json");
    assertEquals(0, markReviewed.exitCode(), markReviewed.stdout() + markReviewed.stderr());
    assertEquals("ready_to_publish", json(markReviewed.stdout()).get("status"));

    NativeResult prepareBlocked = nativeRun(nativeBinary,
        "prepare",
        "--vault", classicVault.toString(),
        "--note", "missing.md",
        "--review", classicReview.toString(),
        "--jobs", jobs.toString(),
        "--json");
    assertEquals(1, prepareBlocked.exitCode());
    assertEquals("prepare", json(prepareBlocked.stdout()).get("command"));
  }

  private void assertNativeMissingApproval(Path nativeBinary) throws Exception {
    Path vault = temp.resolve("native-missing-approval-vault");
    Path review = temp.resolve("native-missing-approval-review");
    Path astro = writeAstroRoot(temp.resolve("native-missing-approval-astro"));
    Path report = temp.resolve("native-missing-approval.md");
    writePublicationSource(vault, "missing.md", "missing", "Missing", "Missing.");
    writeSemanticCatalog(review, "vault-ref-missing", "missing.md");
    writeCompleteSemanticMode(review, List.of(journalPage(
        "blog", "missing", "vault-ref-missing", "missing.md", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")));

    NativeResult result = nativeRun(nativeBinary,
        "build-from-review",
        "--vault", vault.toString(),
        "--out", astro.toString(),
        "--review", review.toString(),
        "--report", report.toString());

    assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
    String text = Files.readString(report);
    assertTrue(text.contains("Diagnostic code: `missing-approved-snapshot`"), text);
    assertTrue(result.stdout().contains("missing-approved-snapshot"), result.stdout());
  }

  private void assertNativeMigrationIncomplete(Path nativeBinary) throws Exception {
    Path vault = temp.resolve("native-migration-incomplete-vault");
    Path review = temp.resolve("native-migration-incomplete-review");
    Path astro = writeAstroRoot(temp.resolve("native-migration-incomplete-astro"));
    Path report = temp.resolve("native-migration-incomplete.md");
    writePublicationSource(vault, "incomplete.md", "incomplete", "Incomplete", "Incomplete.");
    Path semantic = review.resolve(".semantic-links");
    Files.createDirectories(semantic);
    Files.writeString(
        semantic.resolve("migration-v1.journal.json"),
        "{\"state\":\"installed\"}\n",
        StandardCharsets.UTF_8);

    NativeResult result = nativeRun(nativeBinary,
        "build-from-review",
        "--vault", vault.toString(),
        "--out", astro.toString(),
        "--review", review.toString(),
        "--report", report.toString());

    assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
    String text = Files.readString(report);
    assertTrue(text.contains("Diagnostic code: `migration-incomplete`"), text);
    assertTrue(result.stdout().contains("migration-incomplete"), result.stdout());
  }

  private void assertNativeOrderMismatch(Path nativeBinary) throws Exception {
    Path vault = temp.resolve("native-order-mismatch-vault");
    Path review = temp.resolve("native-order-mismatch-review");
    Path astro = writeAstroRoot(temp.resolve("native-order-mismatch-astro"));
    Path report = temp.resolve("native-order-mismatch.md");
    writePublishedSemanticFixture(vault, review, astro);
    writePublicationSource(vault, "page.md", "page", "Page", "See [[Target|target]].");
    writePublicationSource(vault, "target.md", "target", "Target", "Target.");
    writeCompleteSemanticMode(review, List.of(
        journalPage(
            "blog", "page", "vault-ref-page", "page.md", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
        journalPage(
            "blog", "target", "vault-ref-target", "target.md", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));
    Path references = review.resolve("blog/page/published/references.json");
    Map<String, Object> payload = json(Files.readString(references));
    byte[] russian = approvedMarkdown("page", "ru", "See [target](ref:ref-0001).");
    byte[] english = approvedMarkdown("page", "en", "See [target](ref:ref-0001).");
    Files.write(review.resolve("blog/page/published/ru.md"), russian);
    Files.write(review.resolve("blog/page/published/en.md"), english);
    payload.put("ruSha256", PageReferenceMapCodec.sha256(russian));
    payload.put("enSha256", PageReferenceMapCodec.sha256(english));
    payload.put("order", List.of());
    payload.put("references", Map.of());
    Files.writeString(references, JSON.writeValueAsString(payload), StandardCharsets.UTF_8);

    NativeResult result = nativeRun(nativeBinary,
        "build-from-review",
        "--vault", vault.toString(),
        "--out", astro.toString(),
        "--review", review.toString(),
        "--report", report.toString());

    assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
    String text = Files.readString(report);
    assertTrue(text.contains("Diagnostic code: `invalid-approved-snapshot`"), text);
    assertTrue(text.contains("reference order must match both Russian and English bodies"), text);
    assertTrue(text.contains("reference-order-mismatch"), text);
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

  private static void writePublishedSemanticFixture(Path vault, Path review, Path astro) throws Exception {
    writeRawNote(vault, "page.md", "See [[Target|target]] and again [[Target|target]].");
    writeRawNote(vault, "target.md", "Target.");
    writeSemanticCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeAstroRoute(astro, "src/content/blog/ru/target.md", "vault-ref-target", "/ru/essays/target/");
    writeAstroRoute(astro, "src/content/blog/en/target.md", "vault-ref-target", "/en/essays/target/");
    writeApprovedPublishedPairWithDuplicateOccurrences(review, "page", "page.md", "vault-ref-page",
        "See [target](/ru/essays/target/) and again [target](/ru/essays/target/).",
        "See [target](/en/essays/target/) and again [target](/en/essays/target/).");
    writeApprovedPublishedPair(review, "target", "target.md", "vault-ref-target",
        "Target.",
        "Target.");
  }

  private static void writeApprovedPublishedPairWithDuplicateOccurrences(
      Path reviewRoot,
      String publicId,
      String sourcePath,
      String pageRef,
      String ru,
      String en) throws Exception {
    Path published = reviewRoot.resolve("blog").resolve(publicId).resolve("published");
    Files.createDirectories(published);
    byte[] russian = approvedMarkdown(publicId, "ru", ru);
    byte[] english = approvedMarkdown(publicId, "en", en);
    Files.write(published.resolve("ru.md"), russian);
    Files.write(published.resolve("en.md"), english);
    Map<String, Object> occurrence = Map.of(
        "targetRef", "vault-ref-target",
        "authoredTarget", "Target",
        "heading", "",
        "label", "target");
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", pageRef,
        "sourcePath", sourcePath,
        "ruSha256", PageReferenceMapCodec.sha256(russian),
        "enSha256", PageReferenceMapCodec.sha256(english),
        "order", List.of("ref-0001", "ref-0002"),
        "references", Map.of("ref-0001", occurrence, "ref-0002", occurrence))), StandardCharsets.UTF_8);
  }

  private static void writePublicationSource(
      Path vault,
      String path,
      String publicId,
      String title,
      String body) throws Exception {
    writeRawNote(vault, path, """
        ---
        publish: true
        publicId: %s
        publicCollection: blog
        publicContentType: essay
        title: %s
        description: %s.
        ---
        %s
        """.formatted(publicId, title, title, body));
  }

  private static void writeRawNote(Path vault, String path, String body) throws Exception {
    Path target = vault.resolve(path);
    Files.createDirectories(target.getParent() == null ? vault : target.getParent());
    Files.writeString(target, body, StandardCharsets.UTF_8);
  }

  private static void writeSemanticCatalog(Path reviewRoot, String... refsAndPaths) throws Exception {
    LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
    for (int i = 0; i < refsAndPaths.length; i += 2) {
      String pageRef = refsAndPaths[i];
      String currentPath = refsAndPaths[i + 1];
      String title = semanticCatalogTitle(currentPath);
      entries.put(pageRef, Map.of(
          "currentPath", currentPath,
          "stableNoteId", title,
          "title", title,
          "aliases", List.of(),
          "previousPaths", List.of(),
          "state", "active"));
    }
    Path path = reviewRoot.resolve(".semantic-links/catalog-v1.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "entries", entries)), StandardCharsets.UTF_8);
  }

  private static JournalPageFixture journalPage(
      String collection,
      String publicId,
      String pageRef,
      String sourcePath,
      String stagedSha256) {
    return new JournalPageFixture(collection, publicId, pageRef, sourcePath, stagedSha256);
  }

  private static void writeCompleteSemanticMode(
      Path reviewRoot,
      List<JournalPageFixture> pages) throws Exception {
    Path semantic = reviewRoot.resolve(".semantic-links");
    Files.createDirectories(semantic);
    String inventory = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String catalog = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    Files.writeString(semantic.resolve("schema-v1.active.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "inventorySha256", inventory,
        "catalogSha256", catalog,
        "activatedAt", "2026-07-30T00:00:00Z")), StandardCharsets.UTF_8);

    ArrayList<Map<String, Object>> journalPages = new ArrayList<>();
    for (JournalPageFixture page : pages) {
      journalPages.add(Map.of(
          "collection", page.collection(),
          "publicId", page.publicId(),
          "pageRef", page.pageRef(),
          "sourcePath", page.sourcePath(),
          "state", "complete",
          "stagedSha256", page.stagedSha256(),
          "published", page.collection() + "/" + page.publicId() + "/published",
          "staged", ".semantic-links/staging-v1/" + page.collection() + "/" + page.publicId() + "/published",
          "displaced", ".semantic-links/recovery-v1/" + page.collection() + "/" + page.publicId() + "/published"));
    }
    Files.writeString(semantic.resolve("migration-v1.journal.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "state", "complete",
        "inventorySha256", inventory,
        "catalogSha256", catalog,
        "catalogState", "complete",
        "catalogPublished", ".semantic-links/catalog-v1.json",
        "catalogStaged", ".semantic-links/staging-v1/catalog-v1.json",
        "catalogDisplaced", ".semantic-links/recovery-v1/catalog-v1.json",
        "recoveryRoot", ".semantic-links/recovery-v1",
        "pages", journalPages)), StandardCharsets.UTF_8);
  }

  private static String semanticCatalogTitle(String path) {
    String stem = path.replace(".md", "");
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) {
      stem = stem.substring(slash + 1);
    }
    return stem.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + stem.substring(1);
  }

  private static void writeApprovedPublishedPair(
      Path reviewRoot,
      String publicId,
      String sourcePath,
      String pageRef,
      String ru,
      String en) throws Exception {
    Path published = reviewRoot.resolve("blog").resolve(publicId).resolve("published");
    Files.createDirectories(published);
    byte[] russian = approvedMarkdown(publicId, "ru", ru);
    byte[] english = approvedMarkdown(publicId, "en", en);
    boolean linksTarget = ru.contains("target]");
    Files.write(published.resolve("ru.md"), russian);
    Files.write(published.resolve("en.md"), english);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", pageRef,
        "sourcePath", sourcePath,
        "ruSha256", PageReferenceMapCodec.sha256(russian),
        "enSha256", PageReferenceMapCodec.sha256(english),
        "order", linksTarget ? List.of("ref-0001") : List.of(),
        "references", linksTarget
            ? Map.of("ref-0001", Map.of(
                "targetRef", "vault-ref-target",
                "authoredTarget", "Target",
                "heading", "",
                "label", "target"))
            : Map.of())), StandardCharsets.UTF_8);
  }

  private static byte[] approvedMarkdown(String publicId, String language, String body) {
    return """
        ---
        id: %s
        language: %s
        reviewType: essay
        route: /%s/essays/%s/
        targetPath: src/content/blog/%s/%s.md
        title: %s
        description: %s.
        %s---
        %s
        """.formatted(
            publicId,
            language,
            language,
            publicId,
            language,
            publicId,
            publicId,
            publicId,
            "en".equals(language) ? "translationStatus: reviewed\n" : "",
            body).getBytes(StandardCharsets.UTF_8);
  }

  private static void writeAstroRoute(
      Path astro,
      String path,
      String pageRef,
      String route) throws Exception {
    Path target = astro.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, """
        ---
        pageRef: %s
        route: %s
        ---
        Body.
        """.formatted(pageRef, route), StandardCharsets.UTF_8);
  }

  private static Path writeBlogNote(Path vault) throws Exception {
    Path note = vault.resolve("anywhere/Essay.md");
    Files.createDirectories(note.getParent());
    Files.writeString(note, """
        ---
        title: Essay
        publish: true
        publicId: essay
        publicCollection: blog
        publicContentType: essay
        description: Essay.
        ---
        Text.
        """, StandardCharsets.UTF_8);
    return note;
  }

  private static void writeBlogReviewEn(Path review, String sourceHash, String status) throws Exception {
    Path english = review.resolve("blog/essay/en.md");
    Files.createDirectories(english.getParent());
    Files.writeString(english, """
        ---
        sourceHash: %s
        translationStatus: %s
        translatedAt: 2026-07-17
        translationProfile: native-parity-fixture-v1
        title: English title
        description: English description.
        ---
        English text.
        """.formatted(sourceHash, status), StandardCharsets.UTF_8);
  }

  private static Map<String, Object> json(String text) throws Exception {
    return JSON.readValue(
        text,
        new TypeReference<LinkedHashMap<String, Object>>() { });
  }

  private static NativeResult nativeRun(Path binary, String... args) throws Exception {
    ArrayList<String> command = new ArrayList<>();
    command.add(binary.toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command)
        .directory(Path.of("").toAbsolutePath().normalize().toFile())
        .start();
    boolean completed = process.waitFor(60, TimeUnit.SECONDS);
    if (!completed) {
      process.destroyForcibly();
      throw new AssertionError("native command timed out: " + command);
    }
    return new NativeResult(
        process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private record NativeResult(int exitCode, String stdout, String stderr) { }

  private record JournalPageFixture(
      String collection,
      String publicId,
      String pageRef,
      String sourcePath,
      String stagedSha256) { }

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
