#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/path/to/apache-shardingsphere-proxy"
  exit 2
fi

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
proxy_home="$(cd "$1" && pwd)"
plugin="$workspace/target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar"
start_script="$proxy_home/bin/start.sh"
vendor_start="$proxy_home/bin/start.sh.vendor"
original='CLASS_PATH=".:${DEPLOY_DIR}/lib/*:${EXT_LIB}/*"'
hardened='CLASS_PATH=".:${DEPLOY_DIR}/hardening-lib/*:${DEPLOY_DIR}/lib/*:${EXT_LIB}/*"'

test -f "$plugin"
test -f "$start_script"
install -d -m 0750 "$proxy_home/hardening-lib"
install -m 0640 "$plugin" "$proxy_home/hardening-lib/"

if grep -Fqx "$hardened" "$start_script"; then
  echo "Proxy classpath is already hardened."
elif grep -Fqx "$original" "$start_script"; then
  if [[ ! -e "$vendor_start" ]]; then
    cp "$start_script" "$vendor_start"
  fi
  sed -i "s#^${original}$#${hardened}#" "$start_script"
else
  echo "Refusing to edit an unknown start.sh layout: $start_script" >&2
  exit 1
fi

echo "Installed hardened mask module in $proxy_home/hardening-lib"
