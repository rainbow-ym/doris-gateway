#!/usr/bin/env bash
set -euo pipefail
root=/data/gateway-h2-fix-verification
[[ ! -e "$root" ]] || { echo '测试目录已存在，拒绝覆盖'; exit 2; }
mkdir -p "$root/old" "$root/new"
for variant in old new; do
  image=doris-security-gateway-admin:accounts-20261015
  [[ "$variant" == old ]] || image=doris-security-gateway-admin:accounts-20261015-h2fix1
  docker run -d --name "gateway-h2-verify-$variant" --net none --hostname gateway-unresolvable \
    --env-file /data/gateway-clean-package-smoke/worker.env \
    -e ADMIN_PORT=18080 -e ADMIN_BIND_ADDRESS=127.0.0.1 \
    -v /data/gateway-account-build/scripts/h2-fix-test.hosts:/etc/hosts:ro \
    -v "$root/$variant:/opt/gateway-admin/data:Z" "$image"
done
for ((attempt=0; attempt<45; attempt++)); do
  health="$(docker inspect --format '{{.State.Health.Status}}' gateway-h2-verify-new)"
  [[ "$health" != healthy ]] || break
  sleep 2
done
docker logs gateway-h2-verify-old > "$root/old.log" 2>&1
docker logs gateway-h2-verify-new > "$root/new.log" 2>&1
grep -q 'UnknownHostException' "$root/old.log"
grep -q 'Started GatewayAdminApplication' "$root/new.log"
[[ "$health" == healthy ]]
if grep -q 'UnknownHostException' "$root/new.log"; then echo '新镜像仍有名称解析错误'; exit 1; fi
[[ -s "$root/new/gateway-admin.mv.db" ]]
echo 'PASS：旧镜像在缺失主机名映射环境失败，新镜像无需映射即可启动并创建管理库。'
docker restart gateway-h2-verify-new
for ((attempt=0; attempt<45; attempt++)); do
  health="$(docker inspect --format '{{.State.Health.Status}}' gateway-h2-verify-new)"
  [[ "$health" != healthy ]] || break
  sleep 2
done
[[ "$health" == healthy ]]
echo 'PASS：新镜像保留数据重启后健康检查通过。'
docker stop gateway-h2-verify-new
echo '测试完成；隔离容器与测试数据保留，不操作正式服务。'
