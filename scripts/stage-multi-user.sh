#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-multi-user-build
stage=/data/gateway-multi-user-stage
mkdir -p "$stage/conf" "$stage/logs" "$stage/admin-data"
if [[ ! -f "$stage/conf/global.yaml" ]]; then
  [[ "${PROXY_ADMIN_PASSWORD:?required}" =~ ^[A-Za-z0-9_-]{24,}$ ]] || { echo 'Use a 24+ character alphanumeric control password.' >&2; exit 2; }
  sed "s/CHANGE_ME_PROXY_ADMIN_PASSWORD/$PROXY_ADMIN_PASSWORD/g" "$workspace/conf/global-multi-user-test.yaml.example" > "$stage/conf/global.yaml"
fi
cp /data/doris-security-gateway/conf/logback.xml "$stage/conf/logback.xml"
if ! docker inspect gateway-multi-user-admin-test >/dev/null 2>&1; then
  docker run -d --name gateway-multi-user-admin-test --net host \
    --env-file /data/doris-security-gateway-admin/admin.env \
    -e ADMIN_PORT=18081 -e ADMIN_BIND_ADDRESS=127.0.0.1 -e PROXY_HOST=127.0.0.1 -e PROXY_PORT=3310 \
    -e PROXY_ADMIN_USER=gateway_multi_control -e PROXY_ADMIN_PASSWORD="${PROXY_ADMIN_PASSWORD:?required}" \
    -e LINEAGE_INGRESS_TOKEN="${LINEAGE_INGRESS_TOKEN:?required}" \
    -e GATEWAY_MULTI_USER_ENABLED=true \
    -v "$stage/admin-data:/opt/gateway-admin/data:Z" \
    doris-security-gateway-admin:multi-user-20261014
fi
if ! docker inspect gateway-multi-user-proxy-test >/dev/null 2>&1; then
  docker run -d --name gateway-multi-user-proxy-test --net host \
    -e GATEWAY_PORT=3310 -e GATEWAY_BIND_ADDRESS=127.0.0.1 \
    -e GATEWAY_MULTI_USER_ENABLED=true \
    -e GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:18081/api/internal/lineage/events \
    -e GATEWAY_CONTROL_ENDPOINT=http://127.0.0.1:18081/api/internal/sql \
    -e GATEWAY_LINEAGE_TOKEN="${LINEAGE_INGRESS_TOKEN:?required}" \
    -e GATEWAY_LINEAGE_TIMEOUT_MS=30000 \
    -v "$stage/conf:/opt/gateway/conf:Z" -v "$stage/logs:/opt/gateway/logs:Z" \
    doris-security-gateway:multi-user-20261014
fi
