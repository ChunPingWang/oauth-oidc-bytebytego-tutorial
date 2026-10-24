#!/usr/bin/env bash
# ------------------------------------------------------------------
# 用 curl 直接向 Keycloak 要 token（Resource Owner Password Credentials）
# 這「不是」架構圖的 Authorization Code 流程，只是方便觀察 token 內容用。
#
# 用法: scripts/get-token.sh [帳號] [密碼]
#   例:  scripts/get-token.sh alice alice123
# ------------------------------------------------------------------
set -euo pipefail
USERNAME="${1:-alice}"
PASSWORD="${2:-alice123}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8180}"

RESPONSE=$(curl -s -X POST "$KEYCLOAK/realms/demo/protocol/openid-connect/token" \
  -d client_id=cli-demo \
  -d grant_type=password \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" \
  -d scope=openid)

if ! echo "$RESPONSE" | grep -q access_token; then
  echo "取得 token 失敗: $RESPONSE" >&2
  exit 1
fi

ACCESS_TOKEN=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
ID_TOKEN=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id_token"])')

echo "===== Access Token payload（OAuth 2.0：可以做什麼） ====="
"$(dirname "$0")/decode-jwt.sh" "$ACCESS_TOKEN"
echo
echo "===== ID Token payload（OIDC：你是誰） ====="
"$(dirname "$0")/decode-jwt.sh" "$ID_TOKEN"
echo
echo "===== 把下面這行貼到終端機，之後就可以用 \$TOKEN 呼叫 API ====="
echo "export TOKEN=$ACCESS_TOKEN"
