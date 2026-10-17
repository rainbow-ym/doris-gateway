#!/usr/bin/env bash
set -euo pipefail
cd /data/gateway-account-build
export JAVA_HOME=/opt/jdk17
/opt/test-tools/apache-maven-3.9.11/bin/mvn -B -f admin/pom.xml package > /data/gateway-account-build/admin-h2-fix-test.log 2>&1
mkdir -p image-layer/admin-h2-fix
cp admin/target/doris-security-gateway-admin-0.1.0.jar image-layer/admin-h2-fix/app.jar
cp docker/Dockerfile.admin.h2-fix image-layer/admin-h2-fix/Dockerfile
docker build -t doris-security-gateway-admin:accounts-20261015-h2fix1 image-layer/admin-h2-fix
