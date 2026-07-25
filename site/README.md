# astro-blog — Quiet Systems / Living Signal

An [Astro](https://astro.build) static site that recreates the **single blog post**
view from the visual-style guide *«Визуальный стиль публичного блога»*: a warm,
book-like reading surface inside a dark, precise technical shell.

> Working formula: **Quiet Systems / Living Signal** — a calm systems editorial
> with a living cultural signal.

## What is implemented

The central product of the site — the **single-post page** — built to the guide's
recommended combination (Evidence Field Manual structure inside a Structured
Garden reading surface, with Atlas backlinks):

- deep-pine masthead with wordmark, primary nav, and RU/EN + search utilities;
- three-column layout — `On this page` TOC rail · reading column · article rails;
- numbered sections, abstract, and an annotated `FIG. 1` process figure;
- `Article details`, numbered `Sources`, and a `Related concepts` mini-map;
- a full-width `Backlinks in the atlas` band.

The example content is a real long-form note from the vault —
[*Software Archaeology*](src/content/posts/software-archaeology.md).

## Design tokens

Palette and type come from the guide (see [`src/styles/global.css`](src/styles/global.css)):

| Token | Light | Use |
| --- | --- | --- |
| Warm paper | `#F2EFE7` | reading background |
| Ink | `#17201D` | body text |
| Muted ink | `#68716D` | metadata |
| Deep pine | `#17372F` | masthead, headings |
| Signal amber | `#D2A04A` | active state, accents (~5–10%) |
| Sea glass | `#739795` | relationships, diagrams |
| Rule | `#D7D2C7` | borders, dividers |

Typography: **Source Serif 4** (body, titles), **IBM Plex Sans** (nav, headings,
labels), **IBM Plex Mono** (metadata, statuses). A `prefers-color-scheme: dark`
variant flips the reading surface to the guide's Night palette.

## Develop

```sh
npm install
npm run dev      # http://localhost:4321  (root redirects to the post)
npm run build    # static output in dist/
npm run preview
```

## Structure

```
src/
  content/posts/        Markdown posts + editorial frontmatter (schema in content.config.ts)
  layouts/PostLayout    Assembles masthead, rails, reading column, backlinks band
  components/           SiteNav, ProcessFigure, RelatedConcepts, BotanicalSprig
  pages/posts/[...slug] Renders each post through PostLayout
  styles/global.css     Design tokens and base typography
```
