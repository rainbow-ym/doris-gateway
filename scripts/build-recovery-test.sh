#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
test_dir=/data/gateway-recovery-test-20261016
[[ ! -e "$test_dir" ]] || { echo '隔离测试目录已存在，拒绝覆盖。'; exit 2; }
for old in gateway-automatic-admin-test gateway-automatic-proxy-test; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$old")" == false ]]
done
if ss -lnt | awk '{print $4}' | grep -Eq ':(3312|18086)$'; then echo '测试端口已占用'; exit 2; fi
cp -a /data/gateway-automatic-test-20261016 "$test_dir"
set -a
. "$test_dir/admin.env"
set +a
/opt/jdk17/bin/javac scripts/PublicationRecoveryFixture.java
/opt/jdk17/bin/java -cp scripts:/root/.m2/repository/com/h2database/h2/2.1.214/h2-2.1.214.jar PublicationRecoveryFixture
mkdir -p image-layer/recovery-admin
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/recovery-admin/
cp docker/Dockerfile.admin.clusters image-layer/recovery-admin/Dockerfile
docker build -t doris-security-gateway-admin:recovery-test-20261016 image-layer/recovery-admin
mkdir -p image-layer/recovery-proxy
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar image-layer/recovery-proxy/
cp docker/Dockerfile.multi-user image-layer/recovery-proxy/Dockerfile
docker build -t doris-security-gateway:recovery-test-20261016 image-layer/recovery-proxy
docker run -d --name gateway-recovery-admin-test --net host --env-file "$test_dir/admin.env" -v "$test_dir/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:recovery-test-20261016
docker run -d --name gateway-recovery-proxy-test --net host --env-file "$test_dir/gateway.env" -v "$test_dir/conf:/opt/gateway/conf:Z" -v "$test_dir/logs:/opt/gateway/logs:Z" doris-security-gateway:recovery-test-20261016
