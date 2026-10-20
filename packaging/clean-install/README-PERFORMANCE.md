# SM4 5 用户并发性能增量包

这是已编译的离线增量升级包，不是全量安装包。使用服务器现有 Worker/Proxy 镜像作为运行环境，
离线替换两个 JAR 构建新镜像；不需要联网、Maven、Compose 或重新初始化。
需要现有标准 host 网络部署、Docker、Python 3、bash、sha256sum。
不适用于未知分支的定制代码：包基于 main a2289fb，完整源代码和改动记录随包提供。

本次默认支持最多 8 个加密查询并发，目标验证 5 个不同 Doris 用户同时查询。
保持原密钥、账号、数据源、脱敏规则、策略快照和 ROW_V2 文件兼容；平台不升级。
行数、有效期、CSV 格式限制不变。**不删除 install_dir，不重新运行 install/init，不重新生成密钥。**

## 1. 校验与离线构建（不停止服务）

将包解压到独立目录，例如 `/data/sm4-perf-20261020`，不要覆盖 `/data/doris-gateway/install_dir`。
以下容器名按默认示例；若不相同，用 `docker ps` 中的真实名称替换。

```bash
cd /data/sm4-perf-20261020
sha256sum -c SHA256SUMS
bash build-sm4-performance-images.sh doris-gateway-worker doris-gateway-proxy
```

脚本检查运行目录布局，复用当前镜像的运行环境。新标签已存在时停止，不覆盖旧标签。
原镜像、容器及配置不动。实际变更发生在下一步。

## 2. 维护窗口中冷备并隔离升级

暂停业务查询和配置变更。备份目录必须在运行目录之外，保证足够空间（脚本要求至少约两倍数据目录大小）。
只需要把真实容器名传给工具，不依赖 worker.env / worker.ENV 大小写。

```bash
mkdir -p /data/doris-gateway-perf-backups
python3 safe-upgrade.py stage \
  --worker doris-gateway-worker --proxy doris-gateway-proxy \
  --platform doris-gateway-platform \
  --worker-image doris-security-gateway-admin:sm4-perf-20261020 \
  --proxy-image doris-security-gateway:sm4-perf-20261020 \
  --backup-root /data/doris-gateway-perf-backups \
  --maintenance-confirmed
```

此步骤停止平台、Proxy、Worker，完成冷备后启动新 Worker/Proxy；Proxy 仅本机可达，平台仍停止。
会核验策略一致性，复用已有受控迁移机制，不发布未确认草稿。
保存工具输出的 **state.json 完整路径**；不要手动编辑其中的密码或状态。
新容器沿用旧容器的运行时环境变量、挂载、端口和 CPU/内存限制；不会把机器的 100G 全部分配给 JVM。
原 ENV 文件仍保留。工具另存的恢复文件含凭据，不要公开。

## 3. 本机验证 20 / 180 / 200 行和 5 用户并发

先用原数据库账号通过本机 Proxy 查询，确认脱敏结果、末列密文、行数及错误提示。
为 5 个已配置 SM4 加密、行数上限至少 200 的不同 Doris 账号准备 SQL，各返回恰好 180 行。
必须包含受保护字段，否则测试工具会拒绝把普通查询算成加密成功。

复制 `probe.properties.example` 到 **包目录之外**的私有目录，例如 `/data/sm4-probe-private`，
改名为 `probe.properties`，填写五组账号、密码和对应 SQL 文件。文件权限设为 600，目录设为 700。
配置采用 Java Properties 格式：密码中的反斜线写成 `\\`，前导空格写成 `\ `。
SQL 文件 UTF-8，每个文件一条 `SELECT`，不支持多语句和 CTE；不在终端或报告输出 SQL/密码/结果值。

```bash
chmod 700 /data/sm4-probe-private
chmod 600 /data/sm4-probe-private/*
docker run --rm --net host \
  -v /data/sm4-perf-20261020:/bench:ro,Z \
  -v /data/sm4-probe-private:/private:ro,Z \
  --entrypoint /opt/java/bin/java \
  doris-security-gateway:sm4-perf-20261020 \
  -cp '/bench/probe-classes:/bench/mysql-connector-j-8.4.0.jar' \
  Sm4FiveUserProbe /private/probe.properties
```

默认每用户连续 10 次，最多 5 个请求在途。工具报告成功数、包含连接建立时间的整体 QPS、
p50/p95/最大耗时；不是固定每秒发送 5 次。任一查询失败不会输出“通过”或成功 QPS。
先以 iterations=1 验证，再用 10 次采样；测试会真实查询数据库并生成加密/审计记录。
可修改 SQL 和 expectedRows 分别测 20/180/200 行；不要改生产查询的其他条件来混淆前后对比。
此工具不做授权解密验证，仍需恢复平台后抽查 CSV 解密。

## 4. 验证后恢复业务

把下列 `/实际路径/state.json` 换成第 2 步输出的路径：

```bash
python3 safe-upgrade.py accept --state /实际路径/state.json --business-verified
```

恢复原监听地址和平台。随后抽查旧文件/新文件解密、旧用户和新用户查询。
`accept` 之前如发现问题可回退：

```bash
python3 safe-upgrade.py rollback --state /实际路径/state.json
```

开放业务之后不能盲目恢复旧数据冷备，否则会丢失后续配置和记录；工具会拒绝这种回退。
旧容器、旧镜像和冷备保留，不清理。

## 验证范围与限制

见 `TEST-RESULTS.md`。本地完成代码测试、加密并发服务测试、打包校验；没有在你的
96 核服务器或生产 Doris 上运行本包。现场延迟仍由 SQL、字段宽度、来源表数、网络和磁盘决定。
超过原有字节限制仍明确失败。详见 `SM4-PERFORMANCE-20261020.md` 的配置和后续需求记录。
