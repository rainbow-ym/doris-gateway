# SQL 血缘与脱敏验收矩阵

所有测试都必须同时验证两件事：查询条件仍在 Doris 明文上执行；返回值按源字段规则处理。表中“必须拒绝”表示在血缘无法证明安全时应 fail closed。

| 场景 | 示例 | 期望 |
| --- | --- | --- |
| 直接列 | `SELECT id_card FROM customer` | 脱敏 |
| 列别名 | `SELECT id_card AS credential FROM customer` | 脱敏 |
| 表别名 | `SELECT c.id_card FROM customer c` | 脱敏 |
| 模糊查询 | `SELECT id_card FROM customer WHERE id_card LIKE '%0101%'` | 条件匹配明文，结果脱敏 |
| 电话别名 | `SELECT phone AS mobile FROM customer WHERE phone LIKE '%0013%'` | 明文匹配，返回如 `138****8000` |
| 地址子查询 | `SELECT x.location FROM (SELECT address AS location FROM customer) x` | 保留前 6 字符，其余替换 |
| 子查询别名 | `SELECT x.credential FROM (SELECT id_card AS credential FROM customer) x` | 脱敏；无法追溯则拒绝 |
| CTE | `WITH x AS (SELECT id_card FROM customer) SELECT id_card FROM x` | 脱敏；无法追溯则拒绝 |
| 多表同名列 | `SELECT a.id_card, b.id_card FROM a JOIN b ...` | 分别按各自源列规则处理 |
| 星号展开 | `SELECT c.* FROM customer c` | 敏感列脱敏；展开不完整则拒绝 |
| 函数包装 | `SELECT CONCAT('', id_card) AS v FROM customer` | 当前加固版拒绝，不得返回原值 |
| 条件表达式 | `SELECT CASE WHEN ... THEN id_card ELSE backup_id END FROM customer` | 两分支策略一致才允许，否则拒绝 |
| 聚合 | `SELECT MAX(id_card) FROM customer` | 当前加固版拒绝 |
| 常量 | `SELECT 'ok'` | 明文放行 |
| 行数 | `SELECT COUNT(*) FROM customer` | 明文放行 |
| 元数据探测 | `SHOW COLUMNS FROM customer` | 不返回数据值；按权限策略处理 |
| 不支持语法 | 任意解析或绑定失败 SQL | 拒绝，不回退直连 Doris |

建议把生产慢日志中脱敏后的 SQL 模板去重后导入测试集。上线前至少覆盖 100% 高频模板和所有访问敏感表的模板；每次 ShardingSphere、Doris 或 JDBC 驱动升级都重新执行。
