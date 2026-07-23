#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
EXPORTER_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

VAULT_ROOT="${VAULT_ROOT:-/Users/eugene/Documents/personal-wiki/knowledge-base}"
ASTRO_ROOT="${ASTRO_ROOT:-/Users/eugene/POS/software-dev/astro-blog}"
REPORT_PATH="${REPORT_PATH:-$EXPORTER_ROOT/report.md}"

cd "$EXPORTER_ROOT"

args=(--vault "$VAULT_ROOT" --out "$ASTRO_ROOT" --report "$REPORT_PATH" "$@")

if [[ -x "$EXPORTER_ROOT/target/astro-export" ]]; then
  exec "$EXPORTER_ROOT/target/astro-export" "${args[@]}"
fi

printf -v exec_args '%q ' "${args[@]}"
exec mvn -q exec:java "-Dexec.args=${exec_args% }"
