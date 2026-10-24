#!/usr/bin/env bash
# ------------------------------------------------------------------
# 用 curl 模擬「瀏覽器」走完整個 OIDC Authorization Code + PKCE 流程：
#   1-3 打開受保護頁面 -> 302 到 Keycloak
#   4   送出帳密（Keycloak 去 OpenLDAP 驗證）、按下同意
#   5-10 帶著 code+state 回到 Client App，Client App 換 token、建立 session
#   然後用 session 呼叫 /call/* 觀察 200/401/403，最後登出
# 用法: scripts/e2e-login.sh [帳號] [密碼]
# ------------------------------------------------------------------
set -e
U=${1:-alice}; P=${2:-alice123}
WORK=$(mktemp -d); trap 'rm -rf $WORK' EXIT; cd "$WORK"; JAR=$WORK/cookies.txt
C="curl -s -c $JAR -b $JAR"
action() { python3 -I -c "import re,html,sys; b=sys.stdin.read(); m=re.search(r'<form[^>]*id=\"$1\"[^>]*action=\"([^\"]+)\"', b); print(html.unescape(m.group(1)) if m else '')"; }
echo "[1-3] GET /profile 未登入 -> 302 -> Keycloak 登入頁"
$C -L -o login.html -w '   hops=%{num_redirects} final=%{url_effective}\n' http://localhost:8081/profile | cut -c1-120
A=$(action kc-form-login < login.html)
echo "[4] 送出帳密 ($U)"
$C -L -o step4.html -w '   http=%{http_code} final=%{url_effective}\n' --data-urlencode "username=$U" --data-urlencode "password=$P" -d credentialId= "$A" | cut -c1-120
if grep -q 'login-actions/consent' step4.html; then
  echo "   出現同意畫面 (consent) -> 按下同意"
  A2=http://localhost:8180$(python3 -I -c "import re,html,sys; print(html.unescape(re.search(r'action=\"(/realms/demo/login-actions/consent[^\"]+)\"', sys.stdin.read()).group(1)))" < step4.html); CODE=$(python3 -I -c "import re,sys; print(re.search(r'name=\"code\"\s+value=\"([^\"]+)\"', sys.stdin.read()).group(1))" < step4.html)
  $C -L -o final.html -w '   http=%{http_code} hops=%{num_redirects} final=%{url_effective}\n' -d "code=$CODE" -d accept=Yes "$A2"
else cp step4.html final.html; fi
echo "[5-10] 登入者: $(grep -o '登入者：<span>[^<]*' final.html | sed 's/.*<span>//')"
for t in rs-public rs-profile rs-admin mockoon-orders rs-profile/no-token mockoon-orders/no-token; do
  printf '   /call/%-26s -> HTTP %s\n' "$t" "$($C http://localhost:8081/call/$t | grep -o 'class="status [a-z]*">[0-9]*' | grep -o '[0-9]*$')"
done
CSRF=$($C http://localhost:8081/profile | grep -o 'name="_csrf"[^>]*value="[^"]*"' | sed 's/.*value="//;s/"//')
echo "[logout] -> $($C -L -o out.html -w '%{url_effective}' -d "_csrf=$CSRF" http://localhost:8081/logout) 已登出=$(grep -c '你還沒登入' out.html)"
