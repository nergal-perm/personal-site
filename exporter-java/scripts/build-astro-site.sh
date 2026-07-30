#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
EXPORTER_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

VAULT_ROOT="${VAULT_ROOT:-/Users/eugene/Documents/personal-wiki/knowledge-base}"
ASTRO_ROOT="${ASTRO_ROOT:-/Users/eugene/POS/software-dev/astro-blog}"
REPORT_PATH="${REPORT_PATH:-$EXPORTER_ROOT/report.md}"

VAULT_ROOT="$VAULT_ROOT" \
ASTRO_ROOT="$ASTRO_ROOT" \
REPORT_PATH="$REPORT_PATH" \
  "$SCRIPT_DIR/build-from-review.sh" "$@"

cd "$ASTRO_ROOT"
exec npm run build
