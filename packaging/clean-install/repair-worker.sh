#!/usr/bin/env bash
# 只重建 worker；不删除数据、不更换密码密钥、不操作 Doris。
set -euo pipefail
set +x
trap 'echo "修复未完成（第 $LINENO 行）。数据和配置保留，请根据上方错误处理。" >&2' ERR
install_dir="${1:-/data/doris-gateway/install_dir}"
container="${2:-doris-gateway-worker}"
[[ "$container" =~ ^[a-z][a-z0-9-]{0,60}$ ]] || { echo '容器名不合法。' >&2; exit 2; }
for cmd in docker hostname readlink awk mktemp cp mv chmod; do command -v "$cmd" >/dev/null || { echo "缺少命令：$cmd" >&2; exit 2; }; done
install_dir="$(readlink -f -- "$install_dir")"
[[ -f "$install_dir/worker.env" && -d "$install_dir/worker-data" ]] || { echo '安装目录缺少 worker.env 或 worker-data，拒绝创建空数据目录。' >&2; exit 2; }
gateway_host_name="$(hostname)"
[[ "$gateway_host_name" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ && ${#gateway_host_name} -le 253 ]] || { echo '主机名不合法，请检查 hostname。' >&2; exit 2; }
docker inspect "$container" >/dev/null
[[ "$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container")" == host ]] || { echo '旧容器不是 host 网络，拒绝自动修改。' >&2; exit 2; }
old_data="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/gateway-admin/data"}}{{.Source}}{{end}}{{end}}' "$container")"
[[ "$old_data" == "$install_dir/worker-data" ]] || { echo "旧容器数据挂载与指定目录不一致，拒绝操作：$old_data" >&2; exit 2; }
# 使用旧容器的确切镜像，避免意外升级或下载。
image="$(docker inspect --format '{{.Image}}' "$container")"
if [[ -n "${WORKER_REPAIR_IMAGE:-}" ]]; then image="$WORKER_REPAIR_IMAGE"; fi
docker image inspect "$image" >/dev/null
backup="$container-before-repair-$(date +%Y%m%d%H%M%S)"
if docker inspect "$backup" >/dev/null 2>&1; then echo '备份容器名称已存在，请稍后重试。' >&2; exit 2; fi
echo '一键修复版本：H2 单进程模式（关闭 AUTO_SERVER）。'
# 保持原数据库文件路径；遇到定制数据路径时拒绝切换到一个空数据库。
if ! awk '
  /^ADMIN_DATA_DIR=/ && $0 != "ADMIN_DATA_DIR=/opt/gateway-admin/data" {bad=1}
  /^SPRING_DATASOURCE_URL=/ && index($0,"SPRING_DATASOURCE_URL=jdbc:h2:file:/opt/gateway-admin/data/gateway-admin;") != 1 {bad=1}
  END {exit bad ? 1 : 0}
' "$install_dir/worker.env"; then
  echo '检测到定制数据库路径，拒绝自动覆盖，请提供不含凭据的路径信息。' >&2
  exit 2
fi
umask 077
env_backup="$(mktemp "$install_dir/worker.env.before-h2-fix.XXXXXX")"
cp "$install_dir/worker.env" "$env_backup"
chmod 600 "$env_backup"
env_new="$(mktemp "$install_dir/.worker.env.h2-fix.XXXXXX")"
awk '!/^SPRING_DATASOURCE_URL=/ {print} END {print "SPRING_DATASOURCE_URL=jdbc:h2:file:/opt/gateway-admin/data/gateway-admin;AUTO_SERVER=FALSE;MODE=MySQL;DATABASE_TO_UPPER=FALSE"}' "$install_dir/worker.env" > "$env_new"
chmod 600 "$env_new"
mv "$env_new" "$install_dir/worker.env"
echo "已自动更新 H2 配置；原配置备份：$env_backup（含密钥，请勿公开）。"
echo "主机名：$gateway_host_name；容器：$container；数据目录：$install_dir/worker-data"
echo '停止旧容器并保留为备份，新容器继续使用原数据和密钥。'
docker stop "$container"
docker rename "$container" "$backup"
if ! docker run -d --name "$container" --net host \
  --add-host "$gateway_host_name:127.0.0.1" \
  --restart unless-stopped --env-file "$install_dir/worker.env" \
  -v "$install_dir/worker-data:/opt/gateway-admin/data:Z" "$image"; then
  echo "创建失败。旧容器保留为 $backup；不要同时启动两个 worker。" >&2
  exit 1
fi
echo '等待 worker 健康检查（最多约 120 秒）……'
for ((attempt=0; attempt<60; attempt++)); do
  state="$(docker inspect --format '{{.State.Status}}' "$container")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")"
  if [[ "$state" == running && "$health" == healthy ]]; then
    echo "修复成功：worker 健康检查通过。备份容器：$backup（保持停止，不要与新容器同时启动）。"
    echo "确认业务正常后，可执行 docker rm $backup 删除旧容器；不加 -v，不删除数据目录。"
    exit 0
  fi
  sleep 2
done
echo "尚未确认启动成功，容器状态：$state，健康状态：$health。" >&2
echo '以下为自动提取的相关启动日志：' >&2
docker logs --since 5m --tail 150 "$container" 2>&1 | grep -E 'UnknownHostException|Application run failed|Started GatewayAdminApplication|Caused by:|JdbcSQLNonTransientException' | tail -25 || true
echo "请执行：docker logs --tail 100 $container；备份容器：$backup。" >&2
exit 1
