#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-account-build
backup=/data/gateway-accounts-backup-20261015
[[ ! -e "$backup" ]] || { echo 'Backup exists; inspect before retrying.' >&2; exit 1; }
for image in doris-security-gateway:accounts-20261015 doris-security-gateway-admin:accounts-20261015 doris-security-gateway-platform:accounts-20261015; do docker image inspect "$image" >/dev/null; done
for name in doris-security-gateway doris-security-gateway-admin doris-security-gateway-platform; do
 [[ "$(docker inspect --format '{{.State.Running}}' "$name")" == true ]]
done
umask 077
mkdir -m 0700 "$backup"
docker inspect doris-security-gateway doris-security-gateway-admin doris-security-gateway-platform > "$backup/containers.json"
docker stop doris-security-gateway doris-security-gateway-platform doris-security-gateway-admin
cp -a /data/doris-security-gateway/conf "$backup/proxy-conf"
cp -a /data/doris-security-gateway-admin/data "$backup/admin-data"
cp -a /data/doris-security-gateway-platform/data "$backup/platform-data"
cp -a /data/doris-security-gateway/gateway.env "$backup/gateway.env"
cp -a /data/doris-security-gateway-admin/admin.env "$backup/admin.env"
cp -a /data/doris-security-gateway-platform/platform.env "$backup/platform.env"
for name in doris-security-gateway doris-security-gateway-admin doris-security-gateway-platform; do
 docker rename "$name" "$name-pre-accounts-20261015"
done
sed -i '/^IMAGE_NAME=/d' /data/doris-security-gateway/gateway.env
printf '\nIMAGE_NAME=doris-security-gateway:accounts-20261015\n' >> /data/doris-security-gateway/gateway.env
sed -i '/^ADMIN_IMAGE_NAME=/d' /data/doris-security-gateway-admin/admin.env
printf '\nADMIN_IMAGE_NAME=doris-security-gateway-admin:accounts-20261015\n' >> /data/doris-security-gateway-admin/admin.env
sed -i '/^PLATFORM_IMAGE_NAME=/d' /data/doris-security-gateway-platform/platform.env
printf '\nPLATFORM_IMAGE_NAME=doris-security-gateway-platform:accounts-20261015\n' >> /data/doris-security-gateway-platform/platform.env
bash "$workspace/scripts/run-admin-docker.sh"
bash "$workspace/scripts/run-docker.sh"
bash "$workspace/scripts/run-platform-docker.sh"
echo "Started release; verify health and smoke tests. Cold backup: $backup"
