#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
export JAVA_HOME=/opt/jdk17
/opt/test-tools/apache-maven-3.9.11/bin/mvn -B package > snapshot-proxy.log 2>&1
/opt/test-tools/apache-maven-3.9.11/bin/mvn -B -f admin/pom.xml package > snapshot-admin.log 2>&1
test_dir=/data/gateway-snapshot-test-20261016
[[ ! -e "$test_dir" ]] || { echo '隔离测试目录已存在，拒绝覆盖。'; exit 2; }
for old in gateway-boundary-admin-test gateway-boundary-proxy-test; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$old")" == false ]]
done
if ss -lnt | awk '{print $4}' | grep -Eq ':(3312|18086)$'; then echo '测试端口已占用'; exit 2; fi
cp -a /data/gateway-boundary-test-20261015 "$test_dir"
mkdir -p image-layer/snapshot-proxy image-layer/snapshot-admin
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar image-layer/snapshot-proxy/
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/snapshot-admin/
cp docker/Dockerfile.multi-user image-layer/snapshot-proxy/Dockerfile
cp docker/Dockerfile.admin.clusters image-layer/snapshot-admin/Dockerfile
docker build -t doris-security-gateway:snapshot-test-20261016 image-layer/snapshot-proxy
docker build -t doris-security-gateway-admin:snapshot-test-20261016 image-layer/snapshot-admin
docker run -d --name gateway-snapshot-admin-test --net host --env-file "$test_dir/admin.env" -v "$test_dir/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:snapshot-test-20261016
docker run -d --name gateway-snapshot-proxy-test --net host --env-file "$test_dir/gateway.env" -v "$test_dir/conf:/opt/gateway/conf:Z" -v "$test_dir/logs:/opt/gateway/logs:Z" doris-security-gateway:snapshot-test-20261016
