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

node --input-type=module - "$ASTRO_TMP/package.json" <<'NODE'
import fs from "node:fs";
const packagePath = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(packagePath, "utf8"));
pkg.scripts = {
  ...pkg.scripts,
  check: "node -e \"console.log('synthetic content gate')\"",
  build: "astro build --force",
};
fs.writeFileSync(packagePath, `${JSON.stringify(pkg, null, 2)}\n`);
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

if [[ -d "$SITE_SRC/src/data/pages" ]]; then
  rm -rf "$ASTRO_TMP/src/data/pages"
  mkdir -p "$ASTRO_TMP/src/data"
  cp -R "$SITE_SRC/src/data/pages" "$ASTRO_TMP/src/data/pages"
fi
if [[ -d "$SITE_SRC/src/content" ]]; then
  mkdir -p "$ASTRO_TMP/src/content"
  cp -R "$SITE_SRC/src/content"/. "$ASTRO_TMP/src/content"/
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
