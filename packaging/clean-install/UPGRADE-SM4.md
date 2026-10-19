# 已部署实例升级：保留数据、账号、策略和密钥

适用于标准 host 网络、独立挂载目录的旧部署。准备维护窗口，升级会中断连接。需要 root、Docker、Python 3、Bash、openssl，不能用 init 覆盖旧目录。不需要 Compose、Java 或 Maven。

解压后进入 clean-install：

```bash
sha256sum -c SHA256SUMS
gzip -dc doris-security-gateway-sm4-row-v2-20261019-images.tar.gz | docker load
docker ps --format '{{.Names}}'
mkdir -p /data/gateway-sm4-upgrade-backups
```

确认容器名称，再执行（名称按实际部署替换）：

```bash
bash upgrade-sm4.sh doris-gateway-worker doris-gateway-proxy doris-gateway-platform /data/gateway-sm4-upgrade-backups
```

脚本保留已有密钥，缺少时在原 worker 数据挂载目录创建密钥。随后停止三个服务、冷备数据、保留旧容器，worker 和 Proxy 以回环地址启动。平台暂时停止。不会重新初始化旧配置，也不会操作 Doris 表。

在服务器本机使用真实业务账号验证查询和脱敏，不做业务写入或修改策略。通过后，将输出的 state.json 完整路径填入：

```bash
bash safe-upgrade.sh accept --state /实际备份目录/state.json --business-verified
```

放行会更新平台镜像并保留原账户、集群登记和端口。登录平台进入集群 → SM4 加密导出，按 SM4-EXPORT.md 启用。确认看到的是“逐行”及 __gateway_sm4 列，不是旧“末行”方案。

放行前发现问题可以回退：

```bash
bash safe-upgrade.sh rollback --state /实际备份目录/state.json
```

放行后发生写入不能直接覆盖冷备回滚。备份、旧容器、旧镜像均保留；请勿立即清理。端口、容器布局不满足条件时工具会拒绝，不能绕过校验硬套脚本。平台管理页的升级不保证业务系统自动展示新增列，仍需业务端实际验收。
