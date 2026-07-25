# Deploying this plugin to Obsidian

## How it's wired up (already done, for reference)

There is no build step and no packaging — Obsidian loads `main.js`,
`manifest.json`, and `styles.css` directly from a folder under
`.obsidian/plugins/<plugin-id>/`.

The chain that makes this repo the live source:

```
knowledge-base/.obsidian                                (vault)
  -> symlink -> ~/Dev/dotfiles/.obsidian                  (dotfiles repo)
       .obsidian/plugins/astro-publication-workflow
         -> symlink -> ~/Dev/personal-site/obsidian-plugin  (this repo)
```

`~/Documents/personal-wiki/private/projects/.../.obsidian` and any other
vault that points at the same dotfiles `.obsidian` picks up this plugin the
same way.

`data.json` (per-install settings: paths to the exporter binary, etc.) lives
in this folder but is **gitignored** — it's runtime state, not source, and is
never part of this repo's history.

## Day-to-day: editing the plugin

Because the live plugin folder *is* a symlink into this repo, there is
nothing to copy or deploy for a normal edit:

1. Edit `main.js` / `bridge-client.js` / `manifest.json` / `styles.css` here.
2. In Obsidian: **Settings → Community plugins → toggle "Подготовка
   публикаций для Astro" off, then on** (or **Ctrl/Cmd+R** / "Reload app
   without saving" from the command palette) to force Obsidian to re-read
   `main.js`.
3. `manifest.json` changes (version bump, `minAppVersion`, etc.) need a full
   Obsidian restart, not just a plugin toggle.

`main.js` intentionally **inlines** the same logic as `bridge-client.js`
rather than `require`-ing it at runtime (see the comment at the top of
`main.js`) — Obsidian's plugin loader does not resolve relative requires
rooted at the plugin directory. `bridge-client.js` exists for the test suite
(`tests/bridge-client.test.cjs`) and as the readable source of truth; **when
you change one, update the other.**

## Setting this up on a new machine / fresh dotfiles checkout

If dotfiles gets re-cloned somewhere the symlink doesn't exist yet:

```sh
ln -s ~/Dev/personal-site/obsidian-plugin \
      ~/Dev/dotfiles/.obsidian/plugins/astro-publication-workflow
```

(adjust the `personal-site` path if it lives somewhere else on that
machine). No `npm install` or build is required — the plugin has no
dependencies of its own; it only shells out to the exporter binary
(`exporter-java`, built separately — see `../exporter-java/README.md`).

## Verifying the exporter path after moving `exporter-java`

`data.json.exporterBinary` holds an absolute path to the built exporter
executable. It was updated as part of this migration to point at
`~/Dev/personal-site/exporter-java/target/astro-export`. If you rebuild the
exporter (`mvn -Pnative native:compile` in `exporter-java/`) at a different
location, update that path via the plugin's settings tab or by editing
`data.json` directly.
