# 接入约定 v1（待第二阶段实施）

## 稳定边界

业务适配层负责 begin / stage / add / finish；模块不能读取业务数据库、持有数据库连接、改变事务或决定是否放行。阶段采用 Observation.Stage 固定枚举，不依赖当前逐行或未来批量实现。

内部关联头拟定为 X-Gateway-Observation-Request-Id 和 X-Gateway-Observation-Parent-Span-Id，值为 UUID；只从已认证内部请求接受，不能信任外部业务客户端值。集群归属由认证通道注入，不由请求体指定。不要将这些信息写入业务 SQL。

跨线程显式传递 Observation；不自动使用 ThreadLocal。Proxy span 与 Worker span 通过 parentSpanId 关联。阶段汇总是包含时间，不是互斥时间；并行/嵌套阶段不能相加。所有本进程耗时使用单调时钟，跨主机时间戳只用于展示。

结果类目从 Reason 映射，权限不足不是程序异常。未知错误使用 UNKNOWN；不要将 exception.getMessage() 作为标签或详情。业务原异常不变。事务提交后才记录成功；网络发送完成与客户端消费完成是不同边界。

## 请求详情协议

生产记录应增加可信 clusterId、instanceId、startedAt、finishedAt、schemaVersion、requestId、spanId、parentSpanId、operation、reason、durationNanos、measures、stages、incomplete。
核心 Recorder 不接收自由文本集群名或主机名，由受信适配器在外层绑定这些身份。
unfinishedScopes > 0 的记录应显示为不完整，不能把未闭合阶段展示成零耗时。

字节口径：RAW_BYTES 是敏感原值 UTF-8 字节，不含元数据；PAYLOAD_BYTES 是算法明文载荷；HTTP_BYTES 是请求传输载荷；CIPHERTEXT_BYTES 是返回的编码后密文字节。密文膨胀率必须明确分母，不能混用。

## 接口草案

Worker GET /api/observability/{summary,trends,status,requests} 和 GET /api/observability/requests/{requestId}。
管理员认证及集群隔离由实际平台链路实现；预览 API 只是固定样本，无认证，不能直接生产复用。
生产请求分页建议使用稳定游标；预览 page/size 只表达页面交互。summary 使用指定时窗；未知指标 null，采集异常提供 degraded 标志。

## 尚未接入的指标出口

Micrometer 适配器应位于单独依赖层，核心不导入 Spring/Micrometer。实例级固定标签为 operation、stage、reason；requestId、表名、用户名禁止作指标标签。Prometheus 使用累计 bucket，Recorder 返回的是互斥桶，需正确累加；不能把内存历史样本当完整总体计算生产 P95。

## 验收边界

第二阶段必须验证：事务回滚、取消、源表变化、撤权、密文篡改、超时、观测队列满/磁盘满、跨线程关联、跨集群越权、监控不可用、进程重启以及原有性能优化回归。使用合成数据，输出开销对比，不预设提升比例。
