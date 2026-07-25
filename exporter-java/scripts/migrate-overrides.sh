#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
EXPORTER_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

cd "$EXPORTER_ROOT"

args=(migrate-overrides "$@")

if [[ -x "$EXPORTER_ROOT/target/astro-export" ]]; then
  exec "$EXPORTER_ROOT/target/astro-export" "${args[@]}"
fi

printf -v exec_args '%q ' "${args[@]}"
exec mvn -q exec:java "-Dexec.args=${exec_args% }"
