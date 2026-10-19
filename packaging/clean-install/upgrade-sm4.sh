#!/usr/bin/env bash
# Existing standard host-network deployment only. Does not auto-accept maintenance.
set -euo pipefail
set +x
base="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# != 4 ]]; then
  echo '用法：bash upgrade-sm4.sh worker容器名 proxy容器名 platform容器名 /专用备份根目录' >&2
  echo '需要维护窗口；先校验包并加载镜像。不会清空或重新初始化旧数据。' >&2
  exit 2
fi
for name in "$1" "$2" "$3"; do [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo '容器名无效'; exit 2; }; done
[[ "$4" = /* && "$4" != *'..'* && -d "$4" ]] || { echo '请先创建专用绝对路径备份目录'; exit 2; }
data="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/gateway-admin/data"}}{{.Source}}{{end}}{{end}}' "$1")"
[[ "$data" = /* && -d "$data" && "$data" != *$'\n'* ]] || { echo '无法确定 worker 数据挂载目录'; exit 2; }
bash "$base/init-sm4-key.sh" "$data/sm4-export-keys.properties"
python3 "$base/safe-upgrade.py" stage --worker "$1" --proxy "$2" --platform "$3" \
  --worker-image doris-security-gateway-admin:sm4-row-v2-20261019 \
  --proxy-image doris-security-gateway:sm4-row-v2-20261019 \
  --platform-image doris-security-gateway-platform:sm4-row-v2-20261019 \
  --backup-root "$4" --maintenance-confirmed
echo '维护状态已建立。核实本机业务查询后，按 SAFE-UPGRADE.md 使用输出的 state.json 执行 accept；不要重新 init。'
