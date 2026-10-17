#!/usr/bin/env bash
set -euo pipefail
root=/data/gateway-h2-upgrade-verification
[[ ! -e "$root" ]] || exit 2
[[ "$(docker inspect --format '{{.State.Running}}' gateway-clean-package-smoke-worker)" == false ]]
mkdir -p "$root"
cp -a /data/gateway-clean-package-smoke/worker-data "$root/worker-data"
awk '!/^ADMIN_PORT=/' /data/gateway-clean-package-smoke/worker.env > "$root/worker.env"
echo ADMIN_PORT=18192 >> "$root/worker.env"
chmod 600 "$root/worker.env"
cp "$root/worker.env" "$root/worker.env.test-original"
docker create --name gateway-h2-upgrade-test --net host --env-file "$root/worker.env" \
  -v "$root/worker-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:accounts-20261015
WORKER_REPAIR_IMAGE=doris-security-gateway-admin:accounts-20261015-h2fix1 \
  bash /data/gateway-accounts-export-20261015/h2fix-update/repair-worker.sh "$root" gateway-h2-upgrade-test
awk '!/^SPRING_DATASOURCE_URL=/' "$root/worker.env" | cmp - "$root/worker.env.test-original"
echo 'PASS：旧版本管理库副本已启动，除 H2 URL 外全部 env 内容保持不变。'
docker stop gateway-h2-upgrade-test
