# 分阶段安全升级（开发验证版本，尚未合入正式离线包）

适用于当前标准 worker + Proxy 独占目录、host 网络、多用户模式部署。需要 Docker、Python 3 标准库、root 权限、已导入的新镜像和足够备份空间；不需要 Compose 或 pip。worker 必须仅监听 127.0.0.1。不符合挂载布局的旧部署直接拒绝，不猜测迁移。

升级会中断业务连接，必须先安排维护窗口并暂停外部写入管理库。请勿在这两个容器之外同时启动共享 H2 数据目录的程序。

## 1. 隔离升级

以下名称、镜像和目录需替换为实际值；镜像必须是经过整套验收的配套版本，不应随意混搭。

```bash
bash safe-upgrade.sh stage \
  --worker doris-gateway-worker --proxy doris-gateway-proxy \
  --platform doris-gateway-platform \
  --worker-image YOUR_VERIFIED_WORKER_IMAGE \
  --proxy-image YOUR_VERIFIED_PROXY_IMAGE \
  --backup-root /data/gateway-upgrade-backups \
  --maintenance-confirmed
```

备份根目录须预先创建为独立目录，不能包含运行目录，也不能位于运行目录内。只有真正独立、没有平台的测试实例才可用 `--no-platform` 代替平台参数。

流程：校验目录/镜像/网络和空间 → 停止平台、Proxy、worker → 冷备 worker 数据、Proxy 配置与日志 → 保留原容器 → 使用新镜像以回环地址启动 → 检查所有逻辑库 → 必要时生成已校验的策略快照。

首次快照只在旧运行规则与已确认配置一致时生成；不一致会失败并回退，不会偷偷发布管理员草稿。已有快照则核对实际规则，存在未完成发布先要求恢复。存在非 ACTIVE 逻辑库不会自动跳过。

输出会显示 `state.json` 的完整位置。该文件和备份包含敏感凭据，权限为仅当前用户可读；不能上传或公开。当前实现没有额外的备份加密，需纳入服务器备份安全管理。

## 2. 验证后放行

隔离阶段仅服务器本机可连接业务端口，平台保持停止。应实际验证业务账号登录、脱敏/明文策略、权限拒绝与预编译查询；TCP 和策略校验不能替代业务验证。不要在试运行阶段做新的业务写入或配置编辑。

```bash
bash safe-upgrade.sh accept --state /实际备份目录/state.json --business-verified
```

工具再次检查策略，按原地址和重启策略重建配套容器，最后启动原平台容器。不会升级平台镜像。

## 3. 放行前回退

```bash
bash safe-upgrade.sh rollback --state /实际备份目录/state.json
```

停止隔离新容器，恢复旧容器和冷备。失败数据目录及新容器改名保留，不删除。stage 失败也会尝试相同回退。回退失败时停止并保留现场，不保证任意磁盘/主机故障下自动修复。

进入 ACCEPTING / ACCEPTED 后不允许自动恢复旧冷备，避免覆盖已经发生的新配置变更；该阶段失败须核实实际状态再人工处理。本工具不是高可用滚动升级、任意版本降级或业务数据库备份方案，也不回滚 Doris 中执行过的 SQL。

升级后请检查所有服务及业务连接；确认不再需要历史版本后，另行安排旧容器和备份清理，本工具不自动清理。
