# 一键更新

本次容量监控增量包目录为 `crypto-capacity-oneclick-20261023`，包含 Worker、Proxy、Platform 三份 JAR，依赖服务器已有运行镜像。安装完整监控请优先阅读 `CRYPTO-CAPACITY.md`，使用 `monitoring/update-with-monitoring.sh` 一次完成网关更新与监控安装。只运行下面的 update.sh 将更新网关代码，不会安装 Grafana。

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
bash /data/sm4-types-oneclick-20261021/update.sh \
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

刷新浏览器页面，在 SM4 配置中勾选“允许编辑普通列后解密”，保存后重新查询导出。
不填写业务标识列、不修改 SQL。原先配置了标识列的配置自动迁移到新模式，未配置的严格模式需手动开启。
受保护列只能移动，不能删列、改名、改值；最后的 __gateway_sm4 列必须保留。旧文件继续按旧规则处理。

支持按账号批量配置多个库、编辑和删除；文件期限按天设置（1—365 天）。自动关联默认为关闭，
开启后影响当前及后续未人工配置、未删除的有效账号＋库关系。人工配置不被覆盖。
停止加密与停止解密独立；要让已导出的文件永久失效，请撤销批次。

本次增量包更新 Worker、Proxy 和 Platform（包含新的管理页面），适用于已有三服务部署。
包内不含完整 Docker 运行时镜像，不支持空服务器全新安装；使用现有本地镜像离线构建，不下载依赖。

新模式不能认证普通编辑内容的业务归属，受保护显示值相同的两行交换密文可能无法识别。
详见 SM4-EDITABLE-CSV.md。请先保留一份原始 CSV，再逐项测试新增普通列、删普通列、改值、排序及篡改拒绝。
