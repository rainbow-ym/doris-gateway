# 加解密观测与容量验证

本版包含网关计量、慢请求样本、独立 Prometheus/Grafana/Node Exporter 离线安装、告警规则和并发客户端。不会自动调整生产并发、线程、堆或数据库连接数，也不会改变 SM4 授权与完整性规则。

## 一键离线更新

适用：已有 Worker/Proxy/Platform 三容器、host 网络、核心数据绑定在指定安装目录；Linux x86_64、systemd、Python 3、Docker、sha256sum。新监控使用独立 systemd 服务，不依赖拉取镜像或在线安装插件。需保留当前网关运行镜像，更新脚本以这些镜像为基础离线替换三个 jar。

将新包解压到 `/data/crypto-history-oneclick-20261023` 后，在维护窗口执行。下面的 `--platform-url` 必须换成你浏览器实际访问的平台地址（含端口，不含登录页路径）：

```bash
bash /data/crypto-history-oneclick-20261023/update.sh \
  --install-dir /data/doris-gateway/install_dir \
  --backup-dir /data/doris-gateway-backups \
  --monitor-dir /data/doris-gateway-monitor \
  --platform-url http://192.168.1.3:18081
```

四个路径分别是解压目录、现有安装目录、冷备目录、监控目录。已安装上一版监控时，使用原监控目录，会复用凭据和历史数据；没有安装时使用尚不存在的新目录。监控目录与网关安装目录必须互不包含。自定义容器名前缀时增加 `--prefix 前缀`。`update.sh` 现在能识别监控参数并转入集成流程，不再使用 `--grafana-bind`。

更新会冷备、短暂停止查询并重启三个网关容器，保留原配置、密钥、业务数据和容器 CPU/内存限制。不会清库。监控密码自动生成，以独立环境变量注入新容器；原 worker.env 不被重写。今后若手工按旧 worker.env 重建容器，须同时传入监控目录的 metrics.env，否则采集会失效。

登录现有管理平台，点击首页右上方 **历史监控**。不再单独登录 Grafana，13000 强制仅监听 127.0.0.1；原 login.txt 是本机运维凭据，不需提供给用户。平台每次代理请求都检查管理员会话，后端固定转发到本机 Grafana，使用 Viewer 身份，不转发浏览器 Cookie、Authorization 或伪造的身份头，不向浏览器返回 Grafana 登录 Cookie。修改平台密码或退出平台后，按原平台会话策略失效。

看板标注“本机历史监控”：当前采集的是本机 Worker/Proxy/主机，不冒充其他登记的远程集群。平台其他配置操作继续要求 CSRF；仅 Grafana 的数据查询和功能开关评估两个只读 POST 使用严格的同源 Origin + JSON 校验，其他 Grafana 写操作不代理。直接 HTTP 平台和平台自身 HTTPS 可用；若在另一个 HTTPS 反向代理后部署，需先配置可信的转发头处理，使平台识别的协议/主机/端口与浏览器一致，不能简单关闭同源校验。

Prometheus 19090、Node Exporter 19100、Proxy 指标 18092 均仅本机监听；Worker 指标使用原管理端口和只读账号。不要把 login.txt、metrics.env 或数据库文件发到聊天、工单或版本库。

监控二进制及配置约需数 GB 空间，另外为指标留至少 10 GB 和网关冷备空间。Prometheus 保留上限为 **30 天或 10 GB，先达到者生效**；WAL、索引和其他服务数据会额外占空间。修改保留配置需编辑生成的 prometheus.service 并重新部署服务，非业务密文有效期配置。

安装先校验包和现有监控归属，新安装还校验厂商校验和及 Prometheus 配置；再更新网关、备份并修改 Grafana 接入配置，最后启动监控并检查三个采集目标。Proxy 指标已改为在 GatewayBootstrap 启动时监听，不再等待第一条查询。端口已占用或凭据无效时，Proxy 日志输出异常类别，不输出凭据。

## 中断与恢复

现有网关更新器保留备份和状态文件；开放业务前失败可按其状态恢复。开放业务后不会自动用旧冷备覆盖新写入。

若网关已更新成功，仅监控未启动，不要重复整个更新，也不要删除数据。查看服务日志和端口后执行：

```bash
python3 /data/crypto-history-oneclick-20261023/monitoring/install.py start \
  --monitor-dir /data/doris-gateway-monitor
journalctl -u gateway-prometheus -u gateway-node -u gateway-grafana -n 100
```

若 prepare 已完成而更新尚未执行，使用已生成的凭据继续：

```bash
bash /data/crypto-history-oneclick-20261023/update.sh \
  --install-dir /data/doris-gateway/install_dir \
  --backup-dir /data/doris-gateway-backups \
  --metrics-env /data/doris-gateway-monitor/metrics.env
```

prepare 在生成 manifest.json 前失败时，会保留部分目录。先核实原因，再使用另一个全新的监控目录重试；不要盲目清空原目录。已有不同配置的监控服务会被拒绝覆盖。

如果网关更新完成、集成配置步骤未完成，可运行 `monitoring/install.py integrate --monitor-dir 原监控目录 --platform-url 当前平台地址`，然后运行上述 start。该步骤会保留原 grafana.ini 备份，不清空 Grafana 或 Prometheus 数据。

## 本次错误修复与详细日志

截图能确认 Prometheus ready、主机采集 up，而 Proxy 的 18092 连接被拒绝；无法仅凭截图判定 Grafana 状态。代码确认旧版指标依赖首次查询初始化，已改为启动时初始化，并用无数据库查询的独立进程测试验证。

一键更新把四阶段输出保存到备份目录 `monitoring-update-时间-进程号.log`，结束时打印退出码和路径；成功、失败都会输出逐项健康检查。监控目录 `setup-logs/health-UTC时间.json` 包含：

- Prometheus/Node/Grafana 的 ActiveState、SubState、Result、ExecMainStatus。
- Worker、Proxy、Prometheus、Node、Grafana 各端口连通性。
- Prometheus 正确的 `/-/ready`、Grafana 子路径下 `/history/api/health` 数据库状态。
- 三个采集目标状态及 CONNECTION_REFUSED、HTTP_401、HTTP_403、TIMEOUT 等分类。

诊断不输出环境变量、密码、令牌或响应正文，也不自动导出可能含其他信息的完整服务日志；需要时输出 journalctl 检查命令。健康失败明确提示网关可能已经更新成功，不再误导为“整个过程没有修改网关”。

单独重新检查（不更新、不重启）：

```bash
python3 /data/crypto-history-oneclick-20261023/monitoring/install.py diagnose \
  --monitor-dir /data/doris-gateway-monitor
```

## 指标口径

选择组件和操作后，Grafana 展示：成功请求/行/保护单元格吞吐、累计成功数量、加权平均请求和每行摊销耗时、P50/P95/P99、完整分钟最长耗时、各阶段成本、各结果分类、采集质量、连接池等待、JVM 堆/GC、主机 CPU/内存/磁盘和容器资源。

* **Proxy 加密查询**从加密结果准备开始，包含读取结果、脱敏、Worker 调用和结果消费释放，不包含此前全部 SQL 执行时间，也不保证客户端已接收。RESULT_DRAIN 是构造完结果后到释放的墙钟时间。
* **Worker**计量服务端加解密工作及相关事务边界，不含客户端上传前和下载后的等待。Worker 是 Proxy 的子过程，两者不能相加。
* **每行摊销耗时** = 成功请求总耗时 / 成功行总数，包含固定开销，不是逐行独立计时的分位数。ROW_ENCRYPT/ROW_DECRYPT 按每次密码运算计时，适用于当前可编辑格式；公共元数据、格式识别单列，旧格式不保证有同样细分。
* 阶段有嵌套，不能求和当成总耗时。累计耗时秒/秒在并发时可以大于 1。
* 新累计指标 `gateway_export_*` 由各进程本地维护，不依赖请求列表抽样或 Proxy 明细上传成功；进程重启归零，Prometheus rate/increase 处理采集到的计数器重置。重启前未被采到的增量无法恢复。
* P 值是直方图估计；超过最高有限桶 600 秒的尾部不能精确估算。increase 有边界外推，显示数量可能为小数，不是精确审计账本。
* 最长耗时为**采集到的完整分钟最大值**，按请求完成时间归属，最近未完成分钟不计；所选窗口边界不是逐请求精确截取。采集中断、重启和无覆盖时不能认定这是全量最大值。`stage_lifetime_max` 只表示进程生命周期最大值。
* 管理页面额外保留每组件/操作最多 20 条慢请求样本，最长 1 小时，独立于普通列表淘汰；这是诊断样本，旧大值到期后不会补回已经淘汰的小值。
* 只有成功请求进入成功吞吐/均值；安全拒绝、输入错误、依赖错误、繁忙和取消单独显示。无样本/缺失数据不是 0 耗时。
* Proxy 连接池指标为用户池合计，不包含所有系统池。cgroup v2 支持内存、CPU 配额/限流；v1 仅支持 namespace 根布局，其他布局显示缺失，避免误报宿主机数据。主机指标始终由 Node Exporter 采集。

指标标签不含 SQL、用户姓名、数据单元格、密文、密钥或请求编号，避免泄密与高基数。监控保存数值时间序列，不保存每次查询的加密结果。原有 SM4 凭据/元数据与审计存储仍按原功能处理；原有可选诊断日志也不等于完整历史请求库。

## 告警与采集质量

15 秒抓取并评估，内置采集失败、详情丢失、核心观测拒绝、连接池持续等待、错误/繁忙占比及 P95 告警。初始阈值 1% 和 5 秒仅供起步，应按业务 SLO 修改 rules.json 后检查并重启 Prometheus。

规则在 Prometheus 的 Alerts 页面生效；没有配置短信、邮件、Webhook 或 Alertmanager 收件端。当前不会自动向外发送通知。应先确认三个目标 up=1、无观测拒绝、无采集间断，再评估容量。详情上传丢失不影响本地累计指标，但会影响关联诊断。核心观测拒绝意味着累计统计也存在缺口。

## 五用户容量验证

容量必须通过目标硬件、Doris 4、实际驱动与查询测量；96 核/100 GB 无法直接换算 QPS。本机测试不代表生产极限。

包内 `capacity-probe-classes` 和 MySQL JDBC 驱动可用 Java 8+ 运行。复制 monitoring/query.properties.example 到私有目录，填写五个不同的 Doris 用户及密码、网关地址、SQL 文件名；SQL 文件使用单条 SELECT（不带分号）。配置文件 chmod 600，不要提交；Properties 中反斜杠需按 Java Properties 规则转义。不要把未经审查的函数或外部调用放入压测 SQL。

```bash
java -cp '/data/crypto-history-oneclick-20261023/capacity-probe-classes:/data/crypto-history-oneclick-20261023/mysql-connector-j-8.4.0.jar' \
  CapacityProbe /data/private-probes/query.properties
```

`concurrency=5` 表示五个在途查询，每个线程固定一个不同用户，不表示 QPS=5。`arrivalQps=0` 为完成后立即发下一次；设为 5、10 等正数时按固定到达率发起，仍最多五个在途，超出的请求计入 clientRejected，不能忽略这些拒绝后宣称达到该 QPS。

先预热 60 秒，再分别测 LIMIT 20、180、200 和业务上限（须不超过实际配置）；每组用 60～300 秒测试。对照关闭加密与开启加密（关闭时 expectEncrypted=false），再测 prepared=true/false。固定到达率从低到高逐档增加；出现错误/拒绝、持续池等待或 P95 超出业务目标就停止加压。测试会真实查询、生成原有凭据/审计，须使用已授权测试账号和测试数据。

解密使用 decrypt.properties.example，指向对应 Worker 的本机端口或 HTTPS 地址、真实授权 CSV 和管理员凭据。工具不把返回明文写盘或打印。查询与解密混合负载可同时运行两个进程，分别保存数值输出，记录实际并发合计。

结果包括 offered/success/failed/clientRejected、成功行数、包含排空时间的实际 QPS、成功请求均值/P95/P99/最大值。查询计时包含执行到读取全部结果；固定到达率计时从计划发起时刻开始，包括调度延迟。失败单独计数，不混入成功延迟。客户端本身过载也会拒绝，因此必须同时检查压测机资源。最多一百万成功延迟样本，达到上限会拒绝给出容量结论，应缩短单轮时间。CSV 输入限制 1 MiB、响应 8 MiB。

建议报告：版本/硬件/容器与 JVM 限额/数据库连接池、驱动、查询行列与保护列数、密文字节、并发、目标 QPS、成功 QPS、失败及拒绝数、客户端 P95/P99/max、Worker 行均值、Proxy 等待与 GC、CPU/内存/磁盘、采集覆盖、业务 SLO。将满足 SLO 的稳定负载作为本轮测得容量，留出业务峰值与资源余量，不把短时峰值当作保证。

## 本版验证边界

自动化验证包括网关单元与安全回归、累计指标与明细淘汰独立性、采集账号只读、容器缺失指标、慢样本边界、离线配置生成、目录穿越拒绝、升级保留资源/凭据、模拟客户端到达率与拒绝计数，以及真实隔离 Worker 的浏览器页面。具体计数见包内 TEST-RESULTS.md。

真实 Windows Grafana 与平台已在本机隔离联调，Prometheus 查询使用模拟响应；Linux systemd 安装、实际 Doris 4 五用户压力、监控开销与最大容量仍需在内网测试。安装脚本会现场执行厂商 promtool 校验并检查服务与抓取健康；这些检查不能替代真实业务验证。
