import fs from 'fs';
import path from 'path';
import YAML from 'yaml';

const workspaceRoot = process.cwd();
const contentDir = path.join(workspaceRoot, 'src/content');
const pagesDir = path.join(workspaceRoot, 'src/data/pages');

// Рекурсивный поиск файлов с нужным расширением
function getFiles(dir, ext) {
  if (!fs.existsSync(dir)) return [];
  let results = [];
  const list = fs.readdirSync(dir);
  for (const file of list) {
    const filePath = path.join(dir, file);
    const stat = fs.statSync(filePath);
    if (stat && stat.isDirectory()) {
      results = results.concat(getFiles(filePath, ext));
    } else if (file.endsWith(ext)) {
      results.push(filePath);
    }
  }
  return results;
}

const errors = [];
const collections = ['blog', 'bibliography', 'music', 'concepts'];

// Структуры для сбора данных
// Для коллекций: { [collectionName]: { ru: Set(slugs), en: Set(slugs) } }
const collectionSlugs = {};
collections.forEach(col => {
  collectionSlugs[col] = { ru: new Set(), en: new Set() };
});
// Для кураторских страниц: { ru: Set(pageIds), en: Set(pageIds) }
const pageIds = { ru: new Set(), en: new Set() };

// Все обнаруженные сущности с их данными для проверки полей и ссылок
const allEntries = [];

// Парсинг Markdown файлов
const mdFiles = getFiles(contentDir, '.md');
for (const filePath of mdFiles) {
  const relativePath = path.relative(contentDir, filePath);
  const parts = relativePath.split(path.sep); // [<collection>, <lang>, <filename>]
  
  if (parts.length !== 3) {
    errors.push(`[Path Error] Invalid content file path structure: ${relativePath}`);
    continue;
  }
  
  const [collection, lang, filename] = parts;
  const slug = filename.replace(/\.md$/, '');
  
  if (!collections.includes(collection)) {
    errors.push(`[Collection Error] Unknown collection "${collection}" in path ${relativePath}`);
    continue;
  }
  
  if (lang !== 'ru' && lang !== 'en') {
    errors.push(`[Locale Error] Unknown locale "${lang}" in path ${relativePath}`);
    continue;
  }
  
  // Добавляем в реестр
  collectionSlugs[collection][lang].add(slug);
  
  try {
    const fileContent = fs.readFileSync(filePath, 'utf8');
    const fmParts = fileContent.split('---');
    if (fmParts.length < 3) {
      errors.push(`[Frontmatter Error] Missing frontmatter boundary in ${relativePath}`);
      continue;
    }
    
    const data = YAML.parse(fmParts[1]);
    if (!data) {
      errors.push(`[Frontmatter Error] Empty or invalid frontmatter in ${relativePath}`);
      continue;
    }
    
    allEntries.push({
      filePath: relativePath,
      isMarkdown: true,
      lang,
      slug,
      data
    });
  } catch (err) {
    errors.push(`[Parse Error] Failed to parse ${relativePath}: ${err.message}`);
  }
}

// Парсинг JSON файлов
const jsonFiles = getFiles(pagesDir, '.json');
for (const filePath of jsonFiles) {
  const relativePath = path.relative(pagesDir, filePath);
  const parts = relativePath.split(path.sep); // [<lang>, <filename>]
  
  if (parts.length !== 2) {
    errors.push(`[Path Error] Invalid page JSON file path structure: ${relativePath}`);
    continue;
  }
  
  const [lang, filename] = parts;
  const pageId = filename.replace(/\.json$/, '');
  
  if (lang !== 'ru' && lang !== 'en') {
    errors.push(`[Locale Error] Unknown locale "${lang}" in path ${relativePath}`);
    continue;
  }
  
  pageIds[lang].add(pageId);
  
  try {
    const data = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    allEntries.push({
      filePath: relativePath,
      isMarkdown: false,
      lang,
      slug: pageId,
      data
    });
  } catch (err) {
    errors.push(`[Parse Error] Failed to parse page JSON ${relativePath}: ${err.message}`);
  }
}

// 1. Проверка идентичности наборов слагов ru и en
for (const col of collections) {
  const ruSet = collectionSlugs[col].ru;
  const enSet = collectionSlugs[col].en;
  
  // Ищем ru слаги, которых нет в en
  for (const slug of ruSet) {
    if (!enSet.has(slug)) {
      errors.push(`[Parity Error] Collection "${col}" has RU slug "${slug}" but is missing corresponding EN version`);
    }
  }
  // Ищем en слаги, которых нет в ru
  for (const slug of enSet) {
    if (!ruSet.has(slug)) {
      errors.push(`[Parity Error] Collection "${col}" has EN slug "${slug}" but is missing corresponding RU version`);
    }
  }
}

// Проверка идентичности кураторских страниц
for (const pageId of pageIds.ru) {
  if (!pageIds.en.has(pageId)) {
    errors.push(`[Parity Error] Pages have RU id "${pageId}" but are missing corresponding EN version`);
  }
}
for (const pageId of pageIds.en) {
  if (!pageIds.ru.has(pageId)) {
    errors.push(`[Parity Error] Pages have EN id "${pageId}" but are missing corresponding RU version`);
  }
}

// Собираем все известные ID в системе для проверки целостности ссылок
const knownIds = new Set();
collections.forEach(col => {
  collectionSlugs[col].ru.forEach(slug => knownIds.add(slug));
  collectionSlugs[col].en.forEach(slug => knownIds.add(slug));
});
pageIds.ru.forEach(id => knownIds.add(id));
pageIds.en.forEach(id => knownIds.add(id));

// 2. Проверка заполнения полей, перевода и целостности ссылок
for (const entry of allEntries) {
  const { filePath, isMarkdown, lang, slug, data } = entry;
  
  // Проверяем title
  if (!data.title || typeof data.title !== 'string' || data.title.trim() === '') {
    errors.push(`[Metadata Error] Missing or empty "title" in ${filePath}`);
  }
  
  // Проверяем description (для md) или summary (для json)
  if (isMarkdown) {
    if (!data.description || typeof data.description !== 'string' || data.description.trim() === '') {
      errors.push(`[Metadata Error] Missing or empty "description" in ${filePath}`);
    }
    // Markdown должен быть опубликован: publish: true
    if (data.publish !== true) {
      errors.push(`[Validation Error] "publish" must be true in ${filePath}`);
    }
  } else {
    if (!data.summary || typeof data.summary !== 'string' || data.summary.trim() === '') {
      errors.push(`[Metadata Error] Missing or empty "summary" in ${filePath}`);
    }
  }
  
  // Проверяем связь переводов: en entries должны иметь translationOf равный ru слагу
  if (isMarkdown && lang === 'en') {
    if (data.translationOf !== slug) {
      errors.push(`[Translation Error] EN entry ${filePath} must have "translationOf" equal to "${slug}", found "${data.translationOf}"`);
    }
  }
  
  // Проверка ссылок в связях (links)
  if (Array.isArray(data.links)) {
    data.links.forEach(linkId => {
      if (!knownIds.has(linkId)) {
        errors.push(`[Link Error] Entry ${filePath} links to missing ID: "${linkId}"`);
      }
    });
  }
  
  // Проверка специфических полей связей в JSON
  if (!isMarkdown) {
    if (Array.isArray(data.selected)) {
      data.selected.forEach(id => {
        if (!knownIds.has(id)) {
          errors.push(`[Link Error] Page JSON ${filePath} references missing selected ID: "${id}"`);
        }
      });
    }
    
    if (Array.isArray(data.paths)) {
      data.paths.forEach((pathObj, idx) => {
        if (pathObj && pathObj.route && !knownIds.has(pathObj.route)) {
          errors.push(`[Link Error] Page JSON ${filePath} references missing path route: "${pathObj.route}" at index ${idx}`);
        }
      });
    }
    
    if (Array.isArray(data.items)) {
      data.items.forEach(id => {
        if (!knownIds.has(id)) {
          errors.push(`[Link Error] Page JSON ${filePath} references missing item ID: "${id}"`);
        }
      });
    }
    
    if (data.featured && !knownIds.has(data.featured)) {
      errors.push(`[Link Error] Page JSON ${filePath} references missing featured ID: "${data.featured}"`);
    }
    
    if (data.primary && !knownIds.has(data.primary)) {
      errors.push(`[Link Error] Page JSON ${filePath} references missing primary ID: "${data.primary}"`);
    }
    
    if (data.centerId && !knownIds.has(data.centerId)) {
      errors.push(`[Link Error] Page JSON ${filePath} references missing centerId: "${data.centerId}"`);
    }
    
    if (Array.isArray(data.nodes)) {
      data.nodes.forEach((nodeObj, idx) => {
        if (nodeObj && nodeObj.id && !knownIds.has(nodeObj.id)) {
          errors.push(`[Link Error] Page JSON ${filePath} references missing node ID: "${nodeObj.id}" at index ${idx}`);
        }
      });
    }
  }
}

// Выводим отчет
if (errors.length > 0) {
  console.error('\x1b[31m%s\x1b[0m', `Content validation failed with ${errors.length} errors:`);
  errors.forEach(err => console.error(`  ${err}`));
  process.exit(1);
} else {
  console.log('\x1b[32m%s\x1b[0m', 'Content validation passed successfully!');
  process.exit(0);
}
