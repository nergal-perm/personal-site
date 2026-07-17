// Единый реестр публичных страниц: записи четырёх коллекций + кураторские
// страницы из src/data/pages/. Аналог реестра ROUTES кликабельного прототипа —
// из него строятся связанные материалы, карта, индексы и поисковый индекс.

import { getCollection } from 'astro:content';
import {
  SECTION_BY_TYPE,
  TOPICS,
  TYPE_LABELS,
  formatDate,
  isoDate,
  type ContentType,
  type Language,
  type TopicKey,
} from './site';
import { plainText } from './rich-text';

export interface SiteEntry {
  id: string; // слаг без языкового префикса: essay-ai-process, now, essays…
  type: string; // essay | claim | note | album | book | concept | home | index | now | about | search
  url: string;
  language: Language;
  title: string;
  summary: string;
  topics: TopicKey[];
  topicLabels: string[];
  typeLabel: string;
  date?: string;
  updated?: string;
  readTime?: number;
  foundational: boolean;
  status?: string;
  searchable: boolean;
  links: string[];
  data: Record<string, any>;
}

export const CLAIM_RELATION_FIELDS = [
  'supports',
  'opposes',
  'assumes',
  'refines',
  'contradicts',
] as const;

export type ClaimRelationField = (typeof CLAIM_RELATION_FIELDS)[number];

export interface ClaimBacklink {
  relation: ClaimRelationField;
  entry: SiteEntry;
}

const pageModules = import.meta.glob('../data/pages/*/*.json', { eager: true }) as Record<
  string,
  { default: Record<string, any> }
>;

function pageUrl(language: Language, id: string): string {
  return id === 'home' ? `/${language}/` : `/${language}/${id}/`;
}

export function entryUrl(language: Language, type: ContentType, slug: string): string {
  return `/${language}/${SECTION_BY_TYPE[type]}/${slug}/`;
}

function topicLabels(topics: TopicKey[], language: Language): string[] {
  return topics.map((topic) => TOPICS[topic][language]);
}

function fromContent(
  language: Language,
  type: ContentType,
  astroId: string,
  data: Record<string, any>,
): SiteEntry {
  const slug = astroId.replace(/^(ru|en)\//, '');
  return {
    id: slug,
    type,
    url: entryUrl(language, type, slug),
    language,
    title: plainText(data.title),
    summary: plainText(data.description),
    topics: (data.topics ?? []) as TopicKey[],
    topicLabels: topicLabels((data.topics ?? []) as TopicKey[], language),
    typeLabel: TYPE_LABELS[type][language],
    date: isoDate(data.date),
    updated: isoDate(data.updated),
    readTime: data.readTime,
    foundational: Boolean(data.foundational),
    status: data.status,
    searchable: true,
    links: data.links ?? [],
    data,
  };
}

function fromPage(language: Language, data: Record<string, any>): SiteEntry {
  const type = data.type ?? data.id;
  return {
    id: data.id,
    type,
    url: pageUrl(language, data.id),
    language,
    title: plainText(data.title),
    summary: plainText(data.summary),
    topics: (data.topics ?? []) as TopicKey[],
    topicLabels: topicLabels((data.topics ?? []) as TopicKey[], language),
    typeLabel: TYPE_LABELS[type]?.[language] ?? TYPE_LABELS.index[language],
    date: data.date,
    updated: data.updated,
    foundational: false,
    status: data.status,
    searchable: Boolean(data.searchable),
    links: data.links ?? [],
    data,
  };
}

const registryCache = new Map<Language, Promise<Map<string, SiteEntry>>>();

async function buildRegistry(language: Language): Promise<Map<string, SiteEntry>> {
  const byLanguage = (entry: { data: { language: string } }) =>
    entry.data.language === language;

  const [blog, bibliography, music, concepts] = await Promise.all([
    getCollection('blog', byLanguage),
    getCollection('bibliography', byLanguage),
    getCollection('music', byLanguage),
    getCollection('concepts', byLanguage),
  ]);

  const registry = new Map<string, SiteEntry>();

  for (const entry of blog) {
    registry.set(
      entry.id.replace(/^(ru|en)\//, ''),
      fromContent(language, entry.data.contentType as ContentType, entry.id, entry.data),
    );
  }
  for (const entry of bibliography) {
    registry.set(entry.id.replace(/^(ru|en)\//, ''), fromContent(language, 'book', entry.id, entry.data));
  }
  for (const entry of music) {
    registry.set(entry.id.replace(/^(ru|en)\//, ''), fromContent(language, 'album', entry.id, entry.data));
  }
  for (const entry of concepts) {
    registry.set(entry.id.replace(/^(ru|en)\//, ''), fromContent(language, 'concept', entry.id, entry.data));
  }

  for (const [path, module] of Object.entries(pageModules)) {
    if (!path.includes(`/pages/${language}/`)) continue;
    const page = fromPage(language, module.default);
    registry.set(page.id, page);
  }

  return registry;
}

export function getRegistry(language: Language): Promise<Map<string, SiteEntry>> {
  let cached = registryCache.get(language);
  if (!cached) {
    cached = buildRegistry(language);
    registryCache.set(language, cached);
  }
  return cached;
}

export async function getEntry(language: Language, id: string): Promise<SiteEntry> {
  const registry = await getRegistry(language);
  const entry = registry.get(id);
  if (!entry) {
    throw new Error(`Registry (${language}) has no entry «${id}»`);
  }
  return entry;
}

export async function tryGetEntry(
  language: Language,
  id: string | undefined | null,
): Promise<SiteEntry | undefined> {
  if (!id) return undefined;
  const registry = await getRegistry(language);
  return registry.get(id);
}

export async function getEntriesByType(
  language: Language,
  type: string,
): Promise<SiteEntry[]> {
  const registry = await getRegistry(language);
  return [...registry.values()].filter((entry) => entry.type === type);
}

export async function getClaimBacklinks(
  language: Language,
  targetId: string,
): Promise<ClaimBacklink[]> {
  const claims = await getEntriesByType(language, 'claim');
  const backlinks: ClaimBacklink[] = [];

  for (const entry of claims) {
    for (const relation of CLAIM_RELATION_FIELDS) {
      const references = entry.data[relation];
      if (
        Array.isArray(references)
        && references.some((reference) => reference?.target === targetId)
      ) {
        backlinks.push({ relation, entry });
      }
    }
  }

  return backlinks.sort((left, right) =>
    CLAIM_RELATION_FIELDS.indexOf(left.relation) - CLAIM_RELATION_FIELDS.indexOf(right.relation)
    || String(right.entry.date ?? '').localeCompare(String(left.entry.date ?? ''))
    || left.entry.title.localeCompare(right.entry.title, language)
    || left.entry.id.localeCompare(right.entry.id),
  );
}

export async function getCollectionEntries(
  language: Language, type: ContentType, pinned: string[] = [], omitted: string[] = [],
): Promise<SiteEntry[]> {
  const registry = await getRegistry(language);
  const omittedIds = new Set(omitted);
  const seenPins = new Set<string>();
  const pins: SiteEntry[] = [];
  for (const id of pinned) {
    if (seenPins.has(id)) {
      throw new Error(`Collection pin "${id}" is duplicated`);
    }
    seenPins.add(id);
    const entry = registry.get(id);
    if (!entry) {
      throw new Error(`Collection pin "${id}" is unknown for locale "${language}"`);
    }
    if (entry.type !== type) {
      throw new Error(`Collection pin "${id}" must be type "${type}", found "${entry.type}"`);
    }
    if (!omittedIds.has(entry.id)) pins.push(entry);
  }
  const pinnedIds = new Set(pins.map((entry) => entry.id));
  const remainder = [...registry.values()]
    .filter((entry) => entry.type === type && !pinnedIds.has(entry.id) && !omittedIds.has(entry.id))
    .sort((left, right) =>
      String(right.date ?? '').localeCompare(String(left.date ?? '')) ||
      left.title.localeCompare(right.title, language) ||
      left.id.localeCompare(right.id),
    );
  return [...pins, ...remainder];
}

export async function resolveLinks(language: Language, ids: string[]): Promise<SiteEntry[]> {
  const registry = await getRegistry(language);
  return ids.map((id) => {
    const entry = registry.get(id);
    if (!entry) throw new Error(`Registry (${language}) has no linked entry «${id}»`);
    return entry;
  });
}

export async function getSearchableEntries(language: Language): Promise<SiteEntry[]> {
  const registry = await getRegistry(language);
  return [...registry.values()].filter((entry) => entry.searchable);
}

export function normalizeText(value: string): string {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLocaleLowerCase();
}

// Строка поискового индекса — то, что уходит в /{lang}/search-index.json
// и потребляется клиентским скриптом (диалог, страница поиска, фильтры архива).
export interface SearchRow {
  id: string;
  url: string;
  type: string;
  typeLabel: string;
  title: string;
  summary: string;
  topics: string[];
  topicLabels: string[];
  date: string;
  updated: string;
  readTime: number | null;
  foundational: boolean;
  dateLabel: string;
  cover: string;
  mediaCoverLabel: string;
  mediaCreator: string;
  mediaDetail: string;
  text: string;
}

function mediaFields(entry: SiteEntry): Pick<SearchRow, 'cover' | 'mediaCoverLabel' | 'mediaCreator' | 'mediaDetail'> {
  const cover = typeof entry.data.cover === 'string' ? entry.data.cover : '';

  if (entry.type === 'book') {
    const authors = Array.isArray(entry.data.authors) ? entry.data.authors.join(' · ') : '';
    return {
      cover,
      mediaCoverLabel: authors,
      mediaCreator: authors,
      mediaDetail: entry.data.publication ?? entry.data.readingStatus ?? entry.date ?? '',
    };
  }

  if (entry.type === 'album') {
    const released = formatDate(isoDate(entry.data.releaseDate), entry.language);
    return {
      cover,
      mediaCoverLabel: entry.data.work ?? entry.title,
      mediaCreator: entry.data.artist ?? '',
      mediaDetail: [released, entry.data.format].filter(Boolean).join(' · '),
    };
  }

  return {
    cover,
    mediaCoverLabel: '',
    mediaCreator: '',
    mediaDetail: '',
  };
}

export function toSearchRow(entry: SiteEntry): SearchRow {
  return {
    id: entry.id,
    url: entry.url,
    type: entry.type,
    typeLabel: entry.typeLabel,
    title: entry.title,
    summary: entry.summary,
    topics: entry.topics,
    topicLabels: entry.topicLabels,
    date: entry.date ?? '',
    updated: entry.updated ?? '',
    readTime: entry.readTime ?? null,
    foundational: entry.foundational,
    dateLabel: entry.date ? formatDate(entry.date, entry.language) : '',
    ...mediaFields(entry),
    text: normalizeText(
      `${JSON.stringify(entry.data)} ${entry.topicLabels.join(' ')} ${entry.type}`,
    ),
  };
}

export function sortRows<T extends { foundational: boolean; date?: string; title: string }>(
  rows: T[],
  sort: 'latest' | 'foundational',
  language: Language,
): T[] {
  return [...rows].sort((left, right) => {
    if (sort === 'foundational' && left.foundational !== right.foundational) {
      return Number(right.foundational) - Number(left.foundational);
    }
    return (
      String(right.date || '').localeCompare(String(left.date || '')) ||
      left.title.localeCompare(right.title, language)
    );
  });
}
