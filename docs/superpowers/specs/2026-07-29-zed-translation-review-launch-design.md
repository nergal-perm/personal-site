# Zed Translation Review Launch

**Status:** Approved design

**Date:** 2026-07-29

## Purpose

Replace the Obsidian plugin's current folder-opening behavior with a
comparison-oriented Zed workflow for one publication review.

For a fresh proposed Russian/English pair:

- when no approved published pair exists, open proposed `ru.md` and `en.md`
  in two new Zed workspace windows;
- when an approved published pair exists, open the Russian and English diffs
  in two new Zed workspace windows;
- when the published cache is inconsistent, open nothing and show a blocking
  diagnostic.

The feature does not position or tile the Zed windows.

## Current behavior

The Obsidian command **Открыть проверку перевода текущей заметки** calls:

```text
astro-export inspect-publication --note <vault-relative-path>
```

If the exporter reports a fresh review, the plugin passes the returned
`reviewDirectory` to Electron's `shell.openPath`. The operating system then
opens the directory rather than presenting the proposed files or their
approved comparisons.

The same folder-opening helper is used by the **Открыть проверку** button in
the modal shown after a successful preparation.

The approved comparison baseline is exporter-owned:

```text
review/<collection>/<publicId>/published/ru.md
review/<collection>/<publicId>/published/en.md
```

Only `mark-reviewed` advances that pair. Export, build, preview, and deploy
do not advance it.

## Decisions

1. The exporter owns review-plan construction, published-cache
   classification, path validation, and proposal freshness validation.
2. The Obsidian plugin owns only Zed CLI configuration, plan-shape
   validation, process invocation, and user feedback.
3. A review requires a fresh, valid proposed Russian/English pair. Stale or
   invalid proposals retain the current blocking behavior.
4. The published baseline has three states:
   - `absent`: neither published file exists;
   - `complete`: both safe published files exist;
   - `inconsistent`: exactly one exists or either existing artifact is
     unsafe or unreadable.
5. `absent` opens two plain files in two new Zed workspace windows.
6. `complete` opens two diffs in two new Zed workspace windows.
7. `inconsistent` blocks the action with diagnostics and opens no window.
8. Both the explicit Obsidian command and the post-prepare modal button use
   the same inspect-and-launch path.
9. The post-prepare button re-inspects the note captured when preparation
   began. It does not trust the older prepare response or whichever note is
   active when the button is clicked.
10. Zed and macOS decide window placement. The plugin performs no tiling,
    resizing, focus control, Accessibility automation, or AppleScript.
11. The bridge response advances to schema version 2. The plugin never
    derives review filenames or falls back to opening the review directory.

## Bridge contract

### Schema version

Every bridge response uses `schemaVersion: 2`. The response retains the
existing top-level fields and adds nullable `reviewPlan`.

The exporter sets `reviewPlan` only on a successful `inspect-publication`
response. Other commands return `reviewPlan: null`.

A version-1 exporter paired with the new plugin, or a version-2 exporter
paired with the old plugin, is a deployment mismatch. The new plugin reports
the observed and expected schema versions, instructs the operator to rebuild
or reload the mismatched component, and does not guess paths or use the old
folder-opening behavior.

### Successful plan without a published baseline

```json
{
  "schemaVersion": 2,
  "command": "inspect-publication",
  "ok": true,
  "status": "ready_for_review",
  "reviewPlan": {
    "baselineState": "absent",
    "targets": [
      {
        "language": "ru",
        "proposedPath": "/absolute/review/blog/example/ru.md",
        "publishedPath": null
      },
      {
        "language": "en",
        "proposedPath": "/absolute/review/blog/example/en.md",
        "publishedPath": null
      }
    ]
  }
}
```

The surrounding existing bridge fields remain present; they are omitted from
the example only for readability. A fresh proposal whose translation status
is already `reviewed` retains the existing `ready_to_publish` status; the
review plan is available for both fresh workflow statuses.

### Successful plan with a published baseline

```json
{
  "schemaVersion": 2,
  "command": "inspect-publication",
  "ok": true,
  "status": "ready_for_review",
  "reviewPlan": {
    "baselineState": "complete",
    "targets": [
      {
        "language": "ru",
        "proposedPath": "/absolute/review/blog/example/ru.md",
        "publishedPath": "/absolute/review/blog/example/published/ru.md"
      },
      {
        "language": "en",
        "proposedPath": "/absolute/review/blog/example/en.md",
        "publishedPath": "/absolute/review/blog/example/published/en.md"
      }
    ]
  }
}
```

Targets are always ordered Russian first and English second. Paths are
absolute, normalized filesystem paths produced by the exporter.

### Inconsistent published baseline

An inconsistent baseline returns:

```text
ok: false
status: published_snapshot_inconsistent
reviewPlan: null
diagnostics[].field: published-snapshot
```

The diagnostic identifies the unsafe, unreadable, or missing member without
claiming that no approved baseline exists.

## Exporter components

### `ReviewLaunchPlanner`

Add a focused exporter-owned planner under the review package. It receives
the validated current manifest entry, bounded review directory, and the safe
English snapshot already validated by the existing freshness check. It
returns either a two-target plan or a typed blocking failure.

It performs these checks:

1. Resolve fixed proposed paths `ru.md` and `en.md` beneath the confirmed
   review directory.
2. Require both proposed artifacts to be readable, valid UTF-8, single-link
   regular files with no symbolic-link leaf.
3. Render the expected current Russian review from the manifest entry and
   require proposed `ru.md` to match it. A mismatch is stale review state.
4. Require proposed `en.md` still to match the safe English snapshot that
   passed the existing structural and source-hash validation.
5. Inspect `published/ru.md` and `published/en.md` without following unsafe
   leaves or accepting paths outside the confirmed review directory.
6. Classify two missing published files as `absent`.
7. Classify two safe, readable, valid-UTF-8 published files as `complete`.
8. Classify every partial or unsafe published state as `inconsistent`.

The planner is editor-neutral. It returns comparison operands and language
identity, not Zed arguments.

### `inspect-publication`

`inspect-publication` retains its existing order:

1. preflight and identify the publication;
2. validate the proposed English translation and freshness;
3. build the review launch plan;
4. emit the version-2 bridge response.

Metadata, missing proposal, stale proposal, invalid proposal, and unsafe
workspace failures return no plan. Published-pair inconsistency uses the
specific blocking status and diagnostic described above.

Inspection remains read-only. The exporter validates snapshots while
constructing the plan but does not lock files for the lifetime of the Zed
windows. `mark-reviewed` therefore continues to revalidate the exact source
and English proposal bytes before approval and remains the authoritative
safety boundary.

### `BridgeResponse`

Add immutable review-plan records to the bridge model and serialize
`reviewPlan` in the exact stable top-level key order used by bridge tests.
All commands emit schema version 2 so a mixed deployment cannot appear
compatible accidentally.

## Obsidian plugin components

### Shared inspect-and-launch path

Replace `openConfirmedReview(result)` with a helper that accepts the captured
vault-relative note path:

```text
inspectAndOpenReview(notePath)
  -> bridge inspect-publication
  -> validate version-2 reviewPlan
  -> launch RU target
  -> launch EN target
  -> report combined outcome
```

The explicit command obtains the current Markdown note and passes its path to
this helper.

After preparation, the modal callback closes over the path of the note that
was prepared and passes that path to the same helper. Changing the active
Obsidian note before clicking the button cannot redirect the review.

The plugin does not inspect `reviewDirectory`, look for `published/`, or
construct any review-file path.

### Zed CLI setting

Add a setting named **Zed CLI** with this default:

```text
/Applications/Zed.app/Contents/MacOS/cli
```

Persist it with the existing plugin settings. Before either launch, require a
non-empty absolute path to an executable regular file. If that preflight
fails, open the diagnostics modal and launch neither language.

No fallback to `$PATH`, `open -a Zed`, Electron `shell.openPath`, or another
editor is part of this feature.

### Zed process invocation

Invoke the configured executable directly with an argument array and no
shell.

For `baselineState: absent`:

```text
<zed-cli> -n <proposed-ru>
<zed-cli> -n <proposed-en>
```

For `baselineState: complete`:

```text
<zed-cli> -n --diff <published-ru> <proposed-ru>
<zed-cli> -n --diff <published-en> <proposed-en>
```

The published path is the old diff operand and the proposed path is the new
operand.

Launch targets in deterministic RU-then-EN order. After Zed CLI preflight,
attempt both targets even if one invocation fails. Each invocation waits only
for the CLI process to accept or reject the request; it does not wait for the
Zed window to close.

### Plan validation

Before invoking Zed, the plugin requires:

- `baselineState` is exactly `absent` or `complete`;
- `targets` contains exactly two entries ordered `ru`, then `en`;
- every proposed path is a non-empty absolute string;
- absent-baseline targets have `publishedPath: null`;
- complete-baseline targets have non-empty absolute published paths;
- no extra target language is accepted.

Invalid plans produce a bridge diagnostic and launch nothing. The plugin
does not repair or reinterpret a malformed plan.

## User-visible behavior

### No published pair

The action opens:

- a new Zed workspace window containing proposed `ru.md`;
- a second new Zed workspace window containing proposed `en.md`.

### Complete published pair

The action opens:

- a new Zed workspace window containing the RU published-to-proposed diff;
- a second new Zed workspace window containing the EN
  published-to-proposed diff.

### Failures

The success notice appears only when both Zed CLI invocations exit
successfully.

If one invocation fails:

- the other language is still attempted;
- an already opened window remains open;
- no success notice appears;
- diagnostics identify RU, EN, or both and include a concise Zed CLI failure
  reason.

If CLI preflight, exporter inspection, schema validation, or plan validation
fails, no Zed invocation occurs.

The plugin cannot roll back a successfully opened GUI window and does not
claim to do so.

## Data flow

```text
Captured Obsidian source note path
        |
        v
plugin: inspect-publication
        |
        v
exporter preflight + proposal freshness validation
        |
        v
ReviewLaunchPlanner
        |
        +--> proposed ru.md + en.md validation
        |
        +--> published pair classification
        |
        v
bridge v2 reviewPlan
        |
        v
plugin plan validation + Zed CLI preflight
        |
        +--> new RU file/diff workspace window
        |
        +--> new EN file/diff workspace window
        |
        v
combined success notice or language-specific diagnostics
```

## Testing

### Java planner tests

Cover:

1. Missing both published files produces an absent-baseline plan.
2. Two safe published files produce a complete-baseline plan with old/new
   operands in the correct direction.
3. Only published RU exists.
4. Only published EN exists.
5. A published artifact is a symbolic link.
6. A published artifact is a hard link.
7. A published artifact is a directory, unreadable, or invalid UTF-8.
8. A proposed artifact is missing or unsafe.
9. Proposed RU does not match the current rendered manifest entry.
10. Returned targets are absolute, normalized, and ordered RU then EN.

### Java bridge tests

Cover:

- exact schema-version-2 top-level key order for every command;
- `reviewPlan: null` on commands other than successful inspection;
- absent and complete successful inspection payloads;
- inconsistent published state returns
  `published_snapshot_inconsistent`, a blocking `published-snapshot`
  diagnostic, and no plan;
- stale or invalid proposals return no plan;
- before/after tree snapshots prove inspection is read-only;
- native CLI parity exercises the version-2 inspection response.

### Obsidian plugin tests

Cover:

1. Version-1 and malformed responses are rejected without launching Zed.
2. Default and persisted Zed CLI settings load and save correctly.
3. Missing, relative, non-regular, or non-executable CLI paths block both
   launches.
4. An absent plan produces exactly two `-n <proposed>` invocations.
5. A complete plan produces exactly two
   `-n --diff <published> <proposed>` invocations.
6. Invocation uses an argument array and no shell.
7. RU and EN are attempted in deterministic order.
8. One-language and two-language failures suppress success and name the
   failed targets.
9. The explicit command and post-prepare button call the same helper.
10. The post-prepare button uses the prepared note path after the active note
    changes.
11. No code path calls `shell.openPath` for review.

### Verification commands

Run:

```text
mvn test
node --test obsidian-plugin/tests/bridge-client.test.cjs
mvn -Pnative native:compile
```

Exercise `inspect-publication` through the rebuilt native binary with one
absent and one complete baseline fixture. Automated tests stub Zed process
execution and never open GUI windows.

## Documentation changes

Update the plugin deployment notes and exporter documentation to state:

- opening a review requires Zed CLI at the configured path;
- the action opens two new workspace windows;
- a complete approved baseline produces two diffs;
- an absent baseline produces two proposed files;
- partial or unsafe published baselines block review;
- the exporter and plugin must be deployed together because bridge schema
  version 2 is coordinated.

## Non-goals

- Positioning, tiling, resizing, or focusing Zed windows.
- Opening both languages in one Zed window or one multi-diff.
- Falling back to tabs, the operating-system folder handler, another editor,
  or an older bridge contract.
- Editing, repairing, deleting, or recreating the published cache.
- Advancing the approved baseline.
- Approving a translation from the open-review action.
- Opening stale or invalid proposals for debugging.
- Locking review files while their Zed windows remain open.
- Supporting non-macOS Zed discovery in this feature.

## Acceptance criteria

The feature is complete when:

1. A fresh proposal with no published pair opens proposed RU and EN in two
   new Zed workspace windows.
2. A fresh proposal with a complete published pair opens RU and EN diffs in
   two new Zed workspace windows, with published as old and proposed as new.
3. A partial or unsafe published pair opens nothing and produces a blocking
   diagnostic.
4. Stale or invalid proposals retain the existing blocking behavior.
5. Both Obsidian review entry points use the captured note path and the same
   exporter-owned plan.
6. No review flow uses folder opening or derives cache paths in the plugin.
7. Success is reported only after both Zed CLI requests succeed.
8. Java, plugin, native, and read-only inspection tests pass.
