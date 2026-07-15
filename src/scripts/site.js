// Клиентский скрипт сайта: тема, мобильное меню, восстановление прокрутки,
// поисковый диалог и фильтрация списков.
// Портирован из prototype/app.js без хэш-роутера.

const UI_LABELS = {
  ru: {
    read: 'читать',
    emptySearch: 'Введите запрос или выберите тип материала.',
    noResults: 'Ничего не найдено. Попробуйте более широкий запрос.',
    close: 'Закрыть',
    results: 'Результаты',
  },
  en: {
    read: 'read',
    emptySearch: 'Enter a query or choose a content type.',
    noResults: 'Nothing found. Try a broader query.',
    close: 'Close',
    results: 'Results',
  },
};

let searchIndex = null;

function normalizeText(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase();
}

async function ensureSearchIndex(language) {
  if (searchIndex) return searchIndex;
  const indexUrl = `/${language}/search-index.json`;
  try {
    const response = await fetch(indexUrl);
    searchIndex = await response.json();
    return searchIndex;
  } catch (err) {
    console.error('Failed to load search index:', err);
    return [];
  }
}

function searchContent(index, { query, type, topic, sort, language }) {
  const normQuery = normalizeText(query);
  let results = index.filter((row) => {
    if (type !== 'all' && row.type !== type) return false;
    if (topic !== 'all' && !row.topics.includes(topic)) return false;
    if (normQuery && !row.text.includes(normQuery)) return false;
    return true;
  });

  results.sort((left, right) => {
    if (sort === 'foundational' && left.foundational !== right.foundational) {
      return Number(right.foundational) - Number(left.foundational);
    }
    return (
      String(right.date || '').localeCompare(String(left.date || '')) ||
      left.title.localeCompare(right.title, language)
    );
  });

  return results;
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function renderArticleRowHtml(row, language) {
  const readLabel = UI_LABELS[language].read;
  const readTimeHtml = row.readTime ? `<small>${row.readTime} min ${readLabel}</small>` : '';
  const dateLabel = row.dateLabel || '';
  return `
    <a class="article-row" data-component="article-row" href="${row.url}">
      <span class="article-row__type">${escapeHtml(row.typeLabel)}</span>
      <span class="article-row__main">
        <strong>${escapeHtml(row.title)}</strong>
        <span>${escapeHtml(row.summary)}</span>
      </span>
      <span class="article-row__topic">${escapeHtml(row.topicLabels[0] || '')}</span>
      <span class="article-row__date">${dateLabel}${readTimeHtml}</span>
    </a>
  `;
}

function initTheme() {
  const themeToggle = document.getElementById('theme-toggle') || document.querySelector('[data-action="theme"]');
  if (!themeToggle) return;

  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('quiet-theme', theme);
    themeToggle.setAttribute('aria-pressed', String(theme === 'dark'));
  }

  themeToggle.addEventListener('click', () => {
    const current = document.documentElement.dataset.theme || 'light';
    setTheme(current === 'light' ? 'dark' : 'light');
  });

  // Устанавливаем корректное значение aria-pressed при инициализации
  const currentTheme = document.documentElement.dataset.theme || 'light';
  themeToggle.setAttribute('aria-pressed', String(currentTheme === 'dark'));
}

function initMobileMenu() {
  const menuToggle = document.getElementById('menu-toggle') || document.querySelector('[data-action="menu"]');
  const mobileNav = document.getElementById('mobile-nav');
  if (!menuToggle || !mobileNav) return;

  function setMenu(open) {
    mobileNav.hidden = !open;
    menuToggle.setAttribute('aria-expanded', String(open));
    document.body.classList.toggle('menu-open', open);
  }

  menuToggle.addEventListener('click', () => {
    const open = !mobileNav.hidden;
    setMenu(!open);
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !mobileNav.hidden) {
      setMenu(false);
    }
  });

  window.addEventListener('resize', () => {
    if (window.innerWidth > 900 && !mobileNav.hidden) {
      setMenu(false);
    }
  });

  mobileNav.addEventListener('click', (e) => {
    if (e.target.closest('a')) {
      setMenu(false);
    }
  });
}

function initLanguageToggle() {
  const language = document.body.dataset.language || 'ru';
  localStorage.setItem('quiet-language', language);

  const restore = sessionStorage.getItem('quiet-scroll');
  if (restore) {
    sessionStorage.removeItem('quiet-scroll');
    const scrollPos = parseInt(restore, 10);
    if (!isNaN(scrollPos)) {
      window.scrollTo(0, scrollPos);
    }
  }

  document.addEventListener('click', (e) => {
    const langLink = e.target.closest('[data-action="language"]');
    if (langLink) {
      sessionStorage.setItem('quiet-scroll', String(window.scrollY));
    }
  });
}

function initSearchDialog() {
  const openButtons = document.querySelectorAll('[data-action="open-search"]');
  const searchDialog = document.getElementById('search-dialog');
  const closeButton = searchDialog?.querySelector('[data-action="close-search"]');
  const globalSearchInput = document.getElementById('global-search-input');
  const globalSearchType = document.getElementById('global-search-type');
  const globalSearchResults = document.getElementById('global-search-results');

  if (!searchDialog) return;

  const language = document.body.dataset.language || 'ru';
  const labels = UI_LABELS[language];

  async function handleSearch() {
    const query = globalSearchInput.value;
    const type = globalSearchType.value;
    const index = await ensureSearchIndex(language);

    if (!query && type === 'all') {
      globalSearchResults.innerHTML = `<p class="empty-state">${labels.emptySearch}</p>`;
      return;
    }

    const results = searchContent(index, { query, type, topic: 'all', sort: 'latest', language }).slice(0, 6);
    globalSearchResults.innerHTML = results.length
      ? results.map((row) => renderArticleRowHtml(row, language)).join('')
      : `<p class="empty-state">${labels.noResults}</p>`;
  }

  function openSearch(e) {
    if (e) e.preventDefault();
    if (typeof searchDialog.showModal === 'function') {
      searchDialog.showModal();
    } else {
      const fallbackUrl = e.currentTarget.dataset.searchFallback;
      if (fallbackUrl) {
        window.location.href = fallbackUrl;
        return;
      }
      searchDialog.setAttribute('open', '');
    }

    ensureSearchIndex(language);

    requestAnimationFrame(() => globalSearchInput?.focus());
  }

  function closeSearch() {
    if (typeof searchDialog.close === 'function') {
      searchDialog.close();
    } else {
      searchDialog.removeAttribute('open');
    }
  }

  openButtons.forEach((btn) => btn.addEventListener('click', openSearch));
  closeButton?.addEventListener('click', closeSearch);

  searchDialog.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closeSearch();
    }
  });

  searchDialog.addEventListener('click', (e) => {
    if (e.target === searchDialog) {
      closeSearch();
    }
  });

  globalSearchInput?.addEventListener('input', handleSearch);
  globalSearchType?.addEventListener('change', handleSearch);
}

function initEssaysIndexFilters() {
  const queryInput = document.querySelector('[data-filter="essay-query"]');
  const topicSelect = document.querySelector('[data-filter="essay-topic"]');
  const typeSelect = document.querySelector('[data-filter="essay-type"]');
  const sortSelect = document.querySelector('[data-filter="essay-sort"]');
  const resultsContainer = document.getElementById('essay-results');

  if (!resultsContainer) return;

  const language = document.body.dataset.language || 'ru';

  async function updateResults() {
    const query = queryInput ? queryInput.value : '';
    const topic = topicSelect ? topicSelect.value : 'all';
    const type = typeSelect ? typeSelect.value : 'all';
    const sort = sortSelect ? sortSelect.value : 'latest';

    const index = await ensureSearchIndex(language);
    const results = searchContent(index, { query, type, topic, sort, language });

    const labels = UI_LABELS[language];
    resultsContainer.innerHTML = results.length
      ? results.map((row) => renderArticleRowHtml(row, language)).join('')
      : `<p class="empty-state">${labels.noResults}</p>`;
  }

  if (queryInput) queryInput.addEventListener('input', updateResults);
  if (topicSelect) topicSelect.addEventListener('change', updateResults);
  if (typeSelect) typeSelect.addEventListener('change', updateResults);
  if (sortSelect) sortSelect.addEventListener('change', updateResults);
}

function initSearchPage() {
  const queryInput = document.querySelector('[data-filter="page-query"]');
  const typeSelect = document.querySelector('[data-filter="page-type"]');
  const topicSelect = document.querySelector('[data-filter="page-topic"]');
  const resultsContainer = document.getElementById('page-search-results');
  const countEl = document.querySelector('.search-page__heading b');

  if (!resultsContainer) return;

  const language = document.body.dataset.language || 'ru';

  async function updateResults() {
    const query = queryInput ? queryInput.value : '';
    const type = typeSelect ? typeSelect.value : 'all';
    const topic = topicSelect ? topicSelect.value : 'all';

    const labels = UI_LABELS[language];

    if (!query && type === 'all' && topic === 'all') {
      resultsContainer.innerHTML = `<p class="empty-state">${labels.emptySearch}</p>`;
      if (countEl) countEl.textContent = '0';
      return;
    }

    const index = await ensureSearchIndex(language);
    const results = searchContent(index, { query, type, topic, sort: 'latest', language });

    if (countEl) countEl.textContent = String(results.length);
    resultsContainer.innerHTML = results.length
      ? results.map((row) => renderArticleRowHtml(row, language)).join('')
      : `<p class="empty-state">${labels.noResults}</p>`;
  }

  if (queryInput) queryInput.addEventListener('input', updateResults);
  if (typeSelect) typeSelect.addEventListener('change', updateResults);
  if (topicSelect) topicSelect.addEventListener('change', updateResults);
}

function initReadingProgress() {
  const progressBar = document.getElementById('reading-progress');
  const progressBarSpan = progressBar?.querySelector('span');
  if (!progressBarSpan) return;

  function updateReadingProgress() {
    const article = document.querySelector('.post-body, .note-grid, .case-sections, .album-body, .book-body, .concept-body');
    if (!article) {
      progressBarSpan.style.width = '0%';
      return;
    }
    const start = article.getBoundingClientRect().top + window.scrollY;
    const distance = Math.max(article.offsetHeight - window.innerHeight * 0.55, 1);
    const progress = Math.min(Math.max((window.scrollY - start + window.innerHeight * 0.2) / distance, 0), 1);
    progressBarSpan.style.width = `${Math.round(progress * 100)}%`;
  }

  window.addEventListener('scroll', updateReadingProgress, { passive: true });
  window.addEventListener('resize', updateReadingProgress);
  updateReadingProgress();
}

function initAll() {
  initTheme();
  initMobileMenu();
  initLanguageToggle();
  initSearchDialog();
  initEssaysIndexFilters();
  initSearchPage();
  initReadingProgress();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initAll);
} else {
  initAll();
}
