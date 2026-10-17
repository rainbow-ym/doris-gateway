#!/usr/bin/env bash
set -euo pipefail

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven_bin="${MAVEN_BIN:-mvn}"

cd "$workspace"
"$maven_bin" -B clean verify
install -d -m 0750 "$workspace/hardening-lib"
install -m 0640 \
  "$workspace/target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar" \
  "$workspace/hardening-lib/"

echo "Hardened plugin copied to $workspace/hardening-lib"
