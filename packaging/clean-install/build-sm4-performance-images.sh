#!/usr/bin/env bash
# Offline overlays of the running images; builds do not replace containers.
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
worker="${1:-doris-gateway-worker}"
proxy="${2:-doris-gateway-proxy}"
tag="${3:-sm4-perf-20261020}"
platform="${4:-doris-gateway-platform}"
[[ "$tag" =~ ^[a-z0-9][a-z0-9.-]{0,90}$ ]] || { echo '镜像版本格式无效'; exit 2; }
cd "$base"
sha256sum -c SHA256SUMS
names=("$worker" "$proxy")
repositories=(doris-security-gateway-admin doris-security-gateway)
jars=(doris-security-gateway-admin-0.1.0.jar doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar)
destinations=(/opt/gateway-admin/app.jar /opt/shardingsphere-proxy/hardening-lib/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar)
if [[ -f gateway-platform-0.1.0.jar ]]; then
  names+=("$platform"); repositories+=(doris-security-gateway-platform)
  jars+=(gateway-platform-0.1.0.jar); destinations+=(/opt/gateway-platform/app.jar)
fi
bases=()
for i in "${!names[@]}"; do
  [[ "${names[$i]}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo '容器名无效'; exit 2; }
  id="$(docker inspect --format '{{.Image}}' "${names[$i]}")"
  [[ "$id" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo '无法确认原镜像'; exit 2; }
  bases+=("$id")
  docker exec "${names[$i]}" /usr/bin/bash -c "test -f ${destinations[$i]}"
  if docker image inspect "${repositories[$i]}:$tag" >/dev/null 2>&1; then
    echo "目标镜像已存在，停止以免覆盖：${repositories[$i]}:$tag"; exit 2
  fi
done
temporary="$(mktemp -d)"
cleanup() { for i in "${!names[@]}"; do rm -f -- "$temporary/Dockerfile.$i" "$temporary/${jars[$i]}"; done; rmdir -- "$temporary"; }
trap cleanup EXIT
for i in "${!names[@]}"; do
  cp -- "$base/${jars[$i]}" "$temporary/"
  printf 'FROM %s\nCOPY %s %s\n' "${bases[$i]}" "${jars[$i]}" "${destinations[$i]}" > "$temporary/Dockerfile.$i"
done
for i in "${!names[@]}"; do
  docker build --pull=false --network=none -f "$temporary/Dockerfile.$i" -t "${repositories[$i]}:$tag" "$temporary"
done
echo "${#names[@]} 份离线增量镜像已构建，运行中的服务未修改。"
