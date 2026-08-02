# astro-export

Java port of the Astro exporter. The command-line entry point is `astro-export`.

## Development

Run the JVM smoke test suite:

```bash
mvn test
```

Build the native executable with GraalVM Native Image:

```bash
mvn -Pnative native:compile
target/astro-export --help
```

The Python exporter at `/Users/eugene/Documents/personal-wiki/tools/astro-export`
remains the behavioral reference while the Java implementation is migrated.

## Approved translation baseline

`astro-export mark-reviewed` is the only command that advances:

`review/<collection>/<publicId>/published/{ru.md,en.md,references.json}`

The command saves one validated RU/EN/reference triple after English review
approval and returns success only after the triple is durable. `prepare` writes
the pending candidate triple at
`review/<collection>/<publicId>/candidate/{ru.md,en.md,references.json}` and
uses the approved Russian snapshot for its next source diff. Export,
`build-from-review`, Astro build, preview, and deployment never change the
approved baseline.

In semantic mode, `references.json` is schema version 1. It binds a stable
private `pageRef`, the selected vault `sourcePath`, exact `ruSha256` and
`enSha256` values, an occurrence `order`, and per-occurrence target records.
Release materialization reads only `published/` triples. Pending candidates are
ignored until `mark-reviewed` installs them as the new approved triple.

Target approval is the link activation boundary. If a referrer is approved
while its target is private, released RU/EN output contains the approved plain
label. When the target later has an approved selected triple, the same
referrer snapshot materializes localized public links. Removing or restoring
`publish: true` on the target only changes release selection; it does not
require referrer review, rewriting, or retranslation.

If approval reports `published-snapshot`, the English/source approval may
already be durable but the prior baseline was preserved. Run **Mark current
translation reviewed** again; retry is idempotent.

## Review launch plans

`inspect-publication --json` uses bridge schema version 3. A successful
response contains `reviewPlan` with ordered `ru` and `en` targets and reports
four independent release-state fields: `candidateState`,
`approvedSnapshotState`, `semanticReferencesState`, and `releaseState`.

- `baselineState: absent` means neither approved snapshot exists; each target
  has only `proposedPath`.
- `baselineState: complete` means both approved snapshots are safe; each
  target has `publishedPath` and `proposedPath`.
- A partial or unsafe approved pair returns
  `published_snapshot_inconsistent`, a blocking `published-snapshot`
  diagnostic, and no plan.

Inspection is read-only. `mark-reviewed` still revalidates exact bytes and is
the only command that advances `published/`.

## Semantic migration and release commands

> **Historical migration/recovery surface, not the selected implementation
> path.** The binding source-ID decision upgrades legacy pages through ordinary
> Prepare, review, and `mark-reviewed`; it does not authorize the command below
> to mutate a real review workspace. `migrate-semantic-links --apply` remains
> available for recovery-compatible legacy tooling, but every real invocation
> requires separate, explicit human approval against a fresh validated
> inventory and decisions file.

Semantic migration state lives under `review/.semantic-links/`:

- `catalog-v1.json` maps stable private page references to current vault paths.
- `schema-v1.active.json` switches the review workspace into semantic mode.
- `migration-v1.journal.json` records install/recovery progress.
- `staging-v1/` and `recovery-v1/` are used for roll-forward and rollback.

Run a read-only inventory first:

```bash
target/astro-export migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /absolute/path/to/review \
  --astro /absolute/path/to/astro-site \
  --report /private/tmp/semantic-link-migration-inventory.json \
  --json
```

After reviewing the inventory, apply with explicit decisions:

```bash
target/astro-export migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /absolute/path/to/review \
  --astro /absolute/path/to/astro-site \
  --report /private/tmp/semantic-link-migration-inventory.json \
  --decisions /absolute/path/to/semantic-link-decisions.json \
  --apply \
  --json
```

If an interrupted migration leaves a journal, recover explicitly:

```bash
target/astro-export migrate-semantic-links --review /absolute/path/to/review --roll-forward --json
target/astro-export migrate-semantic-links --review /absolute/path/to/review --roll-back --json
```

`scripts/build-from-review.sh` materializes only the approved release input.
`scripts/build-astro-site.sh` materializes, runs the content/provenance gate,
and then runs `npm run build`. Direct `npm run build` in the Astro app requires
the last `.astro-export/release-provenance.json` to match the managed payload;
it rebuilds only that provenance-valid release. None of these commands deploys
automatically.
