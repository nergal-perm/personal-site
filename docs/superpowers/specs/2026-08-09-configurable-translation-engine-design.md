# Configurable translation engine — design

## Goal

Allow the existing `prepare` command to use either the installed Codex CLI or
the installed Antigravity `agy` CLI, selected without rebuilding the native
`publication-exporter` executable. Keep all translation-worker and approval
contracts unchanged.

## Decision to record

The selected design is a persistent TOML configuration file in the exporter
working directory plus a one-run environment override. The active engine is
one of `codex` or `antigravity`. Codex remains the default when no setting is
present, preserving every existing installation.

This requires the operator's explicit Haft decision before implementation.

## Configuration contract

The resolver reads these sources in order:

1. `PUBLICATION_EXPORTER_TRANSLATION_ENGINE`, when non-blank.
2. `<exporterRoot>/publication-exporter.toml`.
3. The built-in default `codex`.

`exporterRoot` is the process working directory. The Obsidian plugin already
starts every bridge command with its configured exporter root as `cwd`, so it
does not need a new setting or command-line argument. Direct CLI users run
from the intended exporter root.

The supported file is deliberately small:

```toml
[translation]
engine = "antigravity" # or "codex"
```

The implementation recognizes only this table/key and validates duplicate,
blank, malformed, and unknown values fail closed. It does not introduce a
generic configuration framework or a new runtime dependency just to parse one
setting.

## Runtime design

`PrepareCommand` replaces its direct construction of `CodexTranslationCommand`
with a small configuration resolver and a two-case command factory. The
existing `ProcessTranslationWorker` remains unchanged: it creates the
per-translation `JobWorkspace`, passes the current prompt, enforces the same
timeout, drains output, verifies the job fingerprint, and reads exactly:

- `candidate.en.title.txt`
- `candidate.en.description.txt`
- `candidate.en.md`

The Codex case preserves its existing `codex exec --ephemeral` command.
The Antigravity case uses non-interactive print mode and edit acceptance in the
same job workspace:

```text
agy --print --mode accept-edits --prompt <existing prompt>
```

Antigravity discovers and applies its own installed skills. The exporter does
not copy skills into a job workspace, set up a shared skill registry, or claim
to make the two agent installations identical.

## Failure behavior

Configuration errors are converted into the existing `TranslationResult`
failure path, so `prepare --json` produces a schema-v2
`translation_failed` response with an actionable `translation-engine`
diagnostic. A selected executable that cannot start or exits unsuccessfully
keeps the existing `ProcessTranslationWorker` failure behavior. There is no
fallback from `antigravity` to `codex`, because that would make the operator's
editorial choice invisible.

## Tests

Start with executable-boundary acceptance coverage using the existing fake
worker-command harness:

- no file and no environment variable selects Codex;
- TOML selects Codex or Antigravity and produces the corresponding argv;
- non-blank environment value overrides the file;
- unknown/malformed/duplicate selection returns `translation_failed` and does
  not start either CLI;
- both engines run through the unchanged three-result-file contract.

Keep parser tests narrow and only for the deliberately supported TOML subset.
Run the full publication-exporter suite, the native-image build, and one real
`agy` prepare pilot before claiming the Antigravity path operational.

## Documentation

Update the root README with:

- the file location and TOML example;
- the two engine names and default;
- environment-variable precedence and temporary-switch example;
- the fact that each CLI uses its own installed skills;
- failure guidance for a missing `agy` or invalid configuration.

## Explicit exclusions

- no bridge argument, plugin setting, plugin rebuild, or plugin deployment;
- no engine-specific prompt variants or result formats;
- no environment variable for executable paths, models, effort, or arbitrary
  agent arguments;
- no agent-skill installation, synchronization, or copying;
- no automatic fallback, retries across engines, batching, or per-note engine
  selection.
