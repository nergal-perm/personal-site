#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SITE_SRC="$ROOT/site"
EXPORTER_ROOT="$ROOT/exporter-java"
VAULT_SRC="$ROOT/e2e/fixtures/semantic-vault"
REVIEW_SRC="$ROOT/e2e/fixtures/semantic-review"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/semantic-release.XXXXXX")"

cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

ASTRO_TMP="$TMP/site"
VAULT="$TMP/vault"
REVIEW="$TMP/review"
REPORT="$TMP/build-from-review.md"
mkdir -p "$ASTRO_TMP"
cp -R "$SITE_SRC"/. "$ASTRO_TMP"/
cp -R "$VAULT_SRC" "$VAULT"
cp -R "$REVIEW_SRC" "$REVIEW"
rm -rf \
  "$ASTRO_TMP/node_modules" \
  "$ASTRO_TMP/dist" \
  "$ASTRO_TMP/.astro-export" \
  "$ASTRO_TMP/src/content" \
  "$ASTRO_TMP/src/data/pages" \
  "$ASTRO_TMP/public/assets/vault"

if [[ -d "$SITE_SRC/node_modules" ]]; then
  ln -s "$SITE_SRC/node_modules" "$ASTRO_TMP/node_modules"
fi

node --input-type=module - "$VAULT" "$REVIEW" <<'NODE'
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const vault = process.argv[2];
const review = process.argv[3];
const pages = ["about", "claims", "concepts", "essays", "home", "library", "music", "notes"];
const catalogPath = path.join(review, ".semantic-links", "catalog-v1.json");
const catalog = JSON.parse(fs.readFileSync(catalogPath, "utf8"));

for (const page of pages) {
  const sourcePath = `editorial/${page}.md`;
  const pageRef = `vault-ref-editorial-${page}`;
  writeVaultEditorial(page, sourcePath);
  catalog.entries[pageRef] = {
    currentPath: sourcePath,
    stableNoteId: `synthetic-editorial-${page}`,
    title: `Synthetic ${page}`,
    aliases: [],
    previousPaths: [],
    state: "active",
  };

  const published = path.join(review, "editorial", page, "published");
  fs.mkdirSync(published, { recursive: true });
  const ru = approvedMarkdown(page, "ru");
  const en = approvedMarkdown(page, "en");
  fs.writeFileSync(path.join(published, "ru.md"), ru);
  fs.writeFileSync(path.join(published, "en.md"), en);
  fs.writeFileSync(
    path.join(published, "references.json"),
    JSON.stringify({
      schemaVersion: 1,
      pageRef,
      sourcePath,
      ruSha256: sha256(ru),
      enSha256: sha256(en),
      order: [],
      references: {},
    }),
  );
}

fs.writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);

function writeVaultEditorial(page, sourcePath) {
  const target = path.join(vault, sourcePath);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, `---
publish: true
publicId: ${page}
publicCollection: editorial
publicContentType: curated_page
editorialPage: ${page}
title: Synthetic ${page}
description: Synthetic ${page}.
---
Synthetic editorial source.
`);
}

function approvedMarkdown(page, language) {
  const metadata = {
    id: page,
    language,
    sourceLanguage: "ru",
    title: `Synthetic ${page}`,
    summary: `Synthetic ${page}.`,
    type: page === "home" ? "home" : page === "about" ? "about" : "index",
    searchable: false,
    topics: [],
    links: [],
    eyebrow: "Synthetic",
    lead: "Synthetic lead.",
  };
  if (language === "en") {
    metadata.translationOf = page;
    metadata.translationStatus = "reviewed";
  }
  if (page === "home") {
    metadata.heroTitle = "Synthetic home";
    metadata.heroImageAlt = "Synthetic image";
    metadata.currentTitle = "Synthetic current";
    metadata.current = [
      { key: "studying", label: "Studying", layout: "text", title: "Synthetic study", text: "Synthetic text." },
      { key: "building", label: "Building", layout: "text", title: "Synthetic build", text: "Synthetic text." },
      { key: "reading", label: "Reading", layout: "book", title: "Synthetic read", text: "Synthetic text." },
      { key: "listening", label: "Listening", layout: "album", title: "Synthetic listen", text: "Synthetic text." },
    ];
  }
  if (page === "about") {
    metadata.principles = [];
    metadata.colophon = "Synthetic colophon.";
  }
  return `---\n${yaml(metadata)}---\n`;
}

function yaml(value) {
  return Object.entries(value)
    .map(([key, item]) => `${key}: ${scalar(item)}\n`)
    .join("");
}

function scalar(value) {
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Array.isArray(value)) return JSON.stringify(value);
  throw new Error(`unsupported synthetic metadata value: ${value}`);
}

function sha256(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest("hex");
}
NODE

args=(
  build-from-review
  --vault "$VAULT"
  --review "$REVIEW"
  --out "$ASTRO_TMP"
  --report "$REPORT"
)

if [[ -x "$EXPORTER_ROOT/target/astro-export" ]] \
  && "$EXPORTER_ROOT/target/astro-export" --help 2>/dev/null | grep -q "migrate-semantic-links"; then
  "$EXPORTER_ROOT/target/astro-export" "${args[@]}"
else
  cd "$EXPORTER_ROOT"
  printf -v exec_args '%q ' "${args[@]}"
  mvn -q exec:java "-Dexec.args=${exec_args% }"
fi

cd "$ASTRO_TMP"
npm run build

grep -R --fixed-strings "[B label](/ru/notes/b/)" src/content/blog/ru/a.md >/dev/null
grep -R --fixed-strings "[B label EN](/en/notes/b/)" src/content/blog/en/a.md >/dev/null
grep -R --fixed-strings "/ru/notes/b/" dist/ru/notes/a/index.html >/dev/null
grep -R --fixed-strings "/en/notes/b/" dist/en/notes/a/index.html >/dev/null
! grep --fixed-strings "ref:" src/content/blog/ru/a.md src/content/blog/en/a.md >/dev/null
! grep --fixed-strings "vault-ref-" src/content/blog/ru/a.md src/content/blog/en/a.md >/dev/null

echo "Synthetic semantic release passed: $REPORT"
