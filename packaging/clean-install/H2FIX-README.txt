worker H2 嵌入式模式更新包

仅更新 worker。镜像默认关闭 AUTO_SERVER，避免 H2 启动依赖主机名解析。
保持 H2 数据库文件位置、版本、账号密码和密钥不变，不操作 Doris。

在故障服务器解压本包后进入 h2fix-update 目录，执行：
  bash upgrade-worker-h2.sh

默认安装目录：/data/doris-gateway/install_dir
默认容器名称：doris-gateway-worker
其他安装可指定：bash upgrade-worker-h2.sh /实际安装目录 实际worker容器名

脚本校验文件、导入镜像、备份 env、保留停止的旧容器，然后重建 worker。
原配置中显式的 AUTO_SERVER=TRUE 会被覆盖为 FALSE，无需手动修改。
不会删除数据库目录。旧容器不能与新容器同时启动。
检查通过表示 worker 监听端口健康，不等于 Doris 接入、权限和业务查询全部通过。
升级后请在平台验证集群连接。

此包仅包含 worker 镜像和升级脚本，不含生产数据或凭据。
