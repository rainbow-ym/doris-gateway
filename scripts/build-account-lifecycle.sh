#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
export JAVA_HOME=/opt/jdk17
mvn=/opt/test-tools/apache-maven-3.9.11/bin/mvn
"$mvn" -B package >proxy-test.log 2>&1
"$mvn" -B -f admin/pom.xml package >admin-test.log 2>&1
"$mvn" -B -f platform/pom.xml package >platform-test.log 2>&1
mkdir -p image-layer/proxy image-layer/admin image-layer/platform
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar image-layer/proxy/
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/admin/
cp platform/target/gateway-platform-0.1.0.jar image-layer/platform/
cp docker/Dockerfile.multi-user image-layer/proxy/Dockerfile
cp docker/Dockerfile.admin.clusters image-layer/admin/Dockerfile
cp docker/Dockerfile.platform image-layer/platform/Dockerfile
docker build -t doris-security-gateway:accounts-20261015 image-layer/proxy
docker build -t doris-security-gateway-admin:accounts-20261015 image-layer/admin
docker build -t doris-security-gateway-platform:accounts-20261015 image-layer/platform
