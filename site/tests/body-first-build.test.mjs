import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';

const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(import.meta.dirname, '..');
const contentRoot = path.join(projectRoot, 'src/content/blog');
const bibliographyRoot = path.join(projectRoot, 'src/content/bibliography');
const conceptsRoot = path.join(projectRoot, 'src/content/concepts');
const distRoot = path.join(projectRoot, 'dist');

const fixtureSlug = 'body-first-regression-7f4c';
const fixtures = [
  {
    language: 'ru',
    contentType: 'essay',
    marker: 'ASTRO_BODY_FIRST_RU_ESSAY_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST RU ESSAY H2 7F4C',
    title: 'Регрессионное эссе с телом Markdown 7F4C',
    description: 'Проверка отображения эссе, содержимое которого находится только в теле Markdown.',
    translationStatus: 'source',
  },
  {
    language: 'en',
    contentType: 'essay',
    marker: 'ASTRO_BODY_FIRST_EN_ESSAY_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST EN ESSAY H2 7F4C',
    title: 'Markdown body regression essay 7F4C',
    description: 'Checks rendering for an essay whose content exists only in the Markdown body.',
    translationStatus: 'generated',
  },
  {
    language: 'ru',
    contentType: 'note',
    marker: 'ASTRO_BODY_FIRST_RU_NOTE_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST RU NOTE H2 7F4C',
    title: 'Регрессионная заметка с телом Markdown 7F4C',
    description: 'Проверка отображения заметки, содержимое которой находится только в теле Markdown.',
    translationStatus: 'source',
  },
  {
    language: 'en',
    contentType: 'note',
    marker: 'ASTRO_BODY_FIRST_EN_NOTE_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST EN NOTE H2 7F4C',
    title: 'Markdown body regression note 7F4C',
    description: 'Checks rendering for a note whose content exists only in the Markdown body.',
    translationStatus: 'generated',
  },
  {
    language: 'ru',
    contentType: 'book',
    marker: 'ASTRO_BODY_FIRST_RU_BOOK_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST RU BOOK H3 7F4C',
    title: 'Регрессионная книга с конспектом Markdown 7F4C',
    description: 'Проверка отображения конспекта книги из тела Markdown.',
    translationStatus: 'source',
  },
  {
    language: 'en',
    contentType: 'book',
    marker: 'ASTRO_BODY_FIRST_EN_BOOK_MARKER_7F4C',
    heading: 'ASTRO BODY FIRST EN BOOK H3 7F4C',
    title: 'Markdown synopsis regression book 7F4C',
    description: 'Checks rendering for a book synopsis in the Markdown body.',
    translationStatus: 'generated',
  },
  {
    language: 'ru',
    contentType: 'concept',
    marker: 'ASTRO_BODY_FIRST_RU_CONCEPT_MARKER_7F4C',
    heading: 'Определение',
    title: 'Регрессионный concept с телом Markdown 7F4C',
    description: 'Проверка определения concept из тела Markdown.',
    translationStatus: 'source',
  },
  {
    language: 'en',
    contentType: 'concept',
    marker: 'ASTRO_BODY_FIRST_EN_CONCEPT_MARKER_7F4C',
    heading: 'Definition',
    title: 'Markdown body regression concept 7F4C',
    description: 'Checks concept definition rendering from the Markdown body.',
    translationStatus: 'generated',
  },
];

function fixtureSource(fixture) {
  const translationOf = fixture.language === 'en' ? `translationOf: "${fixtureSlug}-${fixture.contentType}"\n` : '';
  if (fixture.contentType === 'book') {
    return `---
id: "${fixtureSlug}-${fixture.contentType}"
title: "${fixture.title}"
publish: true
description: "${fixture.description}"
topics: []
tags: []
aliases: []
links: []
language: ${fixture.language}
sourceLanguage: ru
${translationOf}sourceHash: "body-first-regression-7f4c"
translationStatus: ${fixture.translationStatus}
authors:
  - Regression Author
---

${fixture.marker}

### ${fixture.heading}

Body-first regression synopsis for ${fixture.language}.
`;
  }
  if (fixture.contentType === 'concept') {
    return `---
id: "${fixtureSlug}-${fixture.contentType}"
title: "${fixture.title}"
publish: true
description: "${fixture.description}"
topics: []
tags: []
aliases: []
links: []
language: ${fixture.language}
sourceLanguage: ru
${translationOf}sourceHash: "body-first-regression-7f4c"
translationStatus: ${fixture.translationStatus}
relations: []
examples: []
---

${fixture.marker}

## ${fixture.heading}

Body-first regression definition for ${fixture.language}.
`;
  }
  return `---
id: "${fixtureSlug}-${fixture.contentType}"
title: "${fixture.title}"
publish: true
contentType: ${fixture.contentType}
description: "${fixture.description}"
topics: []
tags: []
aliases: []
links: []
language: ${fixture.language}
sourceLanguage: ru
${translationOf}sourceHash: "body-first-regression-7f4c"
translationStatus: ${fixture.translationStatus}
---

${fixture.marker}

## ${fixture.heading}

Body-first regression paragraph for ${fixture.language} ${fixture.contentType}.
`;
}

async function runBuild() {
  try {
    return await execFileAsync('npm', ['run', 'build'], {
      cwd: projectRoot,
      env: { ...process.env, CI: '1', NO_COLOR: '1' },
      maxBuffer: 50 * 1024 * 1024,
    });
  } catch (error) {
    error.message += `\n\nstdout:\n${error.stdout ?? ''}\n\nstderr:\n${error.stderr ?? ''}`;
    throw error;
  }
}

function fixturePath(fixture) {
  const root = fixture.contentType === 'book'
    ? bibliographyRoot
    : fixture.contentType === 'concept'
      ? conceptsRoot
      : contentRoot;
  return path.join(root, fixture.language, `${fixtureSlug}-${fixture.contentType}.md`);
}

function outputPath(fixture) {
  const section = fixture.contentType === 'essay'
    ? 'essays'
    : fixture.contentType === 'book'
      ? 'library'
      : fixture.contentType === 'concept'
        ? 'concepts'
        : 'notes';
  return path.join(distRoot, fixture.language, section, `${fixtureSlug}-${fixture.contentType}`, 'index.html');
}

test('build renders body-first RU/EN essays, notes, books, and concepts without invented semantic sections', { timeout: 120_000 }, async () => {
  const createdFiles = [];

  try {
    for (const fixture of fixtures) {
      const sourcePath = fixturePath(fixture);
      await mkdir(path.dirname(sourcePath), { recursive: true });
      await writeFile(sourcePath, fixtureSource(fixture), { encoding: 'utf8', flag: 'wx' });
      createdFiles.push(sourcePath);
    }

    await runBuild();

    const pages = new Map();
    for (const fixture of fixtures) {
      const html = await readFile(outputPath(fixture), 'utf8');
      pages.set(`${fixture.language}-${fixture.contentType}`, html);
      assert.match(html, new RegExp(fixture.marker), `${fixture.language} ${fixture.contentType} must render its Markdown body`);
      assert.match(html, new RegExp(fixture.heading), `${fixture.language} ${fixture.contentType} must render its Markdown H2`);
    }

    for (const language of ['ru', 'en']) {
      const noteHtml = pages.get(`${language}-note`);
      assert.doesNotMatch(noteHtml, /class="numbered-heading"/, `${language} body-first note must not render prototype blocks`);

      const essayHtml = pages.get(`${language}-essay`);
      const absentLabels = language === 'ru'
        ? ['Зачем существует этот текст', 'Что изменилось', 'Где модель может не сработать', 'Следующий эксперимент', 'Источники и provenance']
        : ['Why this exists', 'What changed', 'Where the model may fail', 'Next experiment', 'Sources and provenance'];
      for (const label of absentLabels) {
        assert.ok(!essayHtml.includes(label), `${language} body-first essay must not invent absent section: ${label}`);
      }

      const bookHtml = pages.get(`${language}-book`);
      const bookHeading = fixtures.find((fixture) => fixture.language === language && fixture.contentType === 'book').heading;
      const headingMatch = bookHtml.match(new RegExp(`<h3 id="([^"]+)">${bookHeading}</h3>`));
      assert.ok(headingMatch, `${language} book synopsis heading must render as an H3`);
      assert.ok(bookHtml.includes(`href="#${headingMatch[1]}"`), `${language} book synopsis heading must appear in the left rail`);
      assert.ok(!bookHtml.includes(language === 'ru' ? 'Центральная идея' : 'Central idea'), `${language} book must not render a central idea panel`);

      const conceptHtml = pages.get(`${language}-concept`);
      const concept = fixtures.find((fixture) => fixture.language === language && fixture.contentType === 'concept');
      assert.ok(
        conceptHtml.includes(`<p class="page-lead">${concept.description}</p>`),
        `${language} concept must retain description as its page lead`,
      );
      assert.match(
        conceptHtml,
        new RegExp(`<h2 id="[^"]+">${concept.heading}</h2>`),
        `${language} concept definition heading must render as an H2`,
      );
      assert.equal((conceptHtml.match(/<h1\b/g) ?? []).length, 1, `${language} concept must have only the page H1`);
      assert.ok(!conceptHtml.includes('class="concept-definition"'), `${language} concept must not render the legacy definition panel`);
    }
  } finally {
    await Promise.all(createdFiles.map((sourcePath) => rm(sourcePath, { force: true })));
  }
});
