# 实时血缘与管理平台验收报告（2026-10-13）

## 环境

- Apache Doris 4.1.3，MySQL 协议端口 9030。
- Apache ShardingSphere-Proxy 5.5.3，隔离测试端口 `127.0.0.1:13307`。
- Docker 1.13.x 单层离线镜像，未使用 Compose。
- 管理平台监听 `0.0.0.0:18080`，最终镜像健康检查为 `UP`。

## 自动化结果

最终执行 39 项测试，39 项通过，0 失败，0 错误。另有 1 项需要显式环境变量的 fixture 初始化测试不计入本轮执行数，该 fixture 已在此前真实 Doris 初始化阶段单独执行通过。

- 管理端单元测试 9 项：策略目录、密钥加解密、SQL 字段血缘解析。
- 网关插件单元测试 8 项全部通过：Unicode 脱敏、AES-GCM 与 SPI。
- 真实 Doris/Proxy 端到端测试 22 项全部通过：查询脱敏 8 项、安全边界 7 项、同步血缘 4 项、受管对象闭环 3 项。
- `GatewayLineageE2ETest` 4 项全部通过，覆盖 CTAS、View、Materialized View、Insert-Select、Rename 和 Drop 的策略继承。
- `GatewayManagedObjectE2ETest` 3 项全部通过，覆盖网关内建表自动激活和敏感字段脱敏、绕过网关创建对象的拒绝、扫描后批准并生效。

## 实际验证

- 逻辑数据库 `gateway_mask_test` 已由管理平台初始化并回读为 ACTIVE，Storage Unit 为 `doris_ds`。
- 查询结果列血缘能够穿透别名、子查询和表达式，目标记录为 `__RESULT__` 加最终结果标签。
- CTAS、视图、异步物化视图和 Insert-Select 创建后，在同一 SQL 周期内继承源字段策略并发布。
- Rename 后规则随目标对象更新，Drop 后相应元数据和规则清理。
- 业务查询仅允许访问 `ACTIVE` 对象；不存在注册映射或处于 `UNMANAGED`、`DRIFTED`、`FAILED` 等状态时失败关闭。
- 绕过网关直接创建的表经扫描进入 `UNMANAGED`，管理页面批准并完成单表加载、元数据刷新和策略发布后转为 `ACTIVE`。
- 血缘事件仅持久化 SQL SHA-256 摘要和字段边，不保存原始 SQL或业务数据值。
- 最终数据网关镜像和管理平台镜像均已构建；管理平台已用最终镜像重建并通过健康检查。
- 已验证不放置任何 `database-*.yaml` 时，Proxy 仅凭 `global.yaml` 正常启动并达到 healthy，业务库可完全由管理平台初始化。
- 最终数据网关容器已绑定 `192.168.232.132:3308`，管理端已连接该地址；重新执行脱敏、安全边界、实时血缘和受管对象闭环 22 项端到端测试，结果为 22 通过、0 失败、0 错误。
- 按需求明确不对 `SHOW` 和直接访问 `information_schema` 的语句实施受管对象过滤，该差异已作为接受边界保留。
- 服务器原有 `scippkg-nginx`、`scippkg-app` 容器未被修改，验收后仍保持运行。
