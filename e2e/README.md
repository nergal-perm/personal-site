# End-to-end harness

Runs the real pipeline — exporter + site build — against a real vault
checkout, so a cross-cutting change to `exporter-java/` or `site/` can be
tested with actual notes instead of guessing.

## Usage

```sh
export VAULT_PATH=~/Documents/personal-wiki/knowledge-base
./e2e/run.sh
```

This runs, in order:

1. `astro-export refresh-publication-queue` — reconciles publication state
   against the vault at `VAULT_PATH`.
2. `astro-export build-from-review --out site/` — writes already-reviewed
   translation pairs into the Astro site's content tree.
3. `npm run build` inside `site/` — proves the resulting content actually
   builds.

`REVIEW_PATH` defaults to `e2e/.review` (gitignored scratch space) but can be
pointed at an existing review workspace, e.g.
`tools/astro-export/review`-style layout, via
`REVIEW_PATH=/path/to/review ./e2e/run.sh`.

## What this does *not* cover

Translating a brand-new note and reviewing it is a human-in-the-loop step
(Codex-assisted translation + manual review, see the vault's
`operating-manual/sops/astro-publication.md`) — it isn't scripted here.
This harness exercises the parts of the pipeline that *are* mechanical:
reading real vault frontmatter/state, writing into the site, and building.

## Prerequisites

- `exporter-java` built: `(cd exporter-java && mvn -Pnative native:compile)`
- `site` dependencies installed: `(cd site && npm install)`
