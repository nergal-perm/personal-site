#!/usr/bin/env bash
set -euo pipefail

: "${VAULT_PATH:?Set VAULT_PATH to a real vault root, e.g. export VAULT_PATH=~/Documents/personal-wiki/knowledge-base}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPORTER="$ROOT/exporter-java/target/astro-export"
SITE="$ROOT/site"
REVIEW="${REVIEW_PATH:-$ROOT/e2e/.review}"

if [[ ! -x "$EXPORTER" ]]; then
  echo "Exporter binary not found at $EXPORTER" >&2
  echo "Build it first: (cd $ROOT/exporter-java && mvn -Pnative native:compile)" >&2
  exit 1
fi

echo "==> build-from-review approved materialization into $SITE"
"$EXPORTER" --vault "$VAULT_PATH" --out "$SITE" --review "$REVIEW" build-from-review

echo "==> npm run build in site/"
(cd "$SITE" && npm run build)

echo "E2E pipeline ran cleanly against $VAULT_PATH"
