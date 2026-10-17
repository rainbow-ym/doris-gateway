#!/usr/bin/env bash
set -euo pipefail
set +x
command -v python3 >/dev/null || { echo '需要 Python 3 标准库运行升级工具，不需要 pip 或 Compose。' >&2; exit 2; }
exec python3 "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/safe-upgrade.py" "$@"
