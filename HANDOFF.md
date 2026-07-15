# HANDOFF — Rebuild of the Astro site from the clickable prototype

**Date:** 2026-07-14
**Repo:** `/Users/eugene/POS/software-dev/astro-blog/`
**State:** mid-rebuild, ~60% done. Content model and most views exist; client script, remaining views, page wrappers, search endpoints, build gate, and the first successful build are still missing. **The project does not build yet** (pages/ tree is empty — `astro build` would produce almost nothing and the layout imports `../scripts/site.js` which does not exist yet).

## 1. The task (full description)

Recreate the Astro blog **from scratch** in this repo so that it reproduces, as a full-fledged statically-generated Astro site, the single-HTML clickable prototype located at:

- `~/Documents/personal-wiki/knowledge-base/private/projects/Публичный сайт/prototype/`
  - `content.js` — the content contract: registry of 17 content routes + `not-found`, full RU/EN pairs
  - `app.js` — 18 render functions (markup to port), interactions, search logic
  - `styles.css` — the design system (4143 lines), already copied verbatim
  - `index.html` — shell markup (masthead, dialog, footer), already ported into the base layout
  - `DESIGN.md`, `README.md` — design rationale and acceptance criteria

The target folder/collection structure is defined by the architecture note (read it before continuing):

- `~/Documents/personal-wiki/knowledge-base/private/projects/Публичный сайт/Архитектура Astro-сайта — папки и коллекции.md`

Key decisions from that note (already implemented here):

- Collections: `blog` (discriminated union `essay | case | note`), `bibliography`, `music`, `concepts`; layout on disk `src/content/<collection>/{ru,en}/<slug>.md`.
- **URL slugs ≠ collection names.** Nav sections: `essays`, `work`, `notes` (all fed by `blog` via `contentType`), `music`, `library` (= bibliography), `concepts`; utility: `map`, `now`, `search`, `about`; topic nav at `topics/[topic]` (7 fixed topic keys, NOT free tags).
- Parallel page trees `/ru/...` and `/en/...`; RU is source of truth; pairing by same slug.
- Curated pages (home, map, now, about, plus all index intros and search labels) live as JSON in `src/data/pages/{ru,en}/*.json` — 11 files per language.
- Cross-collection related links: frontmatter `links: [id, …]`; cards are resolved from the registry at render time (`src/lib/registry.ts`), never authored as strings.
- A pre-build gate script must verify ru/en id parity, resolvable `links`, and locale completeness (port of prototype `validateInternalLinks()`), and fail the build otherwise.

Fidelity rule used throughout: **keep the prototype's DOM structure and class names exactly**, so the copied `styles.css` applies 1:1. When porting a render function, compare against `app.js` — classes, wrappers, aria attributes, `data-component` markers, `data-home-pattern` markers.

## 2. Completed steps

1. **Git safety:** previous uncommitted WIP saved via `git stash push -u -m "pre-rebuild snapshot: single-post + home WIP"` (recover with `git stash list` / `git stash pop` — do NOT pop over the new work). `src/` was then deleted and rebuilt. Nothing has been committed. Do not commit unless the user asks.
2. **Design system:** `src/styles/global.css` = verbatim copy of prototype `styles.css`. Assets copied to `public/assets/` (`signal-and-tide-coast-panorama.png`, `signal-and-tide-coast.webp`).
3. **`src/content.config.ts`** — schemas exactly per the architecture note: `TOPIC_KEYS`, `publicBaseFields` (id, title, publish:true literal, date, updated, description, topics enum array, tags, cover, aliases), `editorialFields` (status, foundational, readTime, links), `translationFields` (language, sourceLanguage, translationOf, sourceHash, translationStatus, translatedAt, translationProfile) + language invariant refine; `blog` = `z.discriminatedUnion('contentType', [blogEssay, blogCase, blogNote])`; `bibliography` (authors, publication, readingStatus, centralIdea, use, boundary, selectedQuote{kind: quote|paraphrase, text, locator}); `music` (reviewType, artist, work, format, context, association, listenFor, care, …); `concepts` (definition, notThis, relations{name,relation}[], examples).
4. **`src/lib/site.ts`** — LANGUAGES, TOPICS labels, TYPE_LABELS, SECTION_BY_TYPE (essay→essays, case→work, note→notes, album→music, book→library, concept→concepts), PRIMARY_NAV, UTILITY_NAV, full UI string dictionary (port of app.js `UI`), `otherLanguage`, `formatDate` (ru-RU / en-GB, UTC), `isoDate`.
5. **`src/lib/registry.ts`** — unified registry (port of prototype `ROUTES` idea): merges the 4 collections + `src/data/pages/*/*.json` (via `import.meta.glob` eager) into `Map<slug, SiteEntry>` per language, cached. Exposes `getRegistry`, `getEntry` (throws on unknown id), `resolveLinks` (throws on broken link — acts as an implicit link gate during build), `getSearchableEntries` (content entries always searchable; JSON pages only when `searchable:true` — that's `now` and `about`), `normalizeText`, `toSearchRow` (includes `text` = normalized JSON.stringify of data for full-text matching, `dateLabel` preformatted), `sortRows(rows, 'latest'|'foundational', lang)` — same comparator as prototype `searchContent`.
6. **Content files (12):** `src/content/blog/{ru,en}/essay-ai-process.md`, `case-observable-publishing.md`, `note-recovery-path.md`; `src/content/music/{ru,en}/album-suite-to-be-you-and-me.md`; `src/content/bibliography/{ru,en}/book-thinking-in-systems.md`; `src/content/concepts/{ru,en}/concept-working-artifacts.md`. All frontmatter ported verbatim from prototype `content.js` (RU + EN locales). EN files carry `translationOf`, `translationStatus: reviewed`, `translatedAt`. `sourceHash` values are `proto-<slug>-001` placeholders.
7. **Page data JSON (22):** `src/data/pages/{ru,en}/{home,essays,work,notes,music,library,concepts,map,now,about,search}.json` — all curated texts from prototype routes, including home's six-pattern contract (hero/focus/case/essays/music/paths), map center+nodes+legend, now sections, about principles/colophon, index intros, search labels. `now`/`about` have `searchable: true`; `now` has `date: 2026-07-13`.
8. **`src/layouts/Base.astro`** — port of `index.html` shell + `renderNavigation()`: fontsource imports (Source Serif 4 400/600/700, IBM Plex Sans 400–700, IBM Plex Mono 400/500), global.css, head with `hreflang` alternates, early inline theme script (localStorage `quiet-theme`, prefers-color-scheme fallback), skip-link, reading-progress bar, masthead with primary/utility nav + `aria-current` via `active` prop, language toggle as a real `<a>` to the mirrored URL (swap `/ru/`↔`/en/` prefix), theme button, mobile menu, footer nav (map/library/concepts/now/about), search `<dialog>` (ids: `search-dialog`, `global-search-input`, `global-search-type`, `global-search-results`), `<script> import '../scripts/site.js'</script>` (FILE NOT YET CREATED — see remaining work), body attrs: `data-page-kind`, `data-language`, `data-search-index="/{lang}/search-index.json"`. Search buttons carry `data-action="open-search"` and `data-search-fallback={/{lang}/search/}`.
9. **Components (9):** `MetaStrip` (renderMetadata), `PageHeader`, `ArticleRow`, `RelatedStrip` (resolves `links` via registry), `MiniSystemDiagram`, `Callout`, `ProcessFigure`, `AlbumCover` (sizes default/large/xl), `BookCover` (default/large).
10. **Views done (11 of 16):** `Home.astro` (all six `data-home-pattern` blocks), `EssaysIndex.astro` (sidebar "start here", filter controls with `data-filter="essay-query|essay-topic|essay-type|essay-sort"`, statically rendered initial list in `#essay-results`, foundational-first, includes ALL searchable entries incl. now/about — prototype behavior), `WorkIndex.astro` (case-row with dossier dl, first 6 fields), `NotesIndex.astro`, `MusicIndex.astro`, `LibraryIndex.astro`, `ConceptsIndex.astro`, `EssayPage.astro` (rails, sections, figure, callouts, closing, related strip), `CasePage.astro`, `NotePage.astro`, `AlbumPage.astro`, `BookPage.astro`, `ConceptPage.astro`. Views take `{ language }` (indexes) or `{ language, entry: SiteEntry }` (details) and render the full page via `Base`.

## 3. Remaining actions (in order)

1. **Views (4):**
   - `src/views/MapPage.astro` — port `renderMap()`: `.atlas-section` with `.atlas-map` (center node → link to `concept-working-artifacts` via `centerId`, 5 positioned `.atlas-node--N` links from `page.nodes` resolved through registry, 5 `.atlas-line--N` `<i>`s) + `.atlas-legend` with `mapLegend` label and node list. `active="map"`, `pageKind="map"`.
   - `src/views/NowPage.astro` — port `renderNow()`: PageHeader, `.now-stamp` with `updatedLabel`, `.now-grid` of 4 sections (number, eyebrow label, h2, p), `.collection-links` with links to `essay-ai-process` (`questionAction`) and `album-suite-to-be-you-and-me` (`listeningAction`). `active="now"`.
   - `src/views/AboutPage.astro` — port `renderAbout()`: PageHeader, `.about-lead`, `.principles-list` (3 items), `.colophon` with links to `work` and `now`. `active="about"`.
   - `src/views/SearchPage.astro` — port `renderSearchPage()`: PageHeader, `.search-page` with controls (`data-filter="page-query|page-type|page-topic"`; type options: essay, case, note, album, book, concept, now, about; topic options from TOPICS), `.search-page__heading` with `<span>{results}</span><b>0</b>`, `#page-search-results` container with empty-state `<p class="empty-state">{page.empty}</p>`. Results are entirely client-rendered. `active="search"`.
   - `src/views/TopicPage.astro` — new page kind (architecture §3, replaces prototype-less tags): PageHeader (eyebrow = UI.topicPage, title = topic label), `.article-list` of searchable entries filtered by topic, latest-first. Reuse `ArticleRow`.
2. **Page wrappers** under `src/pages/ru/` and `src/pages/en/` (each 3–8 lines, pass `language`):
   - `index.astro` → `Home`
   - `essays/index.astro`; `essays/[id].astro` with `getStaticPaths` = blog entries where `language===X && contentType==='essay'`, `params.id = entry.id.replace(/^(ru|en)\//,'')`, build `SiteEntry` via registry `getEntry(lang, slug)` and render `EssayPage`
   - `work/index.astro`, `work/[id].astro` (contentType 'case' → `CasePage`)
   - `notes/index.astro`, `notes/[id].astro` (contentType 'note' → `NotePage`)
   - `music/index.astro`, `music/[id].astro` (music → `AlbumPage`)
   - `library/index.astro`, `library/[id].astro` (bibliography → `BookPage`)
   - `concepts/index.astro`, `concepts/[id].astro` (concepts → `ConceptPage`)
   - `topics/[topic].astro` (getStaticPaths over TOPIC_KEYS → `TopicPage`)
   - `search.astro`, `map.astro`, `now.astro`, `about.astro`
   - `search-index.json.ts` — static endpoint: `GET` returns `JSON.stringify((await getSearchableEntries(lang)).map(toSearchRow))`.
   - `src/pages/404.astro` — port `renderNotFound()`: bilingual or RU-primary with actions "Вернуться на главную" (`/ru/`), "Открыть карту" (`/ru/map/`), search link; keep `.not-found` markup ("404 / ROUTE").
3. **Client script `src/scripts/site.js`** (referenced by Base — build fails without it). Port from prototype `app.js`, minus the hash router:
   - theme toggle (`[data-action="theme"]`, localStorage `quiet-theme`, set `document.documentElement.dataset.theme`, aria-pressed)
   - mobile menu (`[data-action="menu"]`, `#mobile-nav` hidden toggle, `body.menu-open`, close on link click / Escape / resize >900px)
   - language toggle: it's an `<a>`; before navigation store `sessionStorage['quiet-scroll'] = scrollY`; on load, if flag present, restore scroll and clear (prototype preserves reading position on language switch); also persist `quiet-language` preference on every page load from `body[data-language]`
   - search dialog: open via `[data-action="open-search"]` (fallback: if `dialog.showModal` missing, navigate to `data-search-fallback`), lazy-fetch `body[data-search-index]` once, filter with ported `searchContent` logic (normalize NFKD lowercase, `row.text.includes(query)`, type filter, sort latest, slice 6), render `.article-row` markup (same classes as ArticleRow — build with DOM APIs or escaped strings), empty/no-results states from embedded UI strings (read texts from the dialog's existing DOM or inline dictionary keyed by `data-language`), close on Escape / `[data-action="close-search"]`
   - essays index filters: `input`/`change` on `[data-filter^="essay-"]` → re-render `#essay-results` from the fetched index (query+topic+type+sort incl. foundational-first comparator)
   - search page: `[data-filter^="page-"]` → render `#page-search-results` + update `.search-page__heading b` count; empty state when no query and both filters `all`
   - reading progress: port `updateReadingProgress()` (targets `.post-body, .note-grid, .case-sections, .album-body, .book-body, .concept-body`, sets `#reading-progress span` width), on scroll/resize, passive
4. **Build gate `scripts/check-content.mjs`** (architecture §6.1 + prototype `validateInternalLinks()`): add `yaml` as devDependency. Parse frontmatter of all `src/content/**/*.md` + all `src/data/pages/*/*.json`; verify (a) ru/en slug sets identical per collection, (b) every `links` id + every referenced id in page JSON (`selected`, `paths[].route`, `items`, `featured`, `primary`, `centerId`, `nodes[].id`) resolves to a known slug/page id, (c) every entry has title+description(/summary), (d) en entries carry `translationOf` equal to the ru slug, (e) `publish: true` everywhere. Exit non-zero with a readable report. Wire `package.json`: `"build": "node scripts/check-content.mjs && astro build"`, add `"check": "node scripts/check-content.mjs"`.
5. **Build & fix:** `npm install` (adds `yaml`), then `npm run build`. Expect first-pass issues: TS strictness in `.astro` files, `z.discriminatedUnion(...).refine(...)` acceptance by Astro content layer (if it errors, wrap differently — e.g. use `superRefine` on a plain union or validate the invariant only in the gate script), fontsource subpath imports (verify installed versions expose `/400.css` files), `import.meta.glob` JSON default export shape.
6. **Verification (before claiming done):**
   - `npm run build` green including gate; `npm run preview` and click through every route in both languages: `/ru/`, `/ru/essays/`, `/ru/essays/essay-ai-process/`, `/ru/work/`, `/ru/work/case-observable-publishing/`, `/ru/notes/`, `/ru/notes/note-recovery-path/`, `/ru/music/`, `/ru/music/album-suite-to-be-you-and-me/`, `/ru/library/`, `/ru/library/book-thinking-in-systems/`, `/ru/concepts/`, `/ru/concepts/concept-working-artifacts/`, `/ru/topics/systems/`, `/ru/map/`, `/ru/now/`, `/ru/about/`, `/ru/search/`, same under `/en/`, plus `/` redirect and 404.
   - Grep `dist/` for `href="#/"` (must be none — all links are real paths) and for unresolved `undefined`.
   - Check search dialog + essays filters + search page in the browser (JS), theme + language toggles, mobile menu.
   - Compare home desktop layout against prototype acceptance (six `data-home-pattern` blocks visible; see prototype `DESIGN.md` §"desktop overview").
   - Report results honestly; leave repo uncommitted.

## 4. Gotchas / conventions

- **Language of communication:** answer the user in the language they write (recent turns: English). Vault notes are Russian; code comments here are Russian (match existing style).
- Astro `entry.id` for glob collections is the path relative to base **without extension** (`ru/essay-ai-process`) — always strip `^(ru|en)/` to get the slug.
- `resolveLinks`/`getEntry` **throw** on unknown ids — during `astro build` this is a feature (acts as a link gate), don't swallow the error.
- Active-nav mapping (prototype `activeSection()`): essay→essays, case→work, album→music, note/book/concept→**notes** (book/concept detail pages highlight "Заметки"; library/concepts index pages highlight nothing in the primary nav — pass `active="library"` / `"concepts"` which match no primary item). Already followed in the existing detail views.
- Essays archive intentionally includes `now`/`about` rows (prototype behavior, documented in the architecture note §7 item 11).
- The `text-link` markup is `<a class="text-link" href>label <span aria-hidden="true">→</span></a>`.
- Home hero image: `/assets/signal-and-tide-coast-panorama.png`, width 2172 height 724, `data-image-archetype="signal-and-tide"`.
- `favicon.svg` still exists in `public/` from the old repo; head references `/favicon.svg`.
- The old `astro.config.mjs` survives (redirect `/` → `/ru/`, shiki github-light) — fine as is.
- The vault-side architecture note (§7) documents every prototype↔architecture discrepancy and is the authority on intent; the prototype is the authority on markup and content.
