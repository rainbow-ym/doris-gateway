#!/usr/bin/bash
set -euo pipefail

data_dir="${GATEWAY_DATA_DIR:-/data/doris-security-gateway}"
env_file="${GATEWAY_ENV_FILE:-${data_dir}/gateway.env}"

# Optional root-readable startup settings. Keep AES keys out of YAML and out of
# shell history while retaining file-based deployment on legacy hosts.
if [[ -f "${env_file}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${env_file}"
  set +a
fi

image_name="${IMAGE_NAME:-doris-security-gateway:0.1.0}"
container_name="${CONTAINER_NAME:-doris-security-gateway}"
listen_ip="${GATEWAY_PUBLISH_IP:-127.0.0.1}"
host_port="${GATEWAY_HOST_PORT:-3307}"
network_mode="${GATEWAY_NETWORK_MODE:-host}"

mkdir -p "${data_dir}/conf" "${data_dir}/logs"

if [[ ! -s "${data_dir}/conf/global.yaml" ]]; then
  docker run --rm \
    -v "${data_dir}/conf:/opt/gateway/conf:Z" \
    -v "${data_dir}/logs:/opt/gateway/logs:Z" \
    "${image_name}" init
  echo "Edit ${data_dir}/conf/*.yaml, then run this script again."
  echo "Put runtime settings and the AES key in ${env_file} (chmod 600)."
  exit 0
fi

if grep -q 'type:[[:space:]]*OUTPUT_AES_GCM' "${data_dir}/conf/"*.yaml \
    && [[ -z "${GATEWAY_OUTPUT_AES_KEY_B64:-}" ]]; then
  echo "GATEWAY_OUTPUT_AES_KEY_B64 is required because OUTPUT_AES_GCM is configured." >&2
  exit 2
fi

lineage_token="${GATEWAY_LINEAGE_TOKEN:-}"
if [[ -z "${GATEWAY_LINEAGE_ENDPOINT:-}" ]]; then
  echo "GATEWAY_LINEAGE_ENDPOINT is required because managed-object enforcement is enabled." >&2
  exit 2
fi
if [[ ${#lineage_token} -lt 24 ]]; then
  echo "GATEWAY_LINEAGE_TOKEN must contain at least 24 characters when lineage reporting is enabled." >&2
  exit 2
fi

docker_args=(
  -d
  --name "${container_name}"
  --restart=unless-stopped
  -v "${data_dir}/conf:/opt/gateway/conf:Z"
  -v "${data_dir}/logs:/opt/gateway/logs:Z"
  -e "GATEWAY_OUTPUT_AES_KEY_B64=${GATEWAY_OUTPUT_AES_KEY_B64:-}"
  -e "GATEWAY_LINEAGE_ENDPOINT=${GATEWAY_LINEAGE_ENDPOINT:-}"
  -e "GATEWAY_CONTROL_ENDPOINT=${GATEWAY_CONTROL_ENDPOINT:-}"
  -e "GATEWAY_LINEAGE_TOKEN=${GATEWAY_LINEAGE_TOKEN:-}"
  -e "GATEWAY_LINEAGE_TIMEOUT_MS=${GATEWAY_LINEAGE_TIMEOUT_MS:-5000}"
  -e "GATEWAY_LINEAGE_FAIL_CLOSED=${GATEWAY_LINEAGE_FAIL_CLOSED:-true}"
  -e "GATEWAY_MULTI_USER_ENABLED=${GATEWAY_MULTI_USER_ENABLED:-false}"
  -e "GATEWAY_JAVA_OPTS=${GATEWAY_JAVA_OPTS:--server -Xms512m -Xmx1g -Xmn256m -Xss1m -Dfile.encoding=UTF-8 -Djava.awt.headless=true}"
)

if [[ "${network_mode}" == "host" ]]; then
  docker_args+=(
    --net=host
    -e "GATEWAY_BIND_ADDRESS=${listen_ip}"
    -e "GATEWAY_PORT=${host_port}"
  )
else
  docker_args+=(
    -p "${listen_ip}:${host_port}:3307"
    -e GATEWAY_BIND_ADDRESS=0.0.0.0
    -e GATEWAY_PORT=3307
  )
fi

docker run "${docker_args[@]}" "${image_name}"

echo "Started ${container_name}; listening on ${listen_ip}:${host_port} (${network_mode} network)."
