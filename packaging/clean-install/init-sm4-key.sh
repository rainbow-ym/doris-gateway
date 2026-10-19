#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
target="${1:?用法：bash init-sm4-key.sh /专用安装目录/worker-data/sm4-export-keys.properties}"
[[ "$target" = /* && "$target" != *'..'* && "$target" == */sm4-export-keys.properties ]] || { echo '必须指定专用 worker 数据目录下 sm4-export-keys.properties 的绝对路径'; exit 2; }
[[ -d "$(dirname -- "$target")" && ! -L "$target" ]] || { echo '父目录不存在或目标是符号链接'; exit 2; }
if [[ -e "$target" ]]; then echo '密钥文件已存在，保留原文件，不覆盖、不重新生成。'; exit 0; fi
command -v openssl >/dev/null
secret="$(openssl rand -base64 16)"
(set -C; printf 'v1=%s\n' "$secret" > "$target")
chmod 600 "$target"
unset secret
echo "SM4 密钥已生成：$target。请离线安全备份，丢失后无法解密旧文件。"
