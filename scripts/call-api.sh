#!/usr/bin/env bash
# ------------------------------------------------------------------
# 拿 access token 呼叫兩個 Protected Resource，觀察 200 / 401 / 403
# 用法: scripts/call-api.sh [帳號] [密碼]
# ------------------------------------------------------------------
set -euo pipefail
USERNAME="${1:-alice}"
PASSWORD="${2:-alice123}"

TOKEN=$(curl -s -X POST http://localhost:8180/realms/demo/protocol/openid-connect/token \
  -d client_id=cli-demo -d grant_type=password -d "username=$USERNAME" -d "password=$PASSWORD" -d scope=openid \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

call() {  # call <說明> <url> [是否帶 token]
  local desc="$1" url="$2" with="${3:-yes}" code
  if [ "$with" = "yes" ]; then
    code=$(curl -s -o /tmp/body.$$ -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$url")
  else
    code=$(curl -s -o /tmp/body.$$ -w '%{http_code}' "$url")
  fi
  printf '%-52s -> HTTP %s\n' "$desc" "$code"
  sed 's/^/     /' /tmp/body.$$ | head -c 600; echo
  rm -f /tmp/body.$$
}

echo "以 $USERNAME 的身分呼叫："
call "Resource Server /api/public（不帶 token）"     http://localhost:8082/api/public no
call "Resource Server /api/profile（不帶 token）"    http://localhost:8082/api/profile no
call "Resource Server /api/profile（帶 token）"      http://localhost:8082/api/profile
call "Resource Server /api/admin/stats（需 admins）" http://localhost:8082/api/admin/stats
call "Mockoon /orders（不帶 token）"                 http://localhost:3100/orders no
call "Mockoon /orders（帶 token）"                   http://localhost:3100/orders
