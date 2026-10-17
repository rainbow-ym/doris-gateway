#!/usr/bin/bash
set -euo pipefail

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven_bin="${MAVEN_BIN:-/opt/test-tools/apache-maven-3.9.11/bin/mvn}"
image_name="${ADMIN_IMAGE_NAME:-doris-security-gateway-admin:0.1.0}"
build_dir="$(mktemp -d /var/tmp/doris-gateway-admin.XXXXXX)"
trap 'rm -rf "${build_dir}"' EXIT

"${maven_bin}" -B -f "${workspace}/admin/pom.xml" clean verify
cp "${workspace}/admin/target/doris-security-gateway-admin-0.1.0.jar" "${build_dir}/"
cp "${workspace}/docker/Dockerfile.admin.legacy" "${build_dir}/Dockerfile"
docker build -t "${image_name}" "${build_dir}"
echo "Built ${image_name}"
