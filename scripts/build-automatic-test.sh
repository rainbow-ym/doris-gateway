#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
test_dir=/data/gateway-automatic-test-20261016
[[ ! -e "$test_dir" ]] || { echo '隔离测试目录已存在，拒绝覆盖。'; exit 2; }
for old in gateway-snapshot-admin-test gateway-snapshot-proxy-test; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$old")" == false ]]
done
if ss -lnt | awk '{print $4}' | grep -Eq ':(3312|18086)$'; then echo '测试端口已占用'; exit 2; fi
cp -a /data/gateway-snapshot-test-20261016 "$test_dir"
mkdir -p image-layer/automatic-admin
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/automatic-admin/
cp docker/Dockerfile.admin.clusters image-layer/automatic-admin/Dockerfile
docker build -t doris-security-gateway-admin:automatic-test-20261016 image-layer/automatic-admin
docker run -d --name gateway-automatic-admin-test --net host --env-file "$test_dir/admin.env" -v "$test_dir/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:automatic-test-20261016
docker run -d --name gateway-automatic-proxy-test --net host --env-file "$test_dir/gateway.env" -v "$test_dir/conf:/opt/gateway/conf:Z" -v "$test_dir/logs:/opt/gateway/logs:Z" doris-security-gateway:snapshot-test-20261016
