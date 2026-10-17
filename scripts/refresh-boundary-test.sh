#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
export JAVA_HOME=/opt/jdk17
/opt/test-tools/apache-maven-3.9.11/bin/mvn -B package > boundary-proxy.log 2>&1
/opt/test-tools/apache-maven-3.9.11/bin/mvn -B -f admin/pom.xml package > boundary-admin.log 2>&1
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar image-layer/boundary-proxy/
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/boundary-admin/
docker build -t doris-security-gateway:boundary-test-20261015 image-layer/boundary-proxy
docker build -t doris-security-gateway-admin:boundary-test-20261015 image-layer/boundary-admin
docker stop gateway-boundary-admin-test gateway-boundary-proxy-test
docker rename gateway-boundary-admin-test gateway-boundary-admin-test-v1
docker rename gateway-boundary-proxy-test gateway-boundary-proxy-test-v1
test_dir=/data/gateway-boundary-test-20261015
docker run -d --name gateway-boundary-admin-test --net host --env-file "$test_dir/admin.env" -v "$test_dir/admin-data:/opt/gateway-admin/data:Z" doris-security-gateway-admin:boundary-test-20261015
docker run -d --name gateway-boundary-proxy-test --net host --env-file "$test_dir/gateway.env" -v "$test_dir/conf:/opt/gateway/conf:Z" -v "$test_dir/logs:/opt/gateway/logs:Z" doris-security-gateway:boundary-test-20261015
for ((i=0;i<60;i++)); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' gateway-boundary-admin-test)" == healthy && "$(docker inspect --format '{{.State.Health.Status}}' gateway-boundary-proxy-test)" == healthy ]]; then
    set -a; . "$test_dir/admin.env"; set +a
    /opt/node/bin/node scripts/boundary-regression-e2e.cjs
    ACCEPTANCE_ADMIN_PORT=18086 ACCEPTANCE_PROXY_PORT=3312 /opt/node/bin/node scripts/multi-user-e2e.cjs
    exit 0
  fi
  sleep 2
done
echo '隔离服务未就绪'; exit 1
