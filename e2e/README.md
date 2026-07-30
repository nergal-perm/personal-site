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

1. `astro-export build-from-review --out site/` — materializes only approved
   review triples or legacy approved pairs into the Astro site's content tree.
2. `npm run build` inside `site/` — proves the resulting content actually
   builds.

`REVIEW_PATH` defaults to `e2e/.review` (gitignored scratch space) but can be
pointed at an existing review workspace, e.g.
`tools/astro-export/review`-style layout, via
`REVIEW_PATH=/path/to/review ./e2e/run.sh`.

## What this does *not* cover

The harness deliberately does not advance `published/`. That baseline belongs
only to the human approval action in Obsidian/`mark-reviewed`; the harness
tests later mechanical export and site-build consumption.

Translating a brand-new note and reviewing it is a human-in-the-loop step
(Codex-assisted translation + manual review, see the vault's
`operating-manual/sops/astro-publication.md`) — it isn't scripted here.
This harness exercises the parts of the pipeline that *are* mechanical:
reading real vault frontmatter/state, writing into the site, and building.

## Synthetic semantic fixture

Task 11 adds a committed synthetic semantic workspace:

- `fixtures/semantic-vault/` contains publishable A and B notes.
- `fixtures/semantic-review/` contains `.semantic-links/catalog-v1.json`,
  `schema-v1.active.json`, `migration-v1.journal.json`, A's candidate triple,
  A's approved triple, and B's approved target triple.

Run it from the repository root or from `site/`:

```sh
./e2e/run-synthetic.sh
# or
(cd site && ../e2e/run-synthetic.sh)
```

The script copies the Astro app to its own temp directory, removes managed
output from that copy, links the existing `site/node_modules`, materializes the
approved fixture release, and then merges the current site registry/content
dependencies needed by Astro's global routes into that temp tree. It runs
`npm run build`, asserts RU/EN target links in the generated fixture source and
HTML, and deletes only its own temp directory.

The fixture demonstrates the release boundary: pending `candidate/` files are
ignored, target B's approved triple activates A's semantic occurrence, and no
`ref:` or `vault-ref-` payload appears in public output. No e2e command deploys
automatically.

## Prerequisites

- `exporter-java` built: `(cd exporter-java && mvn -Pnative native:compile)`
- `site` dependencies installed: `(cd site && npm install)`
