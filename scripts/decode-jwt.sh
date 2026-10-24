#!/usr/bin/env bash
# 把 JWT 的 payload 解碼成 JSON 印出來（只解碼，不驗證簽章）
# 用法: scripts/decode-jwt.sh <jwt>
set -euo pipefail
JWT="${1:?請傳入 JWT}"
PAYLOAD=$(echo "$JWT" | cut -d. -f2 | tr '_-' '/+')
# Base64 需要補 '=' 到 4 的倍數
PAD=$(( (4 - ${#PAYLOAD} % 4) % 4 ))
PAYLOAD="$PAYLOAD$(printf '=%.0s' $(seq 1 $PAD 2>/dev/null))"
echo "$PAYLOAD" | base64 -d 2>/dev/null | python3 -m json.tool
