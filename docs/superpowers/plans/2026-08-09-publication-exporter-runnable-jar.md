# Publication Exporter Native Executable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a GraalVM native executable for `publication-exporter` that accepts the Obsidian plugin's existing bridge command arguments and emits schema-v2 JSON.

**Architecture:** Add an opt-in Maven `native` profile using GraalVM Native Build Tools. The executable launches `dev.eugene.publicationexporter.cli.Main`; narrowly scoped reflection metadata preserves picocli command binding and Jackson bridge serialization. Command behaviour, plugin settings, vault files, review data, and Astro output remain untouched. Verify the packaged process with `inspect-publication`, the plugin's read-only bridge command.

**Tech Stack:** Java 17, Maven 3, GraalVM Native Image, picocli, Jackson.

## Global Constraints

- Build `publication-exporter/target/publication-exporter` as the only new distributable artifact.
- Use `dev.eugene.publicationexporter.cli.Main` as the native-image main class.
- Do not edit `obsidian-plugin/`, vault notes, review workspaces, or `site/`.
- Verify with a read-only `inspect-publication` invocation using the exact `--vault`, `--note`, `--review`, and `--json` argument shape used by the plugin.

---

### Task 1: Package and verify the native bridge artifact

**Files:**
- Modify: `publication-exporter/pom.xml`
- Create: `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json`
- Create: `publication-exporter/target/publication-exporter` (build artifact, ignored)

**Interfaces:**
- Consumes: picocli entry point `dev.eugene.publicationexporter.cli.Main`.
- Produces: `publication-exporter/target/publication-exporter <command> ...` as a native macOS arm64 executable.

- [x] **Step 1: Configure the GraalVM native profile and metadata**

Add `org.graalvm.buildtools:native-maven-plugin` to the opt-in `native` profile, set `imageName` and `mainClass`, and add reflect metadata for picocli command classes plus Jackson bridge response types.

- [x] **Step 2: Build the native executable**

Run:

```bash
mvn -f publication-exporter/pom.xml -Pnative -DskipTests native:compile
```

Expected: Maven succeeds and creates `publication-exporter/target/publication-exporter`.

- [x] **Step 3: Verify the live read-only bridge contract**

Run:

```bash
publication-exporter/target/publication-exporter inspect-publication \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --note 'blog/Фрактальность TDD.md' \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --json
```

Expected: schema-v2 JSON with `command` `inspect-publication`; no vault or review files are changed.

- [x] **Step 4: Verify the exact plugin client contract**

Invoke `createBridgeClient(...).run("inspect-publication", note)` with `exporterBinary` pointed at the native executable. Expected: its parsed response is schema v2.

- [ ] **Step 5: Commit only the build configuration and plan if requested**

Do not stage the pre-existing `.codex/config.toml` modification. Stage only `publication-exporter/pom.xml` and this plan when the user explicitly asks for a commit.
