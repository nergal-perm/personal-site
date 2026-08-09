# personal-site

Publishing pipeline for the personal blog: turns notes from the private
Obsidian vault (`~/Documents/personal-wiki/knowledge-base`) into a published
Astro site. The vault itself is **not** part of this repo and stays private;
everything here is meant to be shareable/open-sourceable on its own.

## Layout

```
site/             Astro site (formerly ~/POS/software-dev/astro-blog)
publication-exporter/
                  Java bridge: vault notes -> reviewed en/ru translation pairs
obsidian-plugin/  Obsidian plugin that drives the exporter from inside the vault
                  (formerly ~/Dev/dotfiles/.obsidian/plugins/astro-publication-workflow)
e2e/              End-to-end harness: run the real pipeline against a real vault
```

## Pipeline

1. Author sets `publish: true` and the required frontmatter on a vault note.
2. The **obsidian-plugin** (`obsidian-plugin/`) runs the exporter as a CLI
   subprocess to prepare a reviewed English/Russian translation pair. See
   `obsidian-plugin/DEPLOY.md` for how this plugin gets into a running
   Obsidian instance.

The review action asks the exporter for an explicit two-target review plan.
Before the first approval it opens proposed `ru.md` and `en.md`; afterward it
opens published-to-proposed RU and EN diffs. Both targets open in one Zed
window.

3. **publication-exporter** validates the pair. Successful **Mark current translation
   reviewed** stores the exact approved page pair at
   `review/<collection>/<publicId>/published/{ru,en}.md`.
4. Later Russian edits are diffed against that approved Russian snapshot when
   the next translation draft is prepared.
5. `build-from-review`, `npm run build`, preview, and deployment consume
   reviewed content but never advance the approved baseline.
6. Deploy is currently manual (copy `site/dist/` to the host).

## Translation engine configuration

`prepare` selects its translation agent without rebuilding the native binary.
The persistent setting belongs in `<exporterRoot>/publication-exporter.toml`,
where `exporterRoot` is the exporter working directory configured in the
Obsidian plugin. This file is local configuration and is ignored when that
directory is a checkout of this repository.

To use the default Codex engine explicitly:

```toml
[translation]
engine = "codex"
```

To use Antigravity instead:

```toml
[translation]
engine = "antigravity"
```

The selection order is:

1. A non-blank `PUBLICATION_EXPORTER_TRANSLATION_ENGINE` environment variable.
2. `publication-exporter.toml` in `exporterRoot`.
3. Codex when neither is set.

For a one-run shell override, prefix the normal bridge command, for example:

```bash
PUBLICATION_EXPORTER_TRANSLATION_ENGINE=antigravity ./publication-exporter prepare ...
PUBLICATION_EXPORTER_TRANSLATION_ENGINE=codex ./publication-exporter prepare ...
```

Only lowercase `codex` and `antigravity` are valid. A selected invalid value,
malformed configuration file, or failed agent process returns the usual
`translation_failed` bridge response; the exporter does not silently fall back
to another engine.

The Antigravity option requires `agy` on the process `PATH`. It uses the skills
already installed for Antigravity and writes through the same isolated job
workspace and three-file review-output contract as Codex. Changing the config
file or environment variable needs neither a binary rebuild nor an Obsidian
plugin setting change.

## End-to-end testing

`e2e/` wires the three pieces together against a real vault checkout via a
`VAULT_PATH` environment variable — see `e2e/README.md`.
