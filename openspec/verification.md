# Requirements derivation verification

## Window

Run on 2026-08-03 against repository baseline `aac0104` plus the uncommitted OpenSpec and Haft requirements artefacts.

## Structural acceptance

| Check | Result |
| --- | --- |
| `openspec validate --specs --strict --json --no-interactive` | Passed: 8 of 8 capability specifications. The CLI later emitted non-fatal telemetry network noise. |
| Requirement/scenario census | 47 requirements; 101 scenarios; 101 `GIVEN`, 101 `WHEN`, and 101 `THEN` clauses. |
| `git diff --check` | Passed before executable tests; rerun in final verification. |

## Executable evidence

| Harness | Result | Interpretation |
| --- | --- | --- |
| Java Maven suite | 686 passed; 1 errored of 687. | Existing behavioural evidence is overwhelmingly green. The error is the already-observed stale native executable returning empty stdout to `NativeCliParityTest.nativeExecutableExercisesSemanticSubcommandsBeyondHelp`; it is not caused by specification files. |
| Obsidian plugin Node suite | 35 passed; 1 failed of 36. | Bridge and workflow behaviours pass. The failure is a repository-layout assumption: the test resolves `../../../community-plugins.json` to `/Users/eugene/Dev/community-plugins.json`, which does not exist. |
| Site body-first test | Passed: 1 of 1. | Corroborates body-first RU/EN rendering across essays, notes, books, and concepts. |
| Site content check | Passed. | Corroborates current managed content and site validation rules. |

## Governance acceptance

| Check | Result |
| --- | --- |
| Haft problem | `prob-20260803-c1c6eca8` frames the derivation and its acceptance criteria. |
| Haft evidence | `evid-20260803-239834000` records the live bridge mismatch, code-size pressure, and specification census. |
| `haft sync` | 0 synced, 15 unchanged, 0 failed. |
| `haft spec check` | Clean across five active sections and seven term-map entries. |
| `haft check --json` | No stale artefacts, drift, or coverage gaps. One pre-existing active decision remains unassessed: `dec-20260802-bind-the-vanilla-publication-frontier-zettelkast-b77c183c`. |

## Verification boundary

These checks establish that the requirements artefacts are structurally valid, traceable to current executable evidence, and consistent with current Haft constraints. They do not establish implementation conformance for the future replacement exporter. The two baseline test defects remain intentionally unfixed because this task does not refactor or repair the existing exporter or plugin.
