# Doris 安全网关部署与实时血缘使用说明

## 1. 交付范围

本版本保持业务系统使用 MySQL 协议和原有 SQL。Doris 继续用明文执行模糊查询等条件，结果经过 ShardingSphere-Proxy 后按物理字段策略脱敏或加密。

业务 MASK 规则不再依赖手写 YAML。管理平台负责初始化逻辑库和 Storage Unit、扫描元数据、批量配置策略、发布和回滚。Proxy 在每条业务 SQL 执行前同步检查受管对象；DDL 在一个 SQL 执行周期内完成预登记、Doris 执行、元数据刷新、策略继承和激活，完成后客户端才收到成功响应。

本版本只处理以下血缘：

- 查询结果列，包括别名、子查询、CTE、表达式和星号展开。
- `CREATE TABLE ... AS SELECT ...`。
- `CREATE VIEW ... AS SELECT ...`。
- Doris `CREATE MATERIALIZED VIEW ... AS SELECT ...`。
- `INSERT INTO ... SELECT ...`。
- 表或视图重命名、删除。

不包含审批流、第三方数据目录、其他数据库适配和通用数据治理功能。

## 2. 离线包内容

服务器交付目录为 `/data/doris-security-gateway-package`：

```text
doris-security-gateway-0.1.0.tar.gz        数据网关镜像
doris-security-gateway-admin-0.1.0.tar.gz  管理平台镜像
doris-security-gateway-source-0.1.0.tar.gz 源码
dwh-entry-config.zip                       旧版手工业务 YAML（仅迁移参考）
run-docker.sh                              数据网关启动脚本
admin-console/run-admin-docker.sh          管理平台启动脚本
SHA256SUMS                                 文件校验值
```

镜像采用 `docker save | gzip` 格式，兼容 Docker 1.13，不使用 Compose。

## 3. 首次部署

校验并导入镜像：

```bash
cd /data/doris-security-gateway-package
sha256sum -c SHA256SUMS
gzip -dc doris-security-gateway-0.1.0.tar.gz | docker load
gzip -dc doris-security-gateway-admin-0.1.0.tar.gz | docker load
```

准备管理端配置：

```bash
mkdir -p /data/doris-security-gateway-admin
cp admin-console/admin.env.example /data/doris-security-gateway-admin/admin.env
vi /data/doris-security-gateway-admin/admin.env
chmod 600 /data/doris-security-gateway-admin/admin.env
```

关键配置含义：

- `ADMIN_USERNAME`、`ADMIN_PASSWORD`：管理页面账号和密码。
- `ADMIN_MASTER_KEY_B64`：加密保存 Doris 连接密码的固定 32 字节 Base64 密钥；投入使用后不可随意更换。
- `LINEAGE_INGRESS_TOKEN`：Proxy 上报血缘的共享 Token，至少 24 字符。
- `ADMIN_BIND_ADDRESS`、`ADMIN_PORT`：管理页面监听地址和端口。
- `PROXY_HOST`、`PROXY_PORT`：管理端执行 DistSQL 的 Proxy 地址和端口。如果 Proxy 只绑定服务器实际 IP（例如 `192.168.232.132`）而不是 `0.0.0.0`，这里也必须填写该实际 IP，不能填写 `127.0.0.1`。
- `PROXY_ADMIN_USER`、`PROXY_ADMIN_PASSWORD`：Proxy 独立管理账号。
- `ADMIN_DB_PASSWORD`：管理端 H2 数据库密码。

先启动管理端：

```bash
cd /data/doris-security-gateway-package
ADMIN_HOST_DATA_DIR=/data/doris-security-gateway-admin \
  ./admin-console/run-admin-docker.sh
docker logs -f doris-security-gateway-admin
```

准备数据网关配置。首次运行只会生成全局配置和日志配置模板并退出；业务数据库配置由管理平台创建：

```bash
mkdir -p /data/doris-security-gateway
GATEWAY_DATA_DIR=/data/doris-security-gateway ./run-docker.sh
vi /data/doris-security-gateway/conf/global.yaml
vi /data/doris-security-gateway/gateway.env
chmod 600 /data/doris-security-gateway/gateway.env
```

`gateway.env` 示例：

```bash
GATEWAY_PUBLISH_IP=192.168.232.132
GATEWAY_HOST_PORT=3307
GATEWAY_NETWORK_MODE=host
GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:18080/api/internal/lineage/events
# 可留空；留空时根据 GATEWAY_LINEAGE_ENDPOINT 自动推导为 /api/internal/sql
GATEWAY_CONTROL_ENDPOINT=
GATEWAY_LINEAGE_TOKEN=与管理端LINEAGE_INGRESS_TOKEN完全相同
GATEWAY_LINEAGE_TIMEOUT_MS=5000
GATEWAY_LINEAGE_FAIL_CLOSED=true
GATEWAY_JAVA_OPTS='-server -Xms512m -Xmx1g -Xmn256m -Xss1m -Dfile.encoding=UTF-8 -Djava.awt.headless=true'
```

如果策略使用 `OUTPUT_AES_GCM`，还必须加入固定的 `GATEWAY_OUTPUT_AES_KEY_B64`。只使用脱敏策略时不需要该项。

启动数据网关：

```bash
GATEWAY_DATA_DIR=/data/doris-security-gateway ./run-docker.sh
docker logs -f doris-security-gateway
```

脚本每次启动都会重新读取 `gateway.env`。修改该文件后需要删除并重建容器，单纯 `docker restart` 不会重新注入环境变量：

```bash
docker rm -f doris-security-gateway
GATEWAY_DATA_DIR=/data/doris-security-gateway ./run-docker.sh
```

## 4. 管理页面操作顺序

浏览器访问 `http://管理服务器IP:18080`，然后按以下顺序操作：

1. 新增 Doris 连接并执行连接测试。
2. 选择连接，填写逻辑数据库、Doris 物理数据库和 Storage Unit 名称，执行初始化。
3. 扫描元数据。扫描只读取 `information_schema.COLUMNS`，不读取业务数据。
4. 按库、表或字段筛选，复选需要保护的字段，批量选择证件号、姓名、电话、地址或 AES-GCM 策略。
5. 检查待发布变更并发布；页面会执行 DistSQL、回读验证并保存版本。
6. 在“实时血缘”页面查看受管对象、同步执行记录以及查询和受支持 DDL 的字段级来源、目标及继承策略。

扫描发现的现有表不会被静默放行。新发现对象为 `UNMANAGED`；在“实时血缘”页面点击批准后，管理端执行单表加载/刷新、确认推荐字段策略、发布 MASK 规则，全部成功后才转为 `ACTIVE`。结构发生变化的已受管表会标记为 `DRIFTED`，也必须重新批准。

发布后，业务系统只需把连接地址从 Doris FE 改为 Proxy：

```text
jdbc:mysql://网关服务器IP:3307/逻辑数据库?useSSL=false&allowPublicKeyRetrieval=true
```

业务 SQL 不需要增加请求标识或其他参数。必须用网络 ACL 和账号权限阻止业务账号绕过网关直连 Doris。

## 5. 同步血缘和策略继承

查询完成绑定后，Proxy 根据 Binder 得到真实源库、源表和源列，即使最终列使用别名、子查询或表达式也不依赖返回列名猜测。查询事件的目标表使用 `__RESULT__`，目标列使用最终结果标签。

每条业务 SQL 在发送给 Doris 前先调用管理端预校验。普通查询引用的每个表都必须在注册表中且状态为 `ACTIVE`；否则返回“表不存在”语义的错误。受支持的 DDL 先把目标对象预登记为 `PREPARED`，Doris 执行成功后转为 `SYNCING`，再刷新目标表元数据、继承源字段的敏感策略、发布 MASK 规则，最后转为 `ACTIVE`。`INSERT INTO ... SELECT ...` 在写入前先完成目标策略继承和发布，避免写入成功而策略尚未建立。

事件按 OpenLineage 风格保存字段边，但只持久化 SQL SHA-256 摘要，不保存原始 SQL和数据值。管理端同时保存同步执行记录，失败记录可在页面重试；定时任务也会协调长时间停留在中间状态的记录。

受管对象校验固定采用失败关闭：管理端超时、拒绝、元数据刷新或策略发布失败都会让本次语句向客户端返回错误，避免“新对象已可查询但规则尚未建立”的静默窗口。由于 Doris DDL 和管理端发布不属于同一个分布式事务，极端情况下 Doris 对象可能已经创建而客户端收到错误；此时对象保持非 `ACTIVE`，后续查询仍被拒绝，应在“实时血缘”页面检查执行记录并重试协调，不要盲目重复创建。

对象状态含义：

- `UNMANAGED`：扫描发现但尚未批准。
- `PREPARED`：DDL 已预登记，尚未确认 Doris 执行结果。
- `SYNCING`：正在刷新元数据、继承并发布策略。
- `ACTIVE`：唯一允许业务查询访问的状态。
- `DRIFTED`：扫描发现结构变化，需重新批准。
- `FAILED`：同步失败，保持不可访问，可重试协调。
- `DROPPED`：对象已删除或重命名后的旧名称。

按已确认的边界，`SHOW` 和直接访问 `information_schema` 的语句不执行受管对象过滤；生产权限仍应限制业务账号的元数据访问范围。

## 6. 运行检查

```bash
docker ps | grep doris-security-gateway
docker logs --tail 200 doris-security-gateway-admin
docker logs --tail 200 doris-security-gateway
```

检查监听：

```bash
ss -lntp | grep -E ':18080|:3307'
```

从业务服务器测试：

```bash
mysql -h 192.168.232.132 -P 3307 -u业务账号 -p 逻辑数据库
```

如果启动输出仍显示 `127.0.0.1:3307`，首先确认启动命令使用的 `GATEWAY_DATA_DIR` 与修改配置的目录一致，再确认容器已删除重建：

```bash
grep -E 'GATEWAY_(PUBLISH_IP|HOST_PORT|NETWORK_MODE)' /data/doris-security-gateway/gateway.env
docker inspect doris-security-gateway | grep -A20 Env
```

如果血缘接口返回 `Communications link failure`，检查管理端的 `PROXY_HOST`、`PROXY_PORT` 是否与 Proxy 实际监听地址一致。修改 `admin.env` 后同样需要删除并重建管理端容器。

## 7. 安全要求

- 管理端只开放到管理网，并在生产环境前置 HTTPS。
- Proxy 业务端口只开放到业务系统网段。
- 管理账号、业务账号和 Proxy 访问 Doris 的账号分离并最小授权。
- `ADMIN_MASTER_KEY_B64`、`LINEAGE_INGRESS_TOKEN`、AES 密钥和密码只保存在权限为 `600` 的环境文件或密钥系统中。
- Proxy 使用持久化 Standalone JDBC 仓库；多 Proxy 生产集群应改为共享的 Cluster 仓库。
- 变更 ShardingSphere、Doris 或 JDBC 驱动版本后，重新执行全部安全边界和血缘回归测试。
