#!/usr/bin/bash
health_host="${GATEWAY_BIND_ADDRESS:-127.0.0.1}"
case "${health_host}" in
  0.0.0.0|::) health_host=127.0.0.1 ;;
esac
exec 3<>"/dev/tcp/${health_host}/${GATEWAY_PORT:-3307}"
