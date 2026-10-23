#!/usr/bin/env bash
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
install=""; backup=""; monitor=""; bind="127.0.0.1"; prefix="doris-gateway"
while (($#)); do
  [[ $# -ge 2 ]] || { echo 'Missing parameter value' >&2; exit 2; }
  case "$1" in
    --install-dir) install="$2";; --backup-dir) backup="$2";; --monitor-dir) monitor="$2";;
    --grafana-bind) bind="$2";; --prefix) prefix="$2";;
    *) echo "Unknown parameter: $1" >&2; exit 2;;
  esac
  shift 2
done
: "${install:?--install-dir required}" "${backup:?--backup-dir required}" "${monitor:?--monitor-dir required}"
(cd "$base/.." && sha256sum -c SHA256SUMS)
python3 "$base/install.py" prepare --monitor-dir "$monitor" --install-dir "$install" --grafana-bind "$bind" --prefix "$prefix"
bash "$base/../update.sh" --install-dir "$install" --backup-dir "$backup" --prefix "$prefix" --metrics-env "$monitor/metrics.env"
python3 "$base/install.py" start --monitor-dir "$monitor"
