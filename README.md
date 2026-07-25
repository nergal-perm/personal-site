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
3. **exporter-java** (`exporter-java/`) is the CLI itself: validates
   frontmatter, translates, and writes review files.
4. Reviewed content is built into the **site** (`site/`) with
   `astro-export build-from-review`, then `npm run build` / `npm run preview`.
5. Deploy is currently manual (copy `site/dist/` to the host).

## End-to-end testing

`e2e/` wires the three pieces together against a real vault checkout via a
`VAULT_PATH` environment variable — see `e2e/README.md`.
