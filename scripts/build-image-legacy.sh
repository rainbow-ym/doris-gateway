#!/usr/bin/bash
set -euo pipefail

# Offline image builder for Docker 1.13.x. It creates a scratch-based runtime
# from the installed JRE and verified Proxy; no registry, Maven or compiler.
workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
proxy_home="${PROXY_HOME:-/opt/apache-shardingsphere-5.5.3-shardingsphere-proxy-bin}"
jre_home="${JRE_HOME:-/opt/test-tools/jdk8-rpmroot/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.412.b08-1.el7_9.x86_64}"
image_name="${IMAGE_NAME:-doris-security-gateway:0.1.0}"
build_parent="${BUILD_PARENT:-/var/tmp}"
build_dir="$(mktemp -d "${build_parent%/}/doris-gateway-image.XXXXXX")"
rootfs="${build_dir}/rootfs"

cleanup() {
  rm -rf "${build_dir}"
}
trap cleanup EXIT

test -x "${jre_home}/bin/java"
test -d "${proxy_home}/lib"
test -f "${proxy_home}/hardening-lib/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar"
test -f "${proxy_home}/ext-lib/mysql-connector-j-8.4.0.jar"

mkdir -p "${rootfs}/opt" "${rootfs}/usr/bin" "${rootfs}/usr/lib64" \
  "${rootfs}/usr/lib" "${rootfs}/usr/sbin" "${rootfs}/usr/local/bin" \
  "${rootfs}/usr/share" "${rootfs}/etc" "${rootfs}/tmp" \
  "${rootfs}/opt/gateway/conf" "${rootfs}/opt/gateway/logs"
chmod 1777 "${rootfs}/tmp"

# Resolve the two RPM-managed links whose targets sit outside JAVA_HOME. Other
# relative JRE links must remain links (for example server/libjsig.so).
cp -a "${jre_home}" "${rootfs}/opt/java"
rm -f "${rootfs}/opt/java/jre/lib/tzdb.dat" \
  "${rootfs}/opt/java/jre/lib/security/cacerts"
cp -L /usr/share/javazi-1.8/tzdb.dat "${rootfs}/opt/java/jre/lib/tzdb.dat"
cp -L /etc/pki/java/cacerts "${rootfs}/opt/java/jre/lib/security/cacerts"
mkdir -p "${rootfs}/opt/shardingsphere-proxy"
cp -a "${proxy_home}/lib" "${rootfs}/opt/shardingsphere-proxy/"
cp -a "${proxy_home}/ext-lib" "${rootfs}/opt/shardingsphere-proxy/"
cp -a "${proxy_home}/hardening-lib" "${rootfs}/opt/shardingsphere-proxy/"
cp -a "${proxy_home}/LICENSE" "${proxy_home}/NOTICE" "${rootfs}/opt/shardingsphere-proxy/"

mkdir -p "${rootfs}/opt/shardingsphere-proxy/default-conf"
cp "${workspace}/conf/global.yaml.example" \
  "${rootfs}/opt/shardingsphere-proxy/default-conf/global.yaml"
cp "${proxy_home}/conf/logback.xml" \
  "${rootfs}/opt/shardingsphere-proxy/default-conf/logback.xml"

install -m 0755 "${workspace}/docker/docker-entrypoint.sh" \
  "${rootfs}/usr/local/bin/docker-entrypoint"
install -m 0755 "${workspace}/docker/gateway-healthcheck.sh" \
  "${rootfs}/usr/local/bin/gateway-healthcheck"

# CentOS 7 on the test server uses this usr-merge layout.
ln -s usr/bin "${rootfs}/bin"
ln -s usr/lib64 "${rootfs}/lib64"
ln -s usr/lib "${rootfs}/lib"
ln -s usr/sbin "${rootfs}/sbin"

copy_executable() {
  local source="$1"
  local destination="${rootfs}${source}"
  mkdir -p "$(dirname "${destination}")"
  cp -L "${source}" "${destination}"
}

copy_library() {
  local source="$1"
  local normalized
  case "${source}" in
    /lib64/*) normalized="/usr/lib64/${source#/lib64/}" ;;
    /lib/*) normalized="/usr/lib/${source#/lib/}" ;;
    *) normalized="${source}" ;;
  esac
  mkdir -p "$(dirname "${rootfs}${normalized}")"
  cp -L "${source}" "${rootfs}${normalized}"
}

copy_dependencies() {
  local object="$1"
  ldd "${object}" 2>/dev/null | awk '
    /=> \// { print $3 }
    /^[[:space:]]*\// { print $1 }
  ' | while read -r library; do
    [[ -n "${library}" && -e "${library}" ]] && copy_library "${library}"
  done
}

for executable in \
  /usr/bin/bash /usr/bin/cp /usr/bin/mkdir /usr/bin/ls /usr/bin/cat \
  /usr/bin/grep /usr/bin/sed /usr/bin/vi /usr/bin/find /usr/bin/env \
  /usr/bin/ps /usr/bin/tail /usr/bin/head /usr/bin/basename /usr/bin/dirname; do
  copy_executable "${executable}"
  copy_dependencies "${executable}"
done

# The JVM loads native libraries lazily, so collect system dependencies for all
# native objects in the copied JRE, not only for the java launcher.
while IFS= read -r native_object; do
  copy_dependencies "${native_object}"
done < <(find "${jre_home}" -type f \( -name '*.so' -o -name 'java' \))

cp -a /etc/pki "${rootfs}/etc/" 2>/dev/null || true
cp -a /etc/ssl "${rootfs}/etc/" 2>/dev/null || true
cp -a /usr/share/zoneinfo "${rootfs}/usr/share/" 2>/dev/null || true
cp -a /usr/share/terminfo "${rootfs}/usr/share/" 2>/dev/null || true
cp -a /usr/share/vim "${rootfs}/usr/share/" 2>/dev/null || true
for file in /etc/nsswitch.conf /etc/hosts /etc/resolv.conf /etc/passwd /etc/group /etc/localtime; do
  [[ -e "${file}" ]] && cp -L "${file}" "${rootfs}${file}"
done

cp "${workspace}/docker/Dockerfile.legacy" "${build_dir}/Dockerfile"
tar -C "${rootfs}" -cf "${build_dir}/rootfs.tar" .
docker build -t "${image_name}" "${build_dir}"
echo "Built ${image_name}"
