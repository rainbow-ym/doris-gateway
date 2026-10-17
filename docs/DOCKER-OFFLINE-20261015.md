# Docker 离线镜像包

版本：2026.10.15-accounts.1；平台：Linux x86_64（amd64）。

本包为 192.168.232.132 当前正式发布的三个镜像，采用 docker save 格式，非容器文件系统 export；无需 Compose。

## 包含镜像

- doris-security-gateway:accounts-20261015：MySQL 网关。
- doris-security-gateway-admin:accounts-20261015：集群管理服务。
- doris-security-gateway-platform:accounts-20261015：管理员登录与多集群门户。

三个镜像共用的层在同一归档中导出。不包含 Doris 数据库镜像。

## 校验和导入

将镜像包和 SHA256SUMS 下载到同一目录，在 Linux 执行：

```bash
sha256sum -c SHA256SUMS
gzip -dc doris-security-gateway-accounts-20261015-images.tar.gz | docker load
docker images
```

gzip 解压后通过标准输入导入，可用于支持 docker load 的老版 Docker；具体内核及 Docker 版本仍需在目标服务器验证。

## 配置说明

此包仅包含镜像，不包含生产环境的密码、密钥、账号配置、H2 数据、Doris 业务数据或挂载目录。导入镜像不会自动启动容器，也不会自动替换已有运行容器。

升级既有部署须先备份数据和配置，保留原挂载目录、环境变量及主密钥，再用新镜像重建对应容器。仅 docker restart 不会切换镜像。

新服务器首次部署还需要初始化配置。不要把本镜像包视为完整生产数据迁移备份，也不要为迁移重新生成已有配置的加密主密钥，否则历史凭据无法解密。

IMAGE-MANIFEST.txt 记录镜像标识及架构，可用于核对导入结果。具体功能与使用边界见 RELEASE-20261015-accounts.md。
