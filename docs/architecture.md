# Doris 敏感数据访问网关设计

## 1. 目标与边界

业务系统继续使用 MySQL 协议连接，不修改 SQL，也不增加请求参数。连接地址从 Doris FE 改为 ShardingSphere-Proxy：

```text
业务系统 -> MySQL/TLS -> ShardingSphere-Proxy -> JDBC/TLS -> Doris FE
```

当前实现的主模式是“库内明文、出口保护”：Doris 仍以明文执行 `LIKE`、等值、范围、关联、排序和分组；Proxy 在结果集返回前，根据物理表和物理列规则执行脱敏或 AES-GCM 加密。写入 SQL 不被改写，因此不会破坏现有模糊查询。

项目不依赖调用方提供请求标识。凡通过网关账号访问的查询，都受同一套规则约束。必须通过网络 ACL 和 Doris 账号权限禁止业务系统绕过 Proxy 直连 Doris。

## 2. 已实现组件

| 类型 | 用途 | 关键属性 |
| --- | --- | --- |
| `UNICODE_KEEP_FIRST_N_LAST_M` | 证件号等固定保留首尾 | `first-n`、`last-m`、`replace-char` |
| `LENGTH_BASED_MASK` | 姓名按字符长度应用不同规则 | `rules`、`replace-char` |
| `OUTPUT_AES_GCM` | 将返回值替换为可认证密文 | `key-env`、`key-id`、`aad` |

当前参考映射为：证件号保留前 6 后 4，姓名按 1/2/3/其他长度分类，电话保留前 3 后 4，地址保留前 6 字符。

所有字符位置按 Unicode code point 计算，避免生僻字或扩展汉字被拆成半个代理字符。AES-GCM 每个值使用随机 96 位 nonce，128 位认证标签，输出格式为：

```text
GW1:<key-id>:<base64url-nonce>:<base64url-ciphertext-and-tag>
```

生产环境应把 AES 根密钥放入 KMS/HSM 或密钥管理系统。当前环境变量读取器仅用于 MVP 和受控部署，不应把明文密钥写入 YAML、镜像或 Git。

## 3. 血缘识别与失败关闭

ShardingSphere 5.5.3 的 Mask 模块不依赖结果列标签，而是调用 `SelectStatementContext.findColumnBoundInfo(columnIndex)` 获取原始数据库、表和列。因此普通别名和可绑定的子查询列有机会识别到源列，例如：

```sql
SELECT c.id_card AS credential FROM customer c;
SELECT x.credential FROM (SELECT id_card AS credential FROM customer) x;
```

但 5.5.3 的默认实现遇到“无法得到列绑定信息”时，会直接返回底层原值。本项目以同包名覆盖 `MaskMergedResult` 的方式加固 5.5.3：对无法直接脱敏的投影，递归提取表达式源列；一旦命中 MASK 规则就抛出 SQL 异常，而不是返回原值。

上线门槛必须是：

1. 对目标业务 SQL 建立自动化语料库，验证每个敏感输出列都能绑定至物理源列。
2. 确保 `hardening-lib/*` 的类路径位于官方 `lib/*` 之前，并用启动自检或攻击用例确认加固类已加载。
3. 仅对经过审核的非敏感表达式（如 `COUNT(*)`、常量）建立显式放行规则。
4. 禁止 `SELECT *` 作为新增 SQL；存量 SQL需逐条回归。

推荐的内核修改点是：

```text
features/mask/core/.../MaskMergedResult#getValue
infra/binder/.../SelectStatementContext#findColumnBoundInfo
```

`MaskMergedResult#getValue` 中 `findColumnBoundInfo` 为空时，当前版本读取并返回原值；定制版本应抛出可审计的安全异常，或调用经过审核的投影分类器。单纯按返回列名猜测来源不允许作为兜底。

## 4. 访问控制与审计

管理端维护受管对象注册表。普通业务查询在发往 Doris 前同步检查所引用对象，只有 `ACTIVE` 状态能够访问；未登记、扫描后未批准、结构漂移或同步失败均按“表不存在”语义拒绝。网关内的 CREATE/ALTER/RENAME/DROP 采用 `PREPARED -> SYNCING -> ACTIVE/FAILED` 状态机，并在客户端收到成功前完成元数据刷新和策略发布。绕过网关创建的对象只能通过扫描、批准后进入 `ACTIVE`。

按已确认的产品边界，`SHOW` 和直接访问 `information_schema` 的语句不进入该对象状态检查；这部分依靠数据库账号权限和网络边界管理。

- Proxy 对外只开放 TLS 端口；限制客户端网段和证书。
- Proxy 使用独立 Doris 服务账号，业务账号不得直连 Doris。
- Doris 服务账号遵循最小权限；管理面与数据面账号分离。
- 日志记录用户、来源 IP、逻辑库、SQL 摘要、规则版本、命中列、拒绝原因和 trace ID；不得记录敏感原值、完整密文或密钥。
- 规则变更需双人复核、版本化、可回滚，并保留审批证据。
- AES 密钥定期轮换；`key-id` 用于识别版本，旧密钥按解密保留期管理。
- 监控未解析投影拒绝数、规则缺失数、解密失败数、Proxy 绕过尝试和 Doris 直连行为。

## 5. 两种处理模式

### 明文查询，出口脱敏或加密

这是当前代码直接支持的模式。底层值不变化，查询能力完整。输出脱敏不可逆；`OUTPUT_AES_GCM` 可逆，但调用方必须具备相应解密能力。

### 写入加密，查询解密

该模式应使用 ShardingSphere Encrypt 规则，而不是 Mask 算法。它需要明文列、密文列以及模糊查询辅助列的表结构设计。任意子串 `LIKE '%片段%'` 与强加密天然冲突，通常需要 n-gram/token 辅助索引，带来存储膨胀、泄漏面和查询改写成本。未完成专项威胁建模前，不与当前出口保护模式混用。
