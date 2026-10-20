#!/usr/bin/env bash
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$base/one-click-update.py" "$@"
