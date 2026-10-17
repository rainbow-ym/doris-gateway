# 数据安全网关：全新安装包

版本 2026.10.15-accounts.1，Linux x86_64。保留四类内置脱敏规则：证件号码、姓名、手机号、地址。**不包含现有数据源、密码、密钥、管理员账号、血缘、H2 数据库或自定义字段映射。** 不包含 Doris，需连接已有 Doris 集群。

## 1. 环境与导入

需要 root、Linux x86_64、Docker、Bash、openssl、ss、gzip、tar、sha256sum。无需 Compose、Java 或 Maven。建议可用内存至少 4 GB、可用磁盘至少 3 GB；实际业务资源需另行评估。使用 host 网络，兼容本次测试服务器的老版 Docker；未对所有 Docker/内核版本逐一验证。

在安装包解压后的目录执行：

```bash
sha256sum -c SHA256SUMS
gzip -dc doris-security-gateway-accounts-20261015-images.tar.gz | docker load
cp install.conf.example install.conf
vi install.conf
```

默认门户 18080、业务 MySQL 3308、内部管理 18082。请先确认端口空闲，INSTALL_DIR 指向一个尚不存在的专用目录。不要使用旧环境目录。本安装器不会覆盖现有部署。

## 2. 初始化与启动

```bash
bash install.sh init
bash install.sh start
```

init 为此次安装生成新的随机密码和主密钥；start 创建三个容器。启动后稍等约 30 秒，用 docker ps 检查状态。

浏览器打开 http://服务器IP:18080/login.html（以实际端口为准）。管理员用户名为 admin，初始密码读取安装目录 platform.env 中的 PLATFORM_ADMIN_PASSWORD。它不是 Doris 的 root 密码。首次登录后可在平台修改管理员密码；之后修改 env 中的初始密码不会重置已有管理员密码。

INSTALL_DIR 默认 /data/doris-gateway-clean，配置文件权限仅允许 root 读取。妥善备份各 env、conf 及数据目录，尤其不要丢失或重新生成主密钥。

## 3. 登记第一个集群

在门户新增集群，填写：

| 项目 | 填写内容 |
|---|---|
| 集群名称 | 例如生产 Doris |
| 管理服务地址 | http://127.0.0.1:18082（WORKER_PORT） |
| 管理服务用户名 | 安装目录 worker.env 的 ADMIN_USERNAME，默认 admin |
| 管理服务密码 | worker.env 的 ADMIN_PASSWORD，与门户管理员密码不同 |
| 业务入口 | 服务器实际 IP:3308（GATEWAY_PORT） |

这里的 127.0.0.1 由服务器上的门户访问，不是浏览器所在电脑。登记后进入集群，添加 Doris 真实连接，发现并接入数据库，再复核字段策略。建议真实 root 先接入，以承担元数据维护。

四类内置识别规则无需手工导入。“关系人证件号码”等自定义名字需要在“字段识别规则”新增映射，重新扫描确认并发布。内置规则只是推荐机制，不代表所有敏感字段已经自动保护，必须复核。

本安装包默认使用星号脱敏，不要求配置 AES 输出密钥。若后续选择 AES-GCM 返回加密，应另行设置并保管 GATEWAY_OUTPUT_AES_KEY_B64。

## 4. 日常管理

默认容器名称：doris-gateway-clean-platform、doris-gateway-clean-worker、doris-gateway-clean-proxy。INSTANCE_NAME 不同则前缀相应变化。

已有容器停机后用 docker start 启动，普通重启可用 docker restart；不要重复 init。修改 env 后需要保持原挂载和密钥重建对应容器，普通 restart 不会重新读取 env。安装器不会自动删除容器或覆盖数据。若 start 中途失败，用 docker ps -a 检查已创建的容器，切勿重新初始化目录。

默认向所有网卡监听门户和业务端口，仅限可信内网；配置防火墙访问范围，不要直接暴露公网。内部 worker 仅监听 127.0.0.1，不对外开放。门户当前为 HTTP，跨不可信网络前应配置 HTTPS。SQL 客户端用已接入的真实数据库账号和密码连接业务端口，并指定已接入数据库。

需要额外集群时，为独立实例选择不同目录、INSTANCE_NAME 和端口；每个 worker/proxy 使用独立数据。可在现有门户登记另一个 worker（本脚本每次会同时创建独立门户，暂不提供只创建 worker/proxy 的模式）。不要把同一个数据目录挂给多个实例。

功能边界详见 RELEASE-20261015-accounts.md；其描述的 132 现有环境测试及备份路径不是本包携带的数据。
