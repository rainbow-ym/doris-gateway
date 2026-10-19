#!/usr/bin/env bash
set -euo pipefail
set +x
trap 'echo "执行失败：第 $LINENO 行。请检查上方错误；保留已有数据，不要重新初始化覆盖。" >&2' ERR
package_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
config="${2:-$package_dir/install.conf}"
[[ -f "$config" ]] || { echo '请复制 install.conf.example 为 install.conf 并修改配置。' >&2; exit 2; }
source "$config"
: "${INSTALL_DIR:?}" "${INSTANCE_NAME:?}" "${PLATFORM_BIND_IP:?}" "${PLATFORM_PORT:?}" "${WORKER_PORT:?}" "${GATEWAY_BIND_IP:?}" "${GATEWAY_PORT:?}"
[[ "$INSTALL_DIR" =~ ^/[A-Za-z0-9_/-]+$ && "$INSTALL_DIR" != / && "$INSTALL_DIR" != /data && "$INSTALL_DIR" != /home && "$INSTALL_DIR" != /opt && "$INSTALL_DIR" != /root && "$INSTALL_DIR" != *'..'* ]] || { echo '请使用专用绝对目录，不能是根目录或上层目录。' >&2; exit 2; }
[[ "$INSTANCE_NAME" =~ ^[a-z][a-z0-9-]{0,30}$ ]] || { echo 'INSTANCE_NAME 必须是以小写字母开头的 1～31 位名称，只允许小写字母、数字和短横线，不能填写路径。' >&2; exit 2; }
for ip in "$PLATFORM_BIND_IP" "$GATEWAY_BIND_IP"; do [[ "$ip" =~ ^[0-9a-fA-F:.]+$ ]] || { echo "监听 IP 格式错误：$ip；不能包含协议、空格或主机名。" >&2; exit 2; }; done
for port in "$PLATFORM_PORT" "$WORKER_PORT" "$GATEWAY_PORT"; do [[ "$port" =~ ^[1-9][0-9]{3,4}$ && "$port" -ge 1024 && "$port" -le 65535 ]] || { echo "端口错误：$port；应为 1024～65535 的整数，不带前导零。" >&2; exit 2; }; done
[[ "$PLATFORM_PORT" != "$WORKER_PORT" && "$PLATFORM_PORT" != "$GATEWAY_PORT" && "$WORKER_PORT" != "$GATEWAY_PORT" ]] || { echo '三个端口不能相同。' >&2; exit 2; }
proxy_image=doris-security-gateway:sm4-export-20261019
worker_image=doris-security-gateway-admin:sm4-export-20261019
platform_image=doris-security-gateway-platform:sm4-export-20261019
for cmd in docker openssl ss hostname; do command -v "$cmd" >/dev/null || { echo "缺少命令：$cmd" >&2; exit 2; }; done
gateway_host_name="$(hostname)"
[[ "$gateway_host_name" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ && ${#gateway_host_name} -le 253 ]] || { echo '本机 hostname 为空或包含不支持的字符，请检查服务器主机名。' >&2; exit 2; }
echo "检测到主机名：$gateway_host_name；将为各容器添加本机回环解析。"
host_args=(--add-host "$gateway_host_name:127.0.0.1")
for image in "$proxy_image" "$worker_image" "$platform_image"; do docker image inspect "$image" >/dev/null; done
case "${1:-}" in
init)
  [[ ! -e "$INSTALL_DIR" ]] || { echo '安装目录已存在，拒绝覆盖。已有安装请使用 start。' >&2; exit 1; }
  umask 077
  mkdir -p "$INSTALL_DIR/conf" "$INSTALL_DIR/logs" "$INSTALL_DIR/worker-data" "$INSTALL_DIR/platform-data"
  bash "$package_dir/init-sm4-key.sh" "$INSTALL_DIR/worker-data/sm4-export-keys.properties"
  docker run --rm -v "$INSTALL_DIR/conf:/opt/gateway/conf:Z" -v "$INSTALL_DIR/logs:/opt/gateway/logs:Z" "$proxy_image" init >/dev/null
  for example in "$INSTALL_DIR/conf"/database-*.yaml; do [[ ! -f "$example" ]] || mv "$example" "$example.disabled"; done
  control_user=gateway_control
  control_password="$(openssl rand -hex 24)"
  worker_password="$(openssl rand -hex 24)"
  platform_password="$(openssl rand -hex 16)"
  token="$(openssl rand -hex 32)"
  cat > "$INSTALL_DIR/conf/global.yaml" <<EOF
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
  # 只保存本次安装新生成的凭据，不复制其他环境数据。
  cat > "$INSTALL_DIR/worker.env" <<EOF
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$worker_password
ADMIN_MASTER_KEY_B64=$(openssl rand -base64 32)
ADMIN_BIND_ADDRESS=127.0.0.1
ADMIN_PORT=$WORKER_PORT
PROXY_HOST=127.0.0.1
PROXY_PORT=$GATEWAY_PORT
PROXY_ADMIN_USER=$control_user
PROXY_ADMIN_PASSWORD=$control_password
LINEAGE_INGRESS_TOKEN=$token
GATEWAY_MULTI_USER_ENABLED=true
EOF
  # 若业务网关绑定指定 IP，内部连接也必须使用该 IP。
  if [[ "$GATEWAY_BIND_IP" != 0.0.0.0 && "$GATEWAY_BIND_IP" != '::' ]]; then sed -i "s/^PROXY_HOST=.*/PROXY_HOST=$GATEWAY_BIND_IP/" "$INSTALL_DIR/worker.env"; fi
  cat > "$INSTALL_DIR/gateway.env" <<EOF
GATEWAY_PORT=$GATEWAY_PORT
GATEWAY_BIND_ADDRESS=$GATEWAY_BIND_IP
GATEWAY_MULTI_USER_ENABLED=true
GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:$WORKER_PORT/api/internal/lineage/events
GATEWAY_CONTROL_ENDPOINT=http://127.0.0.1:$WORKER_PORT/api/internal/sql
GATEWAY_LINEAGE_TOKEN=$token
GATEWAY_LINEAGE_TIMEOUT_MS=30000
EOF
  cat > "$INSTALL_DIR/platform.env" <<EOF
PLATFORM_ADMIN_USER=admin
PLATFORM_ADMIN_PASSWORD=$platform_password
PLATFORM_MASTER_KEY_B64=$(openssl rand -base64 32)
PLATFORM_BIND_ADDRESS=$PLATFORM_BIND_IP
PLATFORM_PORT=$PLATFORM_PORT
EOF
  cp "$config" "$INSTALL_DIR/install.conf"
  echo "初始化完成。管理员初始密码见 $INSTALL_DIR/platform.env；集群登记凭据见 $INSTALL_DIR/worker.env。"
  echo '这些文件只允许 root 读取。请妥善备份主密钥，不要提交或公开。'
  ;;
start)
  [[ -f "$INSTALL_DIR/platform.env" && -f "$INSTALL_DIR/worker.env" && -f "$INSTALL_DIR/gateway.env" ]] || { echo '请先执行 init。' >&2; exit 2; }
  cmp -s "$config" "$INSTALL_DIR/install.conf" || { echo '配置与初始化时不一致。请先核对生成的 env 文件，不自动覆盖已有配置。' >&2; exit 2; }
  for name in "$INSTANCE_NAME-platform" "$INSTANCE_NAME-worker" "$INSTANCE_NAME-proxy"; do
    if docker inspect "$name" >/dev/null 2>&1; then echo "容器已存在：$name。请用 docker start 启动已停止容器；不自动重建或删除。" >&2; exit 1; fi
  done
  if ss -lnt | awk '{print $4}' | grep -Eq ":($PLATFORM_PORT|$WORKER_PORT|$GATEWAY_PORT)$"; then echo '端口已占用，请在 init 前选择空闲端口。' >&2; exit 1; fi
  docker run -d --name "$INSTANCE_NAME-worker" --net host "${host_args[@]}" --restart unless-stopped --env-file "$INSTALL_DIR/worker.env" -v "$INSTALL_DIR/worker-data:/opt/gateway-admin/data:Z" "$worker_image"
  docker run -d --name "$INSTANCE_NAME-proxy" --net host "${host_args[@]}" --restart unless-stopped --env-file "$INSTALL_DIR/gateway.env" -v "$INSTALL_DIR/conf:/opt/gateway/conf:Z" -v "$INSTALL_DIR/logs:/opt/gateway/logs:Z" "$proxy_image"
  docker run -d --name "$INSTANCE_NAME-platform" --net host "${host_args[@]}" --restart unless-stopped --env-file "$INSTALL_DIR/platform.env" -v "$INSTALL_DIR/platform-data:/opt/gateway-platform/data:Z" "$platform_image"
  echo "容器已创建，应用仍需完成启动。门户端口 $PLATFORM_PORT，业务端口 $GATEWAY_PORT；内部管理服务仅监听 127.0.0.1:$WORKER_PORT。"
  echo "请执行 docker ps -a 和 docker logs --tail 100 $INSTANCE_NAME-worker 检查状态。"
  ;;
*) echo '用法：bash install.sh init [配置文件]；bash install.sh start [配置文件]' >&2; exit 2;;
esac
