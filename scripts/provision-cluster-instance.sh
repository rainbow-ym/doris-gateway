#!/usr/bin/env bash
# Creates one independent cluster instance. No Compose, no remote Docker socket.
set -euo pipefail
id="${CLUSTER_ID:?Set CLUSTER_ID to a short unique name}"
[[ "$id" =~ ^[a-z][a-z0-9-]{0,30}$ ]] || { echo 'CLUSTER_ID must be lowercase letters, digits or hyphens.' >&2; exit 2; }
worker_port="${CLUSTER_ADMIN_PORT:?Set an unused local management port}"
proxy_port="${CLUSTER_GATEWAY_PORT:?Set an unused MySQL gateway port}"
for port in "$worker_port" "$proxy_port"; do [[ "$port" =~ ^[0-9]+$ && "$port" -ge 1024 && "$port" -le 65535 ]] || exit 2; done
[[ "$worker_port" != "$proxy_port" ]] || { echo 'Ports must be different.' >&2; exit 2; }
bind="${CLUSTER_GATEWAY_BIND_ADDRESS:-127.0.0.1}"
[[ "$bind" =~ ^[0-9a-fA-F:.]+$ ]] || { echo 'Use an IP address for gateway binding.' >&2; exit 2; }
base="/data/doris-gateway-clusters/$id"
[[ ! -e "$base" ]] || { echo "$base already exists; will not overwrite it." >&2; exit 1; }
for name in "gateway-$id" "gateway-admin-$id"; do if docker inspect "$name" >/dev/null 2>&1; then echo "$name already exists" >&2; exit 1; fi; done
if ss -lnt | awk '{print $4}' | grep -Eq ":($worker_port|$proxy_port)$"; then echo 'A requested port is already in use.' >&2; exit 1; fi
docker image inspect doris-security-gateway:multi-user-20261014 >/dev/null
docker image inspect doris-security-gateway-admin:clusters-20261015 >/dev/null
umask 077
mkdir -p "$base/conf" "$base/logs" "$base/admin-data"
control_user="gateway_control_${id//-/_}"
control_password="$(openssl rand -hex 24)"
admin_password="$(openssl rand -hex 24)"
token="$(openssl rand -hex 32)"
docker run --rm -v "$base/conf:/opt/gateway/conf:Z" -v "$base/logs:/opt/gateway/logs:Z" doris-security-gateway:multi-user-20261014 init >/dev/null
# Keep only the generated logging configuration; example business YAML is never loaded.
for example in "$base/conf"/database-*.yaml; do [[ ! -f "$example" ]] || mv "$example" "$example.disabled"; done
cat > "$base/conf/global.yaml" <<EOF
mode:
  type: Standalone
  repository:
    type: JDBC
    props:
      provider: H2
      jdbc_url: jdbc:h2:file:/opt/gateway/conf/repository/config;DATABASE_TO_UPPER=false;MODE=MYSQL
      username: sa
      password: ''
authority:
  users:
    - user: $control_user@%
      password: $control_password
      admin: true
  privilege:
    type: DATABASE_PERMITTED
    props:
      user-database-mappings: $control_user@%=bootstrap_unused
transaction:
  defaultType: LOCAL
props:
  proxy-frontend-database-protocol-type: MySQL
  sql-show: false
EOF
cat > "$base/admin.env" <<EOF
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$admin_password
ADMIN_MASTER_KEY_B64=$(openssl rand -base64 32)
ADMIN_BIND_ADDRESS=127.0.0.1
ADMIN_PORT=$worker_port
PROXY_HOST=$bind
PROXY_PORT=$proxy_port
PROXY_ADMIN_USER=$control_user
PROXY_ADMIN_PASSWORD=$control_password
LINEAGE_INGRESS_TOKEN=$token
GATEWAY_MULTI_USER_ENABLED=true
EOF
cat > "$base/gateway.env" <<EOF
GATEWAY_PORT=$proxy_port
GATEWAY_BIND_ADDRESS=$bind
GATEWAY_MULTI_USER_ENABLED=true
GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:$worker_port/api/internal/lineage/events
GATEWAY_CONTROL_ENDPOINT=http://127.0.0.1:$worker_port/api/internal/sql
GATEWAY_LINEAGE_TOKEN=$token
GATEWAY_LINEAGE_TIMEOUT_MS=30000
EOF
docker run -d --name "gateway-admin-$id" --net host --restart unless-stopped --env-file "$base/admin.env" -v "$base/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:clusters-20261015
docker run -d --name "gateway-$id" --net host --restart unless-stopped --env-file "$base/gateway.env" -v "$base/conf:/opt/gateway/conf:Z" -v "$base/logs:/opt/gateway/logs:Z" doris-security-gateway:multi-user-20261014
echo "Instance created. Register http://127.0.0.1:$worker_port in the platform. Credentials are in $base/admin.env (root-only). Gateway: $bind:$proxy_port"
