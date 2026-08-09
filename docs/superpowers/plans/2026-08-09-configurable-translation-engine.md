# Configurable Translation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the native `prepare` command select Codex or Antigravity (`agy`) from persistent exporter-root configuration, with a one-run environment override, without changing the Obsidian plugin or rebuilding the binary.

**Architecture:** Keep translation process execution, prompt construction, job workspaces, timeout, output-file validation, and cleanup in `ProcessTranslationWorker`. Add a small strict configuration resolver that chooses one `TranslationCommand`; `PrepareCommand` resolves it from the process working directory and passes the selected command to the existing worker. A configuration error is represented as an ordinary failed translation so the established `prepare` JSON/exit-code contract remains intact.

**Tech Stack:** Java 21, Maven/JUnit 5, picocli, GraalVM native-image, TOML-shaped configuration parsed with JDK code only.

## Global constraints

- Configuration precedence is: non-blank `PUBLICATION_EXPORTER_TRANSLATION_ENGINE`, then `<exporterRoot>/publication-exporter.toml`, then `codex`.
- The only valid engine identifiers are lowercase `codex` and `antigravity`; malformed, duplicate, blank, or unsupported selected values fail loudly. There is no fallback after a selected value is invalid or an engine process exits unsuccessfully.
- The accepted file shape is deliberately only:

  ```toml
  [translation]
  engine = "antigravity"
  ```

  Blank lines and `#` comment-only lines are permitted. Other non-comment syntax, a missing `[translation]`/`engine` pair in an existing file, duplicate `engine` keys, or unknown engine values are configuration errors.
- `antigravity` runs the locally installed CLI as `agy --print --mode accept-edits --prompt <existing prompt>` in the existing per-job working directory. It receives no copied Codex skills and no `--sandbox` override.
- The Obsidian plugin remains unchanged: it continues to launch the configured native binary from its `exporterRoot`, which is therefore also the configuration directory.
- Preserve the three mandatory worker outputs (`candidate.en.md`, `candidate.en.title.txt`, and `candidate.en.description.txt`) and every existing process-confinement rule.
- Do not add a generic TOML library, arbitrary command configuration, engine-specific prompt variants, retries/fallback, a plugin setting, or support for Windows/Linux.

---

### Task 1: Add engine commands and a strict configuration resolver

**Files:**

- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/AntigravityTranslationCommand.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationEngineConfiguration.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/AntigravityTranslationCommandTest.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationEngineConfigurationTest.java`

- [ ] **Step 1: Write the command-shape tests before production code.**

  In `AntigravityTranslationCommandTest`, assert the exact argv for `Path.of("/tmp/job-42")` and prompt `"Translate this"`:

  ```java
  assertEquals(List.of(
          "agy", "--print", "--mode", "accept-edits", "--prompt", "Translate this"),
      new AntigravityTranslationCommand().argsFor(Path.of("/tmp/job-42"), "Translate this"));
  ```

  This intentionally proves that the command relies on the worker's `ProcessBuilder.directory(workdir)` rather than inventing an agent-specific workspace flag.

- [ ] **Step 2: Write resolver tests with a temporary exporter root and injected environment map.**

  Make tests cover all resolution boundaries without mutating the real process environment:

  - no environment value and no file returns a `CodexTranslationCommand`;
  - `[translation]\nengine = "antigravity"\n` returns an `AntigravityTranslationCommand`;
  - `[translation]\nengine = "antigravity"\n` plus `Map.of("PUBLICATION_EXPORTER_TRANSLATION_ENGINE", "codex")` returns Codex, proving environment precedence;
  - a blank environment value is ignored and the file is used;
  - an unsupported environment value, a missing engine in an existing file, a duplicate engine key, and arbitrary non-comment syntax each throw `IllegalArgumentException` whose message names `PUBLICATION_EXPORTER_TRANSLATION_ENGINE` or `publication-exporter.toml` and the invalid value/syntax;
  - when the environment selects a valid engine, an invalid config file is not read and does not prevent selection.

  Check selected commands through their concrete class and their `argsFor(...)` output; do not start `codex` or `agy` in unit tests.

- [ ] **Step 3: Run the focused tests and confirm they fail for the intended missing classes.**

  Run:

  ```bash
  mvn -f publication-exporter/pom.xml -Dtest=AntigravityTranslationCommandTest,TranslationEngineConfigurationTest test
  ```

  Expected before implementation: test compilation fails because the two production classes do not yet exist.

- [ ] **Step 4: Implement `AntigravityTranslationCommand`.**

  Add the final `TranslationCommand` implementation with:

  ```java
  @Override
  public List<String> argsFor(Path workdir, String prompt) {
      return List.of("agy", "--print", "--mode", "accept-edits", "--prompt", prompt);
  }
  ```

  Require non-null inputs consistently with the existing command class if necessary, but do not otherwise consume `workdir`; process execution already sets it as the working directory.

- [ ] **Step 5: Implement `TranslationEngineConfiguration` as the one configuration boundary.**

  Expose a testable static API such as:

  ```java
  public static TranslationCommand commandFor(Path exporterRoot, Map<String, String> environment)
  ```

  It must:

  1. validate the supplied root/map;
  2. use a trimmed, non-blank environment value first;
  3. otherwise read exactly `exporterRoot.resolve("publication-exporter.toml")` if it exists;
  4. return Codex when the file is absent;
  5. parse only the constrained grammar in the global constraints; and
  6. map `codex` to `new CodexTranslationCommand()` and `antigravity` to `new AntigravityTranslationCommand()`.

  Use JDK `Files.readAllLines`/`Files.readString` and a small line-oriented parser. Do not silently ignore malformed selected configuration and do not introduce a TOML dependency. Include the configuration source in every `IllegalArgumentException` message so it can be surfaced to the operator.

- [ ] **Step 6: Re-run the focused tests.**

  Run the command from Step 3. Expected: all resolver and argv tests pass; neither external agent is invoked.

### Task 2: Wire configuration into `prepare` and surface failures as schema-v2 diagnostics

**Files:**

- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationResult.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/translation/TranslationWorker.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/translation/TranslationResultTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java`

- [ ] **Step 1: Extend tests for a named failure diagnostic without changing existing failures.**

  In `TranslationResultTest`, establish that `TranslationResult.failure("boom")` retains the default field `candidate`, and add a factory/overload assertion for a failure with field `translation-engine`. Keep success values free of a failure field.

  In `PrepareCliAcceptanceTest`, add a valid publishable note, create an exporter-root temporary directory containing:

  ```toml
  [translation]
  engine = "unsupported-agent"
  ```

  Construct the command through its new package-visible configuration-aware constructor, provide an empty environment map, and execute the normal CLI helper. Assert:

  - exit code is `1`;
  - JSON has `schemaVersion: 2`, `status: "translation_failed"`, and the existing command/identity structure;
  - its blocking diagnostic field is `translation-engine` and the message names `unsupported-agent` and `publication-exporter.toml`;
  - no candidate was installed and no job directory was created, establishing that no agent subprocess was attempted.

- [ ] **Step 2: Run the focused acceptance tests and confirm the new cases fail.**

  Run:

  ```bash
  mvn -f publication-exporter/pom.xml -Dtest=TranslationResultTest,PrepareCliAcceptanceTest test
  ```

  Expected before implementation: the configuration-aware constructor/failure-field assertions fail to compile or the invalid-engine case reports the old behavior.

- [ ] **Step 3: Preserve failure provenance in `TranslationResult`.**

  Add a nullable/optional failure diagnostic field with these invariants:

  - `success(...)` has no failure field;
  - existing `failure(reason)` continues to produce field `candidate`;
  - a new explicit failure factory accepts `(field, reason)` and requires both values;
  - `failureDiagnosticField()` is meaningful only for failures.

  Update `TranslationWorker.createNullFailing(...)` only as needed to retain its current default behavior; add a narrowly named variant for a configuration failure rather than changing every existing call site.

- [ ] **Step 4: Make `PrepareHandler` use the result's failure field.**

  Replace the hard-coded `Diagnostic.blocking("candidate", translation.failureReason())` in `translationFailure(...)` with the result-provided diagnostic field. Do not alter validation, stale-source, candidate-installation, or workflow-status diagnostics: they remain `candidate` and retain their current semantics.

- [ ] **Step 5: Resolve engine configuration once per `prepare` invocation.**

  Refactor the no-argument `PrepareCommand` to delegate to a package-visible constructor taking an exporter root and environment map:

  ```java
  public PrepareCommand() {
      this(Path.of(System.getProperty("user.dir")), System.getenv());
  }
  ```

  That constructor must build the existing `ProcessTranslationWorker` using `TranslationEngineConfiguration.commandFor(exporterRoot, environment)`, the unchanged 900-second timeout, and the existing job root. If resolution throws `IllegalArgumentException`, produce a `NullTranslationWorker`/`TranslationResult` failure with field `translation-engine`; do not throw from picocli, and do not run a fallback command.

  Keep the existing `PrepareCommand(TranslationWorker)` injection constructor for all current acceptance tests. The user-facing argv and Obsidian-plugin call remain untouched.

- [ ] **Step 6: Re-run focused tests, then the exporter suite.**

  Run:

  ```bash
  mvn -f publication-exporter/pom.xml -Dtest=TranslationResultTest,PrepareCliAcceptanceTest,AntigravityTranslationCommandTest,TranslationEngineConfigurationTest test
  mvn -f publication-exporter/pom.xml test
  ```

  Expected: both commands pass. Treat a sandbox/Jansi/Surefire permission error separately from a source-test failure and report it as an environment blocker if it occurs.

### Task 3: Document the operational contract and verify the native distribution

**Files:**

- Modify: `.gitignore`
- Modify: `README.md`

- [ ] **Step 1: Keep local configuration out of source control.**

  Add `publication-exporter.toml` to the root `.gitignore`. This protects the normal case where the exporter root is a repository checkout; an external plugin-configured exporter root remains the operator's own filesystem location.

- [ ] **Step 2: Update the root README where operators find the pipeline.**

  Correct the pipeline references needed to name `publication-exporter` as the active bridge, then add a concise “Translation engine configuration” section containing:

  - the exact config path: `<exporterRoot>/publication-exporter.toml`, where `exporterRoot` is the plugin's working directory;
  - the two valid file examples (`codex`, `antigravity`);
  - the precedence order and default-to-Codex behavior;
  - one-shot shell examples for `PUBLICATION_EXPORTER_TRANSLATION_ENGINE=antigravity` and `=codex`;
  - the exact Antigravity CLI requirements (`agy` discoverable on the process `PATH`, installed skills used as-is);
  - the no-fallback/error behavior and the existing review-output contract; and
  - an explicit statement that changing this file needs neither a binary rebuild nor an Obsidian-plugin settings change.

  Do not document copied skill directories, arbitrary CLI arguments, or non-macOS support.

- [ ] **Step 3: Build the macOS native executable and run a configuration boundary check.**

  Run:

  ```bash
  mvn -f publication-exporter/pom.xml -Pnative -DskipTests native:compile
  ```

  Then, in a disposable temporary exporter root, run the generated native executable with the real `prepare --json` argv against a controlled fixture and an invalid config. Assert schema-v2 `translation_failed`/`translation-engine`, not a stack trace or a non-JSON process failure. This verifies that external runtime config works in the native image without reflection/resource additions.

- [ ] **Step 4: Perform the opt-in real Antigravity pilot.**

  Only after the operator has selected a disposable or intentionally publishable vault note, place:

  ```toml
  [translation]
  engine = "antigravity"
  ```

  in that launch root and use the unchanged plugin **Prepare translation** action. Confirm `agy` writes all three required files through the existing job workspace, the bridge reports `ready_for_review`, Zed opens the reviewed RU/EN pair, and review/approval proceeds as before. This step is intentionally operator-authorized because it invokes an external agent and writes review artifacts.

- [ ] **Step 5: Review and commit as two coherent changes.**

  Inspect `git diff --check`, the staged diff, and `git status --short`. Keep the source/tests in one commit (for example, `feat(exporter): configure translation engine`) and the ignore/README documentation in a second commit (for example, `docs: explain translation engine selection`). Do not stage the pre-existing user-owned `.codex/config.toml` change. Run `haft sync` after the implementation commits so the G3-refining decision remains reflected in Haft's index.
