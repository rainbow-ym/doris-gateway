# Doris 数据安全网关（ShardingSphere-Proxy 扩展）

这是面向 Apache Doris 的 ShardingSphere-Proxy 5.5.3 脱敏算法扩展。业务系统仍使用 MySQL 协议和原有 SQL；Doris 保留明文查询能力，Proxy 在结果集出口按物理字段规则做脱敏或 AES-GCM 加密。

浏览器批量接入与故障处理见 [接入操作说明](docs/ONBOARDING.md)。当前管理版本 `2026.10.13-onboarding.1`，页面右上角可核对。

## 当前状态

- 已实现证件号前 6 后 4保留、中间替换为 `*`。
- 已实现姓名按 Unicode 字符长度配置不同保留策略。
- 已实现电话前 3 后 4 保留、地址前 6 字符保留的可配置映射。
- 已实现可选的 AES-GCM 出口密文算法。
- 已注册 ShardingSphere `MaskAlgorithm` SPI。
- 已提供 Doris 数据源和 MASK 规则样例。
- 已提供复杂 SQL 血缘验收矩阵。
- 已覆盖 ShardingSphere 5.5.3 的 `MaskMergedResult`：函数、`CASE`、聚合等无法直接脱敏的敏感投影会失败关闭，常量和 `COUNT(*)` 仍可放行。
- 已提供浏览器管理平台：连接编辑/删除/防重复、默认全选发现数据库、后台批量接入、自动账号及映射、分阶段进度和失败重试、批量策略与发布回滚，无需手填 Storage Unit 或业务 MASK YAML。
- 已实现查询结果列与 `CTAS`、视图、物化视图、`INSERT ... SELECT`、重命名和删除的同步血缘采集；派生对象在 SQL 返回成功前完成策略继承和规则发布。
- 已实现受管对象注册表与失败关闭校验：业务查询只允许访问 `ACTIVE` 对象；网关 DDL 在一个 SQL 周期内完成预登记、Doris 执行、元数据刷新、策略继承和激活。
- 对绕过网关直接创建的对象，扫描后标记为 `UNMANAGED`，经管理页面确认并发布策略后才能变为 `ACTIVE`。
- 已在真实 Doris 4.1.3 + ShardingSphere-Proxy 5.5.3 上完成脱敏、安全边界及血缘端到端测试，全部通过。

## 构建

需要 JDK 8 或更高版本及 Maven 3.9：

```powershell
.\scripts\build.ps1
```

Linux：

```bash
MAVEN_BIN=/path/to/mvn ./scripts/build.sh
```

脚本会运行测试并把加固 JAR 复制到 `hardening-lib/`。

产物：

```text
target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar
```

## 部署到 Proxy

1. 使用 ShardingSphere-Proxy 5.5.3，并确保发行包包含 Doris 适配模块；否则从同版本源码使用 `db-doris` profile 构建。
2. 执行 `scripts/install-proxy.sh /path/to/proxy`，将本项目 JAR 安装到优先类路径 `hardening-lib/`。这是 fail-closed 覆盖生效的必要条件。
3. 将 MySQL Connector/J 8.4.x 放入 Proxy 的 `ext-lib/`。
4. 仅配置 Proxy 的全局账号与持久化仓库；逻辑库、Storage Unit 和 MASK 规则由管理平台初始化和发布，不再手写业务 YAML。
5. 如启用 `OUTPUT_AES_GCM`，注入 Base64 AES 密钥。例如生成 256 位测试密钥：

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
   [Convert]::ToBase64String($bytes)
   ```

6. 启动 Proxy，业务连接改为 `proxy-host:3307/business_db`。
7. 用 `docs/sql-acceptance-matrix.md` 回归真实 SQL，并验证业务账号不能绕过 Proxy 直连 Doris。

本项目的 JAR 同时包含自定义算法和内核安全覆盖，因此不能只放在默认的 `ext-lib/`；必须使 `hardening-lib/*` 位于官方 `lib/*` 之前。安装脚本会备份原 `start.sh`并严格匹配后修改类路径。

## Docker 1.13 离线镜像（不使用 Compose）

服务器不需要 Maven，也不需要访问镜像仓库。`scripts/build-image-legacy.sh` 会使用服务器上已经验证的 Proxy、JRE、MySQL 驱动和安全插件，从 `scratch` 构建单层运行镜像，兼容 Docker 1.13.x：

```bash
cd /root/doris-security-gateway
chmod +x scripts/build-image-legacy.sh scripts/run-docker.sh
./scripts/build-image-legacy.sh
```

首次执行运行脚本会把全局配置模板初始化到宿主机目录，然后退出：

```bash
GATEWAY_DATA_DIR=/data/doris-security-gateway ./scripts/run-docker.sh
vi /data/doris-security-gateway/conf/global.yaml
```

配置完成后再次执行，无需 Compose：

```bash
cat >/data/doris-security-gateway/gateway.env <<'EOF'
GATEWAY_OUTPUT_AES_KEY_B64=由密钥系统生成的固定Base64密钥
GATEWAY_PUBLISH_IP=192.168.232.132
GATEWAY_HOST_PORT=3307
GATEWAY_LINEAGE_ENDPOINT=http://127.0.0.1:18080/api/internal/lineage/events
# 可留空；默认根据上一项自动使用 http://127.0.0.1:18080/api/internal/sql
GATEWAY_CONTROL_ENDPOINT=
GATEWAY_LINEAGE_TOKEN=与管理端LINEAGE_INGRESS_TOKEN完全相同且至少24字符
GATEWAY_LINEAGE_TIMEOUT_MS=5000
GATEWAY_LINEAGE_FAIL_CLOSED=true
GATEWAY_JAVA_OPTS='-server -Xms512m -Xmx1g -Xmn256m -Xss1m -Dfile.encoding=UTF-8 -Djava.awt.headless=true'
EOF
chmod 600 /data/doris-security-gateway/gateway.env

GATEWAY_DATA_DIR=/data/doris-security-gateway ./scripts/run-docker.sh
```

`run-docker.sh` 会在启动时读取 `${GATEWAY_DATA_DIR}/gateway.env`。必须先启动管理端，再启动数据网关；受管对象校验是同步失败关闭的，管理端不可用时，除明确豁免的元数据语句外，业务 SQL 会被拒绝。生产环境应由密钥系统创建该文件；不要把真实密钥提交到代码仓库。

启动脚本默认使用 `--net=host`，这是因为当前老版本 Docker 主机未开启 IPv4 转发，同时也便于网关访问宿主机上的 Doris。若其他主机已正确配置 Docker bridge 网络，可设置 `GATEWAY_NETWORK_MODE=bridge`。

配置和日志位于宿主机 `/data/doris-security-gateway`，删除或重建容器不会丢失。也可以进入容器修改挂载的同一份配置：

```bash
docker exec -it doris-security-gateway /usr/local/bin/docker-entrypoint shell
vi /opt/gateway/conf/global.yaml
exit
docker rm -f doris-security-gateway
GATEWAY_DATA_DIR=/data/doris-security-gateway ./scripts/run-docker.sh
```

导出镜像供其他内网服务器使用：

```bash
docker save doris-security-gateway:0.1.0 | gzip > /data/doris-security-gateway-0.1.0.tar.gz
# 目标服务器：
gzip -dc /path/doris-security-gateway-0.1.0.tar.gz | docker load
```

## 重要限制

ShardingSphere 5.5.3 能根据绑定信息识别普通别名，但其默认 `MaskMergedResult` 在列血缘缺失时会返回原值。本项目已将函数、CASE、聚合中的敏感源列改为失败关闭；新的 SQL 类型仍必须先加入回归矩阵再放行。

另外，输出 AES-GCM 密文会改变返回格式和长度。原业务若不具备解密逻辑，应使用脱敏而不是密文输出。

## 浏览器批量管理

`admin/` 提供独立的控制台服务。它可以初始化 Proxy 逻辑库和 Storage Unit，连接 Doris 的 `information_schema` 扫描库、表和字段，根据字段名或字段注释推荐策略，支持复选框批量确认，然后通过 DistSQL 发布到 Proxy，并保存发布前快照、审计记录和回滚版本。控制台的“实时血缘”页面还可查看受管对象状态、批准扫描发现的对象、重试失败同步，以及查看查询和受支持 DDL 的字段级血缘。

```bash
cp admin/admin.env.example /data/doris-security-gateway-admin/admin.env
vi /data/doris-security-gateway-admin/admin.env
chmod 600 /data/doris-security-gateway-admin/admin.env
./scripts/run-admin-docker.sh
```

浏览器访问 `http://管理服务器IP:18080`。生产环境应将管理端口限制在管理网并配置 HTTPS。详细配置与 Proxy 持久仓库要求见 `admin/README.md`。

完整部署、配置、操作顺序及实时血缘边界见 [`docs/deployment-lineage.md`](docs/deployment-lineage.md)。

## 真实 Doris 集成测试

集成测试默认禁用，只有显式设置环境变量才会创建隔离的 `gateway_mask_test` 库：

```bash
export RUN_DORIS_INTEGRATION=true
export DORIS_JDBC_URL='jdbc:mysql://127.0.0.1:9030/?useSSL=false'
export DORIS_USER='root'
export DORIS_PASSWORD='从密钥注入，不写入仓库'
mvn -B -Dtest=DorisFixtureIntegrationTest test
```

Proxy 启动后，再设置 `GATEWAY_JDBC_URL`、`GATEWAY_USER`、`GATEWAY_PASSWORD` 并运行 `GatewayMaskE2ETest,GatewaySecurityBoundaryE2ETest,GatewayLineageE2ETest,GatewayManagedObjectE2ETest`。测试覆盖证件号、姓名、电话、地址、模糊查询、列别名、子查询别名、CTE、`SELECT *`、敏感表达式拒绝、派生对象策略继承、未受管对象拒绝和扫描后批准。基础脱敏报告见 `docs/test-report-2026-10-12.md`，最终管理与血缘验收见 [`docs/test-report-2026-10-13.md`](docs/test-report-2026-10-13.md)。
