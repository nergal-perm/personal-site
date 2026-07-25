// Языки, таксономия, подписи и UI-словарь.
// Перенос реестров LANGUAGES / TOPICS / TYPE_LABELS / PRIMARY_NAV / UTILITY_NAV
// и словаря UI из кликабельного прототипа (prototype/content.js, prototype/app.js).

export const LANGUAGES = ['ru', 'en'] as const;
export type Language = (typeof LANGUAGES)[number];

export type TopicKey =
  | 'systems'
  | 'software'
  | 'ai-work'
  | 'thinking'
  | 'reading'
  | 'music'
  | 'personal-systems';

export const TOPICS: Record<TopicKey, Record<Language, string>> = {
  systems: { ru: 'Системы и стратегия', en: 'Systems and strategy' },
  software: { ru: 'Инженерия ПО', en: 'Software craft' },
  'ai-work': { ru: 'ИИ и рабочая практика', en: 'AI and working practice' },
  thinking: { ru: 'Мышление и модели', en: 'Thinking and models' },
  reading: { ru: 'Чтение', en: 'Reading' },
  music: { ru: 'Музыка и культура', en: 'Music and culture' },
  'personal-systems': { ru: 'Личные системы', en: 'Personal systems' },
};

export type ContentType = 'essay' | 'claim' | 'note' | 'album' | 'book' | 'concept';

export const TYPE_LABELS: Record<string, Record<Language, string>> = {
  home: { ru: 'Главная', en: 'Home' },
  index: { ru: 'Коллекция', en: 'Collection' },
  essay: { ru: 'Эссе', en: 'Essay' },
  claim: { ru: 'Тезис', en: 'Claim' },
  note: { ru: 'Рабочая заметка', en: 'Working note' },
  album: { ru: 'Альбом', en: 'Album' },
  book: { ru: 'Книга', en: 'Book' },
  concept: { ru: 'Концепт', en: 'Concept' },
  now: { ru: 'Сейчас', en: 'Now' },
  about: { ru: 'Обо мне', en: 'About' },
  search: { ru: 'Поиск', en: 'Search' },
  topic: { ru: 'Тема', en: 'Topic' },
  'not-found': { ru: 'Не найдено', en: 'Not found' },
};

// Раздел сайта (URL-слаг) для каждого типа детальной страницы.
export const SECTION_BY_TYPE: Record<ContentType, string> = {
  essay: 'essays',
  claim: 'claims',
  note: 'notes',
  album: 'music',
  book: 'library',
  concept: 'concepts',
};

export const PRIMARY_NAV = [
  { id: 'essays', label: { ru: 'Эссе', en: 'Essays' } },
  { id: 'claims', label: { ru: 'Тезисы', en: 'Claims' } },
  { id: 'notes', label: { ru: 'Заметки', en: 'Notes' } },
  { id: 'music', label: { ru: 'Музыка', en: 'Music' } },
  { id: 'about', label: { ru: 'Обо мне', en: 'About' } },
] as const;

export const UTILITY_NAV = [
  { id: 'search', label: { ru: 'Поиск', en: 'Search' } },
] as const;

export const UI = {
  ru: {
    primaryNav: 'Основная навигация',
    utilityNav: 'Вспомогательная навигация',
    menu: 'Меню',
    close: 'Закрыть',
    theme: 'Тема',
    switchLanguage: 'Switch to English',
    search: 'Поиск',
    allTypes: 'Все типы',
    allTopics: 'Все темы',
    latest: 'Сначала новые',
    foundational: 'Сначала базовые',
    read: 'читать',
    published: 'Опубликовано',
    updated: 'Обновлено',
    source: 'RU · источник',
    translation: 'EN · перевод',
    startHere: 'Начать здесь',
    related: 'Связанные материалы',
    sources: 'Источники и provenance',
    onPage: 'На этой странице',
    view: 'Открыть материал',
    claimAction: 'Открыть тезис',
    allEssays: 'Все материалы',
    whatChanged: 'Что изменилось',
    limits: 'Где модель может не сработать',
    next: 'Следующий эксперимент',
    usefulFeedback:
      'Особенно полезны контрпримеры и условия, при которых модель перестает работать.',
    definition: 'Определение',
    notThis: 'Что не является этим концептом',
    relations: 'Отношения',
    examples: 'Примеры',
    listeningContext: 'Контекст записи',
    association: 'Личная связь',
    listenFor: 'Что слушать',
    recommendation: 'Рекомендация как забота',
    selectedIdea: 'Мысль из книги · пересказ',
    selectedIdeaQuote: 'Цитата из книги',
    use: 'Как использую',
    boundary: 'Граница',
    emptySearch: 'Введите запрос или выберите тип материала.',
    noResults: 'Ничего не найдено. Попробуйте более широкий запрос.',
    results: 'Результаты',
    footer: 'Спокойная системная редакция с живым культурным сигналом.',
    filters: 'Фильтры материалов',
    backHome: 'Вернуться на главную',
    library: 'Библиотека',
    concepts: 'Концепты',
    topicPage: 'Материалы по теме',
  },
  en: {
    primaryNav: 'Primary navigation',
    utilityNav: 'Utility navigation',
    menu: 'Menu',
    close: 'Close',
    theme: 'Theme',
    switchLanguage: 'Переключить на русский',
    search: 'Search',
    allTypes: 'All types',
    allTopics: 'All topics',
    latest: 'Latest first',
    foundational: 'Foundational first',
    read: 'read',
    published: 'Published',
    updated: 'Updated',
    source: 'RU · source',
    translation: 'EN · translation',
    startHere: 'Start here',
    related: 'Related materials',
    sources: 'Sources and provenance',
    onPage: 'On this page',
    view: 'Open material',
    claimAction: 'Open claim',
    allEssays: 'All materials',
    whatChanged: 'What changed',
    limits: 'Where the model may fail',
    next: 'Next experiment',
    usefulFeedback:
      'Counterexamples and conditions under which the model stops working are especially useful.',
    definition: 'Definition',
    notThis: 'What this concept is not',
    relations: 'Relations',
    examples: 'Examples',
    listeningContext: 'Recording context',
    association: 'Personal association',
    listenFor: 'What to listen for',
    recommendation: 'Recommendation as care',
    selectedIdea: 'Idea from the book · paraphrase',
    selectedIdeaQuote: 'Quote from the book',
    use: 'How I use it',
    boundary: 'Boundary',
    emptySearch: 'Enter a query or choose a content type.',
    noResults: 'Nothing found. Try a broader query.',
    results: 'Results',
    footer: 'A quiet systems journal with a living cultural signal.',
    filters: 'Material filters',
    backHome: 'Return home',
    library: 'Library',
    concepts: 'Concepts',
    topicPage: 'Materials on the topic',
  },
} satisfies Record<Language, Record<string, string>>;

export function otherLanguage(language: Language): Language {
  return language === 'ru' ? 'en' : 'ru';
}

export function formatDate(date: Date | string | undefined, language: Language): string {
  if (!date) return '';
  const value = typeof date === 'string' ? new Date(`${date}T00:00:00Z`) : date;
  return new Intl.DateTimeFormat(language === 'ru' ? 'ru-RU' : 'en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(value);
}

export function isoDate(date: Date | string | undefined): string {
  if (!date) return '';
  return (typeof date === 'string' ? new Date(`${date}T00:00:00Z`) : date)
    .toISOString()
    .slice(0, 10);
}
