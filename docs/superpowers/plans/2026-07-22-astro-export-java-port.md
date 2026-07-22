# Astro Export Java Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reimplement the current Python `astro-export` toolchain in Java, keep the existing CLI and publication behavior, port the Python test suite to JUnit, and produce a GraalVM native macOS executable with Maven.

**Architecture:** Build one Maven Java application with small packages that mirror the existing exporter boundaries: CLI orchestration, source selection, publication validation, markdown/link processing, manifest normalization, translation review, guarded workflow writes, asset resolution, writer transactions, reports, and external process adapters. Keep the existing Python exporter as the behavioral oracle until the Java CLI passes parity tests against the migrated fixtures and a live dry-run sample.

**Tech Stack:** Java 21, Maven, GraalVM Native Image, picocli, Jackson, SnakeYAML Engine, JNA for platform atomic-exchange syscalls, JUnit Jupiter.

## Global Constraints

- Source behavior comes from `/Users/eugene/Documents/personal-wiki/tools/astro-export`.
- The Python source tree is read-only during the Java port unless the operator explicitly asks for source changes.
- The Java command must keep the executable name `astro-export`.
- The Java CLI must preserve the current commands, flags, stdout/stderr shape, exit codes, JSON bridge schema, report sections, and managed-output boundaries.
- `prepare`, `inspect-publication`, `mark-reviewed`, and `refresh-publication-queue` must emit exactly one JSON object to stdout when `--json` is supplied.
- Write mode must own only `src/content`, `src/data/pages`, and `public/assets/vault` under the Astro checkout.
- Dry-run must never write managed Astro output, run the Astro content gate, invoke Codex, or alter review files.
- Default export and `build-from-review` must not create, synchronize, or rewrite `en.md`.
- Guarded source/review updates must use atomic path exchange on macOS and Linux; unsupported filesystems or operating systems must fail closed.
- Native image build must produce a macOS executable from Maven with `mvn -Pnative native:compile`.
- The Maven test suite must include the migrated Python behavior tests before the Python implementation is retired.

---

## Source Audit

Current source snapshot used for this plan:

- Python package: `/Users/eugene/Documents/personal-wiki/tools/astro-export/src/astro_export`
- Python tests: `/Users/eugene/Documents/personal-wiki/tools/astro-export/tests`
- Operator scripts: `/Users/eugene/Documents/personal-wiki/tools/astro-export/scripts`
- Review workspace: `/Users/eugene/Documents/personal-wiki/tools/astro-export/review`
- Templates: `/Users/eugene/Documents/personal-wiki/tools/astro-export/templates`
- Maven target repository: `/Users/eugene/Dev/astro-export-java`

Observed source size:

- 22 Python source files including empty `__init__.py`.
- 19 Python test files.
- 385 Python test functions before pytest parameter expansion.
- 23,608 total lines across `src/astro_export/*.py` and `tests/test_*.py`.

Current high-risk areas:

- `cli.py` is 1,600 lines and owns most bridge workflow orchestration.
- `prepare.py` is 1,590 lines and owns bounded translation job setup.
- `writer.py` is 1,322 lines and owns staged output, rollback, recovery, and tree hashing.
- `manifest.py` is 1,061 lines and owns normalization, public-link processing, collection routing, and source hashes.
- `workflow_state.py` owns frontmatter-preserving guarded edits and platform atomic path exchange.
- The source tree had uncommitted Python/review changes when audited. Treat the live tree as the current source of truth and rerun `git status --short /Users/eugene/Documents/personal-wiki/tools/astro-export` before implementation starts.

## File Structure

Create this Java project structure:

- `pom.xml`: Maven build, dependency pins, test plugins, native-image profile.
- `README.md`: Java exporter usage, Maven test/native commands, compatibility with current operator scripts.
- `docs/source-map.md`: Python-to-Java module map and migrated test inventory.
- `docs/superpowers/plans/2026-07-22-astro-export-java-port.md`: this plan.
- `src/main/java/dev/eugene/astroexport/AstroExportApp.java`: process entry point.
- `src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`: picocli root command and subcommand wiring.
- `src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java`: stable JSON bridge response model.
- `src/main/java/dev/eugene/astroexport/cli/CommandServices.java`: dependency bundle for filesystem clocks, process runner, Codex runner, and writer callbacks.
- `src/main/java/dev/eugene/astroexport/discovery/PublicationDiscovery.java`: `rg`-backed candidate discovery.
- `src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterDocument.java`: markdown frontmatter split, parse, and emit.
- `src/main/java/dev/eugene/astroexport/frontmatter/WorkflowFrontmatterEditor.java`: line-preserving workflow field rewrite.
- `src/main/java/dev/eugene/astroexport/model/Note.java`: selected vault note model.
- `src/main/java/dev/eugene/astroexport/model/PublicationKind.java`: supported collection/content type pairs and publication requirements.
- `src/main/java/dev/eugene/astroexport/model/ManifestEntry.java`: normalized output record.
- `src/main/java/dev/eugene/astroexport/model/ManifestResult.java`: bilingual manifest, link, asset, and translation-use aggregate.
- `src/main/java/dev/eugene/astroexport/validation/PublicationValidator.java`: preflight and publication frontmatter validation.
- `src/main/java/dev/eugene/astroexport/markdown/MarkdownScanner.java`: protected-context scanning and section/list extraction.
- `src/main/java/dev/eugene/astroexport/links/LinkProcessor.java`: public wikilink resolution, stripping, transclusion blocking, editorial rich-text tokenization.
- `src/main/java/dev/eugene/astroexport/assets/AssetResolver.java`: deny-by-default asset validation and content-addressed destination planning.
- `src/main/java/dev/eugene/astroexport/manifest/ManifestBuilder.java`: RU manifest construction and collection-specific normalization.
- `src/main/java/dev/eugene/astroexport/editorial/EditorialParser.java`: strict editorial page grammar parser.
- `src/main/java/dev/eugene/astroexport/translation/TranslationProjection.java`: translatable projection and translation-source hash logic.
- `src/main/java/dev/eugene/astroexport/translation/TranslationValidator.java`: review patch loading, structural merge, freshness validation, EN manifest generation.
- `src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`: `review/<collection>/<publicId>/{ru,en}.md` serialization and parsing.
- `src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java`: bounded Codex job preparation and candidate installation.
- `src/main/java/dev/eugene/astroexport/workflow/WorkflowStateService.java`: guarded source and review state transitions.
- `src/main/java/dev/eugene/astroexport/fs/AtomicExchange.java`: macOS/Linux atomic path exchange abstraction.
- `src/main/java/dev/eugene/astroexport/fs/SiteWriter.java`: staged managed-tree serialization and rollback.
- `src/main/java/dev/eugene/astroexport/report/ReportBuilder.java`: dry-run/write/blocked report rendering.
- `src/main/java/dev/eugene/astroexport/process/ProcessRunner.java`: subprocess boundary for `rg`, Codex, and Astro content gate.
- `src/main/resources/templates/pages/ru/search.json`: copied RU search template.
- `src/main/resources/templates/pages/en/search.json`: copied EN search template.
- `src/main/resources/META-INF/native-image/dev.eugene/astro-export/resource-config.json`: native-image resource includes.
- `src/test/java/dev/eugene/astroexport/**`: JUnit tests ported from each Python `tests/test_*.py`.
- `scripts/export-site.sh`: wrapper that executes the Java binary or `mvn exec:java` in development.
- `scripts/build-from-review.sh`: wrapper for `astro-export build-from-review`.
- `scripts/build-astro-site.sh`: wrapper for export plus Astro `npm run build`.
- `scripts/migrate-overrides.sh`: wrapper for `astro-export migrate-overrides`.

## Dependency Pins

Use these Maven coordinates as the first implementation pins:

- `info.picocli:picocli:4.7.7`
- `com.fasterxml.jackson.core:jackson-databind:2.22.0`
- `org.snakeyaml:snakeyaml-engine:3.0.1`
- `net.java.dev.jna:jna:5.19.0`
- `org.junit.jupiter:junit-jupiter:6.1.0`
- `org.graalvm.buildtools:native-maven-plugin:1.1.4`

Recheck these pins only through Maven Central or official project documentation before changing them.

## Test Migration Map

Port the Python tests into these JUnit classes:

- `test_discovery.py` -> `PublicationDiscoveryTest`
- `test_select.py` -> `PublicationSelectionTest`
- `test_publication_contract.py` -> `PublicationContractTest`
- `test_publication_validation.py` -> `PublicationValidatorTest`
- `test_normalize.py` -> `MarkdownNormalizationTest`
- `test_links.py` -> `LinkProcessorTest`
- `test_assets.py` -> `AssetResolverTest`
- `test_editorial.py` -> `EditorialParserTest`
- `test_manifest.py` -> `ManifestBuilderTest`
- `test_translation.py` -> `TranslationValidatorTest`
- `test_translation_projection.py` coverage currently lives inside `test_translation.py` and `test_manifest.py`; port it into `TranslationProjectionTest`.
- `test_review_workspace.py` -> `ReviewWorkspaceTest`
- `test_ru_cache.py` -> `RuCacheTest`
- `test_preflight.py` -> `PreflightTest`
- `test_prepare.py` -> `PrepareWorkflowTest`
- `test_workflow_state.py` -> `WorkflowStateServiceTest`
- `test_writer.py` -> `SiteWriterTest`
- `test_report.py` -> `ReportBuilderTest`
- `test_codex_runner.py` -> `CodexRunnerTest`
- `test_cli.py` -> `AstroExportCommandTest`

Each task below ports the relevant tests before or alongside the Java implementation. A task is not complete until the named JUnit class passes and the migrated assertions cover the same edge cases as the Python test names in the source file.

---

### Task 1: Maven Skeleton And Native Smoke Test

**Files:**
- Create: `pom.xml`
- Create: `README.md`
- Create: `src/main/java/dev/eugene/astroexport/AstroExportApp.java`
- Create: `src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Create: `src/test/java/dev/eugene/astroexport/cli/AstroExportCommandSmokeTest.java`
- Create: `src/main/resources/META-INF/native-image/dev.eugene/astro-export/resource-config.json`

**Interfaces:**
- Produces: `public static void main(String[] args)` in `AstroExportApp`
- Produces: picocli command named `astro-export`
- Produces: Maven commands `mvn test` and `mvn -Pnative native:compile`

- [ ] **Step 1: Create Maven project files**

Create `pom.xml` with this initial content:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.eugene</groupId>
  <artifactId>astro-export</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>astro-export</name>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <picocli.version>4.7.7</picocli.version>
    <jackson.version>2.22.0</jackson.version>
    <snakeyaml-engine.version>3.0.1</snakeyaml-engine.version>
    <jna.version>5.19.0</jna.version>
    <junit.version>6.1.0</junit.version>
    <native.maven.plugin.version>1.1.4</native.maven.plugin.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>${picocli.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.snakeyaml</groupId>
      <artifactId>snakeyaml-engine</artifactId>
      <version>${snakeyaml-engine.version}</version>
    </dependency>
    <dependency>
      <groupId>net.java.dev.jna</groupId>
      <artifactId>jna</artifactId>
      <version>${jna.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.3</version>
        <configuration>
          <useModulePath>false</useModulePath>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.5.0</version>
        <configuration>
          <mainClass>dev.eugene.astroexport.AstroExportApp</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <profile>
      <id>native</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <version>${native.maven.plugin.version}</version>
            <extensions>true</extensions>
            <configuration>
              <imageName>astro-export</imageName>
              <mainClass>dev.eugene.astroexport.AstroExportApp</mainClass>
              <buildArgs>
                <buildArg>--no-fallback</buildArg>
                <buildArg>-H:+ReportExceptionStackTraces</buildArg>
              </buildArgs>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
</project>
```

- [ ] **Step 2: Create the CLI entry point**

Create `src/main/java/dev/eugene/astroexport/AstroExportApp.java`:

```java
package dev.eugene.astroexport;

import dev.eugene.astroexport.cli.AstroExportCommand;
import picocli.CommandLine;

public final class AstroExportApp {
  private AstroExportApp() {
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new AstroExportCommand()).execute(args);
    System.exit(exitCode);
  }
}
```

Create `src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`:

```java
package dev.eugene.astroexport.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
    name = "astro-export",
    mixinStandardHelpOptions = true,
    description = "Export explicitly published Obsidian notes into Astro source trees.")
public final class AstroExportCommand implements Callable<Integer> {
  @Override
  public Integer call() {
    return 0;
  }
}
```

- [ ] **Step 3: Add native resource config**

Create `src/main/resources/META-INF/native-image/dev.eugene/astro-export/resource-config.json`:

```json
{
  "resources": {
    "includes": [
      { "pattern": "templates/pages/ru/search\\.json" },
      { "pattern": "templates/pages/en/search\\.json" }
    ]
  }
}
```

- [ ] **Step 4: Add smoke test**

Create `src/test/java/dev/eugene/astroexport/cli/AstroExportCommandSmokeTest.java`:

```java
package dev.eugene.astroexport.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class AstroExportCommandSmokeTest {
  @Test
  void rootCommandExitsZeroBeforeBehaviorIsAdded() {
    int exitCode = new CommandLine(new AstroExportCommand()).execute();
    assertEquals(0, exitCode);
  }
}
```

- [ ] **Step 5: Run JVM tests**

Run:

```bash
mvn test
```

Expected: build succeeds and runs `AstroExportCommandSmokeTest`.

- [ ] **Step 6: Build native binary**

Run with GraalVM active:

```bash
mvn -Pnative native:compile
```

Expected: `target/astro-export` exists and `target/astro-export --help` exits 0.

- [ ] **Step 7: Commit**

```bash
git add pom.xml README.md src
git commit -m "build: bootstrap Java astro exporter"
```

---

### Task 2: Source Map And Test Porting Harness

**Files:**
- Create: `docs/source-map.md`
- Create: `src/test/java/dev/eugene/astroexport/testsupport/FixtureFiles.java`
- Create: `src/test/java/dev/eugene/astroexport/testsupport/CommandFixture.java`
- Create: `src/test/java/dev/eugene/astroexport/testsupport/GoldenAssertions.java`

**Interfaces:**
- Produces: `FixtureFiles.write(Path root, String relativePath, String content)`
- Produces: `FixtureFiles.read(Path root, String relativePath)`
- Produces: `CommandFixture.run(String... args)`
- Produces: `GoldenAssertions.assertTreeHash(Path root, String expectedHash)`

- [ ] **Step 1: Create the Python-to-Java source map**

Create `docs/source-map.md` with:

```markdown
# Astro Export Python To Java Source Map

## Source Modules

| Python file | Java target |
| --- | --- |
| `src/astro_export/cli.py` | `cli/AstroExportCommand.java`, `cli/BridgeResponse.java`, `prepare/PrepareWorkflow.java`, `workflow/WorkflowStateService.java` |
| `src/astro_export/select.py` | `model/Note.java`, `discovery/PublicationDiscovery.java` |
| `src/astro_export/discovery.py` | `discovery/PublicationDiscovery.java`, `process/ProcessRunner.java` |
| `src/astro_export/publication_contract.py` | `model/PublicationKind.java` |
| `src/astro_export/publication_validation.py` | `validation/PublicationValidator.java` |
| `src/astro_export/preflight.py` | `validation/PreflightService.java` |
| `src/astro_export/normalize.py` | `markdown/MarkdownScanner.java`, `manifest/ManifestBuilder.java` |
| `src/astro_export/links.py` | `links/LinkProcessor.java` |
| `src/astro_export/assets.py` | `assets/AssetResolver.java` |
| `src/astro_export/editorial.py` | `editorial/EditorialParser.java` |
| `src/astro_export/manifest.py` | `manifest/ManifestBuilder.java`, `model/ManifestEntry.java`, `model/ManifestResult.java` |
| `src/astro_export/translation_projection.py` | `translation/TranslationProjection.java` |
| `src/astro_export/translation.py` | `translation/TranslationValidator.java` |
| `src/astro_export/review_workspace.py` | `review/ReviewWorkspace.java` |
| `src/astro_export/ru_cache.py` | `review/RuCache.java` |
| `src/astro_export/prepare.py` | `prepare/PrepareWorkflow.java` |
| `src/astro_export/translation_agent.py` | `prepare/TranslationAgent.java` |
| `src/astro_export/codex_runner.py` | `process/CodexRunner.java` |
| `src/astro_export/workflow_state.py` | `frontmatter/WorkflowFrontmatterEditor.java`, `workflow/WorkflowStateService.java`, `fs/AtomicExchange.java` |
| `src/astro_export/writer.py` | `fs/SiteWriter.java` |
| `src/astro_export/report.py` | `report/ReportBuilder.java` |

## Test Modules

The Java test suite must port every behavior assertion from the Python test file with the matching class listed in the implementation plan.
```

- [ ] **Step 2: Create fixture helpers**

Create `src/test/java/dev/eugene/astroexport/testsupport/FixtureFiles.java`:

```java
package dev.eugene.astroexport.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureFiles {
  private FixtureFiles() {
  }

  public static Path write(Path root, String relativePath, String content) throws IOException {
    Path target = root.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  public static String read(Path root, String relativePath) throws IOException {
    return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
  }
}
```

Create `src/test/java/dev/eugene/astroexport/testsupport/CommandFixture.java`:

```java
package dev.eugene.astroexport.testsupport;

import dev.eugene.astroexport.cli.AstroExportCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

public final class CommandFixture {
  public record Result(int exitCode, String stdout, String stderr) {
  }

  public Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandLine commandLine = new CommandLine(new AstroExportCommand());
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(args);
    return new Result(
        exitCode,
        out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }
}
```

- [ ] **Step 3: Run tests**

Run:

```bash
mvn test
```

Expected: smoke test and helper compilation pass.

- [ ] **Step 4: Commit**

```bash
git add docs src/test
git commit -m "test: add porting harness and source map"
```

---

### Task 3: Frontmatter Parsing, Discovery, And Selection

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/frontmatter/FrontmatterDocument.java`
- Create: `src/main/java/dev/eugene/astroexport/discovery/PublicationDiscovery.java`
- Create: `src/main/java/dev/eugene/astroexport/model/Note.java`
- Create: `src/main/java/dev/eugene/astroexport/model/SelectionResult.java`
- Create: `src/test/java/dev/eugene/astroexport/discovery/PublicationDiscoveryTest.java`
- Create: `src/test/java/dev/eugene/astroexport/discovery/PublicationSelectionTest.java`

**Interfaces:**
- Produces: `FrontmatterDocument.parse(Path path, String vaultPath, String markdown)`
- Produces: `PublicationDiscovery.findCandidates(Path vault)`
- Produces: `SelectionResult select(Path vault)`

- [ ] **Step 1: Port discovery and selection tests**

Port these Python behaviors first:

```text
test_publish_discovery_passes_explicit_current_directory_to_rg
test_select_includes_exact_publish_true_yaml_boolean
test_select_ignores_body_match_without_yaml_boolean
test_select_skips_hidden_paths
test_select_records_confirmed_publish_notes_that_do_not_qualify
```

The first Java tests should use a fake `ProcessRunner` that records the exact `rg` command and working directory. The expected command is:

```text
rg --files-with-matches --glob *.md --glob !.* --glob !**/.* ^publish:[ \t]+true[ \t]*$
```

- [ ] **Step 2: Implement frontmatter split**

Implement `FrontmatterDocument` so it handles only markdown documents whose first line is `---`, parses YAML until the next standalone `---`, and returns an empty metadata map when no frontmatter block exists.

Required Java record:

```java
package dev.eugene.astroexport.frontmatter;

import java.nio.file.Path;
import java.util.Map;

public record FrontmatterDocument(
    Path path,
    String vaultPath,
    Map<String, Object> metadata,
    String body) {
}
```

- [ ] **Step 3: Implement selected note model**

Required Java record:

```java
package dev.eugene.astroexport.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record Note(
    Path path,
    String vaultPath,
    String title,
    Map<String, Object> frontmatter,
    String body,
    boolean publish,
    String publicId,
    String publicCollection,
    String publicContentType,
    List<String> aliases) {
}
```

- [ ] **Step 4: Implement discovery with `rg`**

`PublicationDiscovery.findCandidates(Path vault)` must run `rg` in the vault directory, reject hidden paths after process output is read, and return vault-relative POSIX paths sorted in `rg` output order.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
mvn test -Dtest=PublicationDiscoveryTest,PublicationSelectionTest
```

Expected: all selection tests pass and no filesystem writes occur outside JUnit temp directories.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/frontmatter src/main/java/dev/eugene/astroexport/discovery src/main/java/dev/eugene/astroexport/model src/test/java/dev/eugene/astroexport/discovery
git commit -m "feat: port publication discovery and selection"
```

---

### Task 4: Publication Contract And Preflight Validation

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/model/PublicationKind.java`
- Create: `src/main/java/dev/eugene/astroexport/model/PublicationRequirement.java`
- Create: `src/main/java/dev/eugene/astroexport/validation/PublicationDiagnostic.java`
- Create: `src/main/java/dev/eugene/astroexport/validation/PublicationValidator.java`
- Create: `src/main/java/dev/eugene/astroexport/validation/PreflightService.java`
- Create: `src/test/java/dev/eugene/astroexport/validation/PublicationContractTest.java`
- Create: `src/test/java/dev/eugene/astroexport/validation/PublicationValidatorTest.java`
- Create: `src/test/java/dev/eugene/astroexport/validation/PreflightTest.java`

**Interfaces:**
- Produces: `PublicationKind.requirementsFor(String collection, String contentType)`
- Produces: `PublicationValidator.validate(Note note)`
- Produces: `PreflightService.preflight(Path vault, String notePath)`

- [ ] **Step 1: Encode the supported publication pairs**

Create an enum or immutable registry with exactly these pairs:

```text
blog: essay, claim, note
bibliography: book
music: album
concepts: concept
editorial: curated_page
```

- [ ] **Step 2: Port contract and validation tests**

Port all tests from:

```text
tests/test_publication_contract.py
tests/test_publication_validation.py
tests/test_preflight.py
```

Keep the precise diagnostics from Python, including supported collection lists and field names.

- [ ] **Step 3: Implement requirement validators**

Implement these validator names as code constants, not strings scattered through tests:

```java
public enum RequirementValidator {
  BOOLEAN_TRUE,
  NON_EMPTY_STRING,
  NON_EMPTY_STRING_OR_LIST,
  SUPPORTED_COLLECTION,
  SUPPORTED_CONTENT_TYPE,
  SUPPORTED_EDITORIAL_PAGE,
  CONCEPT_DEFINITION_SECTION
}
```

- [ ] **Step 4: Implement preflight note confinement**

`PreflightService.preflight` must accept only a vault-relative Markdown path, reject absolute paths and traversal, load only that note, validate publication metadata, and return diagnostics without scanning the whole vault.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
mvn test -Dtest=PublicationContractTest,PublicationValidatorTest,PreflightTest
```

Expected: all validation tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/model src/main/java/dev/eugene/astroexport/validation src/test/java/dev/eugene/astroexport/validation
git commit -m "feat: port publication contract validation"
```

---

### Task 5: Markdown Protected Contexts And Link Processing

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/markdown/MarkdownScanner.java`
- Create: `src/main/java/dev/eugene/astroexport/links/PublicLink.java`
- Create: `src/main/java/dev/eugene/astroexport/links/ManifestLink.java`
- Create: `src/main/java/dev/eugene/astroexport/links/LinkProcessor.java`
- Create: `src/test/java/dev/eugene/astroexport/markdown/MarkdownNormalizationTest.java`
- Create: `src/test/java/dev/eugene/astroexport/links/LinkProcessorTest.java`

**Interfaces:**
- Produces: `MarkdownScanner.section(String body, String heading)`
- Produces: `MarkdownScanner.listItems(String body, String heading)`
- Produces: `MarkdownScanner.stripObsidianComments(String body)`
- Produces: `LinkProcessor.processLinks(Note note, Collection<Note> selectedNotes)`
- Produces: `LinkProcessor.tokenizeEditorialText(String source, Collection<Note> selectedNotes)`

- [ ] **Step 1: Port normalization tests**

Port every test in `tests/test_normalize.py`, including headings inside fenced code, HTML comments, Obsidian comments, inline code, raw `<pre>` blocks, carriage-return line endings, and timestamp-derived dates.

- [ ] **Step 2: Port link tests**

Port every test in `tests/test_links.py`, including:

```text
published wikilink route retention
private wikilink label stripping
path-qualified private target basename behavior
unpublished transclusion blocker
asset embed collection
protected context exclusions
editorial rich-text tokenization
ambiguous alias blocking
```

- [ ] **Step 3: Implement the scanner as one reusable state machine**

`MarkdownScanner` must return protected spans for fenced code, inline code, HTML comments, Obsidian comments, raw `<pre>` blocks, and escaped wikilinks. `section`, `listItems`, link replacement, asset collection, and editorial tokenization must call this scanner instead of maintaining separate regular-expression skip logic.

- [ ] **Step 4: Implement public-link index precedence**

Resolution precedence must be:

```text
exact vault path
publicId
filename stem after internal timestamp removal
frontmatter title
aliases
```

Ambiguous title or alias matches must throw a manifest validation error instead of choosing a target.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
mvn test -Dtest=MarkdownNormalizationTest,LinkProcessorTest
```

Expected: all markdown and link tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/markdown src/main/java/dev/eugene/astroexport/links src/test/java/dev/eugene/astroexport/markdown src/test/java/dev/eugene/astroexport/links
git commit -m "feat: port markdown and public link processing"
```

---

### Task 6: Asset Resolver

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/assets/AssetResolver.java`
- Create: `src/main/java/dev/eugene/astroexport/assets/ResolvedAsset.java`
- Create: `src/test/java/dev/eugene/astroexport/assets/AssetResolverTest.java`

**Interfaces:**
- Produces: `AssetResolver.resolveAssets(Path vault, List<String> references)`
- Produces: `AssetResolver.rewriteAssetEmbeds(String body, Collection<ResolvedAsset> allowlist)`

- [ ] **Step 1: Port asset tests**

Port every test in `tests/test_assets.py`, including invalid absolute paths, Windows UNC paths, traversal, hidden path components, ambiguous basenames, symlink escapes, extension-family compatibility, content-addressed names, repeated source byte caching, and EN-only asset blockers.

- [ ] **Step 2: Implement validated references**

Accept only POSIX-style relative references with no hidden components and these suffixes:

```text
.png .jpg .jpeg .gif .svg .webp .mp3 .mp4
```

- [ ] **Step 3: Implement content-addressed destinations**

Destination format:

```text
/assets/vault/<sha256><normalized-extension>
```

`.jpg` and `.jpeg` with identical bytes must share the canonical `.jpg` destination. Identical bytes with incompatible suffix families must block the write.

- [ ] **Step 4: Run targeted tests**

Run:

```bash
mvn test -Dtest=AssetResolverTest
```

Expected: all asset tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/assets src/test/java/dev/eugene/astroexport/assets
git commit -m "feat: port asset resolution"
```

---

### Task 7: Manifest Builder And Editorial Parser

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/model/ManifestEntry.java`
- Create: `src/main/java/dev/eugene/astroexport/model/ManifestResult.java`
- Create: `src/main/java/dev/eugene/astroexport/manifest/ManifestBuilder.java`
- Create: `src/main/java/dev/eugene/astroexport/editorial/EditorialParser.java`
- Create: `src/test/java/dev/eugene/astroexport/manifest/ManifestBuilderTest.java`
- Create: `src/test/java/dev/eugene/astroexport/editorial/EditorialParserTest.java`

**Interfaces:**
- Produces: `ManifestBuilder.buildRussianManifest(SelectionResult selection)`
- Produces: `EditorialParser.normalize(String sourcePath, String publicId, Map<String,Object> frontmatter, String body, Map<String,Object> common)`

- [ ] **Step 1: Port editorial parser tests**

Port every test in `tests/test_editorial.py`, including complete page shapes for `home`, `essays`, `work`, `notes`, `music`, `library`, `concepts`, `now`, and `about`, optional showcase behavior, malformed nested shapes, public-searchable boolean checks, and exact target-field diagnostics.

- [ ] **Step 2: Port manifest tests in behavior groups**

Port `tests/test_manifest.py` in this order:

```text
concept definition extraction and H1 stripping
unsupported publication pair blockers
workflow frontmatter neutrality
editorial link/reference filtering
blog, music, bibliography, concepts, editorial normalization
body sanitation before hashing
public wikilink conversion and stripped private-link reporting
unpublished transclusion blocking
date validation
typed common-field validation
ambiguous title/alias blocking
bilingual manifest ordering
```

- [ ] **Step 3: Implement common metadata normalization**

`ManifestBuilder` must emit common fields:

```text
id
title
publish
description
topics
tags
aliases
links
language
sourceLanguage
translationStatus
date
updated
cover
status
foundational
readTime
sourceHash
```

Only include optional fields when source metadata provides them after validation.

- [ ] **Step 4: Implement collection-specific normalization**

Required routes and targets:

```text
blog -> src/content/blog/ru/<publicId>.md, /ru/essays|claims|notes/<publicId>/
bibliography -> src/content/bibliography/ru/<publicId>.md, /ru/library/<publicId>/
music -> src/content/music/ru/<publicId>.md, /ru/music/<publicId>/
concepts -> src/content/concepts/ru/<publicId>.md, /ru/concepts/<publicId>/
editorial -> src/data/pages/ru/<editorialPage>.json, /ru/<page-specific-route>/
```

- [ ] **Step 5: Implement deterministic source hashing**

`sourceHash` must be SHA-256 over normalized public metadata and canonical body after public-link processing. The Java hash bytes must match the Python hash for the same fixture.

- [ ] **Step 6: Run targeted tests**

Run:

```bash
mvn test -Dtest=EditorialParserTest,ManifestBuilderTest
```

Expected: all manifest and editorial tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/model src/main/java/dev/eugene/astroexport/manifest src/main/java/dev/eugene/astroexport/editorial src/test/java/dev/eugene/astroexport/manifest src/test/java/dev/eugene/astroexport/editorial
git commit -m "feat: port manifest and editorial normalization"
```

---

### Task 8: Translation Projection, Review Workspace, And RU Cache

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/translation/TranslationProjection.java`
- Create: `src/main/java/dev/eugene/astroexport/translation/TranslationPatch.java`
- Create: `src/main/java/dev/eugene/astroexport/translation/TranslationValidator.java`
- Create: `src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- Create: `src/main/java/dev/eugene/astroexport/review/RuCache.java`
- Create: `src/test/java/dev/eugene/astroexport/translation/TranslationProjectionTest.java`
- Create: `src/test/java/dev/eugene/astroexport/translation/TranslationValidatorTest.java`
- Create: `src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
- Create: `src/test/java/dev/eugene/astroexport/review/RuCacheTest.java`

**Interfaces:**
- Produces: `TranslationProjection.translationSourceHash(ManifestEntry entry)`
- Produces: `TranslationValidator.buildEnglishManifest(ManifestResult russian, Path reviewRoot)`
- Produces: `ReviewWorkspace.writeRuReviewFile(Path reviewRoot, ManifestEntry entry)`
- Produces: `ReviewWorkspace.loadEnglishPatch(Path reviewRoot, ManifestEntry entry)`
- Produces: `RuCache.changedRecords(Path cacheRoot, List<ManifestEntry> entries)`

- [ ] **Step 1: Port translation tests**

Port all behavior from `tests/test_translation.py`, including duplicate JSON keys, missing and extra translated fields, stale source hashes, generated and reviewed statuses, internal `/ru/` route rejection, inherited invariant fields, reference translation materialization, dormant reference translations, and localized EN route rewriting.

- [ ] **Step 2: Port review workspace tests**

Port all behavior from `tests/test_review_workspace.py`, including Markdown override migration, editorial JSON override migration, `referenceTranslations` round-trip, generated/reviewed status rewrite, RU review serialization, symlink/hardlink blockers, atomic replacement failure preservation, and editorial review Markdown parsing.

- [ ] **Step 3: Port RU cache tests**

Port `tests/test_ru_cache.py` exactly: normalized public record round-trip and changed-record detection on body changes.

- [ ] **Step 4: Implement translation projection**

Invariant keys must be inherited from RU and not copied from EN:

```text
id
publish
language
sourceLanguage
translationOf
date
updated
topics
links
route
target
sourceHash
status
foundational
readTime
cover
telegram
```

The translatable projection must include only text leaves that the review file is allowed to translate.

- [ ] **Step 5: Implement review file validation**

Accepted review control fields:

```text
sourceHash
translationStatus
translatedAt
translationProfile
```

Accepted stored statuses:

```text
generated
reviewed
```

Anything else blocks the EN pair.

- [ ] **Step 6: Run targeted tests**

Run:

```bash
mvn test -Dtest=TranslationProjectionTest,TranslationValidatorTest,ReviewWorkspaceTest,RuCacheTest
```

Expected: all translation and review tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/translation src/main/java/dev/eugene/astroexport/review src/test/java/dev/eugene/astroexport/translation src/test/java/dev/eugene/astroexport/review
git commit -m "feat: port translation review validation"
```

---

### Task 9: Guarded Workflow State And Prepare Workflow

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/fs/AtomicExchange.java`
- Create: `src/main/java/dev/eugene/astroexport/fs/JnaAtomicExchange.java`
- Create: `src/main/java/dev/eugene/astroexport/frontmatter/WorkflowFrontmatterEditor.java`
- Create: `src/main/java/dev/eugene/astroexport/workflow/WorkflowStateService.java`
- Create: `src/main/java/dev/eugene/astroexport/process/CodexRunner.java`
- Create: `src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java`
- Create: `src/test/java/dev/eugene/astroexport/workflow/WorkflowStateServiceTest.java`
- Create: `src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java`
- Create: `src/test/java/dev/eugene/astroexport/process/CodexRunnerTest.java`

**Interfaces:**
- Produces: `AtomicExchange.exchange(Path first, Path second)`
- Produces: `WorkflowStateService.updateWorkflowState(Path source, WorkflowUpdate update, Instant updatedAt, SnapshotGuard... guards)`
- Produces: `PrepareWorkflow.prepare(Path vault, String notePath, Path reviewRoot, Path jobsRoot)`
- Produces: `CodexRunner.run(Path workdir, List<String> args, Duration timeout)`

- [ ] **Step 1: Port workflow-state tests**

Port all tests from `tests/test_workflow_state.py`, including duplicate YAML key rejection, YAML alias workflow-key rejection, scalar-only workflow field replacement, new workflow field insertion, atomic exchange rollback, recovery copy reporting, and unsupported exchange behavior.

- [ ] **Step 2: Implement platform atomic exchange**

`JnaAtomicExchange` must call:

```text
macOS: renamex_np(first, second, RENAME_SWAP)
Linux: renameat2(AT_FDCWD, first, AT_FDCWD, second, RENAME_EXCHANGE)
```

If the platform call is unavailable, return an `AtomicExchangeUnavailableException` and leave both paths untouched.

- [ ] **Step 3: Port prepare and Codex tests**

Port all behavior from:

```text
tests/test_prepare.py
tests/test_codex_runner.py
```

Required prepare behaviors:

```text
bounded job directory creation
candidate destination confinement
no Astro output writes
valid generated draft installation
prior EN preservation on failure
stale source detection during job
single running agent per publication
job journal state recording
symlink escape rejection
post-run tamper rejection
```

- [ ] **Step 4: Implement guarded workflow writes**

The Java implementation must preserve the Python boundary:

```text
write sibling temp file
fsync temp bytes and parent directory where supported
verify target snapshot and companion snapshots
atomically exchange target and temp
verify displaced bytes and companions
rollback through atomic exchange on mismatch
preserve conflict copies when rollback cannot prove lossless recovery
```

- [ ] **Step 5: Run targeted tests**

Run:

```bash
mvn test -Dtest=WorkflowStateServiceTest,PrepareWorkflowTest,CodexRunnerTest
```

Expected: all guarded workflow and prepare tests pass on macOS.

- [ ] **Step 6: Run native workflow smoke**

Run:

```bash
mvn -Pnative native:compile
target/astro-export --help
```

Expected: native binary starts and prints usage. Workflow native integration tests remain in JVM until Task 12 adds full native parity.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/fs src/main/java/dev/eugene/astroexport/frontmatter src/main/java/dev/eugene/astroexport/workflow src/main/java/dev/eugene/astroexport/process src/main/java/dev/eugene/astroexport/prepare src/test/java/dev/eugene/astroexport/workflow src/test/java/dev/eugene/astroexport/prepare src/test/java/dev/eugene/astroexport/process
git commit -m "feat: port guarded workflow preparation"
```

---

### Task 10: Site Writer, Reports, And Astro Gate

**Files:**
- Create: `src/main/java/dev/eugene/astroexport/fs/SiteWriter.java`
- Create: `src/main/java/dev/eugene/astroexport/fs/TreeHasher.java`
- Create: `src/main/java/dev/eugene/astroexport/report/ReportBuilder.java`
- Copy: `src/main/resources/templates/pages/ru/search.json`
- Copy: `src/main/resources/templates/pages/en/search.json`
- Create: `src/test/java/dev/eugene/astroexport/fs/SiteWriterTest.java`
- Create: `src/test/java/dev/eugene/astroexport/report/ReportBuilderTest.java`

**Interfaces:**
- Produces: `SiteWriter.stageSite(Path siteRoot, ManifestResult manifest)`
- Produces: `SiteWriter.writeSiteAtomic(Path siteRoot, ManifestResult manifest, Consumer<Path> validator)`
- Produces: `TreeHasher.hashManagedTrees(Path root)`
- Produces: `ReportBuilder.buildSelectionReport(...)`
- Produces: `ReportBuilder.buildManifestReport(...)`
- Produces: `ReportBuilder.buildWriteReport(...)`

- [ ] **Step 1: Port writer tests**

Port all behavior from `tests/test_writer.py`, including exact tree serialization, empty directory creation, byte-identical repeated staging, target confinement, duplicate target blocking, staged asset integrity checks, validator failure rollback, capability ownership, symlink and device mismatch blockers, ordered backup and restore failures, committed-with-errors recovery paths, and idempotent live replacements.

- [ ] **Step 2: Port report tests**

Port every test in `tests/test_report.py`. Reports must keep section names and counts stable because they are operator evidence.

- [ ] **Step 3: Implement resource-backed search templates**

Copy current templates from:

```text
/Users/eugene/Documents/personal-wiki/tools/astro-export/templates/pages/ru/search.json
/Users/eugene/Documents/personal-wiki/tools/astro-export/templates/pages/en/search.json
```

Write them to:

```text
src/main/resources/templates/pages/ru/search.json
src/main/resources/templates/pages/en/search.json
```

- [ ] **Step 4: Implement managed roots exactly**

Managed roots:

```text
public/assets/vault
src/content
src/data/pages
```

Required directories:

```text
public/assets/vault
src/content/blog/en
src/content/blog/ru
src/content/bibliography/en
src/content/bibliography/ru
src/content/concepts/en
src/content/concepts/ru
src/content/music/en
src/content/music/ru
src/data/pages/en
src/data/pages/ru
```

- [ ] **Step 5: Implement Astro content gate adapter**

The CLI write path must run:

```bash
npm run check-content
```

with `ASTRO_CONTENT_DIR` and `ASTRO_PAGES_DIR` pointing at the staged tree before the first live move. A non-zero exit or process start failure must block the write and preserve live trees.

- [ ] **Step 6: Run targeted tests**

Run:

```bash
mvn test -Dtest=SiteWriterTest,ReportBuilderTest
```

Expected: all writer and report tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/fs src/main/java/dev/eugene/astroexport/report src/main/resources src/test/java/dev/eugene/astroexport/fs src/test/java/dev/eugene/astroexport/report
git commit -m "feat: port site writer and reports"
```

---

### Task 11: Full CLI Parity And Shell Wrappers

**Files:**
- Modify: `src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- Create: `src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java`
- Create: `src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- Create: `src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- Create: `scripts/export-site.sh`
- Create: `scripts/build-from-review.sh`
- Create: `scripts/build-astro-site.sh`
- Create: `scripts/migrate-overrides.sh`

**Interfaces:**
- Produces root command: `astro-export --vault PATH [--dry-run] [--out PATH] [--report PATH] [--review PATH]`
- Produces subcommands: `build-from-review`, `migrate-overrides`, `prepare`, `inspect-publication`, `mark-reviewed`, `refresh-publication-queue`, `write-publication-contract`

- [ ] **Step 1: Port CLI tests**

Port every behavior from `tests/test_cli.py`, including bridge JSON shape, parser rejections, read-only inspect, mark-reviewed guarded transitions, refresh six-state summary, report/out separation blockers, missing Astro root files, dry-run no-write behavior, successful write gate evidence, gate failure preservation, translation blockers, asset blockers, committed-with-errors status, and programmer-error propagation.

- [ ] **Step 2: Implement root export flow**

Root export flow:

```text
validate --vault
validate --report is outside --out
select publication candidates
build RU manifest
write generated ru.md review files
validate existing EN review files
if --dry-run: print and write dry-run report, exit 0 or 1 from blockers
if write mode: validate Astro root, stage complete bilingual manifest, run Astro gate, replace managed roots, print and write write report
```

- [ ] **Step 3: Implement build-from-review**

`build-from-review` uses the same validation and writer flow but never invokes Codex and never rewrites `en.md`. It may refresh generated `ru.md` because that is exporter-owned.

- [ ] **Step 4: Implement bridge JSON schema**

Every bridge response must contain exactly these top-level keys:

```text
schemaVersion
command
ok
status
note
collection
publicId
reviewDirectory
pairFreshness
translationStatus
diagnostics
workspaceHealth
jobId
summary
updated
unchanged
uncertain
```

Values that do not apply must be JSON `null`, except list fields which must be empty lists when the Python command returns empty lists.

- [ ] **Step 5: Create wrappers**

Create wrappers with the same defaults as the current Python scripts:

```bash
VAULT_ROOT="${VAULT_ROOT:-/Users/eugene/Documents/personal-wiki/knowledge-base}"
ASTRO_ROOT="${ASTRO_ROOT:-/Users/eugene/POS/software-dev/astro-blog}"
REPORT_PATH="${REPORT_PATH:-$EXPORTER_ROOT/report.md}"
```

During development the wrappers may run:

```bash
mvn -q exec:java -Dexec.args="..."
```

After native build they must prefer:

```bash
target/astro-export
```

- [ ] **Step 6: Run full JVM suite**

Run:

```bash
mvn test
```

Expected: all migrated unit and CLI tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/eugene/astroexport/cli src/test/java/dev/eugene/astroexport/cli scripts
git commit -m "feat: port full CLI surface"
```

---

### Task 12: Native Image Parity And Cutover Evidence

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/dev/eugene/astroexport/nativeimage/NativeCliParityTest.java`
- Create: `docs/native-build.md`
- Create: `docs/cutover-checklist.md`

**Interfaces:**
- Produces: native executable `target/astro-export`
- Produces: documented release command `mvn -Pnative native:compile`
- Produces: parity evidence comparing Java CLI output with the Python oracle on fixed fixtures

- [ ] **Step 1: Add native metadata for reflection and resources**

If picocli, Jackson, SnakeYAML, or JNA require native metadata, add it under:

```text
src/main/resources/META-INF/native-image/dev.eugene/astro-export/
```

The metadata must be generated with the GraalVM tracing agent from these executions:

```bash
mvn -Pnative -DskipTests package
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/dev.eugene/astro-export -cp target/classes:$(cat target/classpath.txt) dev.eugene.astroexport.AstroExportApp --help
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/dev.eugene/astro-export -cp target/classes:$(cat target/classpath.txt) dev.eugene.astroexport.AstroExportApp --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-native-dry-run.md
```

- [ ] **Step 2: Build native executable**

Run:

```bash
mvn -Pnative native:compile
```

Expected: `target/astro-export` exists and has executable permissions.

- [ ] **Step 3: Native smoke commands**

Run:

```bash
target/astro-export --help
target/astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-dry-run.md
```

Expected: help exits 0. Dry-run exits with the same status as the Python dry-run for the same live source and writes the report outside the Astro root.

- [ ] **Step 4: Python oracle comparison**

Run the Python oracle:

```bash
cd /Users/eugene/Documents/personal-wiki/tools/astro-export
uv run astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-python-dry-run.md
```

Run the Java native command:

```bash
cd /Users/eugene/Dev/astro-export-java
target/astro-export --vault /Users/eugene/Documents/personal-wiki/knowledge-base --dry-run --report /private/tmp/astro-export-java-dry-run.md
```

Expected comparison:

```text
exit code matches
selector counts match
RU record count matches
EN record count matches
translation blocker count matches
asset count matches
source-to-target mappings match
```

- [ ] **Step 5: Native write-stage comparison in temp Astro root**

Create a temp Astro root with the required gate files and a gate stub. Run both implementations against identical fixture vault and review roots, then compare managed-tree hashes:

```bash
target/astro-export --vault /private/tmp/astro-export-fixture-vault --out /private/tmp/astro-export-java-astro --report /private/tmp/astro-export-java-write.md
```

Expected: Java managed-tree hashes match the Python oracle for the same fixture.

- [ ] **Step 6: Document cutover**

Create `docs/cutover-checklist.md` with these gates:

```markdown
# Cutover Checklist

- [ ] Python source status captured with `git status --short /Users/eugene/Documents/personal-wiki/tools/astro-export`.
- [ ] Java `mvn test` passes.
- [ ] Java `mvn -Pnative native:compile` passes.
- [ ] Native dry-run report matches Python oracle counts and mappings.
- [ ] Native temp write produces managed-tree hashes matching Python oracle.
- [ ] Current operator scripts have Java equivalents.
- [ ] No production Astro deployment is included in cutover.
```

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/test/java/dev/eugene/astroexport/nativeimage docs
git commit -m "test: prove native exporter parity"
```

---

## Completion Criteria

The port is complete only when all of these are true:

- `mvn test` passes in `/Users/eugene/Dev/astro-export-java`.
- `mvn -Pnative native:compile` produces `target/astro-export`.
- `target/astro-export --help` exits 0.
- Native dry-run and Python dry-run agree on exit code, counts, blockers, and source-to-target mappings for the same source snapshot.
- Temp write-mode parity proves identical managed-tree hashes for a fixture Astro root.
- The cutover checklist records the exact Python source status used as the oracle.
- The old `uv run astro-export` workflow remains available until the operator explicitly approves replacement.

## Open Technical Decisions

- Keep JNA if native-image metadata remains small and startup time meets the operator goal; otherwise replace `JnaAtomicExchange` with a GraalVM C interop implementation in the native profile.
- Keep Jackson for JSON if native-image metadata remains manageable; otherwise move bridge/report JSON to Jackson streaming API with explicit maps.
- Keep Java 21 unless GraalVM native-image or platform FFI work is materially simpler on a newer GraalVM JDK available on the target Mac.

## Self-Review

- Spec coverage: Java rewrite, Maven build, GraalVM native executable, macOS native execution, reduced startup, existing scripts, and existing tests are all covered by tasks.
- Placeholder scan: no task depends on unspecified behavior; risky areas are named with concrete files, commands, and acceptance checks.
- Type consistency: package names, command names, and produced interfaces match across tasks.
