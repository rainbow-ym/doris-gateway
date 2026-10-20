#!/usr/bin/env bash
# Assemble already-tested images; no containers, volumes or existing bundles are removed.
set -euo pipefail
export LC_ALL=C
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output="${1:?Usage: bash packaging/build-sm4-offline.sh /new/absolute/output}"
revision="${2:?Provide the tested source Git commit SHA}"
[[ "$revision" =~ ^[a-f0-9]{40}$ ]] || { echo 'Invalid source SHA' >&2; exit 2; }
[[ "$output" = /* && "$output" != *'..'* && ! -e "$output" ]] || { echo 'Output must be a new absolute directory.' >&2; exit 2; }
command -v docker >/dev/null
command -v sha256sum >/dev/null
images=(doris-security-gateway:sm4-row-v2-20261019 doris-security-gateway-admin:sm4-row-v2-20261019 doris-security-gateway-platform:sm4-row-v2-20261019)
for image in "${images[@]}"; do docker inspect --type=image "$image" >/dev/null; done
mkdir -p "$output/clean-install"
for file in install.sh install.conf.example init-sm4-key.sh README.md SM4-EXPORT.md SAFE-UPGRADE.md safe-upgrade.py safe-upgrade.sh upgrade-sm4.sh UPGRADE-SM4.md; do
  cp "$root/packaging/clean-install/$file" "$output/clean-install/"
done
printf 'Source revision: %s\nProtocol: ROW_V2, one encrypted final column per result row\n' "$revision" > "$output/clean-install/IMAGE-MANIFEST.txt"
cp "$root/docs/SM4-ROW-V2-验收.md" "$output/clean-install/ACCEPTANCE.md"
for image in "${images[@]}"; do
  printf '%s ' "$image" >> "$output/clean-install/IMAGE-MANIFEST.txt"
  docker inspect --type=image --format '{{.Id}}' "$image" >> "$output/clean-install/IMAGE-MANIFEST.txt"
done
docker save "${images[@]}" | gzip -1 > "$output/clean-install/doris-security-gateway-sm4-row-v2-20261019-images.tar.gz"
gzip -t "$output/clean-install/doris-security-gateway-sm4-row-v2-20261019-images.tar.gz"
(cd "$output/clean-install"; sha256sum ./* > SHA256SUMS; sha256sum -c SHA256SUMS)
tar -czf "$output/doris-security-gateway-clean-install-sm4-row-v2-20261019.tar.gz" -C "$output" clean-install
gzip -t "$output/doris-security-gateway-clean-install-sm4-row-v2-20261019.tar.gz"
(cd "$output"; sha256sum doris-security-gateway-clean-install-sm4-row-v2-20261019.tar.gz > SHA256SUMS)
echo "Offline bundle ready: $output/doris-security-gateway-clean-install-sm4-row-v2-20261019.tar.gz"
