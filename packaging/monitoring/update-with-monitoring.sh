#!/usr/bin/env bash
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
install=""; backup=""; monitor=""; origin=""; prefix="doris-gateway"
while (($#)); do
  [[ $# -ge 2 ]] || { echo 'Missing parameter value' >&2; exit 2; }
  case "$1" in
    --install-dir) install="$2";; --backup-dir) backup="$2";; --monitor-dir) monitor="$2";;
    --platform-url) origin="$2";; --prefix) prefix="$2";;
    *) echo "Unknown parameter: $1" >&2; exit 2;;
  esac
  shift 2
done
: "${install:?--install-dir required}" "${backup:?--backup-dir required}" "${monitor:?--monitor-dir required}" "${origin:?--platform-url required (current browser address)}"
(cd "$base/.." && sha256sum -c SHA256SUMS)
python3 "$base/preflight.py" --monitor-dir "$monitor" --platform-url "$origin" --prefix "$prefix"
umask 077
mkdir -p -- "$backup"
log="$backup/monitoring-update-$(date +%Y%m%dT%H%M%S)-$$.log"
exec > >(tee -a "$log") 2>&1
trap 'result=$?; echo "Update exit=$result; detailed log: $log"' EXIT
echo 'Step 1/4: prepare or reuse monitoring'
if [[ ! -f "$monitor/manifest.json" ]]; then
  python3 "$base/install.py" prepare --monitor-dir "$monitor" --install-dir "$install" --prefix "$prefix"
fi
echo 'Step 2/4: update gateway with cold backup'
bash "$base/../update.sh" --install-dir "$install" --backup-dir "$backup" --prefix "$prefix" --metrics-env "$monitor/metrics.env"
echo 'Step 3/4: enable platform authentication and loopback-only Grafana'
python3 "$base/install.py" integrate --monitor-dir "$monitor" --platform-url "$origin"
echo 'Step 4/4: start services and verify each scrape target'
python3 "$base/install.py" start --monitor-dir "$monitor"
