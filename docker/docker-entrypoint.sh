#!/usr/bin/bash
set -euo pipefail

gateway_home="${GATEWAY_HOME:-/opt/shardingsphere-proxy}"
conf_dir="${GATEWAY_CONF_DIR:-/opt/gateway/conf}"
log_dir="${GATEWAY_LOG_DIR:-/opt/gateway/logs}"
default_conf="${gateway_home}/default-conf"

initialize_config() {
  mkdir -p "${conf_dir}" "${log_dir}"
  for source in "${default_conf}"/*; do
    target="${conf_dir}/$(basename "${source}")"
    if [[ ! -e "${target}" ]]; then
      cp -a "${source}" "${target}"
      echo "Initialized ${target}"
    fi
  done
}

validate_config() {
  local required
  # Business databases, storage units and MASK rules are initialized by the
  # management platform through DistSQL. Only the global Proxy configuration
  # is required on disk for a fresh deployment.
  for required in global.yaml; do
    if [[ ! -s "${conf_dir}/${required}" ]]; then
      echo "Missing configuration: ${conf_dir}/${required}" >&2
      echo "Run the container with the 'init' command first, then edit the mounted global configuration." >&2
      exit 2
    fi
  done
  if grep -R -n 'CHANGE_ME' "${conf_dir}"/*.yaml >/dev/null 2>&1; then
    echo "Configuration still contains CHANGE_ME placeholders:" >&2
    grep -R -n 'CHANGE_ME' "${conf_dir}"/*.yaml >&2 || true
    exit 2
  fi
}

start_gateway() {
  initialize_config
  validate_config

  local class_path
  class_path="${conf_dir}:.:${gateway_home}/hardening-lib/*:${gateway_home}/lib/*:${gateway_home}/ext-lib/*"
  local default_java_opts
  default_java_opts="-server -Xms512m -Xmx1g -Xmn256m -Xss1m -Dfile.encoding=UTF-8 -Djava.awt.headless=true -Dio.netty.leakDetection.level=DISABLED -XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC"

  echo "Starting Doris security gateway on ${GATEWAY_BIND_ADDRESS:-0.0.0.0}:${GATEWAY_PORT:-3307}"
  exec "${JAVA_HOME}/bin/java" ${GATEWAY_JAVA_OPTS:-${default_java_opts}} \
    -classpath "${class_path}" \
    org.apache.shardingsphere.proxy.Bootstrap \
    "${GATEWAY_PORT:-3307}" "${conf_dir}" "${GATEWAY_BIND_ADDRESS:-0.0.0.0}"
}

case "${1:-start}" in
  init)
    initialize_config
    echo "Configuration initialized in ${conf_dir}. Edit it before starting the gateway."
    ;;
  start)
    start_gateway
    ;;
  shell|bash)
    exec /usr/bin/bash
    ;;
  version)
    exec "${JAVA_HOME}/bin/java" \
      -classpath "${gateway_home}/hardening-lib/*:${gateway_home}/lib/*:${gateway_home}/ext-lib/*" \
      org.apache.shardingsphere.infra.version.ShardingSphereVersion
    ;;
  *)
    exec "$@"
    ;;
esac
