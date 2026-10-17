# Doris 安全网关可视化管理平台

## 功能

- 管理 Doris 连接并加密保存连接密码。
- 初始化 ShardingSphere-Proxy 逻辑数据库、Storage Unit 和单表元数据。
- 扫描 `information_schema.COLUMNS`，不读取业务数据。
- 根据字段名或 `security:POLICY_ID` 注释推荐策略。
- 复选框批量设置证件号、姓名、手机号、地址脱敏或 AES-GCM 返回密文。
- 通过 DistSQL 预览、发布、回读验证 MASK 规则。
- 保存发布前快照、版本记录、审计记录并支持回滚。
- 接收并展示查询结果列、CTAS、视图、物化视图、Insert-Select、Rename 和 Drop 的字段级血缘。
- 对新建派生对象同步继承敏感字段策略，并在 SQL 成功返回前发布规则。
- 维护受管对象注册表；只有 `ACTIVE` 对象可被业务 SQL 访问。
- 批准扫描发现的 `UNMANAGED`/`DRIFTED` 对象，并重试失败的同步执行。

## 构建与启动

```bash
cd /root/doris-security-gateway
./scripts/build-admin-image-legacy.sh

mkdir -p /data/doris-security-gateway-admin
cp admin/admin.env.example /data/doris-security-gateway-admin/admin.env
vi /data/doris-security-gateway-admin/admin.env
chmod 600 /data/doris-security-gateway-admin/admin.env

./scripts/run-admin-docker.sh
```

浏览器访问 `http://ADMIN_BIND_ADDRESS:18080`，使用 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 登录。

## Proxy 管理账号

在 Proxy 的 `global.yaml` 中配置独立管理账号，并限制来源地址。管理账号只供管理平台发布 DistSQL，不要提供给业务系统。

Proxy 必须使用可持久化的 Standalone 仓库，否则 DistSQL 可能被接受但规则版本无法激活：

```yaml
mode:
  type: Standalone
  repository:
    type: JDBC
    props:
      provider: H2
      jdbc_url: jdbc:h2:file:/opt/gateway/data/repository/config;DATABASE_TO_UPPER=false;MODE=MYSQL
      username: sa
      password: ''
```

生产集群建议改用 ShardingSphere Cluster 模式和 ZooKeeper，并由多个 Proxy 共享配置。首次启用持久仓库后需要重启 Proxy；之后页面发布的规则即时生效。

管理页面当前使用 HTTP Basic。生产应仅监听管理网地址，并在前面配置 HTTPS 反向代理。`ADMIN_MASTER_KEY_B64` 用于加密保存 Doris 密码，首次生成后必须固定保存；更换它会导致已有连接密码无法解密。

## 管理边界

Doris 数据库首次接入时，保存连接并点击“发现数据库”，默认勾选所有尚未接入的匹配库，再点击“接入选中数据库”。平台自动生成同名逻辑库、内部 Storage Unit、账号授权和推荐脱敏策略，无需填写内部名称或业务 YAML。接入后在字段策略页复核推荐结果，调整后预览并发布。操作步骤和失败处理见 [接入操作说明](../docs/ONBOARDING.md)。

Proxy 的 `GATEWAY_LINEAGE_TOKEN` 必须与管理端的 `LINEAGE_INGRESS_TOKEN` 完全相同。Proxy 通过内部控制接口在业务 SQL 执行前校验对象状态，并在 DDL 成功或失败后提交或中止执行记录。管理端不可用时采用失败关闭。血缘事件保存 SQL 摘要和字段边；用于失败恢复的受管 SQL 执行记录还会保存原始 SQL，可能含字面值，须限制管理数据库与备份的访问权限。

绕过网关直接创建的表经扫描后进入 `UNMANAGED`，不会被查询静默放行；在“实时血缘”页面批准并完成单表加载、元数据刷新和策略发布后才进入 `ACTIVE`。按当前确认的边界，`SHOW` 和直接访问 `information_schema` 的语句不执行受管对象过滤。
