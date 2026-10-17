#!/usr/bin/env bash
set -euo pipefail
workspace=/data/gateway-multi-user-build
build_dir="$workspace/image-layer"
mkdir -p "$build_dir/proxy" "$build_dir/admin"
cp "$workspace/target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar" "$build_dir/proxy/"
cp "$workspace/admin/target/doris-security-gateway-admin-0.1.0.jar" "$build_dir/admin/"
cp "$workspace/docker/Dockerfile.multi-user" "$build_dir/proxy/Dockerfile"
cp "$workspace/docker/Dockerfile.admin.multi-user" "$build_dir/admin/Dockerfile"
docker build -t doris-security-gateway:multi-user-20261014 "$build_dir/proxy"
docker build -t doris-security-gateway-admin:multi-user-20261014 "$build_dir/admin"
