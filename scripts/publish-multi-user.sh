#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-multi-user-build
backup=/data/gateway-multi-user-backup-20261014
if [[ -e "$backup" ]]; then
  echo "Backup already exists; inspect previous deployment before retrying." >&2
  exit 1
fi
docker image inspect doris-security-gateway:multi-user-20261014 >/dev/null
docker image inspect doris-security-gateway-admin:multi-user-20261014 >/dev/null
mkdir -m 0700 "$backup"
docker stop doris-security-gateway doris-security-gateway-admin
cp -a /data/doris-security-gateway/conf "$backup/proxy-conf"
cp -a /data/doris-security-gateway-admin/data "$backup/admin-data"
cp -a /data/doris-security-gateway/gateway.env "$backup/gateway.env"
cp -a /data/doris-security-gateway-admin/admin.env "$backup/admin.env"
docker rename doris-security-gateway doris-security-gateway-pre-multi-20261014
docker rename doris-security-gateway-admin doris-security-gateway-admin-pre-multi-20261014
sed -i '/^IMAGE_NAME=/d; /^GATEWAY_MULTI_USER_ENABLED=/d' /data/doris-security-gateway/gateway.env
printf '\nIMAGE_NAME=doris-security-gateway:multi-user-20261014\nGATEWAY_MULTI_USER_ENABLED=true\n' >> /data/doris-security-gateway/gateway.env
sed -i '/^ADMIN_IMAGE_NAME=/d; /^GATEWAY_MULTI_USER_ENABLED=/d' /data/doris-security-gateway-admin/admin.env
printf '\nADMIN_IMAGE_NAME=doris-security-gateway-admin:multi-user-20261014\nGATEWAY_MULTI_USER_ENABLED=true\n' >> /data/doris-security-gateway-admin/admin.env
bash "$workspace/scripts/run-admin-docker.sh"
bash "$workspace/scripts/run-docker.sh"
echo "Published; verify ports 18080 and 3308. Rollback containers and offline backups retained in $backup."
