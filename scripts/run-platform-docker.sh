#!/usr/bin/env bash
set -euo pipefail
data_dir="${PLATFORM_HOST_DATA_DIR:-/data/doris-security-gateway-platform}"
env_file="${PLATFORM_ENV_FILE:-$data_dir/platform.env}"
[[ -f "$env_file" ]] || { echo "Missing $env_file" >&2; exit 2; }
mkdir -p "$data_dir/data"
set -a
source "$env_file"
set +a
docker run -d --name "${PLATFORM_CONTAINER_NAME:-doris-security-gateway-platform}" \
  --net host --restart unless-stopped --env-file "$env_file" \
  -v "$data_dir/data:/opt/gateway-platform/data:Z" \
  "${PLATFORM_IMAGE_NAME:-doris-security-gateway-platform:clusters-20261015}"
