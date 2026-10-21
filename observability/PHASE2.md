# 第二阶段接入与验证

记录日期：2026-10-21。独立分支：codex/crypto-observability-phase2。
集成 b9c2e5d、4610e43 和 b030fe7（可编辑 CSV），不修改算法、权限决策或并发策略。没有发布到生产，也没有运行本机 Docker。

## 已接入

- 共享无依赖 Java 采集核心，由 Maven build-helper 添加源目录，随 Proxy、Worker 各自构建，不需要先安装额外私有依赖。
- Proxy 记录策略、结果收集、序列化、调用 Worker、缓冲释放；内部 UUID 请求头关联 Worker 子阶段，不改变业务 SQL 和结果结构。
- Worker 记录账号、来源、密钥、SM4、凭据、审计、CSV 和权限探测等已埋点阶段。Controller 在 Spring 事务代理返回后记录成功；提交失败不能记成功。
- 管理页面增加“加解密运行监控”，复用管理员认证与当前集群路由；分页列表、原因分类、关联详情弹窗、分钟趋势和固定桶分位数估计。
- Micrometer/Prometheus 提供请求与阶段直方图、固定枚举原因和 JVM 指标。用户、表名和请求编号不是指标标签。
- 性能遥测只收集数值、枚举、UUID，不记录 SQL、敏感原值、密文、密码或异常正文；与安全审计队列完全分离。

## 配置与接口

| 环境变量 | 默认 | 含义 |
| --- | --- | --- |
| OBSERVABILITY_ENABLED | true | Proxy / Worker 采集开关，分别配置于进程环境 |
| OBSERVABILITY_SAMPLE_EVERY | 1 | Worker 成功详情每 N 条保留一条（1–1000）；聚合计数不采样，非成功详情不按此参数采样 |
| OBSERVABILITY_JOURNAL_DIR | 空 | Worker 可选的专用诊断目录；不设置则不落盘 |

Worker 管理员 GET 接口：`/api/observability/status`、`summary`、`requests`、`requests/{requestId}`、`metrics`（均在同一前缀下）。summary 支持 component、operation、minutes；requests 支持分页和 outcome。真实参数校验见 ObservationController。

Prometheus 直接抓取 Worker 的 `/api/observability/metrics`，使用现有管理员认证及受保护网络/TLS；不要通过返回 JSON 的平台转发通道抓取文本指标。不要把密码写入仓库。

内部 POST `/api/internal/sql/observation` 复用内部控制令牌，限制请求体 16 KiB，强制标记来源为 Proxy；调用方不能提交任意集群标签。Proxy 单线程有界队列异步上报，连接/读取上限为 250/500 ms，无重试，失败计入丢弃，不影响查询。

## 保留与统计边界

- 内存详情最多 2000 条、1 小时；趋势为最近 60 个分钟桶。重启清空内存；可选诊断文件不回放到页面。
- 可选文件队列 64 条，单文件 10 MiB，保留当前和上一份。按大小轮转，不承诺按天删除；运维按自身保留政策管理目录。写失败或队列满只丢性能采样，不删除安全审计。
- Proxy 总耗时到结果消费/释放，不等于客户端已收到全部数据；提前释放标记“结果未完全消费即释放”，不能推断为用户主动取消。
- Worker 总耗时包含服务事务提交，但不包含客户端接收文件。提交阶段没有独立计时。
- 父子阶段可能嵌套，不能把 Proxy HTTP 等待和 Worker 耗时相加。页面分位数是固定直方图桶上限估计，不是精确排序值；超出最大桶显示不可用。
- 系统库指标仅覆盖已埋点调用；容器内存和连接池指标尚未接入，显示未知。Proxy 上报为尽力传输，未收到记录不能视为成功。
- 当前没有完成真实 Doris 全链路压测、客户端断开矩阵及遥测开关性能对照；本阶段测试不能替代生产验收。

## 合并验证

- Worker：161 项，0 失败、0 错误，1 项依赖外部接入环境的测试跳过。覆盖可编辑 CSV、真实事务提交失败分类、权限拒绝、遥测队列/文件故障、接口认证和大小限制。
- Platform：5 项通过。
- Linux 隔离目录 Proxy：62 项，0 失败、0 错误，24 项原有条件跳过；不更新 132 上运行的容器或数据库。
- Java 8 核心：30 项断言、1000 次并发；预览 API：4 组通过。
- 真实本机隔离 Worker：管理员认证、内部令牌、Prometheus、实时页面分页和弹窗通过。页面数据为经真实接口注入的合成数值事件，不是实际 Doris 查询。
- 既有 SM4 浏览器测试通过；一键更新 Python 测试 5 项、安全升级测试 3 项通过。

测试夹具修复：五用户并发用例先完成 mock 配置后再放行线程，避免配置与调用竞态（在干净 b030fe7 基线也已复现）。审计传输子进程只加载需要的两个类目录，避免继承整个 Surefire 启动类路径；Linux 用例实际通过。Windows 不支持该目录 fsync，明确跳过此用例，不降低生产审计持久性。

复验入口：根目录、admin、platform 的 `mvn test`；`observability/test.ps1`；`scripts/observation-live-local-test.cjs`（OBS_JAVA 指向 JDK）；`scripts/sm4-ui-local-test.cjs`。
