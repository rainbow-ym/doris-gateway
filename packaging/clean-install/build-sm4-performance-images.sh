#!/usr/bin/env bash
# Offline overlay of the existing runtime; does not stop or replace containers.
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
worker="${1:-doris-gateway-worker}"
proxy="${2:-doris-gateway-proxy}"
tag="${3:-sm4-perf-20261020}"
[[ "$tag" =~ ^[a-z0-9][a-z0-9.-]{0,90}$ ]] || { echo '镜像版本格式无效'; exit 2; }
for name in "$worker" "$proxy"; do
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo '容器名无效'; exit 2; }
done
cd "$base"
sha256sum -c SHA256SUMS
worker_base="$(docker inspect --format '{{.Image}}' "$worker")"
proxy_base="$(docker inspect --format '{{.Image}}' "$proxy")"
for id in "$worker_base" "$proxy_base"; do
  [[ "$id" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo '无法确认原镜像'; exit 2; }
done
docker exec "$worker" /usr/bin/bash -c 'test -f /opt/gateway-admin/app.jar'
docker exec "$proxy" /usr/bin/bash -c 'test -f /opt/shardingsphere-proxy/hardening-lib/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar'
for image in "doris-security-gateway-admin:$tag" "doris-security-gateway:$tag"; do
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "目标镜像已存在，停止以免覆盖：$image"
    exit 2
  fi
done
temporary="$(mktemp -d)"
trap 'rm -f "$temporary/Dockerfile.worker" "$temporary/Dockerfile.proxy" "$temporary/doris-security-gateway-admin-0.1.0.jar" "$temporary/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar"; rmdir "$temporary"' EXIT
cp "$base/doris-security-gateway-admin-0.1.0.jar" "$base/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar" "$temporary/"
printf 'FROM %s\nCOPY doris-security-gateway-admin-0.1.0.jar /opt/gateway-admin/app.jar\n' "$worker_base" > "$temporary/Dockerfile.worker"
printf 'FROM %s\nCOPY doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar /opt/shardingsphere-proxy/hardening-lib/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar\n' "$proxy_base" > "$temporary/Dockerfile.proxy"
docker build --pull=false --network=none -f "$temporary/Dockerfile.worker" -t "doris-security-gateway-admin:$tag" "$temporary"
docker build --pull=false --network=none -f "$temporary/Dockerfile.proxy" -t "doris-security-gateway:$tag" "$temporary"
echo '两份离线增量镜像已构建，运行中的服务未修改。请按 README-PERFORMANCE.md 冷备、隔离验证后开放业务。'
