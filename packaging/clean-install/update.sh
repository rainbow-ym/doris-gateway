#!/usr/bin/env bash
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
for argument in "$@"; do
  case "$argument" in
    --monitor-dir|--platform-url) exec bash "$base/monitoring/update-with-monitoring.sh" "$@";;
  esac
done
exec python3 "$base/one-click-update.py" "$@"
