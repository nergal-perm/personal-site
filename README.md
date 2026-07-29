# personal-site

Publishing pipeline for the personal blog: turns notes from the private
Obsidian vault (`~/Documents/personal-wiki/knowledge-base`) into a published
Astro site. The vault itself is **not** part of this repo and stays private;
everything here is meant to be shareable/open-sourceable on its own.

## Layout

```
site/             Astro site (formerly ~/POS/software-dev/astro-blog)
exporter-java/    Java exporter: vault notes -> reviewed en/ru translation pairs
                  (formerly ~/Dev/astro-export-java)
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
opens published-to-proposed RU and EN diffs. Each target opens in a separate
new Zed workspace window.

3. **exporter-java** validates the pair. Successful **Mark current translation
   reviewed** stores the exact approved page pair at
   `review/<collection>/<publicId>/published/{ru,en}.md`.
4. Later Russian edits are diffed against that approved Russian snapshot when
   the next translation draft is prepared.
5. `build-from-review`, `npm run build`, preview, and deployment consume
   reviewed content but never advance the approved baseline.
6. Deploy is currently manual (copy `site/dist/` to the host).

## End-to-end testing

`e2e/` wires the three pieces together against a real vault checkout via a
`VAULT_PATH` environment variable — see `e2e/README.md`.
