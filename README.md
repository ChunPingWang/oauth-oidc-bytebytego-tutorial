# OAuth 2.0 + OpenID Connect 實戰教學

> 用 **Spring Boot、Keycloak、OpenLDAP、Mockoon** 把下面這張 ByteByteGo 的架構圖「真的跑起來」，
> 一步一步看懂 **OAuth 2.0 Authorization Code + PKCE** 與 **OpenID Connect (OIDC)** 到底在做什麼。

![OAuth 2.0 + OIDC 架構圖](oauth-oidc-arch.png)

這份文件假設你**完全沒碰過 OAuth / OIDC**，只要會打開終端機、會用瀏覽器就能跟著做。
每一節都會告訴你：**要做什麼、會看到什麼、為什麼會這樣**。

---

## 目錄

1. [你會學到什麼](#1-你會學到什麼)
2. [三分鐘搞懂 OAuth 2.0 與 OIDC](#2-三分鐘搞懂-oauth-20-與-oidc)
3. [架構圖與本專案元件的對應](#3-架構圖與本專案元件的對應)
4. [環境需求](#4-環境需求)
5. [快速開始（10 分鐘跑起來）](#5-快速開始10-分鐘跑起來)
6. [動手走一遍：對照架構圖的 10 個步驟](#6-動手走一遍對照架構圖的-10-個步驟)
7. [深入解析](#7-深入解析)
8. [不用瀏覽器，用 curl 觀察 token](#8-不用瀏覽器用-curl-觀察-token)
9. [重要設定檔導覽](#9-重要設定檔導覽)
10. [元件安裝與使用教學：OpenLDAP、Keycloak、Mockoon](#10-元件安裝與使用教學openldapkeycloakmockoon)
    - [10.1 OpenLDAP](#101-openldap)
    - [10.2 Keycloak](#102-keycloak)
    - [10.3 Mockoon](#103-mockoon)
11. [常見問題 FAQ](#11-常見問題-faq)
12. [延伸練習](#12-延伸練習)
13. [清理環境](#13-清理環境)
14. [參考資料](#14-參考資料)

---

## 1. 你會學到什麼

跑完這個專案，你會親眼看到：

| 主題 | 你會看到的東西 |
|------|----------------|
| **OAuth 2.0 授權（authorization）** | Client App 拿到 `access_token`，用它去呼叫受保護的 API |
| **OIDC 驗證（authentication）** | Client App 拿到 `id_token`，知道「登入的人是誰」 |
| **Authorization Code 流程** | 瀏覽器被 302 導來導去，最後帶著一個一次性的 `code` 回來 |
| **PKCE** | `code_challenge` / `code_verifier` 怎麼防止 code 被偷走後拿去換 token |
| **state / nonce** | 怎麼防止 CSRF 與 token 重放 |
| **Resource Server 驗證 token** | 用 Keycloak 公開的 JWKS 公鑰驗簽章、檢查 `iss`、`exp`、`aud` |
| **授權（誰能做什麼）** | `alice` 可以呼叫 `/api/admin/**`，`bob` 會得到 `403` |
| **企業常見架構** | 使用者帳號放在 **LDAP**，Keycloak 透過 **User Federation** 讀取 |
| **Mock API** | 用 Mockoon 快速模擬一個「需要 Bearer token」的後端 |
| **元件本身怎麼用** | OpenLDAP、Keycloak、Mockoon 的安裝方式、管理介面與常用指令（第 10 節） |

---

## 2. 三分鐘搞懂 OAuth 2.0 與 OIDC

### 2.1 一個生活化的比喻

想像你住飯店：

- **你**（使用者）到**櫃台**（Authorization Server）出示身分證（帳號密碼）。
- 櫃台給你一張**房卡**（access token）。房卡上沒寫你的名字，只寫「可以開 1203 號房、可以進健身房」。
- **房門**（Resource Server / Protected Resource）不認識你，它只檢查房卡是不是櫃台發的、有沒有過期、能不能開這扇門。
- 如果櫃台另外給你一張**寫有你姓名、照片的識別證**（ID token），那就是 **OIDC** 加上去的東西：它回答「你是誰」。

### 2.2 四個角色

| 角色（RFC 6749 用語） | 白話 | 本專案 |
|----------------------|------|--------|
| **Resource Owner** | 使用者本人 | 你（用 alice / bob 登入） |
| **Client** | 想代替使用者去存取資料的應用程式 | `client-app`（Spring Boot） |
| **Authorization Server** | 負責驗證使用者、發 token 的服務 | Keycloak |
| **Resource Server** | 持有受保護資料的 API | `resource-server`（Spring Boot）與 Mockoon |

OIDC 把 Authorization Server 叫做 **OpenID Provider (OP / IdP)**，把 Client 叫做 **Relying Party (RP)**，意思一樣。

### 2.3 兩種 token 的差別

| | Access Token | ID Token |
|---|---|---|
| 屬於哪個規範 | OAuth 2.0 | OpenID Connect |
| 回答的問題 | **可以做什麼？** | **你是誰？** |
| 誰要看它 | Resource Server（API） | Client App 自己 |
| 怎麼用 | 放在 `Authorization: Bearer ...` header 呼叫 API | Client App 驗證後，建立自己的登入 session |
| 格式 | 規範沒規定（Keycloak 剛好用 JWT） | **一定是 JWT** |

> **重點**：OIDC = OAuth 2.0 + 一張 ID Token + 一些標準的使用者資訊 claims（`sub`、`name`、`email`…）。
> 只要 authorization request 裡的 `scope` 含有 `openid`，就是 OIDC 流程。

### 2.4 為什麼要 PKCE？

Authorization Code 流程中，`code` 會經過瀏覽器（網址列）。如果有惡意程式在中間攔到 `code`，
就可能搶先拿它去換 token。PKCE（Proof Key for Code Exchange，唸作 "pixy"）的做法：

1. Client App 先產生一個隨機字串 **`code_verifier`**，自己留著。
2. 計算 **`code_challenge = BASE64URL(SHA256(code_verifier))`**，把它放進第一次的 authorization request。
3. 換 token 時，Client App 必須把原本的 `code_verifier` 送給 Authorization Server。
4. Authorization Server 重算一次 SHA256，比對是否和當初收到的 `code_challenge` 一致。

攔到 `code` 的人沒有 `code_verifier`，換不到 token。

---

## 3. 架構圖與本專案元件的對應

```mermaid
flowchart LR
    B["🌐 Browser<br/>（你的瀏覽器）"]
    C["Client App<br/>Spring Boot<br/>:8081"]
    K["Authorization Server /<br/>OpenID Provider<br/>Keycloak :8180"]
    L[("OpenLDAP<br/>:1389<br/>使用者 + 群組")]
    R["Protected Resource ①<br/>resource-server<br/>Spring Boot :8082<br/>（真的驗簽章）"]
    M["Protected Resource ②<br/>Mockoon :3100<br/>（Mock API）"]

    B -- "① 打開受保護頁面" --> C
    C -- "② 302" --> B
    B -- "③ authorization request<br/>(+ code_challenge)" --> K
    K -- "④ 用 LDAP 驗證帳密" --> L
    K -- "⑤ 302 + code + state" --> B
    B -- "⑥ redirect_uri" --> C
    C -- "⑧ code + code_verifier<br/>⑨ access_token + id_token" --> K
    C -- "Bearer access_token" --> R
    C -- "Bearer access_token" --> M
    R -. "抓 JWKS 公鑰驗簽章" .-> K
```

| 架構圖裡的方塊 | 本專案用什麼實作 | 網址 | 帳號 |
|---|---|---|---|
| Browser | 你的瀏覽器 | - | - |
| Client App | `client-app/`（Spring Boot + `spring-boot-starter-oauth2-client`） | http://localhost:8081 | - |
| Authorization Server / OpenID Provider | Keycloak 26（Docker） | http://localhost:8180 | 管理後台 `admin` / `admin` |
| （圖中沒畫）使用者資料庫 | OpenLDAP（Docker） | `ldap://localhost:1389` | `cn=admin,dc=example,dc=org` / `admin` |
| Protected Resource（真正驗證 token） | `resource-server/`（Spring Boot + `spring-boot-starter-oauth2-resource-server`） | http://localhost:8082 | - |
| Protected Resource（Mock） | Mockoon CLI（Docker） | http://localhost:3100 | - |

**測試用的使用者（存在 OpenLDAP 裡，Keycloak 本身沒有這些帳號）：**

| 帳號 | 密碼 | 群組 | 可以呼叫 `/api/admin/**`？ |
|---|---|---|---|
| `alice` | `alice123` | `admins`, `users` | ✅ |
| `bob` | `bob123` | `users` | ❌（403） |

---

## 4. 環境需求

| 工具 | 版本 | 確認指令 |
|---|---|---|
| Docker + Docker Compose | Docker 24+ / Compose v2+ | `docker compose version` |
| Java (JDK) | **21** 以上（macOS：`brew install openjdk@21`；或用 [SDKMAN](https://sdkman.io) `sdk install java 21-tem`） | `java -version` |
| Maven | 不用另外裝，專案內附 `mvnw` wrapper | - |
| curl、python3 | macOS / Linux 內建 | `curl --version` |

> Apple Silicon (M1/M2/M3) 與 Intel 的 Mac、Linux 都可以直接跑，使用的 Docker image 都有 arm64 與 amd64 版本。

本專案會用到這些 port，請先確認沒有被佔用：`8081`、`8082`、`8180`、`3100`、`1389`。

```bash
# 檢查 port 是否被佔用（有輸出就是被佔用）
lsof -nP -iTCP:8180 -sTCP:LISTEN
```

---

## 5. 快速開始（10 分鐘跑起來）

### 步驟 1：取得專案

```bash
git clone https://github.com/ChunPingWang/oauth-oidc-bytebytego-tutorial.git
cd oauth-oidc-bytebytego-tutorial
```

### 步驟 2：啟動基礎設施（OpenLDAP + Keycloak + Mockoon）

```bash
docker compose up -d
```

第一次會下載 image，需要幾分鐘。啟動順序是 **OpenLDAP → （健康檢查通過後）→ Keycloak**，Keycloak 啟動時會自動匯入 `keycloak/realm-demo.json`。

確認都起來了：

```bash
docker compose ps
# 三個容器都應該是 running / healthy

# Keycloak 的 OIDC Discovery 文件（所有端點位置都寫在這裡）
curl -s http://localhost:8180/realms/demo/.well-known/openid-configuration | python3 -m json.tool | head -20

# Mockoon 健康檢查
curl http://localhost:3100/health
```

> Keycloak 約需 10～20 秒啟動。如果 curl 回「Connection refused」，等一下再試。
> 可以用 `docker compose logs -f keycloak` 看到 `Keycloak 26.x started` 這行。

### 步驟 3：啟動 Resource Server（第 1 個終端機）

```bash
cd resource-server
./mvnw spring-boot:run
```

看到 `Started ResourceServerApplication` 就成功了。它會在啟動時向 Keycloak 抓取 Discovery 文件，所以 **Keycloak 必須先起來**。

### 步驟 4：啟動 Client App（第 2 個終端機）

```bash
cd client-app
./mvnw spring-boot:run
```

看到 `Started ClientAppApplication` 後，打開瀏覽器：**http://localhost:8081**

### 步驟 5：登入試試

1. 點 **Sign in with Keycloak**
2. 在 Keycloak 登入頁輸入 `alice` / `alice123`
3. 看到 **同意畫面（consent）**，按 **Yes**
4. 回到 Client App 的「我的 Token」頁面 🎉

接下來第 6 節會帶你**慢慢**再走一次，並解釋每一步發生了什麼。

---

## 6. 動手走一遍：對照架構圖的 10 個步驟

建議先**登出**（頁面上的登出按鈕），然後打開瀏覽器的開發者工具（F12）→ **Network** 分頁，勾選 **Preserve log**，這樣可以看到每一次 302 redirect。

同時看著跑 `client-app` 的那個終端機，它會印出關鍵資訊。

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant C as Client App<br/>(localhost:8081)
    participant K as Keycloak<br/>(localhost:8180)
    participant L as OpenLDAP
    participant R as Resource Server<br/>(localhost:8082)

    B->>C: GET /profile（受保護頁面）
    C-->>B: 302 → /oauth2/authorization/keycloak → 302 → Keycloak
    B->>K: GET /realms/demo/protocol/openid-connect/auth<br/>?response_type=code&client_id=client-app&scope=openid profile email<br/>&state=…&nonce=…&code_challenge=…&code_challenge_method=S256
    K->>L: LDAP bind：驗證 alice 的密碼
    L-->>K: 成功，附上群組資訊
    K-->>B: （顯示登入頁 → 同意頁）→ 302 到 redirect_uri?code=…&state=…
    B->>C: GET /login/oauth2/code/keycloak?code=…&state=…
    Note over C: 驗證 state 與 session 裡存的一致
    C->>K: POST /protocol/openid-connect/token<br/>grant_type=authorization_code&code=…&code_verifier=…<br/>+ client_id / client_secret
    K-->>C: { access_token, id_token, refresh_token, … }
    Note over C: 驗證 id_token（簽章、iss、aud、exp、nonce）<br/>建立 session → 使用者登入完成
    C-->>B: 302 → /profile（顯示 token 內容）
    B->>C: 點「呼叫 API」
    C->>R: GET /api/profile<br/>Authorization: Bearer access_token
    Note over R: 用 Keycloak 的 JWKS 公鑰驗簽章<br/>檢查 iss / exp / aud，解析 groups
    R-->>C: 200 JSON
```

### 步驟 ①②：使用者打開受保護頁面 → 302

在瀏覽器輸入 **http://localhost:8081/profile**。

你會看到瀏覽器**沒有**顯示 profile，而是被導向 Keycloak 的登入頁。Network 分頁會看到：

```
GET /profile                        → 302 Location: /oauth2/authorization/keycloak
GET /oauth2/authorization/keycloak  → 302 Location: http://localhost:8180/realms/demo/protocol/openid-connect/auth?...
```

**為什麼？** `client-app/.../SecurityConfig.java` 設定了 `anyRequest().authenticated()`，
Spring Security 發現你沒登入，就把你導到內建的登入觸發網址 `/oauth2/authorization/{registrationId}`。

### 步驟 ③：Authorization Request（含 PKCE）

看一下現在瀏覽器網址列（或 client-app 終端機印出的 `[步驟 3]` 區塊）：

```
http://localhost:8180/realms/demo/protocol/openid-connect/auth
  ?response_type=code                      ← 我要用 Authorization Code 流程
  &client_id=client-app                    ← 我是誰（在 Keycloak 註冊過的 client）
  &scope=openid profile email              ← 有 openid 就是 OIDC；profile/email 是要哪些使用者資料
  &state=iqP1DTy9j3oN...                   ← 隨機值，等一下回來時要核對（防 CSRF）
  &redirect_uri=http://localhost:8081/login/oauth2/code/keycloak   ← 登入完要導回哪裡
  &nonce=pmbwcan-2lxN...                   ← 隨機值，會被放進 id_token（防重放）
  &code_challenge=oHFLBEhAvV2n...          ← PKCE：SHA256(code_verifier) 的 Base64URL
  &code_challenge_method=S256
```

終端機還會印出 **`code_verifier`**，並註明「只留在 Client App 的 session，不會送出去」。
這是 PKCE 的關鍵：網址上只有 challenge（雜湊值），verifier（原文）從頭到尾沒有經過瀏覽器。

> 產生這段網址的程式碼在 `client-app/.../SecurityConfig.java` 的 `pkceResolver()`，
> 核心只有一行：`resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())`。

### 步驟 ④：使用者驗證 + 同意（Keycloak ↔ OpenLDAP）

輸入 `alice` / `alice123`。

**Keycloak 本身沒有 alice 這個帳號。** 它透過 **User Federation** 連到 OpenLDAP：

1. 用 `cn=admin,dc=example,dc=org` 連上 LDAP，在 `ou=people,dc=example,dc=org` 底下找 `uid=alice`
2. 用 alice 的 DN 和你輸入的密碼做 **LDAP bind**（密碼對了 bind 才會成功）
3. 讀取 `givenName` / `sn` / `mail` 與群組（`ou=groups` 底下 `member` 含有 alice 的 `groupOfNames`）

你可以在 Keycloak 管理後台看到：http://localhost:8180/admin → `admin` / `admin` → 左上角切換到 **demo** realm → **User federation** → `openldap`。
登入過一次後，**Users** 頁面也會出現 alice（這是 Keycloak 從 LDAP「匯入」的快取副本）。

接著出現 **同意畫面**：列出 Client App 要求的資料（`profile`、`email`…）。這就是架構圖步驟 4 的 "consent"。
Google 登入時「某某 App 想要存取你的 email」就是同一個東西。本專案在 realm 設定裡刻意開啟 `consentRequired: true` 讓你看到它。

### 步驟 ⑤⑥：帶著 code + state 回到 Client App

按下同意後，Network 分頁會看到：

```
POST .../login-actions/consent         → 302 Location: http://localhost:8081/login/oauth2/code/keycloak?state=iqP1DT...&session_state=...&code=abc123...
GET  /login/oauth2/code/keycloak?...   → 302 Location: /profile
```

注意：**這裡只有 `code`，還沒有任何 token。** `code` 是一次性的、幾十秒內就過期，而且沒有 `code_verifier` 配合就換不到東西。

### 步驟 ⑦：驗證 state

Client App 收到 `/login/oauth2/code/keycloak?code=...&state=...` 時，第一件事是比對 `state` 和步驟 ③ 存在 session 裡的那個是否相同。
不同就直接拒絕，這樣攻擊者無法把「自己的 code」塞進你的瀏覽器讓你登入成他的帳號（login CSRF）。

這部分 Spring Security 的 `OAuth2LoginAuthenticationFilter` 自動完成，不用寫程式。

### 步驟 ⑧⑨：用 code 換 token（瀏覽器看不到！）

這一步是 **Client App 的後端直接打 Keycloak**，不經過瀏覽器，所以 Network 分頁看不到。想看的話把 `client-app/src/main/resources/application.yml` 裡這行打開：

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

Client App 送的請求長這樣（Spring 自動產生）：

```
POST http://localhost:8180/realms/demo/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(client-app:client-app-secret)     ← Client 自己的身分

grant_type=authorization_code
&code=abc123...
&redirect_uri=http://localhost:8081/login/oauth2/code/keycloak
&code_verifier=-ZlqurTXKNIqV_01oyGqM...                     ← PKCE：原文終於送出，但走的是後端對後端
```

Keycloak 做三件事：確認 `code` 有效且沒用過、確認 client secret 正確、**確認 `SHA256(code_verifier) == 當初的 code_challenge`**。都通過才回：

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 300,
  "refresh_token": "eyJhbGciOiJIUzUxMiIs...",
  "id_token": "eyJhbGciOiJSUzI1NiIs...",
  "token_type": "Bearer",
  "scope": "openid profile email"
}
```

### 步驟 ⑩：驗證 ID Token，建立 session

Client App 拿到 `id_token` 後**不是直接相信它**，而是：

| 檢查 | 說明 |
|---|---|
| 簽章 | 用 Keycloak JWKS 端點（`/protocol/openid-connect/certs`）的公鑰驗證 |
| `iss` | 必須等於 `http://localhost:8180/realms/demo` |
| `aud` | 必須包含 `client-app` |
| `exp` | 沒過期 |
| `nonce` | 和步驟 ③ 送出的一樣 |

全部通過後，Spring 建立 `OidcUser` 物件、寫入 HTTP session、給瀏覽器一個 `JSESSIONID` cookie。**這時候你才算「登入」了。**

打開 http://localhost:8081/profile 可以看到：

- 綠色區塊（**OIDC**）：ID Token 的每一個 claim（`sub`、`name`、`email`、`groups`、`nonce`、`auth_time`…）
- 藍色區塊（**OAuth 2.0**）：Access Token 的內容、到期時間、scopes

### 最後一步：App call with Bearer token → Protected Resource

點 **「拿這個 token 去呼叫 Resource Server」**，或直接開 http://localhost:8081/call/rs-profile。

Client App 後端會送：

```
GET http://localhost:8082/api/profile
Authorization: Bearer eyJhbGciOiJSUzI1NiIs...
```

Resource Server（`resource-server/`）**完全沒有連 Keycloak 問「這個 token 有效嗎」**，它自己驗：

1. 啟動時從 Keycloak 的 Discovery 文件找到 `jwks_uri`，抓公鑰（之後會快取）
2. 驗證 JWT 簽章、`iss`、`exp`
3. 額外檢查 `aud` 包含 `resource-server`（設定在 `application.yml` 的 `audiences`）
4. 把 `groups` claim 轉成 Spring 的權限 `GROUP_admins`、`GROUP_users`

試試這幾個按鈕，觀察狀態碼：

| 按鈕 | alice | bob | 原因 |
|---|---|---|---|
| Resource Server `/api/public` | 200 | 200 | `permitAll()` |
| Resource Server `/api/profile` | 200 | 200 | token 有效即可 |
| Resource Server `/api/admin/stats` | 200 | **403** | 需要 `GROUP_admins` |
| Mockoon `/orders` | 200 | 200 | Mockoon 只檢查有沒有 Bearer header |
| 故意不帶 token 呼叫 | **401** | **401** | 沒有 token |

> **401 vs 403**：401 = 「你是誰我不知道」（沒 token 或 token 無效）；403 = 「我知道你是誰，但你不能做這件事」。

### 登出

按 **登出** 會：

1. 清掉 Client App 的 session
2. 把瀏覽器導向 Keycloak 的 `end_session_endpoint`（帶著 `id_token_hint`），讓 Keycloak 也登出（**OIDC RP-Initiated Logout**）
3. Keycloak 再把瀏覽器導回 `http://localhost:8081/`

如果只做第 1 步，下次點登入會因為 Keycloak 還記得你而**直接登入成功、不用輸入密碼**（這就是 SSO）。

---

## 7. 深入解析

### 7.1 state、nonce、PKCE 到底各防什麼？

| 參數 | 誰產生 | 存在哪裡 | 在哪裡比對 | 防什麼 |
|---|---|---|---|---|
| `state` | Client App | Client session | 步驟 ⑦，Client App 收到 callback 時 | Login CSRF：攻擊者把自己的 code 塞給你 |
| `nonce` | Client App | Client session；Keycloak 把它放進 `id_token` | 步驟 ⑩，驗證 id_token 時 | ID token 重放 |
| `code_challenge` / `code_verifier` | Client App | verifier 在 Client session；challenge 送給 Keycloak | 步驟 ⑧，Keycloak 收到 code_verifier 時 | Authorization code 被攔截後盜用 |

本專案的 Keycloak client 設定了 `"pkce.code.challenge.method": "S256"`，代表 **Keycloak 強制要求 PKCE**。
你可以試試看把 `SecurityConfig.java` 裡的 `withPkce()` 那行註解掉重啟，登入時 Keycloak 會直接報錯 `Missing parameter: code_challenge_method`。

### 7.2 Public client vs Confidential client

- 本專案的 `client-app` 是 **confidential client**：有 `client_secret`，換 token 時要出示。它跑在伺服器上，secret 不會外洩。
- 手機 App、純前端 SPA 無法保密 secret，就是 **public client**，**必須**用 PKCE 來補強。
- 現在的最佳實務（OAuth 2.1 草案）是：**不管哪種 client 都用 PKCE**，所以本專案兩者都用。

### 7.3 Access Token 是怎麼被驗證的（JWT + JWKS）

Keycloak 發的 access token 是 JWT，三段用 `.` 分隔：`header.payload.signature`

```
header    = { "alg": "RS256", "kid": "xxxx", "typ": "JWT" }   ← 用哪把金鑰簽的
payload   = { "iss": "...", "sub": "...", "aud": [...], "exp": ..., "groups": [...] }
signature = RSA-SHA256(header + "." + payload, Keycloak 的私鑰)
```

Resource Server 從 `http://localhost:8180/realms/demo/protocol/openid-connect/certs` 拿到 Keycloak 的**公鑰**（JWKS），
用 `kid` 找到對應的那把，驗證簽章。因為只有 Keycloak 有私鑰，簽章對了就代表 token 真的是 Keycloak 發的、內容沒被改過。

試試看篡改 token（把最後一個字元改掉）：

```bash
scripts/get-token.sh alice alice123     # 複製印出的 export TOKEN=... 貼到終端機執行
curl -i http://localhost:8082/api/profile -H "Authorization: Bearer ${TOKEN}x"
# HTTP/1.1 401
# WWW-Authenticate: Bearer error="invalid_token", error_description="...Invalid signature"
```

### 7.4 為什麼要檢查 `aud`（audience）？

想像公司有 10 個 API，都信任同一個 Keycloak。如果 A 服務拿到的 token 可以直接拿去呼叫 B 服務，
那 A 服務被攻破時所有服務都遭殃。`aud` 就是「這張 token 是發給誰用的」。

本專案在 `keycloak/realm-demo.json` 用 **audience mapper** 把 `resource-server` 放進 access token 的 `aud`，
`resource-server/application.yml` 則用 `audiences: resource-server` 要求 token 一定要有它。

### 7.5 授權（Authorization）：從 groups 到 403

流程：LDAP 群組 → Keycloak 群組 → token 的 `groups` claim → Spring 權限 → URL 規則

| 階段 | 設定位置 |
|---|---|
| LDAP 裡 alice 屬於 `cn=admins,ou=groups` | `openldap/bootstrap.ldif` |
| Keycloak 用 `group-ldap-mapper` 把 LDAP 群組映射成 Keycloak 群組 | `keycloak/realm-demo.json` → `components` |
| 用 `oidc-group-membership-mapper` 把群組放進 token 的 `groups` claim | `keycloak/realm-demo.json` → `protocolMappers` |
| 把 `groups` 轉成 `GROUP_admins` 權限 | `resource-server/.../SecurityConfig.java` → `extractAuthorities()` |
| `/api/admin/**` 需要 `GROUP_admins` | `resource-server/.../SecurityConfig.java` → `hasAuthority("GROUP_admins")` |

### 7.6 Keycloak + OpenLDAP：User Federation 是什麼？

企業通常已經有 AD / LDAP 存放員工帳號，不可能為了 Keycloak 再建一份。
**User Federation** 讓 Keycloak 變成「LDAP 的門面」：帳號密碼仍在 LDAP，Keycloak 只負責 OAuth / OIDC 協定。

本專案設定重點（`realm-demo.json` → `components` → `openldap`）：

| 設定 | 值 | 意思 |
|---|---|---|
| `connectionUrl` | `ldap://openldap:389` | 容器內的 hostname（compose 的 service 名稱） |
| `usersDn` | `ou=people,dc=example,dc=org` | 去哪裡找使用者 |
| `usernameLDAPAttribute` | `uid` | 哪個屬性是帳號 |
| `bindDn` / `bindCredential` | `cn=admin,...` / `admin` | Keycloak 用這組帳號去搜尋 LDAP |
| `editMode` | `READ_ONLY` | 在 Keycloak 改資料不會寫回 LDAP |
| `importEnabled` | `true` | 登入過的使用者會被快取到 Keycloak DB |

直接查 LDAP（macOS / Linux 內建 `ldapsearch`；更多指令見 [10.1 節](#101-openldap)）：

```bash
# 查 alice
ldapsearch -x -H ldap://localhost:1389 -b dc=example,dc=org \
  -D cn=admin,dc=example,dc=org -w admin "(uid=alice)"

# 查 admins 群組的成員
ldapsearch -x -H ldap://localhost:1389 -b ou=groups,dc=example,dc=org \
  -D cn=admin,dc=example,dc=org -w admin "(cn=admins)" member
```

### 7.7 Mockoon 扮演什麼角色？它的限制？

Mockoon 是一個「不用寫程式就能做出假 API」的工具，很適合：前端開發時後端還沒好、或像本專案一樣**快速模擬一個需要 token 的下游服務**。

`mockoon/orders-api.json` 定義了 `GET /orders`（另有 `GET /orders/:id` 示範路徑參數，見 [10.3 節](#103-mockoon)）：

- **規則**：`Authorization` header 符合正規表示式 `^Bearer .+` → 回 200 與訂單資料
- 否則 → 預設回應 401 + `WWW-Authenticate: Bearer realm="orders-api"`
- 回應內容用 Mockoon 的模板語法 `{{jwtPayload (header 'Authorization') 'preferred_username'}}` 把 token 裡的使用者名稱塞進去

**限制：Mockoon 不會驗證簽章。** 隨便給它一個格式正確的 JWT 它都接受。所以本專案另外做了 `resource-server` 示範「真正的」驗證。
這也是為什麼圖中的 "Validate access token" 這一步我們用 Spring Boot 實作。

想改 Mock 回應：修改 `mockoon/orders-api.json` 後 `docker compose restart mockoon`。
也可以用 Mockoon 桌面版打開這個 JSON 檔用 GUI 編輯。

### 7.8 Access token 過期了怎麼辦？

Keycloak 設定 access token 只活 **5 分鐘**（`accessTokenLifespan: 300`）。
登入 5 分鐘後再按「呼叫 API」依然成功，因為 Spring 的 `OAuth2AuthorizedClientManager` 發現 token 快過期，
會自動用 `refresh_token` 向 Keycloak 換一張新的（`grant_type=refresh_token`），完全不用使用者再登入。

---

## 8. 不用瀏覽器，用 curl 觀察 token

專案提供幾個腳本（`scripts/`），方便你不透過瀏覽器直接「玩」token。

> 這些腳本用的是 `cli-demo` client 的 **password grant**（直接送帳密換 token）。
> 這**不是**架構圖的流程，OAuth 2.1 已經廢除它，這裡只是為了教學方便。

```bash
# 1. 以 alice 身分取得 token，印出 access token 與 id token 的內容
scripts/get-token.sh alice alice123

# 2. 用 token 呼叫 Resource Server 與 Mockoon，觀察 200 / 401 / 403（換成 bob 看看 403）
scripts/call-api.sh alice alice123
scripts/call-api.sh bob bob123

# 3. 解碼任何一個 JWT 的 payload
scripts/decode-jwt.sh eyJhbGciOi...

# 4. 用 curl 模擬「瀏覽器」走完整的 Authorization Code + PKCE + consent 流程（client-app 需已啟動）
scripts/e2e-login.sh alice alice123
```

`scripts/e2e-login.sh` 的輸出會長這樣，對照架構圖的編號：

```
[1-3] GET /profile 未登入 -> 302 -> Keycloak 登入頁
[4] 送出帳密 (alice)
   出現同意畫面 (consent) -> 按下同意
[5-10] 登入者: alice
   /call/rs-public                  -> HTTP 200
   /call/rs-profile                 -> HTTP 200
   /call/rs-admin                   -> HTTP 200
   /call/mockoon-orders             -> HTTP 200
   /call/rs-profile/no-token        -> HTTP 401
   /call/mockoon-orders/no-token    -> HTTP 401
[logout] -> http://localhost:8081/ 已登出=1
```

手動打 token endpoint 看原始回應：

```bash
curl -s -X POST http://localhost:8180/realms/demo/protocol/openid-connect/token \
  -d client_id=cli-demo -d grant_type=password \
  -d username=alice -d password=alice123 -d scope=openid | python3 -m json.tool
```

---

## 9. 重要設定檔導覽

```
oauth-oidc-tutorial/
├── README.md                      ← 你正在看的文件
├── oauth-oidc-arch.png            ← 架構圖
├── docker-compose.yml             ← OpenLDAP + Keycloak + Mockoon（+ 選用的 phpLDAPadmin）
├── openldap/
│   └── bootstrap.ldif             ← alice / bob 與 admins / users 群組
├── keycloak/
│   └── realm-demo.json            ← realm、clients、LDAP federation、mappers
├── mockoon/
│   └── orders-api.json            ← Mock API 定義（/health、/orders、/orders/:id）
├── scripts/                       ← curl 小工具
├── client-app/                    ← 架構圖的 Client App（port 8081）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/clientapp/
│       │   ├── SecurityConfig.java                 ← oauth2Login + PKCE + OIDC logout
│       │   ├── LoggingAuthorizationRequestResolver.java  ← 把步驟 3 的網址印到 console
│       │   ├── HomeController.java                 ← / 與 /profile（顯示 token）
│       │   ├── ApiCallController.java              ← /call/* 帶 Bearer token 呼叫 API
│       │   └── JwtDisplay.java                     ← 把 JWT 拆開顯示（純展示用）
│       └── resources/
│           ├── application.yml                     ← client-id / secret / issuer-uri
│           └── templates/*.html                    ← 網頁
└── resource-server/               ← 架構圖的 Protected Resource（port 8082）
    ├── pom.xml
    └── src/main/
        ├── java/com/example/resourceserver/
        │   ├── SecurityConfig.java                 ← JWT 驗證 + groups → 權限
        │   └── ApiController.java                  ← /api/public、/api/profile、/api/admin/stats
        └── resources/application.yml               ← issuer-uri、audiences
```

### 9.1 `client-app/src/main/resources/application.yml`

```yaml
spring.security.oauth2.client:
  registration:
    keycloak:                     # registrationId，決定網址 /oauth2/authorization/keycloak
      client-id: client-app       # 要和 realm-demo.json 的 clientId 一樣
      client-secret: client-app-secret
      authorization-grant-type: authorization_code
      redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"   # 要和 Keycloak 的 redirectUris 一樣
      scope: openid, profile, email     # openid => OIDC
  provider:
    keycloak:
      issuer-uri: http://localhost:8180/realms/demo   # Spring 會自動讀 /.well-known/openid-configuration
```

### 9.2 `resource-server/src/main/resources/application.yml`

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: http://localhost:8180/realms/demo   # 驗 iss + 自動找 jwks_uri
  audiences: resource-server                      # 驗 aud
```

### 9.3 `keycloak/realm-demo.json` 的重點

| 區塊 | 重點 |
|---|---|
| `clients[0]` (`client-app`) | `publicClient: false` + `secret`；`redirectUris` 必須和 Spring 的 redirect-uri 完全一致；`consentRequired: true` 顯示同意畫面；`pkce.code.challenge.method: S256` 強制 PKCE |
| `clients[0].protocolMappers` | `groups` claim、`aud` 加入 `resource-server` |
| `clients[1]` (`cli-demo`) | `directAccessGrantsEnabled: true`，只給 `scripts/` 用 |
| `components` | LDAP User Federation 與 4 個屬性 mapper + 1 個群組 mapper |

### 9.4 為什麼 Spring Boot 不放進 docker-compose？

如果 Client App 跑在容器裡，它連 Keycloak 要用 `http://keycloak:8080`，但瀏覽器要用 `http://localhost:8180`，
兩邊看到的 **issuer 不一樣**，Spring 驗證 `iss` 會失敗。這是初學者最常卡住的地方。
把 Spring Boot 跑在本機，所有人看到的 Keycloak 都是 `localhost:8180`，問題就不存在。
（正式環境會給 Keycloak 一個固定的 hostname，例如 `https://sso.company.com`，就沒有這個問題。）

---

## 10. 元件安裝與使用教學：OpenLDAP、Keycloak、Mockoon

前面的章節把三個元件當成黑盒子。這一節把每個元件拆開來講：**它是什麼、怎麼安裝、平常怎麼操作**。
本專案用 Docker Compose 一次啟動它們，但也會告訴你不用 Docker 時怎麼裝。

### 10.1 OpenLDAP

#### 10.1.1 LDAP 是什麼？

LDAP（Lightweight Directory Access Protocol）是一種「查詢目錄」的協定，OpenLDAP 是它最常見的開源實作。
你可以把它想成一本**樹狀的電話簿**：公司在最上面，底下分部門，部門底下是員工。企業裡的 AD（Active Directory）也是用 LDAP 協定。

```
dc=example,dc=org                         ← 根（公司）
├── ou=people                             ← 組織單位：使用者
│   ├── uid=alice                         ← 一筆 entry
│   └── uid=bob
└── ou=groups                             ← 組織單位：群組
    ├── cn=admins   (member: uid=alice)
    └── cn=users    (member: uid=alice, uid=bob)
```

幾個一定要認識的名詞：

| 名詞 | 意思 | 範例 |
|---|---|---|
| **Entry** | 一筆資料（一個節點） | alice 這個人 |
| **DN**（Distinguished Name） | entry 的完整路徑，從自己往上寫到根 | `uid=alice,ou=people,dc=example,dc=org` |
| **dc / ou / cn / uid** | DN 裡的屬性名稱：domain component、organizational unit、common name、user id | `dc=example`、`ou=people`、`cn=admins`、`uid=alice` |
| **objectClass** | entry 的「型別」，決定它可以有哪些屬性 | `inetOrgPerson`（人）、`groupOfNames`（群組） |
| **Bind** | 用某個 DN + 密碼「登入」LDAP。**密碼對不對就是看 bind 成不成功**，Keycloak 就是這樣驗證密碼的 | `-D uid=alice,... -w alice123` |
| **LDIF** | 用純文字描述 entry 的格式，用來匯入 / 修改資料 | `openldap/bootstrap.ldif` |
| **Base DN** | 搜尋的起點 | `-b dc=example,dc=org` |

#### 10.1.2 安裝方式

**方式 A：Docker（本專案採用）**

`docker-compose.yml` 裡的 `openldap` 服務用的是 `osixia/openldap:1.5.0` 這個 image，重點設定：

```yaml
openldap:
  image: osixia/openldap:1.5.0
  command: ["--copy-service"]          # 讓 image 讀取我們掛進去的自訂 LDIF
  environment:
    LDAP_ORGANISATION: "Example Org"
    LDAP_DOMAIN: "example.org"         # 自動變成 Base DN：dc=example,dc=org
    LDAP_ADMIN_PASSWORD: "admin"       # 管理者 cn=admin,dc=example,dc=org 的密碼
    LDAP_TLS: "false"                  # 教學用，關掉 TLS
  ports:
    - "1389:389"                       # 本機用 1389 連，避免和系統 389 衝突
  volumes:
    - ./openldap/bootstrap.ldif:/container/service/slapd/assets/config/bootstrap/ldif/custom/50-bootstrap.ldif:ro
```

> **注意**：bootstrap LDIF 只會在**第一次建立資料庫**時載入。改了 `bootstrap.ldif` 要用 `docker compose down -v` 刪掉資料再 `up`。

單獨啟動（不用 compose）也可以：

```bash
docker run -d --name openldap -p 1389:389 \
  -e LDAP_ORGANISATION="Example Org" -e LDAP_DOMAIN=example.org -e LDAP_ADMIN_PASSWORD=admin -e LDAP_TLS=false \
  -v "$PWD/openldap/bootstrap.ldif:/container/service/slapd/assets/config/bootstrap/ldif/custom/50-bootstrap.ldif:ro" \
  osixia/openldap:1.5.0 --copy-service
```

**方式 B：macOS（Homebrew）**

macOS 內建 `ldapsearch` 等**客戶端**工具，不用另外裝。如果想在本機跑 LDAP **伺服器**：

```bash
brew install openldap
# 伺服器程式是 slapd，設定檔在 $(brew --prefix)/etc/openldap/slapd.conf
```

**方式 C：Ubuntu / Debian**

```bash
sudo apt update
sudo apt install slapd ldap-utils        # slapd = 伺服器，ldap-utils = ldapsearch 等指令
sudo dpkg-reconfigure slapd              # 互動式設定 domain 與管理者密碼
```

#### 10.1.3 常用操作（指令都可以直接對本專案的 LDAP 執行）

每個指令都會用到同一組「連線參數」，先記起來：

```
-x                                  用簡單驗證（帳號密碼），不用 SASL
-H ldap://localhost:1389            伺服器位址
-D cn=admin,dc=example,dc=org       用哪個身分 bind（管理者）
-w admin                            bind 的密碼
```

**查詢（ldapsearch）**

```bash
# 列出整棵樹
ldapsearch -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin -b dc=example,dc=org

# 找某個使用者，只顯示幾個欄位
ldapsearch -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin \
  -b ou=people,dc=example,dc=org "(uid=alice)" cn mail

# 找 admins 群組有哪些成員
ldapsearch -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin \
  -b ou=groups,dc=example,dc=org "(cn=admins)" member

# 找「email 是 example.org 結尾」的所有人（LDAP filter 語法）
ldapsearch -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin \
  -b ou=people,dc=example,dc=org "(mail=*@example.org)" uid
```

**新增使用者（ldapadd）**：LDIF 從標準輸入餵進去

```bash
ldapadd -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin <<'LDIF'
dn: uid=carol,ou=people,dc=example,dc=org
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
uid: carol
cn: Carol Lin
sn: Lin
givenName: Carol
mail: carol@example.org
userPassword: carol123
LDIF
```

**把使用者加入群組（ldapmodify）**

```bash
ldapmodify -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin <<'LDIF'
dn: cn=users,ou=groups,dc=example,dc=org
changetype: modify
add: member
member: uid=carol,ou=people,dc=example,dc=org
LDIF
```

**改密碼（ldappasswd）**

```bash
ldappasswd -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin \
  -s carol456 uid=carol,ou=people,dc=example,dc=org
```

**驗證密碼對不對（ldapwhoami）**：這就是 Keycloak 驗證密碼時做的事，用使用者自己的 DN 去 bind

```bash
ldapwhoami -x -H ldap://localhost:1389 -D uid=carol,ou=people,dc=example,dc=org -w carol456
# 成功會印出 dn:uid=carol,ou=people,dc=example,dc=org
# 密碼錯會印出 ldap_bind: Invalid credentials (49)
```

**用新使用者登入 Keycloak**：不用重啟任何東西，直接到 http://localhost:8081 用 `carol / carol456` 登入，
或 `scripts/get-token.sh carol carol456`。Keycloak 會即時去 LDAP 查。

**刪除使用者（ldapdelete）**：記得先從群組移除

```bash
ldapmodify -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin <<'LDIF'
dn: cn=users,ou=groups,dc=example,dc=org
changetype: modify
delete: member
member: uid=carol,ou=people,dc=example,dc=org
LDIF
ldapdelete -x -H ldap://localhost:1389 -D cn=admin,dc=example,dc=org -w admin uid=carol,ou=people,dc=example,dc=org
```

> 刪除後 Keycloak 的 Users 頁面可能還看得到 carol（匯入的快取副本）。到 User federation → openldap → Action → **Remove imported** 清掉，或等它下次同步。

#### 10.1.4 圖形化介面：phpLDAPadmin（選用）

不想打指令可以用網頁介面：

```bash
docker compose --profile tools up -d        # 多啟動 phpldapadmin 容器
```

打開 http://localhost:8389 → 左邊 **login** → Login DN 填 `cn=admin,dc=example,dc=org`，Password 填 `admin`。
左側樹狀結構可以點開 `ou=people` 看每個使用者的屬性，也可以直接在介面上新增 / 修改 entry。

#### 10.1.5 OpenLDAP 在本專案的角色

OpenLDAP **只做一件事：存帳號、密碼、群組**。它完全不懂 OAuth / OIDC。
Keycloak 透過 User Federation 連上它（設定在 `keycloak/realm-demo.json` 的 `components`，說明見 [7.6 節](#76-keycloak--openldapuser-federation-是什麼)）。
這和企業實務一樣：帳號在 AD / LDAP，SSO 平台只負責協定。

---

### 10.2 Keycloak

#### 10.2.1 Keycloak 是什麼？

Keycloak 是 Red Hat 開源的**身分與存取管理（IAM）伺服器**，一次給你：OAuth 2.0 Authorization Server、OpenID Connect Provider、SAML IdP、
登入頁、同意頁、使用者管理、社群登入（Google / GitHub …）、LDAP 整合、MFA 等等。
在架構圖裡它同時扮演 **Authorization Server** 與 **OpenID Provider**。

核心概念（管理後台左側選單就是照這些排的）：

| 概念 | 意思 | 本專案 |
|---|---|---|
| **Realm** | 一個完全隔離的空間，有自己的使用者、clients、設定。`master` realm 是管 Keycloak 本身用的 | `demo` |
| **Client** | 要跟 Keycloak 要 token 的應用程式 | `client-app`、`cli-demo` |
| **User** | 使用者 | alice、bob（來自 LDAP） |
| **Group** | 使用者群組，可以放進 token | `admins`、`users`（來自 LDAP） |
| **Role** | 權限角色，分 realm role 與 client role | `admin`、`user`（本專案沒用到，留給練習） |
| **Client Scope** | 一組「token 裡要放什麼」的定義，可被多個 client 共用 | 預設的 `profile`、`email`、`roles` |
| **Protocol Mapper** | 把某個資料放進 token 的規則 | `groups` mapper、audience mapper |
| **User Federation** | 從外部（LDAP / Kerberos）讀使用者 | `openldap` |
| **Identity Provider** | 把登入委託給別人（Google、另一個 Keycloak …），和 Federation 不同 | 未使用 |

#### 10.2.2 安裝方式

**方式 A：Docker（本專案採用）**

```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.8.1
  command: ["start-dev", "--import-realm"]
  environment:
    KC_BOOTSTRAP_ADMIN_USERNAME: admin      # 第一次啟動建立的管理者
    KC_BOOTSTRAP_ADMIN_PASSWORD: admin
    KC_HEALTH_ENABLED: "true"               # 開啟 /health 端點（在 9000 port）
  ports:
    - "8180:8080"                           # 容器內 8080，本機用 8180
  volumes:
    - ./keycloak/realm-demo.json:/opt/keycloak/data/import/realm-demo.json:ro
```

| 選項 | 說明 |
|---|---|
| `start-dev` | **開發模式**：允許 HTTP、內建 H2 資料庫、不用設 hostname。**正式環境不能用** |
| `start` | 正式模式：需要 HTTPS（或反向代理）、外部資料庫、`KC_HOSTNAME` |
| `--import-realm` | 啟動時匯入 `/opt/keycloak/data/import/*.json`，**realm 已存在時會跳過** |
| `KC_BOOTSTRAP_ADMIN_*` | Keycloak 26 的寫法（舊版叫 `KEYCLOAK_ADMIN`） |

開發模式的資料存在容器內的 H2 檔案，`docker compose down -v` 就會清掉。正式環境接 PostgreSQL 的範例：

```yaml
command: ["start", "--optimized"]
environment:
  KC_DB: postgres
  KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
  KC_DB_USERNAME: keycloak
  KC_DB_PASSWORD: secret
  KC_HOSTNAME: https://sso.example.com
  KC_PROXY_HEADERS: xforwarded
```

**方式 B：下載 zip 在本機跑**（需要 JDK 21）

```bash
# 到 https://www.keycloak.org/downloads 下載 keycloak-26.x.x.zip
unzip keycloak-26.8.1.zip && cd keycloak-26.8.1
export KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin
bin/kc.sh start-dev --http-port 8180 --import-realm      # realm JSON 要放在 data/import/
```

但注意：這樣跑的 Keycloak 連 LDAP 時要把 `realm-demo.json` 的 `connectionUrl` 從 `ldap://openldap:389` 改成 `ldap://localhost:1389`。

#### 10.2.3 管理後台導覽（照著點一遍）

1. 打開 http://localhost:8180/admin ，帳號 `admin` / `admin`。
2. 左上角 realm 下拉選單 **master → demo**。接下來的操作都在 demo realm。
3. **Clients → client-app**：
   - **Settings**：`Valid redirect URIs`（步驟 ③ 的 `redirect_uri` 必須在這裡）、`Consent required`（打開才有同意畫面）、`Client authentication`（On = confidential client）。
   - **Credentials**：看 `Client secret`，和 `client-app/application.yml` 的 `client-secret` 一樣。
   - **Client scopes**：列出這個 client 會用到哪些 scope。點 **client-app-dedicated** 看到我們加的 `groups` 與 `resource-server-audience` 兩個 mapper。
   - **Client scopes → Evaluate**：選一個使用者（alice），按 **Generated access token**，**不用真的登入就能預覽 token 長什麼樣子**。改 mapper 時很好用。
   - **Advanced → Proof Key for Code Exchange Code Challenge Method = S256**：強制 PKCE 的開關。
4. **Users**：登入過的 LDAP 使用者會出現在這裡。點 alice → 上方會寫 `Federation link: openldap`，代表它來自 LDAP。
   這裡**改不了密碼**（因為 `editMode: READ_ONLY`）。
5. **Groups**：`admins`、`users`，是 group mapper 從 LDAP 同步過來的。點 admins → Members 可以看到 alice。
6. **User federation → openldap**：
   - **Test connection / Test authentication** 按鈕：確認 Keycloak 連得到 LDAP、bind 帳密正確。
   - 右上角 **Action**：`Sync all users`（全部同步）、`Sync changed users`、`Unlink users`、`Remove imported`（清掉快取副本）。
   - 下方 **Mappers** 分頁：username / first name / last name / email / groups 五個 mapper，和 `realm-demo.json` 的 `subComponents` 一一對應。
7. **Realm settings → Tokens**：`Access Token Lifespan` 預設 5 分鐘（本專案在 JSON 設 300 秒），可以改短來觀察 refresh。
8. **Realm settings → General → Endpoints → OpenID Endpoint Configuration**：就是 Discovery 文件的連結。
9. **Sessions**：看目前誰登入、從哪個 client。可以在這裡強制登出。
10. **Events → Event configs**：把 `Save events` 打開，之後 **Events → User events** 可以看到每次登入成功 / 失敗與原因，**除錯超好用**。

#### 10.2.4 常用操作

**手動新增一個 client（例如你自己的另一個 App）**

Clients → **Create client** → Client type `OpenID Connect`、Client ID 填名字 → Next →
`Client authentication` On（有 secret）、勾 `Standard flow`（Authorization Code）→ Next →
`Valid redirect URIs` 填 `http://localhost:9090/login/oauth2/code/keycloak` 之類 → Save → 到 Credentials 分頁複製 secret。

**手動新增一個「Keycloak 本地」使用者（不經過 LDAP）**

Users → **Add user** → 填 Username → Create → **Credentials** 分頁 → Set password（把 Temporary 關掉）。
這種使用者沒有 `Federation link`，密碼存在 Keycloak 自己的資料庫。LDAP 使用者與本地使用者可以同時存在。

**匯出 realm 設定（備份 / 分享給同事）**

Realm settings → 右上角 **Action → Partial export** → 勾 `Include groups and roles`、`Include clients` → Export，會下載 `realm-export.json`。
把它放到 `keycloak/` 目錄取代 `realm-demo.json` 就能讓別人 `--import-realm` 重現你的設定。

> 為什麼不用 `kc.sh export` 指令？開發模式的 H2 資料庫一次只能被一個程序開啟，伺服器跑著的時候執行 export 會報
> `Database may be already in use`。用 Partial export 最簡單。（匯出檔不含使用者密碼與 LDAP bind 密碼，要自己補回去。）

**用 Admin REST API 操作**（自動化 / 寫腳本時用）

```bash
# 1. 用 master realm 的 admin 帳號取得管理用 token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d client_id=admin-cli -d grant_type=password -d username=admin -d password=admin \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

# 2. 列出 demo realm 的使用者
curl -s http://localhost:8180/admin/realms/demo/users -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool

# 3. 找到 LDAP federation 的 id，並觸發「同步全部使用者」
FED_ID=$(curl -s "http://localhost:8180/admin/realms/demo/components?type=org.keycloak.storage.UserStorageProvider" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
curl -s -X POST "http://localhost:8180/admin/realms/demo/user-storage/$FED_ID/sync?action=triggerFullSync" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# {"ignored":false,"added":0,"updated":2,"removed":0,"failed":0,"status":"0 imported users, 2 updated users"}
```

**本專案會用到的 Keycloak 端點**

| 端點 | 用途 | 誰在用 |
|---|---|---|
| `/realms/demo/.well-known/openid-configuration` | Discovery：列出所有端點與支援的功能 | Spring（兩個都會讀） |
| `/realms/demo/protocol/openid-connect/auth` | Authorization endpoint（步驟 ③） | 瀏覽器 |
| `/realms/demo/protocol/openid-connect/token` | Token endpoint（步驟 ⑧⑨、refresh、password grant） | client-app、scripts |
| `/realms/demo/protocol/openid-connect/certs` | JWKS：公鑰 | client-app（驗 id_token）、resource-server（驗 access_token） |
| `/realms/demo/protocol/openid-connect/userinfo` | 用 access token 換使用者資料 | client-app（可選） |
| `/realms/demo/protocol/openid-connect/logout` | End session（登出） | 瀏覽器 |
| `/admin/realms/demo/...` | Admin REST API | 管理腳本 |
| `http://localhost:8180/admin` | 管理後台 | 你 |

#### 10.2.5 除錯小技巧

- `docker compose logs -f keycloak`：本專案把 `org.keycloak.storage.ldap` 的 log 開到 debug，登入時可以看到 Keycloak 對 LDAP 做了什麼查詢。
- 登入失敗時 log 會有一行 `type="LOGIN_ERROR" ... error="invalid_user_credentials"` 或 `error="cookie_not_found"` 之類，直接告訴你原因。
- Events 功能（10.2.3 第 10 點）打開後，後台可以看每一次登入事件。

---

### 10.3 Mockoon

#### 10.3.1 Mockoon 是什麼？

Mockoon 是一個**做假 API（mock API）的工具**：你用圖形介面定義「哪個路徑、什麼條件、回什麼 JSON」，它就變成一個真的 HTTP server。
適合前後端分離時前端先開發、整合測試時模擬第三方服務，或像本專案一樣，**快速生出一個需要 Bearer token 的下游 API** 而不用寫程式。

它有三種形式，用的是**同一份定義檔**（JSON）：

| 形式 | 用途 | 安裝 |
|---|---|---|
| **Mockoon Desktop** | 圖形介面，用來設計與編輯 | macOS：`brew install --cask mockoon`；或到 https://mockoon.com/download/ 下載 |
| **Mockoon CLI** | 在終端機 / CI 執行定義檔 | `npm install -g @mockoon/cli`（需要 Node.js） |
| **Docker image** `mockoon/cli` | 同 CLI，不用裝 Node | 本專案採用 |

#### 10.3.2 安裝與啟動

**方式 A：Docker（本專案採用）**

```yaml
mockoon:
  image: mockoon/cli:9.9.0
  command: ["--data", "/data/orders-api.json", "--port", "3001"]   # 讀哪個定義檔、聽哪個 port
  ports:
    - "3100:3001"
  volumes:
    - ./mockoon/orders-api.json:/data/orders-api.json:ro
```

單獨啟動：

```bash
docker run -d --name mockoon -p 3100:3001 \
  -v "$PWD/mockoon/orders-api.json:/data/orders-api.json:ro" \
  mockoon/cli:9.9.0 --data /data/orders-api.json --port 3001
```

**方式 B：Node.js CLI**

```bash
npm install -g @mockoon/cli
mockoon-cli start --data ./mockoon/orders-api.json --port 3100
# 停止：Ctrl + C。其他選項（背景執行、多個定義檔、log 設定）見 mockoon-cli start --help
```

**方式 C：Desktop**

安裝後 **File → Open local environment** → 選 `mockoon/orders-api.json` → 左上角綠色 ▶ 按鈕啟動（會聽 JSON 裡設定的 port 3001，可以在介面上改）。

#### 10.3.3 看懂定義檔 `mockoon/orders-api.json`

一個 environment（環境）= 一組 routes（路由）；每個 route 有一個或多個 responses（回應）；每個 response 可以有 rules（規則）決定什麼時候用它。

```
Environment "Orders API"  (port 3001)
├── GET /health
│   └── Response 200 (default)              {"status":"UP"}
├── GET /orders
│   ├── Response 200  rules: header Authorization 符合 ^Bearer .+     ← 有 token
│   └── Response 401  (default)  + WWW-Authenticate header            ← 沒 token
└── GET /orders/:id                                                    ← 路徑參數
    ├── Response 200  rules: 同上；body 用 {{urlParam 'id'}} 與 {{faker ...}} 產生假資料
    └── Response 401  (default)
```

對應到 JSON 裡的重點欄位：

```jsonc
{
  "routes": [{
    "method": "get",
    "endpoint": "orders",                         // 路徑（不含開頭的 /）
    "responses": [
      {
        "statusCode": 200,
        "headers": [{ "key": "Content-Type", "value": "application/json" }],
        "body": "{ \"requested_by\": \"{{jwtPayload (header 'Authorization') 'preferred_username'}}\", ... }",
        "rules": [{                                // 符合規則才用這個回應
          "target": "header", "modifier": "Authorization",
          "operator": "regex", "value": "^Bearer .+"
        }],
        "default": false
      },
      {
        "statusCode": 401,
        "default": true                            // 沒有任何 response 的規則符合時用這個
      }
    ]
  }]
}
```

回應的 `body` 可以用**模板語法**（Handlebars 風格），常用的：

| 語法 | 效果 |
|---|---|
| `{{header 'Authorization'}}` | 讀 request header |
| `{{jwtPayload (header 'Authorization') 'sub'}}` | 把 Bearer token 的 payload 解開，取某個 claim（**不驗簽章**） |
| `{{queryParam 'page'}}` / `{{urlParam 'id'}}` | 讀 query string / 路徑參數（路徑寫成 `orders/:id`） |
| `{{body 'item'}}` | 讀 request JSON body 的欄位 |
| `{{faker 'person.firstName'}}` | 產生假資料 |
| `{{now 'yyyy-MM-dd'}}` | 現在時間 |
| `{{{stringify (jwtPayload (header 'Authorization') 'groups')}}}` | 把陣列 / 物件轉成 JSON 字串（三層大括號避免被 HTML escape） |

#### 10.3.4 動手改一個 Mock

先試試專案內建的路徑參數路由（`:id` 會被換成你給的值，`faker` 每次產生不同假資料）：

```bash
scripts/get-token.sh alice alice123          # 複製最後一行 export TOKEN=... 貼到終端機
curl -s http://localhost:3100/orders/42 -H "Authorization: Bearer $TOKEN"
# { "id": 42, "item": "Ergonomic Steel Fish", "amount": 8152, "owner": "alice" }
```

接著自己加一條新路由，例如 `POST /orders`（建立訂單）：

1. 用 Desktop 開啟 `mockoon/orders-api.json`，按路由列表上方的 **+** → method `POST`、path `orders`。
2. **Body** 填入（把 request body 的欄位回顯出來）：
   ```json
   { "created": true, "item": "{{body 'item'}}", "owner": "{{jwtPayload (header 'Authorization') 'preferred_username'}}" }
   ```
   並在 **Status** 選 `201`、**Headers** 加 `Content-Type: application/json`。
3. **Rules** 分頁加一條規則：`Header` / `Authorization` / `regex` / `^Bearer .+`；再按 **Add response** 新增一個 `401` 的回應並把它設為 **default**（旗子圖示）。
4. 儲存（Desktop 會直接寫回 JSON 檔；也可以不用 Desktop，直接照 `orders-api.json` 裡既有路由的格式複製一段來改）。
5. 重啟容器讓 CLI 重新讀檔並測試：
   ```bash
   docker compose restart mockoon
   curl -s -X POST http://localhost:3100/orders -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"item":"Mouse"}'
   ```

看 Mockoon 收到了什麼請求：`docker compose logs -f mockoon`。每筆 request 都會印成一行 JSON（`"message":"Transaction recorded"`），
裡面有 `requestMethod`、`requestPath`、`responseStatus` 等欄位。

#### 10.3.5 Mockoon 在本專案的角色與限制

- **角色**：架構圖裡的第二個 Protected Resource，展示「任何後端只要檢查 `Authorization: Bearer` 就能接進 OAuth 體系」。
- **限制**：Mockoon 的規則只能比對字串 / 正規表示式，**無法驗證 JWT 簽章**，也不會檢查 `exp`、`aud`。
  所以它適合當 mock，不適合當真正的 Resource Server；真正的驗證示範在 `resource-server/`。
- 想讓 mock 更「像真的」：可以用 `{{jwtPayload ...}}` 把 token 內容回顯，或加一條規則要求 `Authorization` 符合 JWT 的三段格式 `^Bearer [\w-]+\.[\w-]+\.[\w-]+$`。

---

## 11. 常見問題 FAQ

**Q：`docker compose up` 時 Keycloak 一直 restart？**
看 `docker compose logs keycloak`。常見原因是 port 8180 被佔用，或 OpenLDAP 尚未就緒（compose 已設定 healthcheck 等待，通常不會）。

**Q：Resource Server 啟動失敗，訊息含 `Unable to resolve the Configuration with the provided Issuer`？**
Keycloak 還沒起來。等 `curl http://localhost:8180/realms/demo` 有回應後再啟動。

**Q：登入時 Keycloak 顯示 `Invalid parameter: redirect_uri`？**
Spring 送出的 `redirect_uri` 和 Keycloak client 的 `redirectUris` 不一致。確認你是用 `http://localhost:8081`（不是 `127.0.0.1`）開網頁。

**Q：登入時顯示 `Invalid user credentials`，但帳密沒打錯？**
確認 OpenLDAP 有資料：`ldapsearch -x -H ldap://localhost:1389 -b dc=example,dc=org -D cn=admin,dc=example,dc=org -w admin "(uid=alice)"`。
若沒有，`docker compose down -v && docker compose up -d` 重建（LDIF 只在第一次建立資料庫時載入）。

**Q：我改了 `realm-demo.json`，重啟後沒生效？**
Keycloak 的 `--import-realm` 在 realm 已存在時會跳過。執行 `docker compose down -v`（刪除資料）再 `up`。

**Q：呼叫 `/api/admin/stats` 用 alice 也拿到 403？**
看 http://localhost:8081/profile 的 access token 裡有沒有 `groups: ["admins", "users"]`。如果沒有，到 Keycloak 後台 User federation → openldap → 右上角 Action → **Sync all users** 後重新登入。

**Q：Resource Server 回 `spring_authorities` 裡的 `FACTOR_BEARER` 是什麼？**
Spring Security 7 新增的「驗證因子」權限，表示這次是用 Bearer token 驗證的。可以忽略。

**Q：我想看 Spring 內部每一步的 log？**
在對應專案的 `application.yml` 打開 `org.springframework.security: DEBUG`。

---

## 12. 延伸練習

1. **新增一個 LDAP 使用者**：用 [10.1.3 節](#1013-常用操作指令都可以直接對本專案的-ldap-執行)的 `ldapadd` 新增 `carol`（不用重啟），用 carol 登入；或改 `openldap/bootstrap.ldif` 後 `docker compose down -v && docker compose up -d`。
2. **拿掉 PKCE 看看會怎樣**：註解掉 `SecurityConfig.java` 的 `withPkce()`，觀察 Keycloak 的錯誤訊息。
3. **拿掉 audience 檢查**：把 `resource-server/application.yml` 的 `audiences` 刪掉，想想看這有什麼風險。
4. **改用 realm role 做授權**：在 Keycloak 後台把 `admin` role 指派給 alice，改 `SecurityConfig.java` 用 `hasRole("admin")`。
5. **讓 Mockoon 也「驗證」token**：Mockoon 做不到驗簽章，但你可以加一條規則檢查 `{{jwtPayload (header 'Authorization') 'aud'}}` 是否包含特定值。
6. **把 Client App 改成 public client**：Keycloak 設 `publicClient: true`，Spring 設 `client-authentication-method: none`，觀察換 token 時少了什麼。
7. **觀察 refresh**：把 `accessTokenLifespan` 改成 `60`，登入後等一分鐘再呼叫 API，開 `org.springframework.security: DEBUG` 看 refresh_token 請求。

---

## 13. 清理環境

```bash
# 停掉 Spring Boot：在兩個終端機按 Ctrl + C

# 停掉容器（保留資料）
docker compose down

# 停掉容器並刪除資料（Keycloak DB、LDAP 資料都會重建）
docker compose down -v
```

---

## 14. 參考資料

- [RFC 6749 - The OAuth 2.0 Authorization Framework](https://datatracker.ietf.org/doc/html/rfc6749)
- [RFC 7636 - Proof Key for Code Exchange (PKCE)](https://datatracker.ietf.org/doc/html/rfc7636)
- [RFC 6750 - Bearer Token Usage](https://datatracker.ietf.org/doc/html/rfc6750)
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)
- [OAuth 2.0 Security Best Current Practice](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics)
- [Spring Security - OAuth2 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html)
- [Spring Security - OAuth2 Resource Server (JWT)](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Keycloak - Server Administration (User federation / LDAP)](https://www.keycloak.org/docs/latest/server_admin/#_ldap)
- [Mockoon - Templating helpers](https://mockoon.com/docs/latest/templating/overview/)
- 架構圖來源：[ByteByteGo](https://bytebytego.com/)
