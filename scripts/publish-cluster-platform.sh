#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-clusters-build
backup=/data/gateway-clusters-backup-20261015
[[ ! -e "$backup" ]] || { echo 'Backup exists; inspect deployment before retrying.' >&2; exit 1; }
if ss -lnt | awk '{print $4}' | grep -q ':18082$'; then echo 'Port 18082 is occupied.' >&2; exit 1; fi
docker image inspect doris-security-gateway-admin:clusters-20261015 >/dev/null
docker image inspect doris-security-gateway-platform:clusters-20261015 >/dev/null
umask 077
mkdir -m 0700 "$backup"
docker stop doris-security-gateway doris-security-gateway-admin
cp -a /data/doris-security-gateway/conf "$backup/proxy-conf"
cp -a /data/doris-security-gateway-admin/data "$backup/admin-data"
cp -a /data/doris-security-gateway/gateway.env "$backup/gateway.env"
cp -a /data/doris-security-gateway-admin/admin.env "$backup/admin.env"
docker rename doris-security-gateway doris-security-gateway-pre-clusters-20261015
docker rename doris-security-gateway-admin doris-security-gateway-admin-pre-clusters-20261015
sed -i '/^ADMIN_PORT=/d; /^ADMIN_BIND_ADDRESS=/d; /^ADMIN_IMAGE_NAME=/d' /data/doris-security-gateway-admin/admin.env
printf '\nADMIN_PORT=18082\nADMIN_BIND_ADDRESS=127.0.0.1\nADMIN_IMAGE_NAME=doris-security-gateway-admin:clusters-20261015\n' >> /data/doris-security-gateway-admin/admin.env
sed -i '/^GATEWAY_LINEAGE_ENDPOINT=/d; /^GATEWAY_CONTROL_ENDPOINT=/d' /data/doris-security-gateway/gateway.env
printf '\nGATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:18082/api/internal/lineage/events\nGATEWAY_CONTROL_ENDPOINT=http://127.0.0.1:18082/api/internal/sql\n' >> /data/doris-security-gateway/gateway.env
mkdir -p /data/doris-security-gateway-platform/data
set -a
source /data/doris-security-gateway-admin/admin.env
set +a
printf 'PLATFORM_ADMIN_USER=%s\nPLATFORM_ADMIN_PASSWORD=%s\nPLATFORM_BIND_ADDRESS=192.168.232.132\nPLATFORM_PORT=18080\nPLATFORM_MASTER_KEY_B64=%s\nPLATFORM_IMAGE_NAME=doris-security-gateway-platform:clusters-20261015\n' "$ADMIN_USERNAME" "$ADMIN_PASSWORD" "$(openssl rand -base64 32)" > /data/doris-security-gateway-platform/platform.env
bash /data/gateway-multi-user-build/scripts/run-admin-docker.sh
bash /data/gateway-multi-user-build/scripts/run-docker.sh
bash "$workspace/scripts/run-platform-docker.sh"
echo "Containers started. Validate login, register the default worker and smoke-test 3308. Backup: $backup"
