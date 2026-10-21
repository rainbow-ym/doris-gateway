#!/usr/bin/env bash
# Test the assembled bundle with a mocked Docker function; never calls Docker.
set -euo pipefail
package="${1:?Provide the assembled package directory}"
expected=2
[[ ! -f "$package/gateway-platform-0.1.0.jar" ]] || expected=3
export SM4_BUILD_TEST_LOG SM4_EXPECTED
SM4_EXPECTED="$expected"
SM4_BUILD_TEST_LOG="$(mktemp)"
trap 'rm -f "$SM4_BUILD_TEST_LOG"' EXIT
docker() {
  case "$1" in
    inspect) printf 'sha256:%064d\n' 1 ;;
    exec) return 0 ;;
    image) return 1 ;;
    build)
      [[ "$2" = --pull=false && "$3" = --network=none && "$4" = -f ]] || return 2
      [[ -s "$5" ]] || return 2
      local context="${@: -1}"
      [[ -s "$context/doris-security-gateway-admin-0.1.0.jar" &&
         -s "$context/doris-security-gateway-mask-plugin-0.1.0-SNAPSHOT.jar" ]] || return 2
      [[ "$(find "$context" -type f | wc -l)" -eq $((SM4_EXPECTED * 2)) ]] || return 2
      if [[ "$SM4_EXPECTED" -eq 3 ]]; then [[ -s "$context/gateway-platform-0.1.0.jar" ]] || return 2; fi
      printf 'build\n' >> "$SM4_BUILD_TEST_LOG"
      ;;
    *) echo "Unexpected Docker mutation: $1" >&2; return 2 ;;
  esac
}
export -f docker
bash "$package/build-sm4-performance-images.sh"
[[ "$(wc -l < "$SM4_BUILD_TEST_LOG")" -eq "$expected" ]]
echo 'PASS: isolated offline image builds; no runtime/container operation.'
