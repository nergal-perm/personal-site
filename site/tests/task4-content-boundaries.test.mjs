import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(import.meta.dirname, "..");
const gateScript = path.join(projectRoot, "scripts/check-content.mjs");
const albumId = "album-suite-to-be-you-and-me";
const bookId = "book-the-lean-startup";
const essayId = "essay-task5-automatic";
const conceptId = "concept-task5-automatic";
const linkedClaimId = "claim-task4-linked-not-featured";
const backlinkClaimId = "claim-task4-backlink-source";
const pinnedNoteId = "note-task4-pinned";
const laterNoteId = "note-task4-later";
const staleCacheConceptId = "concept-task5-stale-cache";
const pageIds = [
  "about",
  "concepts",
  "essays",
  "home",
  "library",
  "music",
  "notes",
  "now",
  "search",
  "claims",
];

function commandEnv(extra = {}) {
  const env = { ...process.env, CI: "1", NO_COLOR: "1", ...extra };
  if (!Object.hasOwn(extra, "ASTRO_CONTENT_DIR")) delete env.ASTRO_CONTENT_DIR;
  if (!Object.hasOwn(extra, "ASTRO_PAGES_DIR")) delete env.ASTRO_PAGES_DIR;
  return env;
}

async function runGate(extraEnv = {}) {
  return execFileAsync(process.execPath, [gateScript], {
    cwd: projectRoot,
    env: commandEnv(extraEnv),
    maxBuffer: 10 * 1024 * 1024,
  });
}

function gateMarkdown(language, slug = "gate-fixture") {
  const translation = language === "en" ? `translationOf: ${slug}\n` : "";
  return `---
id: ${slug}
title: Gate fixture ${language}
publish: true
contentType: note
description: Valid synthetic gate fixture.
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-gate
translationStatus: ${language === "ru" ? "source" : "generated"}
---

Gate body.
`;
}

async function writeGateFixture(root) {
  const content = path.join(root, "content");
  const pages = path.join(root, "pages");
  for (const language of ["ru", "en"]) {
    await mkdir(path.join(content, "blog", language), { recursive: true });
    await mkdir(path.join(pages, language), { recursive: true });
    await writeFile(
      path.join(content, "blog", language, "gate-fixture.md"),
      gateMarkdown(language),
      "utf8",
    );
    for (const id of pageIds) {
      const data = {
        id,
        type: pageType(id),
        searchable: false,
        topics: [],
        links: id === "home" ? ["gate-fixture"] : [],
        title: `Gate page ${language} ${id}`,
        summary: "Valid synthetic page.",
      };
      if (id !== "search") {
        data.language = language;
        data.sourceLanguage = "ru";
        data.translationStatus = language === "ru" ? "source" : "generated";
        if (language === "en") data.translationOf = id;
      }
      await writeFile(
        path.join(pages, language, `${id}.json`),
        `${JSON.stringify(data, null, 2)}\n`,
        "utf8",
      );
    }
  }
  return { content, pages };
}

async function mutateJson(file, mutate) {
  const data = JSON.parse(await readFile(file, "utf8"));
  mutate(data);
  await writeFile(file, `${JSON.stringify(data, null, 2)}\n`, "utf8");
}

async function assertGateRejects(content, pages, pattern) {
  await assert.rejects(
    runGate({ ASTRO_CONTENT_DIR: content, ASTRO_PAGES_DIR: pages }),
    (error) => {
      const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
      assert.match(output, pattern);
      return true;
    },
  );
}

test("content gate honors valid and invalid root overrides while unset env keeps live defaults", async () => {
  const temporaryRoot = await mkdtemp(
    path.join(os.tmpdir(), "astro-gate-task4-"),
  );
  try {
    const { content, pages } = await writeGateFixture(temporaryRoot);
    const valid = await runGate({
      ASTRO_CONTENT_DIR: content,
      ASTRO_PAGES_DIR: pages,
    });
    assert.match(valid.stdout, /Content validation passed successfully/);

    await assert.rejects(
      runGate({
        ASTRO_CONTENT_DIR: path.join(temporaryRoot, "missing-content"),
        ASTRO_PAGES_DIR: pages,
      }),
      (error) => {
        const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
        assert.match(output, /ASTRO_CONTENT_DIR/);
        assert.match(output, /missing-content/);
        assert.match(output, /directory/i);
        return true;
      },
    );

    const notDirectory = path.join(temporaryRoot, "pages-file");
    await writeFile(notDirectory, "not a directory\n", "utf8");
    await assert.rejects(
      runGate({
        ASTRO_CONTENT_DIR: content,
        ASTRO_PAGES_DIR: notDirectory,
      }),
      (error) => {
        const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
        assert.match(output, /ASTRO_PAGES_DIR/);
        assert.match(output, /not a directory/i);
        assert.doesNotMatch(output, /at getFiles/);
        return true;
      },
    );

    await assert.rejects(
      runGate({
        ASTRO_CONTENT_DIR: "",
        ASTRO_PAGES_DIR: pages,
      }),
      (error) => {
        const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
        assert.match(output, /ASTRO_CONTENT_DIR must not be empty/);
        assert.match(output, /Content validation failed with 1 errors/);
        assert.doesNotMatch(output, /\[Path Error\]|\[Collection Error\]/);
        return true;
      },
    );

    const liveDefault = await runGate();
    assert.match(liveDefault.stdout, /Content validation passed successfully/);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

test("content gate enforces the complete page contract and registry invariants", async (t) => {
  async function withFixture(name, exercise) {
    await t.test(name, async () => {
      const temporaryRoot = await mkdtemp(
        path.join(os.tmpdir(), "astro-gate-contract-"),
      );
      try {
        const fixture = await writeGateFixture(temporaryRoot);
        await exercise(fixture);
      } finally {
        await rm(temporaryRoot, { recursive: true, force: true });
      }
    });
  }

  await withFixture(
    "rejects a symmetric missing required page",
    async ({ content, pages }) => {
      await rm(path.join(pages, "ru", "about.json"));
      await rm(path.join(pages, "en", "about.json"));
      await assertGateRejects(
        content,
        pages,
        /required page.*about|missing.*about/i,
      );
    },
  );

  await withFixture(
    "rejects a symmetric extra page",
    async ({ content, pages }) => {
      for (const language of ["ru", "en"]) {
        await writeFile(
          path.join(pages, language, "extra.json"),
          `${JSON.stringify(
            {
              id: "extra",
              type: "index",
              language,
              sourceLanguage: "ru",
              translationOf: language === "en" ? "extra" : undefined,
              searchable: false,
              topics: [],
              links: [],
              title: "Extra",
              summary: "Must be rejected.",
            },
            null,
            2,
          )}\n`,
          "utf8",
        );
      }
      await assertGateRejects(
        content,
        pages,
        /unexpected page.*extra|extra.*not allowed/i,
      );
    },
  );

  await withFixture(
    "rejects filename and data id mismatch",
    async ({ content, pages }) => {
      await mutateJson(path.join(pages, "ru", "home.json"), (data) => {
        data.id = "wrong-home";
      });
      await assertGateRejects(content, pages, /id.*home|home.*id/i);
    },
  );

  await withFixture(
    "rejects locale and EN translation mismatches",
    async ({ content, pages }) => {
      await mutateJson(path.join(pages, "en", "home.json"), (data) => {
        data.language = "ru";
        data.translationOf = "wrong-home";
      });
      await assertGateRejects(
        content,
        pages,
        /language.*en|translationOf.*home/i,
      );
    },
  );

  await withFixture(
    "rejects unresolved routes objects",
    async ({ content, pages }) => {
      await mutateJson(path.join(pages, "ru", "essays.json"), (data) => {
        data.routes = [{ route: "missing-entry" }];
      });
      await assertGateRejects(
        content,
        pages,
        /routes?.*missing-entry|missing-entry.*route/i,
      );
    },
  );

  await withFixture(
    "rejects cross-collection registry id collisions",
    async ({ content, pages }) => {
      for (const language of ["ru", "en"]) {
        await mkdir(path.join(content, "bibliography", language), {
          recursive: true,
        });
        await writeFile(
          path.join(content, "bibliography", language, "gate-fixture.md"),
          gateMarkdown(language),
          "utf8",
        );
      }
      await assertGateRejects(
        content,
        pages,
        /registry.*gate-fixture|duplicate.*gate-fixture/i,
      );
    },
  );

  await withFixture(
    "accepts localized rich references and rejects malformed rich tokens and invalid pins",
    async ({ content, pages }) => {
      for (const language of ["ru", "en"]) {
        await mutateJson(path.join(pages, language, "now.json"), (data) => {
          data.sections = [
            {
              label: "Current reading",
              title: [
                { kind: "text", value: "Read " },
                { kind: "reference", target: "gate-fixture" },
              ],
              text: [{ kind: "text", value: "A valid localized reference." }],
            },
          ];
        });
        await mutateJson(path.join(pages, language, "notes.json"), (data) => {
          data.pinned = ["gate-fixture"];
        });
      }
      const valid = await runGate({
        ASTRO_CONTENT_DIR: content,
        ASTRO_PAGES_DIR: pages,
      });
      assert.match(valid.stdout, /Content validation passed successfully/);

      await mutateJson(path.join(pages, "en", "now.json"), (data) => {
        data.sections[0].title = [
          { target: "gate-fixture" },
        ];
      });
      await assertGateRejects(content, pages, /rich text.*exactly.*kind.*target/i);

      await mutateJson(path.join(pages, "en", "now.json"), (data) => {
        data.sections[0].title = [{ kind: "text", value: "Valid again." }];
      });
      await mutateJson(path.join(pages, "en", "notes.json"), (data) => {
        data.pinned = ["gate-fixture", "gate-fixture"];
      });
      await assertGateRejects(content, pages, /pinned.*duplicate.*gate-fixture/i);
    },
  );

  await withFixture(
    "accepts generated collection showcases and rejects malformed showcase targets and text",
    async ({ content, pages }) => {
      for (const language of ["ru", "en"]) {
        await mutateJson(path.join(pages, language, "notes.json"), (data) => {
          data.showcase = [
            {
              target: "gate-fixture",
              text: [
                { kind: "text", value: "A valid editorial explanation for this note." },
              ],
            },
          ];
        });
      }

      const valid = await runGate({
        ASTRO_CONTENT_DIR: content,
        ASTRO_PAGES_DIR: pages,
      });
      assert.match(valid.stdout, /Content validation passed successfully/);

      await mutateJson(path.join(pages, "en", "notes.json"), (data) => {
        data.showcase[0].target = "";
      });
      await assertGateRejects(content, pages, /showcase.*target.*non-empty/i);

      await mutateJson(path.join(pages, "en", "notes.json"), (data) => {
        data.showcase[0].target = "gate-fixture";
        data.showcase[0].text = [{ kind: "reference", value: "gate-fixture" }];
      });
      await assertGateRejects(content, pages, /rich text.*exactly.*kind.*target/i);
    },
  );
});

function pageType(id) {
  if (["home", "now", "about", "search"].includes(id)) return id;
  return "index";
}

function commonPage(language, id, extra = {}) {
  const localeFields =
    id === "search"
      ? {}
      : {
          language,
          sourceLanguage: "ru",
          translationStatus: language === "ru" ? "source" : "generated",
          ...(language === "en" ? { translationOf: id } : {}),
        };
  return {
    id,
    type: pageType(id),
    searchable: false,
    topics: [],
    links: [],
    title: `TASK4_${language}_${id}_TITLE`,
    summary: `TASK4_${language}_${id}_SUMMARY`,
    eyebrow: `TASK4_${language}_${id}_EYEBROW`,
    ...localeFields,
    ...extra,
  };
}

function minimalPages(language, { includeLinkedClaim = false } = {}) {
  return {
    about: commonPage(language, "about", {
      links: [],
      lead: `TASK4_${language}_ABOUT_LEAD`,
      principles: [],
      colophon: `TASK4_${language}_COLOPHON`,
    }),
    concepts: commonPage(language, "concepts", {
      links: ["notes"],
      primaryLabel: `TASK4_${language}_HIDDEN_PRIMARY_LABEL`,
    }),
    essays: commonPage(language, "essays", {
      routes: [],
      startTitle: `TASK4_${language}_HIDDEN_START_TITLE`,
      startText: `TASK4_${language}_HIDDEN_START_TEXT`,
      listPrincipleTitle: `TASK4_${language}_LIST_RULE`,
      listPrincipleText: `TASK4_${language}_LIST_RULE_TEXT`,
      searchPlaceholder: `TASK4_${language}_SEARCH_PLACEHOLDER`,
    }),
    home: commonPage(language, "home", {
      links: [
        "concepts",
        "claims",
        albumId,
        "about",
        ...(includeLinkedClaim ? [linkedClaimId] : []),
      ],
      heroTitle: `TASK4_${language}_HOME_HERO`,
      lead: `TASK4_${language}_HOME_LEAD`,
      heroImageAlt: `TASK4_${language}_HERO_ALT`,
      focusLabel: `TASK4_${language}_FOCUS_LABEL`,
      focusTitle: `TASK4_${language}_FOCUS_TITLE`,
      focusText: `TASK4_${language}_FOCUS_TEXT`,
      focusAction: `TASK4_${language}_FOCUS_ACTION`,
      featuredLabel: `TASK4_${language}_HIDDEN_CLAIM_LABEL`,
      featuredTitle: `TASK4_${language}_HIDDEN_CLAIM_TITLE`,
      featuredText: `TASK4_${language}_HIDDEN_CLAIM_TEXT`,
      selectedTitle: `TASK4_${language}_HIDDEN_SELECTED_TITLE`,
      selected: [],
      listeningLabel: `TASK4_${language}_LISTENING_LABEL`,
      listeningAction: `TASK4_${language}_LISTENING_ACTION`,
      albumCoverAlt: `TASK4_${language}_ALBUM_ALT`,
    }),
    library: commonPage(language, "library", {
      links: ["notes"],
      items: [bookId],
    }),
    music: commonPage(language, "music", {
      links: [albumId],
      intro: `TASK4_${language}_MUSIC_INTRO`,
    }),
    notes: commonPage(language, "notes", {
      links: ["library", "concepts"],
      pinned: [pinnedNoteId],
      heading: `TASK4_${language}_HIDDEN_NOTES_HEADING`,
      groups: [],
      items: [],
      libraryAction: `TASK4_${language}_LIBRARY_ACTION`,
      conceptsAction: `TASK4_${language}_CONCEPTS_ACTION`,
    }),
    now: commonPage(language, "now", {
      links: [albumId],
      updatedLabel: `TASK4_${language}_UPDATED`,
      sections: [
        {
          label: `TASK4_${language}_READING`,
          title: [
            { kind: "text", value: `TASK4_${language}_READ ` },
            { kind: "reference", target: bookId },
          ],
          text: [{ kind: "text", value: `TASK4_${language}_READING_TEXT` }],
        },
      ],
      questionAction: `TASK4_${language}_QUESTION_ACTION`,
      listeningAction: `TASK4_${language}_NOW_LISTENING_ACTION`,
    }),
    search: commonPage(language, "search", {
      links: ["essays", "claims", "notes", "music", "library", "concepts"],
      placeholder: `TASK4_${language}_SEARCH`,
      empty: `TASK4_${language}_EMPTY`,
      noResults: `TASK4_${language}_NO_RESULTS`,
    }),
    claims: commonPage(language, "claims", {
      links: [],
      searchPlaceholder: `TASK4_${language}_CLAIMS_SEARCH`,
    }),
  };
}

function albumMarkdown(language) {
  const translation = language === "en" ? `translationOf: ${albumId}\n` : "";
  return `---
id: ${albumId}
title: TASK4_${language}_ALBUM_TITLE
publish: true
description: TASK4_${language}_ALBUM_SUMMARY
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-album
translationStatus: ${language === "ru" ? "source" : "generated"}
reviewType: album
artist: TASK4_${language}_ARTIST
work: TASK4_${language}_WORK
cover: https://example.com/task4-album-cover.jpg
format: TASK4_FORMAT
context: TASK4_CONTEXT
association: TASK4_ASSOCIATION
listenFor: []
---

TASK4 album body.
`;
}

function leanBookMarkdown(language) {
  const translation = language === "en" ? `translationOf: ${bookId}\n` : "";
  return `---
id: ${bookId}
title: TASK4_${language}_LOCALIZED_BOOK_TITLE
publish: true
description: TASK4_${language}_LEAN_SUMMARY
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-lean-book
translationStatus: ${language === "ru" ? "source" : "generated"}
authors:
  - Eric Ries
cover: https://example.com/task4-lean-cover.jpg
---
`;
}

function noteMarkdown(language, id, date, title) {
  const translation = language === "en" ? `translationOf: ${id}\n` : "";
  return `---
id: ${id}
title: ${title}
publish: true
contentType: note
date: ${date}
description: TASK4_${language}_${id}_SUMMARY
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-${id}
translationStatus: ${language === "ru" ? "source" : "generated"}
---

TASK4 note body.
`;
}

function essayMarkdown(language) {
  const translation = language === "en" ? `translationOf: ${essayId}\n` : "";
  return `---\nid: ${essayId}\ntitle: TASK5_${language}_ESSAY_TITLE\npublish: true\ncontentType: essay\ndate: 2026-06-15\ndescription: TASK5_${language}_ESSAY_SUMMARY\ntopics: []\ntags: []\naliases: []\nlinks: []\nlanguage: ${language}\nsourceLanguage: ru\n${translation}sourceHash: task5-essay\ntranslationStatus: ${language === "ru" ? "source" : "generated"}\n---\n\nTASK5 essay body.\n`;
}

function conceptMarkdown(language) {
  const translation = language === "en" ? `translationOf: ${conceptId}\n` : "";
  return `---\nid: ${conceptId}\ntitle: TASK5_${language}_CONCEPT_TITLE\npublish: true\ndate: 2026-06-14\ndescription: TASK5_${language}_CONCEPT_SUMMARY\ntopics: []\ntags: []\naliases: []\nlinks: []\nlanguage: ${language}\nsourceLanguage: ru\n${translation}sourceHash: task5-concept\ntranslationStatus: ${language === "ru" ? "source" : "generated"}\ndefinition: TASK5 concept definition.\nrelations: []\nexamples: []\n---\n`;
}

function claimMarkdown(language) {
  const translation =
    language === "en" ? `translationOf: ${linkedClaimId}\n` : "";
  return `---
id: ${linkedClaimId}
title: TASK4_${language}_LINKED_CLAIM_TITLE
publish: true
contentType: claim
date: 2026-07-09
description: TASK4_${language}_LINKED_CLAIM_SUMMARY
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-linked-claim
translationStatus: ${language === "ru" ? "source" : "generated"}
statement: TASK4_${language}_CLAIM_STATEMENT
claimKinds:
  - causal
supports:
  - label: TASK4_${language}_SUPPORTS_LABEL
opposes: []
assumes: []
refines:
  - label: TASK4_${language}_REFINES_LINKED_LABEL
    target: ${linkedClaimId}
contradicts: []
sources:
  - link:
      label: The Lean Startup
      target: ${bookId}
    attestation: explicit
    evidence:
      - kind: text
        value: TASK4_${language}_CLAIM_EVIDENCE
    locator:
      - kind: text
        value: Introduction
    confidence: high
---

TASK4_${language}_CLAIM_BODY
`;
}

function backlinkClaimMarkdown(language) {
  const translation =
    language === "en" ? `translationOf: ${backlinkClaimId}\n` : "";
  return `---
id: ${backlinkClaimId}
title: TASK4_${language}_BACKLINK_CLAIM_TITLE
publish: true
contentType: claim
date: 2026-07-08
description: TASK4_${language}_BACKLINK_CLAIM_SUMMARY
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translation}sourceHash: task4-backlink-claim
translationStatus: ${language === "ru" ? "source" : "generated"}
statement: TASK4_${language}_BACKLINK_CLAIM_STATEMENT
claimKinds:
  - causal
supports:
  - label: TASK4_${language}_LINKED_CLAIM_TITLE
    target: ${linkedClaimId}
opposes: []
assumes: []
refines: []
contradicts: []
sources: []
---

TASK4_${language}_BACKLINK_CLAIM_BODY
`;
}

function staleCacheConceptMarkdown(language) {
  const translationOf =
    language === "en" ? `translationOf: ${staleCacheConceptId}\n` : "";
  return `---
id: ${staleCacheConceptId}
title: TASK5 stale cache concept ${language}
publish: true
description: Disposable content-cache replacement fixture.
topics: []
tags: []
aliases: []
links: []
language: ${language}
sourceLanguage: ru
${translationOf}sourceHash: task5-stale-cache
translationStatus: ${language === "ru" ? "source" : "generated"}
definition: Disposable concept that must disappear after managed-tree replacement.
relations: []
examples: []
---
`;
}

async function writeStaleCacheConcept(root) {
  for (const language of ["ru", "en"]) {
    await writeFile(
      path.join(
        root,
        "src/content/concepts",
        language,
        `${staleCacheConceptId}.md`,
      ),
      staleCacheConceptMarkdown(language),
      "utf8",
    );
  }
}

async function writeMinimalGeneratedTrees(
  root,
  { includeLinkedClaim = false, includeBacklinkClaim = false } = {},
) {
  for (const collection of ["bibliography", "blog", "concepts", "music"]) {
    for (const language of ["ru", "en"]) {
      await mkdir(path.join(root, "src/content", collection, language), {
        recursive: true,
      });
    }
  }
  for (const language of ["ru", "en"]) {
    await writeFile(
      path.join(root, "src/content/music", language, `${albumId}.md`),
      albumMarkdown(language),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/bibliography", language, `${bookId}.md`),
      leanBookMarkdown(language),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/blog", language, `${pinnedNoteId}.md`),
      noteMarkdown(language, pinnedNoteId, "2026-01-01", `TASK4_${language}_PINNED_NOTE`),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/blog", language, `${laterNoteId}.md`),
      noteMarkdown(language, laterNoteId, "2026-06-01", `TASK4_${language}_LATER_NOTE`),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/blog", language, `${essayId}.md`),
      essayMarkdown(language),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/concepts", language, `${conceptId}.md`),
      conceptMarkdown(language),
      "utf8",
    );
    await writeFile(
      path.join(root, "src/content/blog", language, `${linkedClaimId}.md`),
      claimMarkdown(language),
      "utf8",
    );
    if (includeBacklinkClaim) {
      await writeFile(
        path.join(root, "src/content/blog", language, `${backlinkClaimId}.md`),
        backlinkClaimMarkdown(language),
        "utf8",
      );
    }
    const pages = minimalPages(language, { includeLinkedClaim });
    const pageRoot = path.join(root, "src/data/pages", language);
    await mkdir(pageRoot, { recursive: true });
    for (const id of pageIds) {
      await writeFile(
        path.join(pageRoot, `${id}.json`),
        `${JSON.stringify(pages[id], null, 2)}\n`,
        "utf8",
      );
    }
  }
  await mkdir(path.join(root, "public/assets/vault"), { recursive: true });
}

async function copyDisposableProject(options = {}) {
  const temporaryParent = await mkdtemp(
    path.join(os.tmpdir(), "astro-build-task4-"),
  );
  const fixtureRoot = path.join(temporaryParent, "astro-blog");
  const excluded = [
    ".astro",
    ".git",
    "dist",
    "node_modules",
    "tests",
    "src/content",
    "src/data/pages",
    "public/assets/vault",
  ];
  await cp(projectRoot, fixtureRoot, {
    recursive: true,
    filter(source) {
      const relative = path.relative(projectRoot, source);
      return !excluded.some(
        (entry) =>
          relative === entry || relative.startsWith(`${entry}${path.sep}`),
      );
    },
  });
  const sourceModules = path.join(projectRoot, "node_modules");
  const fixtureModules = path.join(fixtureRoot, "node_modules");
  await mkdir(fixtureModules);
  for (const entry of await readdir(sourceModules, { withFileTypes: true })) {
    if (entry.name === ".vite" || entry.name === ".astro") continue;
    await symlink(
      path.join(sourceModules, entry.name),
      path.join(fixtureModules, entry.name),
      entry.isDirectory() ? "dir" : "file",
    );
  }
  await mkdir(path.join(fixtureModules, ".vite"));
  await mkdir(path.join(fixtureModules, ".astro"));
  await writeMinimalGeneratedTrees(fixtureRoot, options);
  for (const language of ["ru", "en"]) {
    await writeFile(
      path.join(fixtureRoot, "src/pages", language, "now.astro"),
      `---\nimport NowPage from '../../views/NowPage.astro';\n---\n\n<NowPage language="${language}" />\n`,
      "utf8",
    );
  }
  return { temporaryParent, fixtureRoot };
}

async function runFixtureBuild(fixtureRoot) {
  await writeFixtureReleaseProvenance(fixtureRoot);
  return execFileAsync("npm", ["run", "build"], {
    cwd: fixtureRoot,
    env: commandEnv(),
    maxBuffer: 50 * 1024 * 1024,
  });
}

async function writeFixtureReleaseProvenance(root) {
  await mkdir(path.join(root, ".astro-export"), { recursive: true });
  const withoutDigest = {
    schemaVersion: 1,
    selectedPages: [],
    managedTrees: await Promise.all(payloadRoots().map(async (relative) => ({
      relative,
      sha256: await hashFixtureTree(path.join(root, relative)),
    }))),
    managedFiles: await hashFixturePayloadFiles(root),
    activationCount: 0,
    deactivationCount: 0,
    payloadDigest: "",
  };
  const manifest = {
    ...withoutDigest,
    payloadDigest: sha256(Buffer.from(JSON.stringify(withoutDigest), "utf8")),
  };
  await writeFile(
    path.join(root, ".astro-export/release-provenance.json"),
    JSON.stringify(manifest),
    "utf8",
  );
}

function payloadRoots() {
  return [
    "public/assets/vault",
    "src/content",
    "src/data/pages",
  ];
}

async function hashFixturePayloadFiles(root) {
  const records = [];
  for (const relativeRoot of payloadRoots()) {
    const treeRoot = path.join(root, relativeRoot);
    for (const filePath of await listFixtureTree(treeRoot)) {
      const relative = slash(path.relative(root, filePath));
      const stat = fs.lstatSync(filePath);
      if (stat.isDirectory()) continue;
      records.push({ path: relative, sha256: sha256(await readFile(filePath)) });
    }
  }
  return records.sort((left, right) => left.path.localeCompare(right.path));
}

async function hashFixtureTree(root) {
  const digest = crypto.createHash("sha256");
  for (const filePath of await listFixtureTree(root)) {
    const relative = slash(path.relative(root, filePath));
    const relativeBytes = Buffer.from(relative, "utf8");
    const stat = fs.lstatSync(filePath);
    const payload = stat.isDirectory() ? Buffer.alloc(0) : await readFile(filePath);
    digest.update(Buffer.from(stat.isDirectory() ? "D" : "F"));
    digest.update(lengthBuffer(relativeBytes.length));
    digest.update(relativeBytes);
    digest.update(lengthBuffer(payload.length));
    digest.update(payload);
  }
  return digest.digest("hex");
}

async function listFixtureTree(root) {
  const found = [];
  async function visit(dir) {
    for (const entry of (await readdir(dir, { withFileTypes: true }))
        .sort((left, right) => left.name.localeCompare(right.name))) {
      const absolute = path.join(dir, entry.name);
      found.push(absolute);
      if (entry.isDirectory()) await visit(absolute);
    }
  }
  await visit(root);
  return found.sort((left, right) =>
    slash(path.relative(root, left)).localeCompare(slash(path.relative(root, right))),
  );
}

function lengthBuffer(length) {
  const buffer = Buffer.alloc(8);
  buffer.writeBigInt64BE(BigInt(length));
  return buffer;
}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function slash(value) {
  return value.split(path.sep).join("/");
}

async function filesWithExtensions(root, extensions) {
  const found = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = path.join(root, entry.name);
    if (entry.isDirectory())
      found.push(...(await filesWithExtensions(absolute, extensions)));
    else if (extensions.has(path.extname(entry.name))) found.push(absolute);
  }
  return found;
}

function collectionPrimitivePage(pins) {
  return `---
import { getCollectionEntries } from '../../lib/registry';

const entries = await getCollectionEntries('en', 'note', ${JSON.stringify(pins)});
---

{entries.map((entry) => <p>{entry.id}</p>)}
`;
}

function collectionComponentFixturePage(language, { showcase, collectionType }) {
  return `---
import ShowcaseSidebar from '../../components/ShowcaseSidebar.astro';
import CollectionSearchControls from '../../components/CollectionSearchControls.astro';

const language = ${JSON.stringify(language)};
const showcase = ${JSON.stringify(showcase)};
---

<ShowcaseSidebar
  language={language}
  title={[{ kind: 'text', value: 'TASK6_SHOWCASE_TITLE' }]}
  items={showcase}
/>
<CollectionSearchControls
  language={language}
  collectionType=${JSON.stringify(collectionType)}
  placeholder={[{ kind: 'text', value: 'TASK6_SEARCH_PLACEHOLDER' }]}
  resultsId="task6-results"
/>
<div id="task6-results"></div>
`;
}

test(
  "collection primitives render localized showcases and fixed-type search without a type control",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject();
    try {
      for (const language of ["ru", "en"]) {
        const pageRoot = path.join(fixtureRoot, "src/pages", language);
        await writeFile(
          path.join(pageRoot, "collection-showcase.astro"),
          collectionComponentFixturePage(language, {
            showcase: [{
              target: essayId,
              text: [{ kind: "text", value: "TASK6_SHOWCASE_PROSE" }],
            }],
            collectionType: "essay",
          }),
          "utf8",
        );
        await writeFile(
          path.join(pageRoot, "collection-empty.astro"),
          collectionComponentFixturePage(language, {
            showcase: [],
            collectionType: "note",
          }),
          "utf8",
        );
      }

      await runFixtureBuild(fixtureRoot);

      for (const language of ["ru", "en"]) {
        const showcaseHtml = await readFile(
          path.join(fixtureRoot, "dist", language, "collection-showcase", "index.html"),
          "utf8",
        );
        const emptyHtml = await readFile(
          path.join(fixtureRoot, "dist", language, "collection-empty", "index.html"),
          "utf8",
        );
        assert.match(showcaseHtml, /data-component="showcase"/);
        assert.match(showcaseHtml, new RegExp(`/${language}/essays/${essayId}/`));
        assert.match(showcaseHtml, /TASK6_SHOWCASE_PROSE/);
        assert.doesNotMatch(emptyHtml, /data-component="showcase"/);
        assert.match(emptyHtml, /data-component="collection-search"/);

        for (const [html, collectionType] of [
          [showcaseHtml, "essay"],
          [emptyHtml, "note"],
        ]) {
          assert.match(
            html,
            new RegExp(`data-collection-type="${collectionType}"`),
            "collection search should keep its fixed type in data attributes",
          );
          assert.doesNotMatch(
            html,
            /data-filter="task6-results-type"/,
            "collection search should not render a redundant type select",
          );
        }
      }
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test("collection filter hardcodes its type from the collection declaration", async () => {
  const siteScript = await readFile(path.join(projectRoot, "src/scripts/site.js"), "utf8");
  assert.match(siteScript, /function initCollectionIndexFilters\(/);
  assert.match(siteScript, /type:\s*container\.dataset\.collectionType/);
  assert.doesNotMatch(siteScript, /function initEssaysIndexFilters\(/);
});

test(
  "collection showcase routes render a sidebar, fixed-type search, and an automatic list",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject({
      includeLinkedClaim: true,
    });
    const collections = [
      { id: "essays", type: "essay", target: essayId, resultsId: "essay-results" },
      { id: "claims", type: "claim", target: linkedClaimId, resultsId: "claims-results" },
      { id: "notes", type: "note", target: pinnedNoteId, resultsId: "notes-results" },
      { id: "music", type: "album", target: albumId, resultsId: "music-results" },
      { id: "library", type: "book", target: bookId, resultsId: "library-results" },
      { id: "concepts", type: "concept", target: conceptId, resultsId: "concepts-results" },
    ];
    try {
      for (const language of ["ru", "en"]) {
        for (const collection of collections) {
          await mutateJson(
            path.join(fixtureRoot, "src/data/pages", language, `${collection.id}.json`),
            (page) => {
              page.showcase = [{
                target: collection.target,
                text: [{ kind: "text", value: `TASK6_${language}_${collection.id}_PICK` }],
              }];
              page.searchPlaceholder = `TASK6_${language}_${collection.id}_SEARCH`;
            },
          );
        }
      }

      await runFixtureBuild(fixtureRoot);

      for (const language of ["ru", "en"]) {
        for (const collection of collections) {
          const html = await readFile(
            path.join(fixtureRoot, "dist", language, collection.id, "index.html"),
            "utf8",
          );
          assert.match(html, /data-component="showcase"/);
          assert.match(html, new RegExp(`/${language}/[^/]+/${collection.target}/`));
          assert.match(html, new RegExp(`TASK6_${language}_${collection.id}_PICK`));
          assert.match(html, /data-component="collection-search"/);
          assert.match(html, new RegExp(`data-collection-type="${collection.type}"`));
          assert.match(html, new RegExp(`id="${collection.resultsId}"`));
          assert.doesNotMatch(
            html,
            new RegExp(`data-filter="${collection.resultsId}-type"`),
            `${collection.id} should not render a redundant type select`,
          );
        }
      }
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test(
  "collection showcase routes keep search and the automatic list without editorial picks",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject();
    try {
      for (const language of ["ru", "en"]) {
        await mutateJson(
          path.join(fixtureRoot, "src/data/pages", language, "notes.json"),
          (page) => {
            page.showcase = [];
            page.searchPlaceholder = `TASK6_${language}_notes_SEARCH`;
          },
        );
      }
      await runFixtureBuild(fixtureRoot);
      for (const language of ["ru", "en"]) {
        const html = await readFile(
          path.join(fixtureRoot, "dist", language, "notes", "index.html"),
          "utf8",
        );
        assert.doesNotMatch(html, /data-component="showcase"/);
        assert.match(html, /index-layout--single/);
        assert.match(html, /data-component="collection-search"/);
        assert.match(html, /id="notes-results"/);
        assert.match(html, new RegExp(`/${language}/notes/${pinnedNoteId}/`));
      }
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test("no-showcase collection layout is a shared one-column style", async () => {
  const globalStyles = await readFile(
    path.join(projectRoot, "src/styles/global.css"),
    "utf8",
  );

  assert.match(
    globalStyles,
    /\.index-layout--single\s*\{\s*grid-template-columns:\s*minmax\(0,\s*1fr\);\s*\}/,
  );
});

test("Now page omits decorative quadrant numbering", async () => {
  const nowPage = await readFile(
    path.join(projectRoot, "src/views/NowPage.astro"),
    "utf8",
  );
  const globalStyles = await readFile(
    path.join(projectRoot, "src/styles/global.css"), "utf8");

  assert.doesNotMatch(nowPage, /<span>\{String\(index \+ 1\)\.padStart\(2, '0'\)\}<\/span>/);
  assert.doesNotMatch(globalStyles, /\.now-grid article > span\s*\{/);
});

test("music index is a standard album collection without a featured hero", async () => {
  const musicIndex = await readFile(
    path.join(projectRoot, "src/views/MusicIndex.astro"),
    "utf8",
  );

  assert.match(musicIndex, /<PageHeader\b/);
  assert.doesNotMatch(musicIndex, /page\.intro/);
  assert.doesNotMatch(musicIndex, /music-(?:intro|approach)/);
  assert.match(
    musicIndex,
    /\{showcase\.length > 0 && <ShowcaseSidebar\b/,
  );
  assert.equal(
    (musicIndex.match(/<CollectionSearchControls\b/g) ?? []).length,
    1,
  );
  assert.match(musicIndex, /collectionType="album"/);
  assert.match(
    musicIndex,
    /getCollectionEntries\(language, 'album', page\.pinned \?\? \[\]\)/,
  );
  assert.match(musicIndex, /\{albums\.map\(\(item\) => \(/);
  assert.doesNotMatch(musicIndex, /albums\.(?:filter|find|slice|splice|reduce)\s*\(/);
  assert.doesNotMatch(musicIndex, /BandcampPlayer|tryGetEntry|page\.featured/);
  assert.doesNotMatch(musicIndex, /music-feature|bandcamp-player--large/);
});

test("music and library collections use filter-stable media rows", async () => {
  const [musicIndex, libraryIndex, siteScript, globalStyles] = await Promise.all([
    readFile(path.join(projectRoot, "src/views/MusicIndex.astro"), "utf8"),
    readFile(path.join(projectRoot, "src/views/LibraryIndex.astro"), "utf8"),
    readFile(path.join(projectRoot, "src/scripts/site.js"), "utf8"),
    readFile(path.join(projectRoot, "src/styles/global.css"), "utf8"),
  ]);

  for (const [name, view] of [
    ["MusicIndex", musicIndex],
    ["LibraryIndex", libraryIndex],
  ]) {
    assert.match(view, /class="article-list"/, `${name} should use article-list`);
    assert.match(view, /article-row--media/, `${name} should render media rows`);
    assert.doesNotMatch(view, /class="library-grid"/, `${name} should not use the old card grid`);
    assert.doesNotMatch(view, /class="book-card"/, `${name} should not render book-card results`);
  }

  assert.doesNotMatch(libraryIndex, /quote-policy|quotePolicy/, "Library quote policy card is removed");
  assert.match(musicIndex, /src=\{item\.data\.cover\}/, "MusicIndex should render real album art when cover exists");
  assert.match(libraryIndex, /src=\{book\.data\.cover\}/, "LibraryIndex should render real book cover when cover exists");
  assert.match(siteScript, /function renderMediaArticleRowHtml\(/);
  assert.match(siteScript, /row\.cover/, "filtered media rows should receive cover URLs from the search index");
  assert.match(siteScript, /row\.type === 'book' \|\| row\.type === 'album'/);
  assert.match(globalStyles, /\.article-row--media\s*\{/);
});

test("collection index pages omit the redundant all-materials heading", async () => {
  const collectionViews = [
    "ConceptsIndex.astro",
    "EssaysIndex.astro",
    "LibraryIndex.astro",
    "MusicIndex.astro",
    "ClaimsIndex.astro",
  ];

  for (const viewName of collectionViews) {
    const view = await readFile(path.join(projectRoot, "src/views", viewName), "utf8");
    assert.doesNotMatch(view, /\{labels\.allEssays\}/, `${viewName} should not render the all-materials heading`);
    assert.doesNotMatch(view, /aria-labelledby="(?:concepts|materials|library|music|work)-heading"/, `${viewName} should not point at a removed heading`);
    assert.match(view, /<section class="index-main" aria-label=\{plainText\(page\.title\)\}/, `${viewName} should keep an accessible section label`);
  }
});

test("fixed-type collection rows omit the visible record type column", async () => {
  const [articleRow, essaysIndex, notesIndex, conceptsIndex, topicPage, siteScript, globalStyles] =
    await Promise.all([
      readFile(path.join(projectRoot, "src/components/ArticleRow.astro"), "utf8"),
      readFile(path.join(projectRoot, "src/views/EssaysIndex.astro"), "utf8"),
      readFile(path.join(projectRoot, "src/views/NotesIndex.astro"), "utf8"),
      readFile(path.join(projectRoot, "src/views/ConceptsIndex.astro"), "utf8"),
      readFile(path.join(projectRoot, "src/views/TopicPage.astro"), "utf8"),
      readFile(path.join(projectRoot, "src/scripts/site.js"), "utf8"),
      readFile(path.join(projectRoot, "src/styles/global.css"), "utf8"),
    ]);

  assert.match(articleRow, /showType\?: boolean/);
  assert.match(articleRow, /\{showType && <span class="article-row__type">/);

  for (const [name, view] of [
    ["EssaysIndex", essaysIndex],
    ["NotesIndex", notesIndex],
    ["ConceptsIndex", conceptsIndex],
  ]) {
    assert.match(view, /<ArticleRow\b[^>]*showType=\{false\}/, `${name} should hide fixed collection type labels`);
  }

  assert.match(topicPage, /<ArticleRow language=\{language\} entry=\{entry\} \/>/);
  assert.match(siteScript, /function renderCollectionArticleRowHtml\(/);
  assert.doesNotMatch(siteScript, /function renderCollectionArticleRowHtml[\s\S]*article-row__type/);
  assert.match(globalStyles, /\.article-row--no-type\s*\{/);
});

test("notes claims and concepts collection pages omit secondary intro blocks", async () => {
  const [notesIndex, claimsIndex, conceptsIndex] = await Promise.all([
    readFile(path.join(projectRoot, "src/views/NotesIndex.astro"), "utf8"),
    readFile(path.join(projectRoot, "src/views/ClaimsIndex.astro"), "utf8"),
    readFile(path.join(projectRoot, "src/views/ConceptsIndex.astro"), "utf8"),
  ]);

  assert.doesNotMatch(notesIndex, /section-heading|page\.heading|page\.groups|notes-heading/);
  assert.match(notesIndex, /<section class="index-main" aria-label=\{plainText\(page\.title\)\}/);

  assert.doesNotMatch(claimsIndex, /page-thesis|page\.thesis/);
  assert.match(claimsIndex, /<CollectionSearchControls\b/);

  assert.doesNotMatch(conceptsIndex, /concept-index|primaryLabel|mapRuleLabel|mapHint|mapAction/);
  assert.match(conceptsIndex, /<CollectionSearchControls\b/);
});

test(
  "single claim pages reuse essay sidebar rails for relations and metadata sources",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject({
      includeLinkedClaim: true,
      includeBacklinkClaim: true,
    });
    try {
      await runFixtureBuild(fixtureRoot);

      const html = await readFile(
        path.join(fixtureRoot, "dist/en/claims", linkedClaimId, "index.html"),
        "utf8",
      );
      assert.match(html, /class="shell post-layout"/);
      assert.match(html, /class="post-rail post-rail--left"/);
      assert.match(html, /class="post-body markdown-content"/);
      assert.match(html, /class="post-rail post-rail--right"/);
      assert.doesNotMatch(html, /class="shell note-grid"/);

      const leftRail = html.match(/<aside class="post-rail post-rail--left">([\s\S]*?)<\/aside>/)?.[1];
      const rightRail = html.match(/<aside class="post-rail post-rail--right">([\s\S]*?)<\/aside>/)?.[1];
      assert.ok(leftRail, "claim page should render a left rail");
      assert.ok(rightRail, "claim page should render a right rail");
      assert.match(
        leftRail,
        /<div class="post-rail__on-page">\s*<strong>Supports<\/strong>\s*<ol>\s*<li>\s*<span class="post-rail__item"><span>01<\/span>TASK4_en_SUPPORTS_LABEL<\/span>\s*<\/li>\s*<\/ol>\s*<\/div>/,
      );
      assert.match(
        leftRail,
        new RegExp(`<div class="post-rail__on-page">\\s*<strong>Refines<\\/strong>\\s*<ol>\\s*<li>\\s*<a href="/en/claims/${linkedClaimId}/"><span>01<\\/span>TASK4_en_REFINES_LINKED_LABEL<\\/a>\\s*<\\/li>\\s*<\\/ol>\\s*<\\/div>`),
      );
      assert.match(
        leftRail,
        new RegExp(`<div class="post-rail__on-page">\\s*<strong>Supported by<\\/strong>\\s*<ol>\\s*<li>\\s*<a href="/en/claims/${backlinkClaimId}/"><span>01<\\/span>TASK4_en_BACKLINK_CLAIM_TITLE<\\/a>\\s*<\\/li>\\s*<\\/ol>\\s*<\\/div>`),
      );
      const russianHtml = await readFile(
        path.join(fixtureRoot, "dist/ru/claims", linkedClaimId, "index.html"),
        "utf8",
      );
      const russianLeftRail = russianHtml.match(/<aside class="post-rail post-rail--left">([\s\S]*?)<\/aside>/)?.[1];
      assert.ok(russianLeftRail, "Russian claim page should render a left rail");
      assert.match(
        russianLeftRail,
        new RegExp(`<div class="post-rail__on-page">\\s*<strong>Поддерживается<\\/strong>\\s*<ol>\\s*<li>\\s*<a href="/ru/claims/${backlinkClaimId}/"><span>01<\\/span>TASK4_ru_BACKLINK_CLAIM_TITLE<\\/a>\\s*<\\/li>\\s*<\\/ol>\\s*<\\/div>`),
      );
      assert.doesNotMatch(leftRail, /<strong>Claim relations<\/strong>/);
      assert.doesNotMatch(leftRail, /Sources|Claim details/);
      assert.doesNotMatch(rightRail, /Claim details|<dl>|<dt>Type<\/dt>|<dt>Language<\/dt>/);
      assert.match(rightRail, /<div class="revision-stamp">causal<br>2026-07-09<\/div>/);
      assert.doesNotMatch(rightRail, /CLAIM<br>/);
      assert.match(rightRail, /causal/);
      assert.match(
        rightRail,
        new RegExp(`<div class="post-rail__on-page">\\s*<strong>Sources<\\/strong>\\s*<ol>\\s*<li>\\s*<div class="post-rail__source">\\s*<a class="post-rail__source-title" href="/en/library/${bookId}/"><span>01<\\/span>The Lean Startup<\\/a>\\s*<div class="post-rail__source-meta">\\s*Introduction\\s*·\\s*high\\s*<\\/div>\\s*<p>TASK4_en_CLAIM_EVIDENCE<\\/p>\\s*<\\/div>\\s*<\\/li>\\s*<\\/ol>\\s*<\\/div>`),
      );
      assert.doesNotMatch(rightRail, /<strong><a href="\/en\/library\//);
      assert.doesNotMatch(rightRail, /Supports/);
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test(
  "getCollectionEntries rejects invalid direct pins instead of silently dropping or duplicating them",
  { timeout: 180_000 },
  async () => {
    for (const pins of [
      [pinnedNoteId, pinnedNoteId],
      ["missing-direct-pin"],
      [bookId],
    ]) {
      const { temporaryParent, fixtureRoot } = await copyDisposableProject();
      try {
        const page = path.join(fixtureRoot, "src/pages/en/collection-primitive.astro");
        await writeFile(page, collectionPrimitivePage(pins), "utf8");
        await assert.rejects(
          runFixtureBuild(fixtureRoot),
          (error) => {
            const output = `${error.stdout ?? ""}\n${error.stderr ?? ""}`;
            assert.match(output, /pin.*(duplicate|unknown|type)|duplicate.*pin|unknown.*pin|wrong.*type/i);
            return true;
          },
        );
      } finally {
        await rm(temporaryParent, { recursive: true, force: true });
      }
    }
  },
);

test(
  "disposable minimal generated tree builds from authored references without prototype fallbacks",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject();
    try {
      await runFixtureBuild(fixtureRoot);

      const dist = path.join(fixtureRoot, "dist");
      const home = await readFile(path.join(dist, "en/index.html"), "utf8");
      const claims = await readFile(
        path.join(dist, "en/claims/index.html"),
        "utf8",
      );
      const now = await readFile(path.join(dist, "en/now/index.html"), "utf8");
      const nowRu = await readFile(path.join(dist, "ru/now/index.html"), "utf8");
      const concepts = await readFile(
        path.join(dist, "en/concepts/index.html"),
        "utf8",
      );
      const essays = await readFile(
        path.join(dist, "en/essays/index.html"),
        "utf8",
      );
      const notes = await readFile(
        path.join(dist, "en/notes/index.html"),
        "utf8",
      );
      const library = await readFile(
        path.join(dist, "en/library/index.html"),
        "utf8",
      );
      const music = await readFile(
        path.join(dist, "en/music/index.html"),
        "utf8",
      );
      const about = await readFile(
        path.join(dist, "en/about/index.html"),
        "utf8",
      );
      const book = await readFile(
        path.join(dist, "en/library", bookId, "index.html"),
        "utf8",
      );

      assert.doesNotMatch(
        home,
        /TASK4_en_HIDDEN_CLAIM_TITLE|TASK4_en_HIDDEN_SELECTED_TITLE/,
      );
      assert.match(now, new RegExp(`/en/music/${albumId}/`));
      assert.match(now, new RegExp(`/en/library/${bookId}/`));
      assert.match(nowRu, new RegExp(`/ru/music/${albumId}/`));
      assert.match(nowRu, new RegExp(`/ru/library/${bookId}/`));
      assert.match(now, /TASK4_en_LOCALIZED_BOOK_TITLE/);
      assert.doesNotMatch(now, /\[object Object\]|\[\[The Lean Startup\]\]/);
      assert.doesNotMatch(concepts, /concept-index/);
      assert.doesNotMatch(essays, /class="index-sidebar"/);
      assert.match(essays, /index-layout--single/);
      assert.doesNotMatch(notes, /TASK4_en_HIDDEN_NOTES_HEADING/);
      assert.match(essays, new RegExp(`/en/essays/${essayId}/`));
      assert.match(claims, new RegExp(`/en/claims/${linkedClaimId}/`));
      assert.match(notes, new RegExp(`/en/notes/${pinnedNoteId}/`));
      assert.match(library, new RegExp(`/en/library/${bookId}/`));
      assert.match(
        music,
        /<img class="album-cover album-cover--suite album-cover--image" src="https:\/\/example\.com\/task4-album-cover\.jpg" alt="TASK4_en_WORK — TASK4_en_ARTIST"/,
      );
      assert.match(
        library,
        /<img class="book-cover book-cover--image" src="https:\/\/example\.com\/task4-lean-cover\.jpg" alt="Cover of TASK4_en_LOCALIZED_BOOK_TITLE"/,
      );
      assert.match(concepts, new RegExp(`/en/concepts/${conceptId}/`));
      const colophon = about.match(
        /<aside class="shell colophon">([\s\S]*?)<\/aside>/,
      )?.[1];
      assert.ok(colophon, "about colophon should render");
      assert.doesNotMatch(colophon, /href=/);
      assert.match(book, /TASK4_en_LOCALIZED_BOOK_TITLE/);
      assert.match(book, /Eric Ries/);
      assert.doesNotMatch(book, /Central idea/);
      assert.match(
        book,
        /<img class="book-cover book-cover--large book-cover--image" src="https:\/\/example\.com\/task4-lean-cover\.jpg" alt="Cover of TASK4_en_LOCALIZED_BOOK_TITLE"/,
      );
      assert.doesNotMatch(
        book,
        /href="\/en\/notes\/" aria-current="page"/,
      );
      assert.doesNotMatch(
        book,
        /DONELLA|MEADOWS|THINKING IN SYSTEMS|Systems thinking/,
      );
      assert.doesNotMatch(book, /How I use it|Boundary/);
      assert.doesNotMatch(book, /Related materials/);
      assert.ok(
        notes.indexOf("TASK4_en_PINNED_NOTE") < notes.indexOf("TASK4_en_LATER_NOTE"),
        "pinned notes must precede later automatic entries",
      );

      const outputFiles = await filesWithExtensions(
        dist,
        new Set([".html", ".json"]),
      );
      const output = (
        await Promise.all(outputFiles.map((file) => readFile(file, "utf8")))
      ).join("\n");
      for (const forbidden of [
        "/en/work/",
        "/ru/work/",
        "Case study",
        "Case file",
        "case-observable-publishing",
        "concept-working-artifacts",
        "essay-ai-process",
        "note-recovery-path",
        "book-thinking-in-systems",
        "Suite to Be You and Me",
        "Ideas in relation",
        "View all concepts",
        "Working Artifacts",
      ]) {
        assert.ok(
          !output.includes(forbidden),
          `disposable build must not contain prototype fallback: ${forbidden}`,
        );
      }
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test(
  "home omits stale featured copy when a surviving linked claim is not the authored featured id",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject({
      includeLinkedClaim: true,
    });
    try {
      await runFixtureBuild(fixtureRoot);

      const dist = path.join(fixtureRoot, "dist");
      const home = await readFile(path.join(dist, "en/index.html"), "utf8");
      const claims = await readFile(
        path.join(dist, "en/claims/index.html"),
        "utf8",
      );

      assert.match(claims, /TASK4_en_LINKED_CLAIM_TITLE/);
      assert.match(claims, new RegExp(`/en/claims/${linkedClaimId}/`));
      assert.doesNotMatch(home, /data-home-pattern="case"/);
      assert.doesNotMatch(home, /TASK4_en_HIDDEN_CLAIM_TITLE/);
      assert.doesNotMatch(home, new RegExp(`/en/claims/${linkedClaimId}/`));
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);

test(
  "build drops cached records after complete generated-tree replacement",
  { timeout: 180_000 },
  async () => {
    const { temporaryParent, fixtureRoot } = await copyDisposableProject();
    try {
      await writeStaleCacheConcept(fixtureRoot);
      await runFixtureBuild(fixtureRoot);

      const staleRoute = path.join(
        fixtureRoot,
        "dist",
        "en",
        "concepts",
        staleCacheConceptId,
        "index.html",
      );
      assert.match(
        await readFile(staleRoute, "utf8"),
        /TASK5 stale cache concept en/,
      );

      await rm(path.join(fixtureRoot, "src/content"), {
        recursive: true,
        force: true,
      });
      await rm(path.join(fixtureRoot, "src/data/pages"), {
        recursive: true,
        force: true,
      });
      await rm(path.join(fixtureRoot, "public/assets/vault"), {
        recursive: true,
        force: true,
      });
      await writeMinimalGeneratedTrees(fixtureRoot);

      await runFixtureBuild(fixtureRoot);

      await assert.rejects(
        readFile(staleRoute, "utf8"),
        (error) => error?.code === "ENOENT",
      );
    } finally {
      await rm(temporaryParent, { recursive: true, force: true });
    }
  },
);
