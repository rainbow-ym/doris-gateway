#!/usr/bin/env bash
# 只读检查现有 worker；临时检查容器不挂载任何业务数据。
set -uo pipefail
set +x
container="${1:-doris-gateway-worker}"
if ! command -v docker >/dev/null; then echo '未找到 docker。'; exit 2; fi
if ! docker inspect "$container" >/dev/null 2>&1; then echo "找不到容器：$container"; exit 2; fi
echo '=== 1. 宿主机名称 ==='
hostname
echo '=== 2. 容器创建时间、启动状态、网络、附加解析 ==='
docker inspect --format 'Created={{.Created}} Started={{.State.StartedAt}} Status={{.State.Status}} Restarts={{.RestartCount}} Hostname={{.Config.Hostname}} Network={{.HostConfig.NetworkMode}} ExtraHosts={{json .HostConfig.ExtraHosts}}' "$container"
echo '=== 3. 健康状态 ==='
docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}未配置健康检查{{end}}' "$container"
echo '=== 4. 当前容器实际 hosts 文件 ==='
hosts_path="$(docker inspect --format '{{.HostsPath}}' "$container")"
if [[ -n "$hosts_path" && -r "$hosts_path" ]]; then
  while IFS= read -r line; do printf '%s\n' "$line"; done < "$hosts_path"
else
  echo '无法读取当前容器 hosts 文件（可能需要 root 执行）。'
fi
echo '=== 5. 同镜像临时容器的解析检查（不挂载数据） ==='
image="$(docker inspect --format '{{.Image}}' "$container")"
worker_hostname="$(docker inspect --format '{{.Config.Hostname}}' "$container")"
if [[ "$worker_hostname" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  docker run --rm --net host --add-host "$worker_hostname:127.0.0.1" \
    --entrypoint /usr/bin/bash "$image" -c '
      echo "临时容器 hosts："
      while IFS= read -r line; do printf "%s\n" "$line"; done < /etc/hosts
      if command -v getent >/dev/null; then
        echo "系统名称解析："
        getent hosts "$1" || echo "getent 解析失败"
      else
        echo "镜像没有 getent；这里只验证 hosts 内容，不能据此确认 Java 解析正常。"
      fi
    ' check "$worker_hostname" || echo '临时容器检查失败，请保留上方错误。'
else
  echo '容器主机名异常，跳过临时检查。'
fi
echo '=== 6. 最近 10 分钟的相关错误（最多 40 行） ==='
docker logs --since 10m --tail 300 "$container" 2>&1 | \
  grep -E 'UnknownHostException|localhost.localdomain|Application run failed|Started GatewayAdminApplication|90028|Stopping service' | tail -40 || true
echo '=== 检查结束 ==='
echo '未修改现有容器、配置或数据库。临时容器已请求自动移除。'
echo 'hosts 有映射不代表 Java 解析一定成功；请结合新日志判断。'
echo '分享输出前请检查是否含有不希望公开的服务器名称或地址。'
