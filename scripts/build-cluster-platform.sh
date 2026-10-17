#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-clusters-build
cd "$workspace"
export JAVA_HOME=/opt/jdk17
mvn=/opt/test-tools/apache-maven-3.9.11/bin/mvn
"$mvn" -B -f admin/pom.xml package >admin-test.log 2>&1 || { tail -70 admin-test.log; exit 1; }
"$mvn" -B -f platform/pom.xml package >platform-test.log 2>&1 || { tail -70 platform-test.log; exit 1; }
mkdir -p image-layer/admin image-layer/platform
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/admin/
cp platform/target/gateway-platform-0.1.0.jar image-layer/platform/
cp docker/Dockerfile.admin.clusters image-layer/admin/Dockerfile
cp docker/Dockerfile.platform image-layer/platform/Dockerfile
docker build -t doris-security-gateway-admin:clusters-20261015 image-layer/admin
docker build -t doris-security-gateway-platform:clusters-20261015 image-layer/platform
