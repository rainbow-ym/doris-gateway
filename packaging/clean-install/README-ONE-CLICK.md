# 一键更新

把新包解压到 `/data` 后，直接执行：

```bash
bash /data/sm4-perf-oneclick-20261020/update.sh \
  --install-dir /data/doris-gateway/install_dir \
  --backup-dir /data/doris-gateway-backups
```

- `--install-dir`：现有安装目录，包含原配置和数据。脚本核对实际容器挂载，路径不符就停止。
- `--backup-dir`：备份保存目录，必须在安装目录之外；没有则自动创建。
- `--prefix`：可选，默认 `doris-gateway`，对应 `doris-gateway-worker/proxy/platform` 三个容器。

脚本自己找到包文件，无需修改脚本、ENV 文件、镜像名或手动执行 stage / accept。
会自动校验文件、离线构建镜像、停服务冷备、更新、检查策略和服务健康、恢复业务入口。
更新期间查询会中断，请在维护窗口执行。不要删除原安装目录或重新生成密钥。

在开放业务之前失败，原升级流程会尝试回退；一旦已开放业务，不自动覆盖旧数据备份，
避免丢失期间的新配置和记录。失败会显示备份位置和恢复状态文件，保留原镜像和容器。

“更新完成”表示基础健康检查通过，不代表真实业务和 QPS 验收通过。
完成后再测试 20/180/200 行及 5 用户并发。性能测试工具和详细手工步骤见 README-PERFORMANCE.md。
此包的一键镜像标签为每次更新自动生成，因此无需照抄旧手工步骤中的固定镜像标签。
