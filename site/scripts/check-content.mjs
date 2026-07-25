import fs from "fs";
import path from "path";
import YAML from "yaml";

const workspaceRoot = process.cwd();
const errors = [];

function inputDirectory(envName, liveRelativePath) {
  const configured = process.env[envName];
  if (configured !== undefined && configured.trim() === "") {
    errors.push(`[Root Error] ${envName} must not be empty`);
    return null;
  }
  const selected =
    configured === undefined
      ? path.join(workspaceRoot, liveRelativePath)
      : path.resolve(workspaceRoot, configured);
  if (!fs.existsSync(selected)) {
    errors.push(
      `[Root Error] ${envName} directory does not exist: ${selected}`,
    );
    return selected;
  }
  try {
    if (!fs.statSync(selected).isDirectory()) {
      errors.push(`[Root Error] ${envName} is not a directory: ${selected}`);
    }
  } catch (err) {
    errors.push(
      `[Root Error] Cannot inspect ${envName} directory ${selected}: ${err.message}`,
    );
  }
  return selected;
}

const contentDir = inputDirectory("ASTRO_CONTENT_DIR", "src/content");
const pagesDir = inputDirectory("ASTRO_PAGES_DIR", "src/data/pages");

if (errors.length > 0) {
  console.error(
    "\x1b[31m%s\x1b[0m",
    `Content validation failed with ${errors.length} errors:`,
  );
  errors.forEach((err) => console.error(`  ${err}`));
  process.exit(1);
}

// Рекурсивный поиск файлов с нужным расширением
function getFiles(dir, ext) {
  if (!fs.existsSync(dir)) return [];
  try {
    if (!fs.statSync(dir).isDirectory()) return [];
  } catch (_err) {
    return [];
  }
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

const collections = ["blog", "bibliography", "music", "concepts"];
const requiredPageIds = new Set([
  "about",
  "concepts",
  "essays",
  "home",
  "library",
  "music",
  "notes",
  "search",
  "claims",
]);
const systemSearchId = "search";

// Структуры для сбора данных
// Для коллекций: { [collectionName]: { ru: Set(slugs), en: Set(slugs) } }
const collectionSlugs = {};
collections.forEach((col) => {
  collectionSlugs[col] = { ru: new Set(), en: new Set() };
});
// Для кураторских страниц: { ru: Set(pageIds), en: Set(pageIds) }
const pageIds = { ru: new Set(), en: new Set() };

// Все обнаруженные сущности с их данными для проверки полей и ссылок
const allEntries = [];

// Парсинг Markdown файлов
const mdFiles = getFiles(contentDir, ".md");
for (const filePath of mdFiles) {
  const relativePath = path.relative(contentDir, filePath);
  const parts = relativePath.split(path.sep); // [<collection>, <lang>, <filename>]

  if (parts.length !== 3) {
    errors.push(
      `[Path Error] Invalid content file path structure: ${relativePath}`,
    );
    continue;
  }

  const [collection, lang, filename] = parts;
  const slug = filename.replace(/\.md$/, "");

  if (!collections.includes(collection)) {
    errors.push(
      `[Collection Error] Unknown collection "${collection}" in path ${relativePath}`,
    );
    continue;
  }

  if (lang !== "ru" && lang !== "en") {
    errors.push(
      `[Locale Error] Unknown locale "${lang}" in path ${relativePath}`,
    );
    continue;
  }

  // Добавляем в реестр
  collectionSlugs[collection][lang].add(slug);

  try {
    const fileContent = fs.readFileSync(filePath, "utf8");
    const fmParts = fileContent.split("---");
    if (fmParts.length < 3) {
      errors.push(
        `[Frontmatter Error] Missing frontmatter boundary in ${relativePath}`,
      );
      continue;
    }

    const data = YAML.parse(fmParts[1]);
    if (!data) {
      errors.push(
        `[Frontmatter Error] Empty or invalid frontmatter in ${relativePath}`,
      );
      continue;
    }

    allEntries.push({
      filePath: relativePath,
      isMarkdown: true,
      owner: collection,
      lang,
      slug,
      data,
    });
  } catch (err) {
    errors.push(
      `[Parse Error] Failed to parse ${relativePath}: ${err.message}`,
    );
  }
}

// Парсинг JSON файлов
const jsonFiles = getFiles(pagesDir, ".json");
for (const filePath of jsonFiles) {
  const relativePath = path.relative(pagesDir, filePath);
  const parts = relativePath.split(path.sep); // [<lang>, <filename>]

  if (parts.length !== 2) {
    errors.push(
      `[Path Error] Invalid page JSON file path structure: ${relativePath}`,
    );
    continue;
  }

  const [lang, filename] = parts;
  const pageId = filename.replace(/\.json$/, "");

  if (lang !== "ru" && lang !== "en") {
    errors.push(
      `[Locale Error] Unknown locale "${lang}" in path ${relativePath}`,
    );
    continue;
  }

  pageIds[lang].add(pageId);

  try {
    const data = JSON.parse(fs.readFileSync(filePath, "utf8"));
    allEntries.push({
      filePath: relativePath,
      isMarkdown: false,
      owner: "pages",
      lang,
      slug: pageId,
      data,
    });
  } catch (err) {
    errors.push(
      `[Parse Error] Failed to parse page JSON ${relativePath}: ${err.message}`,
    );
  }
}

// 1. Проверка идентичности наборов слагов ru и en
for (const col of collections) {
  const ruSet = collectionSlugs[col].ru;
  const enSet = collectionSlugs[col].en;

  // Ищем ru слаги, которых нет в en
  for (const slug of ruSet) {
    if (!enSet.has(slug)) {
      errors.push(
        `[Parity Error] Collection "${col}" has RU slug "${slug}" but is missing corresponding EN version`,
      );
    }
  }
  // Ищем en слаги, которых нет в ru
  for (const slug of enSet) {
    if (!ruSet.has(slug)) {
      errors.push(
        `[Parity Error] Collection "${col}" has EN slug "${slug}" but is missing corresponding RU version`,
      );
    }
  }
}

// Проверка идентичности кураторских страниц
for (const pageId of pageIds.ru) {
  if (!pageIds.en.has(pageId)) {
    errors.push(
      `[Parity Error] Pages have RU id "${pageId}" but are missing corresponding EN version`,
    );
  }
}
for (const pageId of pageIds.en) {
  if (!pageIds.ru.has(pageId)) {
    errors.push(
      `[Parity Error] Pages have EN id "${pageId}" but are missing corresponding RU version`,
    );
  }
}

// Fixed Astro routes require the complete, exact page contract in each locale.
for (const lang of ["ru", "en"]) {
  for (const requiredId of requiredPageIds) {
    if (!pageIds[lang].has(requiredId)) {
      errors.push(
        `[Page Contract Error] Locale "${lang}" is missing required page "${requiredId}"`,
      );
    }
  }
  for (const actualId of pageIds[lang]) {
    if (!requiredPageIds.has(actualId)) {
      errors.push(
        `[Page Contract Error] Locale "${lang}" has unexpected page "${actualId}"`,
      );
    }
  }
}

// Astro's registry is keyed only by id inside a locale. A later collection/page
// must never silently overwrite an earlier record with the same public id.
const registryOwners = { ru: new Map(), en: new Map() };
for (const entry of allEntries) {
  const previous = registryOwners[entry.lang].get(entry.slug);
  if (previous) {
    errors.push(
      `[Registry Error] Duplicate id "${entry.slug}" in locale "${entry.lang}": ${previous.filePath} (${previous.owner}) and ${entry.filePath} (${entry.owner})`,
    );
  } else {
    registryOwners[entry.lang].set(entry.slug, entry);
  }
}

// Собираем все известные ID в системе для проверки целостности ссылок
const knownIds = {
  ru: new Set(registryOwners.ru.keys()),
  en: new Set(registryOwners.en.keys()),
};

const entriesById = {
  ru: new Map(),
  en: new Map(),
};
for (const entry of allEntries) {
  entriesById[entry.lang].set(entry.slug, entry);
}

const automaticCollectionTypes = {
  notes: "note",
  library: "book",
  music: "album",
  essays: "essay",
  claims: "claim",
  concepts: "concept",
};

function registryType(entry) {
  if (entry.owner === "blog") return entry.data.contentType;
  if (entry.owner === "bibliography") return "book";
  if (entry.owner === "music") return "album";
  if (entry.owner === "concepts") return "concept";
  return entry.data.type;
}

const claimRelationFields = new Set(["supports", "opposes", "assumes", "refines", "contradicts"]);

function isClaimRelationPath(entry, fieldPath) {
  return entry.owner === "blog" && entry.data.contentType === "claim" && claimRelationFields.has(fieldPath);
}

function validateClaimRelations(value, entry, fieldPath) {
  value.forEach((relation, index) => {
    const relationPath = `${fieldPath}[${index}]`;
    if (!relation || typeof relation !== "object" || Array.isArray(relation)) {
      errors.push(`[Claim Relation Error] ${entry.filePath} ${relationPath} must be an object`);
      return;
    }
    const keys = Object.keys(relation).sort();
    const hasValidKeys = keys.length === 1 && keys[0] === "label" ||
      keys.length === 2 && keys[0] === "label" && keys[1] === "target";
    if (!hasValidKeys || typeof relation.label !== "string" || relation.label.trim() === "") {
      errors.push(`[Claim Relation Error] ${entry.filePath} ${relationPath} must have label and optional target`);
      return;
    }
    if (relation.target !== undefined) {
      if (typeof relation.target !== "string" || relation.target.trim() === "") {
        errors.push(`[Claim Relation Error] ${entry.filePath} ${relationPath}.target must be a non-empty string`);
      } else if (!knownIds[entry.lang].has(relation.target)) {
        errors.push(`[Claim Relation Error] ${entry.filePath} ${relationPath}.target references unknown locale ID: "${relation.target}"`);
      }
    }
  });
}

function validateRichText(value, entry, fieldPath = "") {
  if (Array.isArray(value)) {
    if (fieldPath === "current") {
      validateCurrentCards(value, entry);
      return;
    }
    if (isClaimRelationPath(entry, fieldPath)) {
      validateClaimRelations(value, entry, fieldPath);
      return;
    }
    const hasToken = value.some(
      (item) => item && typeof item === "object" &&
        (Object.hasOwn(item, "kind") || Object.hasOwn(item, "target") || Object.hasOwn(item, "value")),
    );
    if (hasToken) {
      value.forEach((token, index) => {
        const tokenPath = `${fieldPath}[${index}]`;
        if (!token || typeof token !== "object") {
          errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must be a token object`);
          return;
        }
        const keys = Object.keys(token).sort();
        if (token.kind === "text") {
          if (keys.length !== 2 || keys[0] !== "kind" || keys[1] !== "value" || typeof token.value !== "string") {
            errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "value"`);
          }
          return;
        }
        if (token.kind === "reference") {
          if (keys.length !== 2 || keys[0] !== "kind" || keys[1] !== "target" ||
            typeof token.target !== "string" || token.target.trim() === "") {
            errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "target"`);
          } else if (!knownIds[entry.lang].has(token.target)) {
            errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} references unknown locale ID: "${token.target}"`);
          }
          return;
        }
        if (!Object.hasOwn(token, "kind")) {
          errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "target"`);
        } else {
          errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} has unsupported kind: "${token.kind}"`);
        }
      });
      return;
    }
    value.forEach((item, index) => validateRichText(item, entry, `${fieldPath}[${index}]`));
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      if (key === "showcase") {
        validateShowcase(child, entry);
        continue;
      }
      if (key === "current") {
        validateCurrentCards(child, entry);
        continue;
      }
      validateRichText(child, entry, fieldPath ? `${fieldPath}.${key}` : key);
    }
  }
}

function validateCurrentCards(value, entry) {
  const { filePath, lang } = entry;
  const expected = [
    ["studying", "text"],
    ["building", "text"],
    ["reading", "book"],
    ["listening", "album"],
  ];
  if (!Array.isArray(value)) {
    errors.push(`[Current Error] Page JSON ${filePath} current must be an array`);
    return;
  }
  if (value.length !== expected.length) {
    errors.push(`[Current Error] Page JSON ${filePath} current must contain exactly four cards`);
  }
  value.forEach((item, index) => {
    const itemPath = `current[${index}]`;
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      errors.push(`[Current Error] Page JSON ${filePath} ${itemPath} must be an object`);
      return;
    }
    const keys = Object.keys(item).sort();
    const expectedKeys = ["key", "label", "layout", "text", "title"];
    const expectedLinkedKeys = ["key", "label", "layout", "target", "text", "title"];
    const hasExpectedKeys =
      keys.length === expectedKeys.length && keys.every((key, keyIndex) => key === expectedKeys[keyIndex]);
    const hasExpectedLinkedKeys =
      keys.length === expectedLinkedKeys.length && keys.every((key, keyIndex) => key === expectedLinkedKeys[keyIndex]);
    if (!hasExpectedKeys && !hasExpectedLinkedKeys) {
      errors.push(`[Current Error] Page JSON ${filePath} ${itemPath} must have key, label, layout, text, title, and optional target`);
      return;
    }
    const [expectedKey, expectedLayout] = expected[index] ?? [];
    if (item.key !== expectedKey) {
      errors.push(`[Current Error] Page JSON ${filePath} ${itemPath}.key must be "${expectedKey}"`);
    }
    if (item.layout !== expectedLayout) {
      errors.push(`[Current Error] Page JSON ${filePath} ${itemPath}.layout must be "${expectedLayout}"`);
    }
    if (typeof item.label !== "string" || item.label.trim() === "") {
      errors.push(`[Current Error] Page JSON ${filePath} ${itemPath}.label must be a non-empty string`);
    }
    if (Object.hasOwn(item, "target")) {
      if (typeof item.target !== "string" || item.target.trim() === "") {
        errors.push(`[Current Error] Page JSON ${filePath} ${itemPath}.target must be a non-empty ID when present`);
      } else if (!knownIds[lang].has(item.target)) {
        errors.push(`[Current Error] Page JSON ${filePath} ${itemPath}.target references unknown locale ID: "${item.target}"`);
      }
    }
    validateShowcaseText(item.title, entry, `${itemPath}.title`);
    validateShowcaseText(item.text, entry, `${itemPath}.text`);
  });
}

function validateShowcase(value, entry) {
  const { filePath, lang, slug } = entry;
  const expectedType = automaticCollectionTypes[slug];
  if (!expectedType) {
    errors.push(`[Showcase Error] Page JSON ${filePath} cannot define showcase entries`);
    return;
  }
  if (!Array.isArray(value)) {
    errors.push(`[Showcase Error] Page JSON ${filePath} showcase must be an array`);
    return;
  }

  value.forEach((item, index) => {
    const itemPath = `showcase[${index}]`;
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      errors.push(`[Showcase Error] Page JSON ${filePath} ${itemPath} must be an object with exactly "target" and "text"`);
      return;
    }
    const keys = Object.keys(item).sort();
    if (keys.length !== 2 || keys[0] !== "target" || keys[1] !== "text") {
      errors.push(`[Showcase Error] Page JSON ${filePath} ${itemPath} must have exactly "target" and "text"`);
      return;
    }
    if (typeof item.target !== "string" || item.target.trim() === "") {
      errors.push(`[Showcase Error] Page JSON ${filePath} ${itemPath}.target must be a non-empty ID`);
    } else {
      const target = entriesById[lang].get(item.target);
      if (!target) {
        errors.push(`[Showcase Error] Page JSON ${filePath} ${itemPath}.target references unknown locale ID: "${item.target}"`);
      } else if (registryType(target) !== expectedType) {
        errors.push(`[Showcase Error] Page JSON ${filePath} ${itemPath}.target must reference a ${expectedType}, found ${registryType(target)}`);
      }
    }

    validateShowcaseText(item.text, entry, `${itemPath}.text`);
  });
}

function validateShowcaseText(value, entry, fieldPath) {
  if (typeof value === "string") return;
  if (!Array.isArray(value)) {
    errors.push(`[Rich Text Error] ${entry.filePath} ${fieldPath} must be a string or a token array`);
    return;
  }
  value.forEach((token, index) => {
    const tokenPath = `${fieldPath}[${index}]`;
    if (!token || typeof token !== "object") {
      errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must be a token object`);
      return;
    }
    const keys = Object.keys(token).sort();
    if (token.kind === "text") {
      if (keys.length !== 2 || keys[0] !== "kind" || keys[1] !== "value" || typeof token.value !== "string") {
        errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "value"`);
      }
      return;
    }
    if (token.kind === "reference") {
      if (keys.length !== 2 || keys[0] !== "kind" || keys[1] !== "target" ||
        typeof token.target !== "string" || token.target.trim() === "") {
        errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "target"`);
      } else if (!knownIds[entry.lang].has(token.target)) {
        errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} references unknown locale ID: "${token.target}"`);
      }
      return;
    }
    if (!Object.hasOwn(token, "kind")) {
      errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} must have exactly "kind" and "target"`);
    } else {
      errors.push(`[Rich Text Error] ${entry.filePath} ${tokenPath} has unsupported kind: "${token.kind}"`);
    }
  });
}

function validatePinned(entry) {
  const { data, filePath, lang, slug } = entry;
  if (data.pinned === undefined) return;
  const expectedType = automaticCollectionTypes[slug];
  if (!expectedType) {
    errors.push(`[Pinned Error] Page JSON ${filePath} cannot define pinned entries`);
    return;
  }
  if (!Array.isArray(data.pinned)) {
    errors.push(`[Pinned Error] Page JSON ${filePath} pinned must be an array`);
    return;
  }
  const seen = new Set();
  data.pinned.forEach((id, index) => {
    const itemPath = `pinned[${index}]`;
    if (typeof id !== "string" || id.trim() === "") {
      errors.push(`[Pinned Error] Page JSON ${filePath} ${itemPath} must be a non-empty ID`);
      return;
    }
    if (seen.has(id)) {
      errors.push(`[Pinned Error] Page JSON ${filePath} ${itemPath} duplicates "${id}"`);
      return;
    }
    seen.add(id);
    const target = entriesById[lang].get(id);
    if (!target) {
      errors.push(`[Pinned Error] Page JSON ${filePath} ${itemPath} references unknown locale ID: "${id}"`);
      return;
    }
    if (registryType(target) !== expectedType) {
      errors.push(`[Pinned Error] Page JSON ${filePath} ${itemPath} must reference a ${expectedType}, found ${registryType(target)}`);
    }
  });
}

// 2. Проверка заполнения полей, перевода и целостности ссылок
for (const entry of allEntries) {
  const { filePath, isMarkdown, lang, slug, data } = entry;
  const isSystemSearch = !isMarkdown && slug === systemSearchId;

  validateRichText(data, entry);

  if (data.id !== slug) {
    errors.push(
      `[Identity Error] Entry ${filePath} must have "id" equal to filename slug "${slug}", found "${data.id}"`,
    );
  }

  // Search is a code-owned locale template, not a translated vault record.
  // Every vault-derived Markdown/JSON record must carry its locale contract.
  if (!isSystemSearch) {
    if (data.language !== lang) {
      errors.push(
        `[Locale Error] Entry ${filePath} must have "language" equal to path locale "${lang}", found "${data.language}"`,
      );
    }
    if (data.sourceLanguage !== "ru") {
      errors.push(
        `[Locale Error] Entry ${filePath} must have "sourceLanguage" equal to "ru", found "${data.sourceLanguage}"`,
      );
    }
    if (lang === "en" && data.translationOf !== slug) {
      errors.push(
        `[Translation Error] EN entry ${filePath} must have "translationOf" equal to "${slug}", found "${data.translationOf}"`,
      );
    }
  }

  // Проверяем title
  if (
    !data.title ||
    typeof data.title !== "string" ||
    data.title.trim() === ""
  ) {
    errors.push(`[Metadata Error] Missing or empty "title" in ${filePath}`);
  }

  // Проверяем description (для md) или summary (для json)
  if (isMarkdown) {
    if (
      !data.description ||
      typeof data.description !== "string" ||
      data.description.trim() === ""
    ) {
      errors.push(
        `[Metadata Error] Missing or empty "description" in ${filePath}`,
      );
    }
    // Markdown должен быть опубликован: publish: true
    if (data.publish !== true) {
      errors.push(`[Validation Error] "publish" must be true in ${filePath}`);
    }
  } else {
    if (
      !data.summary ||
      typeof data.summary !== "string" ||
      data.summary.trim() === ""
    ) {
      errors.push(`[Metadata Error] Missing or empty "summary" in ${filePath}`);
    }
  }

  // Проверка ссылок в связях (links)
  if (Array.isArray(data.links)) {
    data.links.forEach((linkId) => {
      if (!knownIds[lang].has(linkId)) {
        errors.push(
          `[Link Error] Entry ${filePath} links to missing ID: "${linkId}"`,
        );
      }
    });
  }

  // Проверка специфических полей связей в JSON
  if (!isMarkdown) {
    validatePinned(entry);
    if (Array.isArray(data.selected)) {
      data.selected.forEach((id) => {
        if (!knownIds[lang].has(id)) {
          errors.push(
            `[Link Error] Page JSON ${filePath} references missing selected ID: "${id}"`,
          );
        }
      });
    }

    if (Array.isArray(data.paths)) {
      data.paths.forEach((pathObj, idx) => {
        if (pathObj && pathObj.route && !knownIds[lang].has(pathObj.route)) {
          errors.push(
            `[Link Error] Page JSON ${filePath} references missing path route: "${pathObj.route}" at index ${idx}`,
          );
        }
      });
    }

    if (Array.isArray(data.routes)) {
      data.routes.forEach((routeObj, idx) => {
        if (routeObj && routeObj.route && !knownIds[lang].has(routeObj.route)) {
          errors.push(
            `[Link Error] Page JSON ${filePath} references missing routes route: "${routeObj.route}" at index ${idx}`,
          );
        }
      });
    }

    if (Array.isArray(data.items)) {
      data.items.forEach((id) => {
        if (!knownIds[lang].has(id)) {
          errors.push(
            `[Link Error] Page JSON ${filePath} references missing item ID: "${id}"`,
          );
        }
      });
    }

    if (data.featured && !knownIds[lang].has(data.featured)) {
      errors.push(
        `[Link Error] Page JSON ${filePath} references missing featured ID: "${data.featured}"`,
      );
    }

    if (data.primary && !knownIds[lang].has(data.primary)) {
      errors.push(
        `[Link Error] Page JSON ${filePath} references missing primary ID: "${data.primary}"`,
      );
    }

    if (data.centerId && !knownIds[lang].has(data.centerId)) {
      errors.push(
        `[Link Error] Page JSON ${filePath} references missing centerId: "${data.centerId}"`,
      );
    }

    if (Array.isArray(data.nodes)) {
      data.nodes.forEach((nodeObj, idx) => {
        if (nodeObj && nodeObj.id && !knownIds[lang].has(nodeObj.id)) {
          errors.push(
            `[Link Error] Page JSON ${filePath} references missing node ID: "${nodeObj.id}" at index ${idx}`,
          );
        }
      });
    }
  }
}

// Выводим отчет
if (errors.length > 0) {
  console.error(
    "\x1b[31m%s\x1b[0m",
    `Content validation failed with ${errors.length} errors:`,
  );
  errors.forEach((err) => console.error(`  ${err}`));
  process.exit(1);
} else {
  console.log("\x1b[32m%s\x1b[0m", "Content validation passed successfully!");
  process.exit(0);
}
