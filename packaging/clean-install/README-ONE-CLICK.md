# 一键更新

## 完整镜像包（包含 offline-images.json）

解压到一个新的目录，不覆盖原安装目录。进入解压出的 clean-install 后执行：

```bash
sha256sum -c SHA256SUMS
bash update.sh --install-dir /data/doris-gateway/install_dir --backup-dir /data/doris-gateway-backups
```

完整包直接加载三份预构建镜像，同时更新 Worker、Proxy、Platform；不需要 Maven、联网或 Docker Compose。不包含任何测试服务器数据库、账号、密钥或业务配置。默认容器名是 doris-gateway-worker/proxy/platform；其他统一前缀使用 --prefix。名称不符合此前提时先核实，不要猜测删除容器。

升级保留原 ENV 文件、挂载与数据，必须在维护窗口执行。不要对旧安装运行 install.sh init。`OBSERVABILITY_ENABLED=false` 可在对应进程环境中关闭采集；可选诊断落盘默认关闭。

全新安装：先 `docker load -i gateway-images.tar.gz`，复制 install.conf.example 为 install.conf 并配置专用目录、端口，执行 `bash install.sh init`、`bash install.sh start`。基础规则随程序初始化；不复制已有环境配置。完整镜像包中的 install.sh 已绑定本包版本。

以下为旧增量 JAR 包说明，完整镜像包无需离线构建镜像。

把新包解压到 `/data` 后，直接执行：

```bash
bash /data/sm4-editable-oneclick-20261020/update.sh \
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

## 更新后启用可编辑 CSV

在 SM4 配置列表点“编辑”，填写“不可修改的记录标识列”（例如 record_id），保存并重新导出。
标识必须出现在所有相关查询结果中，未脱敏、非空且唯一；留空仍用旧格式。
新格式允许修改普通列；受保护列、记录标识、最后的密文列不能改内容或删除。
旧文件仍按旧规则解密；新格式撤销范围是整次查询。详见 SM4-EDITABLE-CSV.md。
过期解密记录自动清理，历史审计默认保留，不自动删除密钥。
