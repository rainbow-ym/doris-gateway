#!/usr/bin/env bash
# Build from a clean source checkout. Only creates images and a new output directory.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output="${1:?Provide a new absolute output directory}"
revision="${2:?Provide the source Git SHA}"
[[ "$revision" =~ ^[a-f0-9]{40}$ && "$output" = /* && "$output" != *'..'* && ! -e "$output" ]] || exit 2
tag="obs-20261021-${revision:0:7}"
mvn="${MAVEN_BIN:-/opt/test-tools/apache-maven-3.9.11/bin/mvn}"
export JAVA_HOME="${JAVA_HOME:-/opt/jdk17}"
cd "$root"
"$mvn" -B -q package
"$mvn" -B -q -f admin/pom.xml package
"$mvn" -B -q -f platform/pom.xml package
mkdir -p "$output/clean-install" "$output/build"
package="$output/clean-install"
cp packaging/clean-install/* "$package/"
cp observability/PHASE2.md "$package/OBSERVABILITY.md"
cp docs/SM4-EDITABLE-CSV.md "$package/"
cp target/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar "$output/build/proxy.jar"
cp admin/target/doris-security-gateway-admin-0.1.0.jar "$output/build/worker.jar"
cp platform/target/gateway-platform-0.1.0.jar "$output/build/platform.jar"
repositories=(doris-security-gateway doris-security-gateway-admin doris-security-gateway-platform)
kinds=(proxy worker platform)
destinations=(/opt/shardingsphere-proxy/hardening-lib/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar /opt/gateway-admin/app.jar /opt/gateway-platform/app.jar)
for i in 0 1 2; do
  image="${repositories[$i]}:$tag"
  if docker inspect --type=image "$image" >/dev/null 2>&1; then echo "Image exists: $image" >&2; exit 2; fi
  base="$(docker inspect --type=image --format '{{.Id}}' "${repositories[$i]}:sm4-row-v2-20261019")"
  printf 'FROM %s\nCOPY %s.jar %s\nLABEL org.opencontainers.image.revision=%s\n' "$base" "${kinds[$i]}" "${destinations[$i]}" "$revision" > "$output/build/Dockerfile.${kinds[$i]}"
  docker build --pull=false --network=none -f "$output/build/Dockerfile.${kinds[$i]}" -t "$image" "$output/build"
done
export PACKAGE_DIR="$package" RELEASE_TAG="$tag" SOURCE_REVISION="$revision"
python3 - <<'PY'
import json, os, pathlib, subprocess
p=pathlib.Path(os.environ['PACKAGE_DIR']);tag=os.environ['RELEASE_TAG']
m={'archive':'gateway-images.tar.gz','revision':os.environ['SOURCE_REVISION']}
for kind,repo in [('proxy','doris-security-gateway'),('worker','doris-security-gateway-admin'),('platform','doris-security-gateway-platform')]:
    m[kind]=repo+':'+tag
    m[kind+'Id']=subprocess.check_output(['docker','inspect','--type=image','--format','{{.Id}}',m[kind]]).decode().strip()
(p/'offline-images.json').write_text(json.dumps(m,indent=2)+'\n')
install=p/'install.sh'
install.write_text(install.read_text().replace('sm4-row-v2-20261019',tag))
PY
docker save "${repositories[0]}:$tag" "${repositories[1]}:$tag" "${repositories[2]}:$tag" | gzip -1 > "$package/gateway-images.tar.gz"
gzip -t "$package/gateway-images.tar.gz"
(cd "$package"; find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS; sha256sum -c SHA256SUMS)
tar -czf "$output/doris-gateway-$tag.tar.gz" -C "$output" clean-install
gzip -t "$output/doris-gateway-$tag.tar.gz"
(cd "$output"; sha256sum "doris-gateway-$tag.tar.gz" > SHA256SUMS)
echo "Offline package: $output/doris-gateway-$tag.tar.gz"
