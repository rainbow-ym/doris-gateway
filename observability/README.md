# 加解密可观测性

第二阶段已在独立开发分支接入 Proxy、Worker 和管理页面，尚未发布到服务器。当前接入、配置、验证与未覆盖范围见 [第二阶段说明](PHASE2.md)。下文保留第一阶段独立预览的说明；模拟预览不等于真实监控页面。

第一阶段时本目录独立运行；第二阶段由根 pom 和 admin/pom.xml 引用共享 Java 源码，并由适配层采集。预览服务仍仅监听本机，无真实集群访问能力。

## 运行独立预览

需要 Node.js 18 或更新版本，无 npm 依赖：

```powershell
node observability/preview/server.mjs
```

访问 http://127.0.0.1:18190 ，Ctrl+C 停止。端口可通过 OBS_PREVIEW_PORT 修改。不要将模拟服务作为生产服务发布，它没有真实登录认证。

## 运行测试

Java 核心兼容 Java 8，无第三方依赖，测试也无需 Maven 下载依赖：

```powershell
powershell -File observability/test.ps1 -JavaHome '你的JDK目录'
```

也可单独运行 `node --test observability/preview/server.test.mjs`。
可选真实浏览器交互测试：设置 PLAYWRIGHT_PATH 为已有 Playwright 模块目录，CHROME_PATH 为 Chrome 可执行文件路径，然后运行 `node observability/preview/browser.test.mjs`。测试自动启动和关闭回环预览服务，截图保存到忽略提交的 target 目录。不安装浏览器、不访问外部服务器。
独立 Maven 构建入口是 `mvn -f observability/pom.xml package`；自包含 Java 测试需要使用上面的 test.ps1 执行，不伪装成 Maven 已自动执行测试。

## 已实现

- 数值与枚举限定的 Observation 接口、无操作实现、显式请求上下文。
- 单调时钟计时；批次阶段累计次数/总耗时/最大单次耗时。
- 幂等结束、不可变记录；固定耗时直方图桶（累计请求指标不受详情过期影响）。
- 有界活动请求准入、有界内存历史、按保留期限清理；满容量淘汰和拒绝采集计数。
- 独立只读模拟 API、集群过滤、分页、异常分类、详情弹窗与缺失指标提示。
- 没有 SQL、结果值、用户名、密钥、完整异常或密文等自由文本采集入口。

## 第一阶段结束时的待办（当前进度以 PHASE2.md 为准）

- 真实业务生命周期埋点、事务完成回调、异步上下文传播、发送结束/取消处理。
- Micrometer/Prometheus、持久化诊断、跨进程汇总通道、采样、JVM/cgroup 采集。
- 平台真实管理员认证、集群路由、菜单及部署接入。
- 当前内存历史不跨重启；没有业务接入就没有真实指标。活动请求若漏调 finish 会占用采集槽位，最多达到 maxActive 后拒绝新采集；不会自动将它记作业务超时。第二阶段要在真实生命周期验证收尾。
- 概览中的分位数只是固定模拟样本计算；真实分位数必须使用时窗直方图并按实例正确汇总。
- 核心仅提供轻量采集基元，不宣称当前支持生产级故障隔离；接入适配器必须隔离观测异常，且不能用观测失败替换业务结果。

后续先合并另一位开发者的性能优化，再以单独接入提交引用该模块。不要将本阶段预览页面当作已完成生产监控。
