#!/usr/bin/env bash
set -euo pipefail
set +x
package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$package_dir"
echo '校验更新包……'
sha256sum -c SHA256SUMS
echo '导入已修复 H2 默认配置的 worker 镜像……'
gzip -dc worker-h2fix1-image.tar.gz | docker load
export WORKER_REPAIR_IMAGE=doris-security-gateway-admin:accounts-20261015-h2fix1
bash "$package_dir/repair-worker.sh" "${1:-/data/doris-gateway/install_dir}" "${2:-doris-gateway-worker}"
