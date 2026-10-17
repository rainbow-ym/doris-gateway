#!/usr/bin/bash
set -euo pipefail

data_dir="${ADMIN_HOST_DATA_DIR:-/data/doris-security-gateway-admin}"
env_file="${ADMIN_ENV_FILE:-${data_dir}/admin.env}"
mkdir -p "${data_dir}/data"

if [[ ! -f "${env_file}" ]]; then
  echo "Missing ${env_file}. Copy admin.env.example, set passwords and keys, then retry." >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a

for required in ADMIN_PASSWORD ADMIN_MASTER_KEY_B64 PROXY_ADMIN_PASSWORD LINEAGE_INGRESS_TOKEN; do
  if [[ -z "${!required:-}" ]]; then
    echo "${required} is required in ${env_file}." >&2
    exit 2
  fi
done

docker run -d \
  --name "${ADMIN_CONTAINER_NAME:-doris-security-gateway-admin}" \
  --restart=unless-stopped \
  --net=host \
  -v "${data_dir}/data:/opt/gateway-admin/data:Z" \
  --env-file "${env_file}" \
  "${ADMIN_IMAGE_NAME:-doris-security-gateway-admin:0.1.0}"

echo "Admin UI started on ${ADMIN_BIND_ADDRESS:-127.0.0.1}:${ADMIN_PORT:-18080}."
