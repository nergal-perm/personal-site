import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// Тематическая таксономия — совпадает с реестром TOPICS кликабельного прототипа.
// Ключи языконезависимы; локализованные подписи живут в src/lib/site.ts.
export const TOPIC_KEYS = [
  'systems',
  'software',
  'ai-work',
  'thinking',
  'reading',
  'music',
  'personal-systems',
] as const;

// ---- Общие поля публичной записи (раздел 11 продуктового документа) ----
const publicBaseFields = {
  id: z.string(), // стабильный id исходной заметки, не Astro entry.id
  title: z.string(),
  publish: z.literal(true), // экспортёр уже отфильтровал; поле — self-check в CI
  date: z.coerce.date().optional(),
  updated: z.coerce.date().optional(), // существенное обновление: meta strip, REV-штампы
  description: z.string(),
  topics: z.array(z.enum(TOPIC_KEYS)).default([]),
  tags: z.array(z.string()).default([]), // vault-теги; на страницах не отображаются
  cover: z.string().optional(),
  aliases: z.array(z.string()).default([]),
};

// ---- Редакционные поля, общие для детальных страниц (контракт прототипа) ----
const editorialFields = {
  status: z.string().optional(),
  foundational: z.boolean().default(false),
  readTime: z.number().int().positive().optional(),
  links: z.array(z.string()).default([]), // id связанных записей любой коллекции
};

// ---- Сгенерированные языковые поля ----
const translationFields = {
  language: z.enum(['ru', 'en']),
  sourceLanguage: z.literal('ru'),
  translationOf: z.string().optional(), // обязателен у en-записей (refine ниже)
  sourceHash: z.string(),
  translationStatus: z.enum(['source', 'generated', 'reviewed', 'stale', 'failed']),
  translatedAt: z.coerce.date().optional(),
  translationProfile: z.string().optional(),
};

const languageInvariant = [
  (entry: { language: string; translationOf?: string }) =>
    entry.language === 'ru' || Boolean(entry.translationOf),
  { message: 'en-запись должна указывать translationOf (id русского источника)' },
] as const;

function withLanguageInvariant<T extends z.ZodRawShape>(shape: T) {
  return z.object(shape).refine(...languageInvariant);
}

// ---- blog: эссе, тезисы, рабочие заметки — три тела, discriminated union ----
const blogShared = { ...publicBaseFields, ...translationFields, ...editorialFields };

const richTextToken = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('text'), value: z.string() }),
  z.object({ kind: z.literal('reference'), target: z.string() }),
]);

const richTextValue = z.union([z.string(), z.array(richTextToken)]);

const blogEssay = z.object({
  ...blogShared,
  contentType: z.literal('essay'),
  abstract: z.string().optional(),
  why: z.string().optional(),
  sections: z.array(
    z.object({
      id: z.string(),
      number: z.string(),
      title: z.string(),
      paragraphs: z.array(z.string()),
      steps: z.array(z.tuple([z.string(), z.string()])).optional(),
      figureCaption: z.string().optional(),
      callout: z
        .object({
          kind: z.enum(['observation', 'evidence', 'boundary', 'experiment']),
          label: z.string(),
          text: z.string(),
        })
        .optional(),
    }),
  ).default([]),
  closing: z
    .object({
      changed: z.string().optional(),
      limits: z.string().optional(),
      next: z.string().optional(),
    })
    .optional(),
  sources: z.array(z.string()).default([]),
});

const claimReference = z.object({
  label: z.string(),
  target: z.string().optional(),
});

const claimSource = z.object({
  link: claimReference.optional(),
  attestation: z.string().optional(),
  evidence: richTextValue.optional(),
  locator: richTextValue.optional(),
  confidence: z.string().optional(),
});

const blogClaim = z.object({
  ...blogShared,
  contentType: z.literal('claim'),
  statement: z.string(),
  claimKinds: z.array(z.string()).default([]),
  supports: z.array(claimReference).default([]),
  opposes: z.array(claimReference).default([]),
  assumes: z.array(claimReference).default([]),
  refines: z.array(claimReference).default([]),
  contradicts: z.array(claimReference).default([]),
  sources: z.array(claimSource).default([]),
});

const blogNote = z.object({
  ...blogShared,
  contentType: z.literal('note'),
  observation: z.string().optional(),
  model: z.string().optional(),
  boundary: z.string().optional(),
  experiment: z.string().optional(),
});

const blog = defineCollection({
  loader: glob({ pattern: '{ru,en}/**/*.md', base: './src/content/blog' }),
  schema: z
    .discriminatedUnion('contentType', [blogEssay, blogClaim, blogNote])
    .refine(...languageInvariant),
});

// ---- bibliography (маршрут на сайте — /library/) ----
const bibliography = defineCollection({
  loader: glob({ pattern: '{ru,en}/**/*.md', base: './src/content/bibliography' }),
  schema: withLanguageInvariant({
    ...publicBaseFields,
    ...translationFields,
    ...editorialFields,
    authors: z.array(z.string()).min(1),
    publication: z.string().optional(),
    publicationDate: z.coerce.date().optional(),
    start: z.coerce.date().optional(),
    end: z.coerce.date().optional(),
    readingStatus: z.string().optional(),
    use: z.string().optional(),
    boundary: z.string().optional(),
    selectedQuote: z
      .object({
        kind: z.enum(['quote', 'paraphrase']).default('paraphrase'),
        text: z.string(),
        locator: z.string().optional(),
      })
      .optional(),
  }),
});

// ---- music (маршрут на сайте — /music/) ----
const music = defineCollection({
  loader: glob({ pattern: '{ru,en}/**/*.md', base: './src/content/music' }),
  schema: withLanguageInvariant({
    ...publicBaseFields,
    ...translationFields,
    ...editorialFields,
    reviewType: z.enum(['album', 'track']),
    artist: z.string(),
    work: z.string(),
    format: z.string().optional(),
    context: z.string(),
    association: z.string(),
    listenFor: z.array(z.string()).default([]),
    care: z.string().optional(),
    releaseDate: z.coerce.date().optional(),
    genreTags: z.array(z.string()).default([]),
    streamingUrl: z.string().url().optional(),
    bandcampEmbedUrl: z.string().url().optional(),
  }),
});

// ---- concepts ----
const concepts = defineCollection({
  loader: glob({ pattern: '{ru,en}/**/*.md', base: './src/content/concepts' }),
  schema: withLanguageInvariant({
    ...publicBaseFields,
    ...translationFields,
    ...editorialFields,
    notThis: z.string().optional(),
    relations: z.array(z.object({ name: z.string(), relation: z.string() })).default([]),
    examples: z.array(z.string()).default([]),
  }),
});

export const collections = { blog, bibliography, music, concepts };
