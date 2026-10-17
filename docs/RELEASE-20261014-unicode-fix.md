# 2026-10-14 中文规则节点兼容修复

本次按最小开发原则仅更新 ShardingSphere-Proxy 加固 JAR 和 Proxy Docker 镜像，管理平台、Doris 表结构及业务配置均未改动。

修复内容：

- 节点路径匹配开启 Unicode 字符模式，使中文库、表、字段生成的 Mask 规则节点可被正确识别。
- 捕获组为空时返回空结果，避免 `Optional.of(null)` 产生 `NullPointerException`。
- Standalone H2 对不存在版本节点返回空字符串时按“未配置”处理，避免启动时 `NumberFormatException`。
- 清理 `DWD_人口` 失败发布遗留的 15 个 Mask 持久化节点，再由管理平台重新生成；未修改 Doris 数据。

验证结果：

- 3 项兼容性针对测试通过。
- 原有网关端到端回归 22 项通过。
- `DWD_人口` 重新接入成功，状态为 ACTIVE。
- 通过网关执行包含子查询和别名的查询，`姓名`、`证件号码`均返回脱敏结果。
- 发布后策略差异为空，Proxy 与管理容器均为 healthy，日志无空指针和空版本号异常。

回退资料保存在服务器 `/data/gateway-unicode-fix-backup-20261014/`；旧镜像标签为 `doris-security-gateway:pre-unicode-fix-20261014`。
