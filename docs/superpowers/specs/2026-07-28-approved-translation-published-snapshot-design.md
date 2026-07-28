# Approved Translation Published Snapshot

**Status:** Approved design

**Date:** 2026-07-28

## Purpose

Make the Obsidian action **Mark current translation reviewed** the only event
that advances the durable Russian/English comparison baseline for one public
page.

After a successful approval, the exporter must save:

```text
review/<collection>/<publicId>/published/ru.md
review/<collection>/<publicId>/published/en.md
```

The next translation preparation can then compare a slightly edited Russian
source with the last explicitly approved Russian version and ask the
translation agent to focus on only those changes.

## Current behavior

The Obsidian plugin invokes the Java `mark-reviewed` command for the active
Markdown note. The command validates the current source and English review,
changes the English review status to `reviewed`, and sets the source note's
workflow state to `ready_to_publish`.

The command does not update `published/`.

Instead, every successful non-dry-run export advances `published/` after
writing the generated Astro source trees. That makes the baseline mean "last
exported" rather than "last explicitly approved." It also permits an export
of a generated but unreviewed translation to move the comparison baseline.

## Decisions

1. A successful `mark-reviewed` action is the only event that advances
   `published/{ru,en}.md`.
2. Approval snapshots exactly one public page, identified from the validated
   current manifest entry.
3. The Java exporter owns snapshot validation and filesystem writes. The
   Obsidian plugin remains a thin client and does not copy review files.
4. Russian snapshot content is rendered from the stable current manifest
   entry. It is not reread from mutable `review/.../ru.md`.
5. English snapshot content is the exact byte sequence that the approval
   command has committed with `translationStatus: reviewed`.
6. The Russian and English files become visible as one pair-level commit.
7. Non-dry-run export and `build-from-review` no longer update the published
   baseline.
8. Existing snapshots require no migration. The next successful approval
   replaces them.

## Components

### Obsidian plugin

The existing command and bridge call remain:

```text
mark-current-translation-reviewed
  -> astro-export mark-reviewed --note <vault-relative-path>
```

The plugin performs no additional subprocess call. On success, its notice
states that the translation was reviewed and the approved version was saved.
On any failed bridge response, it retains the current diagnostics-modal
behavior and must not show the success notice.

### `AstroExportCommand.markReviewed`

The command remains the orchestration boundary. It uses the existing
per-publication lock and concurrency checks.

It additionally:

1. Produces normalized Russian review content in memory from the stable
   current `ManifestEntry`.
2. Produces the reviewed English content in memory from the validated English
   snapshot.
3. Stages both published files without changing the visible baseline.
4. Commits the reviewed English file with the existing source and review
   guards.
5. Commits `ready_to_publish` and `reviewed` to the source note with the
   existing guards.
6. Re-runs stable preflight and verifies that public identity and
   translation-source hash still match the staged Russian projection.
7. Verifies that `en.md` still exactly matches the staged reviewed English
   bytes.
8. Atomically replaces the visible `published/` pair.
9. Returns `ok: true` only after the approved pair is durable.

An already-reviewed, already-`ready_to_publish` pair still executes the
snapshot step. This makes retry after a snapshot failure idempotent.

### `ReviewWorkspace`

`ReviewWorkspace` exposes two focused operations:

- render normalized Russian review Markdown from one `ManifestEntry`;
- stage and atomically commit one `published/` RU/EN pair.

`writeRuReviewFile` reuses the same Russian renderer so ordinary `ru.md` and
approved `published/ru.md` cannot drift because of separate serializers.

The pair commit uses a sibling staging directory under the validated
`review/<collection>/<publicId>/` directory:

1. Create a private temporary directory.
2. Write `ru.md` and `en.md`.
3. Force both file contents to stable storage.
4. Recheck the supplied source and English snapshot guards.
5. For the first approval, atomically move the staging directory to
   `published/`.
6. For replacement, atomically exchange staging and `published/`, then remove
   the displaced old pair.

The implementation continues to reject escaping paths, symbolic-link leaves,
non-regular files, and unsupported unsafe replacement conditions.

### Export path

`runExport` stops calling `snapshotPublished` after `writeSite`.
`SnapshotPublishedAction` and other bulk-snapshot plumbing are removed unless
another live caller is found during implementation.

Exporting, checking, building, previewing, or deploying the site cannot move
the approval baseline.

## Data flow

```text
Active Obsidian source note
        |
        v
plugin: mark-current-translation-reviewed
        |
        v
Java mark-reviewed acquires per-page lock
        |
        +--> stable preflight -> current RU ManifestEntry -> staged RU bytes
        |
        +--> validate en.md -> reviewed EN bytes
        |
        +--> guarded commit of reviewed en.md
        |
        +--> guarded source workflow update to ready_to_publish
        |
        +--> final source/en validation
        |
        v
atomic published/ pair replacement
        |
        v
bridge ok: true -> Obsidian success notice
```

On the next `prepare`, the existing incremental-translation path reads
`published/ru.md`, compares it with the newly normalized Russian projection,
and includes the resulting source diff in the translation-agent prompt.

## Failure behavior

Failures before the published-pair exchange leave the previous
`published/` pair unchanged. If no previous pair exists, `published/` remains
absent.

Existing approval failure behavior remains in force:

- metadata, identity, source-hash, or translation validation failure blocks
  approval;
- source or English changes at a guarded boundary return `stale`;
- lock contention returns `translating`.

The current approval workflow spans the review workspace and vault, so it
cannot be one filesystem transaction. A failure may occur after `en.md` and
the source workflow state have already become reviewed/ready-to-publish but
before the published pair is committed. In that case:

- the bridge returns `ok: false`;
- the response reports the truthful durable status, normally
  `ready_to_publish`, with pair freshness `fresh` and translation status
  `reviewed`;
- a blocking `published-snapshot` diagnostic explains that the approved
  baseline was not advanced and instructs the operator to invoke the action
  again;
- the prior published pair remains intact;
- retrying the action revalidates the pair and commits the snapshot without
  requiring the English status or source workflow fields to change again.

If the atomic pair exchange succeeds but cleanup of the displaced old
directory fails, the new baseline is already committed. The command reports a
non-blocking cleanup diagnostic and preserves the displaced directory at a
reported recovery path; it does not roll the valid new baseline back.

## Testing

### Java unit and command tests

Cover:

1. First approval writes one `published/` pair containing normalized current
   Russian Markdown and exact reviewed English Markdown.
2. Approval replaces an older pair.
3. Re-approving an already-reviewed pair is idempotent.
4. A source or English change before the pair commit returns `stale` and
   preserves the older baseline.
5. A staging or exchange failure returns a blocking snapshot diagnostic and
   preserves the older baseline.
6. Retry after snapshot failure succeeds.
7. Pair replacement never exposes a mixed old/new RU/EN pair.
8. A successful non-dry-run export does not create or overwrite
   `published/`.
9. Dry-run behavior remains unchanged.
10. Existing review, workflow-state, locking, and native-CLI parity tests
    remain green.

### Incremental-translation integration test

Exercise the user-visible goal:

1. Approve Russian/English version 1 through `mark-reviewed`.
2. Make a small edit to the Russian source.
3. Run `prepare`.
4. Assert that the translation prompt contains the unified diff from approved
   Russian version 1 to the edited Russian projection.
5. Assert that unrelated Russian passages are absent from the changed lines
   in the diff.

### Obsidian plugin tests

Cover:

- success still issues one `mark-reviewed` bridge call;
- success notice mentions both review approval and saved approved version;
- a snapshot-error response opens diagnostics and never shows the success
  notice;
- no new filesystem access or second bridge command is added to the plugin.

## Documentation changes

Update the repository pipeline documentation to define `published/` as the
last explicitly approved pair and state that export/build/deploy do not
advance it. Remove documentation that describes the snapshot as a
post-export operation.

## Non-goals

- Building, previewing, or deploying Astro from the approval action.
- Moving English review files into the Obsidian vault.
- Keeping a history of every approved revision.
- Automatically approving generated translations.
- Changing translation-diff formatting or scope-diagnostic thresholds.
- Adding a new Obsidian command or a second exporter subprocess.

## Acceptance criteria

The feature is complete when:

1. A successful Obsidian approval durably saves the exact single-page
   approved RU/EN pair.
2. Approval is the sole baseline-advancement event.
3. A small subsequent Russian edit produces a translation prompt scoped
   against that approved Russian version.
4. Failed or concurrent approval cannot silently replace the previous
   baseline or produce a mixed pair.
5. Plugin, Java, integration, and native compatibility tests pass.
