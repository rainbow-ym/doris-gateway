#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
export JAVA_HOME=/opt/jdk17
for module in pom.xml admin/pom.xml; do
  /opt/test-tools/apache-maven-3.9.11/bin/mvn -B -f "$module" package > "$(basename "$(dirname "$module")")-boundary-package.log" 2>&1
done
mkdir -p image-layer/boundary-proxy image-layer/boundary-admin
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar image-layer/boundary-proxy/
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/boundary-admin/
cp docker/Dockerfile.multi-user image-layer/boundary-proxy/Dockerfile
cp docker/Dockerfile.admin.clusters image-layer/boundary-admin/Dockerfile
docker build -t doris-security-gateway:boundary-test-20261015 image-layer/boundary-proxy
docker build -t doris-security-gateway-admin:boundary-test-20261015 image-layer/boundary-admin
test_dir=/data/gateway-boundary-test-20261015
[[ ! -e "$test_dir" ]] || { echo '测试目录已存在，拒绝覆盖。'; exit 2; }
for old in gateway-account-admin-test gateway-account-proxy-test; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$old")" == false ]]
done
if ss -lnt | awk '{print $4}' | grep -Eq ':(3312|18086)$'; then echo '测试端口已占用'; exit 2; fi
cp -a /data/doris-gateway-clusters/provision-smoke-20261015 "$test_dir"
docker run -d --name gateway-boundary-admin-test --net host --env-file "$test_dir/admin.env" -v "$test_dir/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:boundary-test-20261015
docker run -d --name gateway-boundary-proxy-test --net host --env-file "$test_dir/gateway.env" -v "$test_dir/conf:/opt/gateway/conf:Z" -v "$test_dir/logs:/opt/gateway/logs:Z" doris-security-gateway:boundary-test-20261015
for ((i=0;i<60;i++)); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' gateway-boundary-admin-test)" == healthy && "$(docker inspect --format '{{.State.Health.Status}}' gateway-boundary-proxy-test)" == healthy ]]; then echo '隔离服务已就绪。'; exit 0; fi
  sleep 2
done
echo '隔离服务未通过健康检查'; exit 1
