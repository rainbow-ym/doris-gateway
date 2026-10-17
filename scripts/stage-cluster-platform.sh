#!/usr/bin/env bash
set -euo pipefail
stage=/data/gateway-clusters-stage
mkdir -p "$stage/platform-data" "$stage/b-conf" "$stage/b-logs" "$stage/b-admin"
umask 077
if [[ ! -f "$stage/platform.env" ]]; then
  printf 'PLATFORM_ADMIN_USER=admin\nPLATFORM_ADMIN_PASSWORD=%s\nPLATFORM_PORT=18084\nPLATFORM_BIND_ADDRESS=192.168.232.132\nPLATFORM_MASTER_KEY_B64=%s\n' "${PLATFORM_TEST_PASSWORD:?required}" "$(openssl rand -base64 32)" > "$stage/platform.env"
fi
if ! docker inspect gateway-multi-user-admin-pre-clusters >/dev/null 2>&1; then
  docker stop gateway-multi-user-admin-test
  cp -a /data/gateway-multi-user-stage/admin-data "$stage/a-admin-backup"
  docker rename gateway-multi-user-admin-test gateway-multi-user-admin-pre-clusters
fi
if ! docker inspect gateway-multi-user-admin-test >/dev/null 2>&1; then
  docker run -d --name gateway-multi-user-admin-test --net host \
    --env-file /data/doris-security-gateway-admin/admin.env \
    -e ADMIN_PORT=18081 -e ADMIN_BIND_ADDRESS=127.0.0.1 -e PROXY_HOST=127.0.0.1 -e PROXY_PORT=3310 \
    -e PROXY_ADMIN_USER=gateway_multi_control -e PROXY_ADMIN_PASSWORD="${PROXY_ADMIN_PASSWORD:?required}" \
    -e LINEAGE_INGRESS_TOKEN="${LINEAGE_INGRESS_TOKEN:?required}" -e GATEWAY_MULTI_USER_ENABLED=true \
    -v /data/gateway-multi-user-stage/admin-data:/opt/gateway-admin/data:Z \
    doris-security-gateway-admin:clusters-20261015
fi
if [[ ! -f "$stage/b-conf/global.yaml" ]]; then
  # Restrict interpolation to YAML-safe generated credentials.
  [[ "${CLUSTER_B_CONTROL_PASSWORD:?required}" =~ ^[A-Za-z0-9_-]{24,}$ ]] || { echo 'Use a 24+ character alphanumeric control password.' >&2; exit 2; }
  sed "s/gateway_multi_control/gateway_cluster_b_control/g;s/CHANGE_ME_PROXY_ADMIN_PASSWORD/$CLUSTER_B_CONTROL_PASSWORD/g" /data/gateway-multi-user-build/conf/global-multi-user-test.yaml.example > "$stage/b-conf/global.yaml"
  cp /data/doris-security-gateway/conf/logback.xml "$stage/b-conf/logback.xml"
fi
if [[ ! -f "$stage/b-admin.env" ]]; then
  printf 'ADMIN_USERNAME=admin\nADMIN_PASSWORD=%s\nADMIN_MASTER_KEY_B64=%s\nADMIN_PORT=18083\nADMIN_BIND_ADDRESS=127.0.0.1\nPROXY_HOST=127.0.0.1\nPROXY_PORT=3311\nPROXY_ADMIN_USER=gateway_cluster_b_control\nPROXY_ADMIN_PASSWORD=%s\nLINEAGE_INGRESS_TOKEN=%s\nGATEWAY_MULTI_USER_ENABLED=true\n' "${CLUSTER_B_ADMIN_PASSWORD:?required}" "$(openssl rand -base64 32)" "${CLUSTER_B_CONTROL_PASSWORD:?required}" "${CLUSTER_B_LINEAGE_TOKEN:?required}" > "$stage/b-admin.env"
fi
if ! docker inspect gateway-cluster-b-admin-test >/dev/null 2>&1; then
  docker run -d --name gateway-cluster-b-admin-test --net host --env-file "$stage/b-admin.env" \
    -v "$stage/b-admin:/opt/gateway-admin/data:Z" doris-security-gateway-admin:clusters-20261015
fi
if ! docker inspect gateway-cluster-b-proxy-test >/dev/null 2>&1; then
  docker run -d --name gateway-cluster-b-proxy-test --net host \
    -e GATEWAY_PORT=3311 -e GATEWAY_BIND_ADDRESS=127.0.0.1 -e GATEWAY_MULTI_USER_ENABLED=true \
    -e GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:18083/api/internal/lineage/events \
    -e GATEWAY_CONTROL_ENDPOINT=http://127.0.0.1:18083/api/internal/sql \
    -e GATEWAY_LINEAGE_TOKEN="${CLUSTER_B_LINEAGE_TOKEN:?required}" -e GATEWAY_LINEAGE_TIMEOUT_MS=30000 \
    -v "$stage/b-conf:/opt/gateway/conf:Z" -v "$stage/b-logs:/opt/gateway/logs:Z" \
    doris-security-gateway:multi-user-20261014
fi
if ! docker inspect gateway-platform-test >/dev/null 2>&1; then
  docker run -d --name gateway-platform-test --net host --env-file "$stage/platform.env" \
    -v "$stage/platform-data:/opt/gateway-platform/data:Z" doris-security-gateway-platform:clusters-20261015
fi
