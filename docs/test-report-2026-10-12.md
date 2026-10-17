# 真实 Doris 集成测试报告（2026-10-12）

## 环境

- Apache Doris 4.1.3，MySQL 协议端口 9030。
- Apache ShardingSphere-Proxy 5.5.3，隔离监听地址 `127.0.0.1:13307`。
- MySQL Connector/J 8.4.0。
- 隔离逻辑库 `gateway_mask_test`，仅重建该库中的 `customer` 测试表。

## 字段映射与结果

| 物理列 | 示例原值 | 出口规则 | 网关返回 |
| --- | --- | --- | --- |
| `customer.id_card` | `110101199001011234` | 前 6 后 4 | `110101********1234` |
| `customer.name` | `张三` | 2 字姓名保留首字 | `张*` |
| `customer.phone` | `13800138000` | 前 3 后 4 | `138****8000` |
| `customer.address` | `北京市朝阳区建国路88号` | 前 6 字符 | `北京市朝阳区******` |
| `customer.secret_value` | `payload-one` | AES-256-GCM 出口加密 | `GW1:output-key-v1:...` |

`id_card LIKE`、`phone LIKE` 和 `address LIKE` 均在 Doris 明文上命中，但结果在 Proxy 出口被处理。

`secret_value LIKE '%payload%'` 也在 Doris 明文上命中。同一原值连续查询两次获得不同密文，两份密文均通过 AES-GCM 认证并回解为 `payload-one`。

## SQL 形态

- 通过：直接列、表别名、列别名、子查询别名、CTE 别名、`SELECT *`、PreparedStatement 模糊查询。
- 失败关闭：`CONCAT('', id_card)`、包含 `id_card` 的 `CASE`、`MAX(id_card)`。
- 明确放行：常量投影、`COUNT(*)`。

## 结果

`mvn -B clean verify` 共运行 24 项测试，`Failures: 0, Errors: 0, Skipped: 0`。
