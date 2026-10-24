package com.example.resourceserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 架構圖中的 Protected Resource。
 * 進到這裡的 token 都已經被 Spring Security 驗證過簽章、iss、exp、aud。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    /** 不需要 token。 */
    @GetMapping("/public")
    public Map<String, Object> publicEndpoint() {
        return Map.of(
            "message", "這是公開端點，不需要 access token",
            "service", "resource-server"
        );
    }

    /** 需要有效 token。回傳 Resource Server「看到」的使用者資訊（全部來自 token，沒有查任何資料庫）。 */
    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        log.debug("收到 token: sub={} username={} aud={}", jwt.getSubject(),
            jwt.getClaimAsString("preferred_username"), jwt.getAudience());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "access token 簽章驗證通過，這些資料全部讀自 token 本身");
        body.put("subject", jwt.getSubject());
        body.put("username", jwt.getClaimAsString("preferred_username"));
        body.put("email", jwt.getClaimAsString("email"));
        body.put("groups", jwt.getClaimAsStringList("groups"));
        body.put("issuer", jwt.getIssuer().toString());
        body.put("audience", jwt.getAudience());
        body.put("authorized_party(azp)", jwt.getClaimAsString("azp"));
        body.put("issued_at", jwt.getIssuedAt());
        body.put("expires_at", jwt.getExpiresAt());
        body.put("spring_authorities", authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).sorted().toList());
        return body;
    }

    /** 需要 token 的 groups 含 "admins"（alice 可以，bob 會得到 403）。 */
    @GetMapping("/admin/stats")
    public Map<String, Object> adminStats(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
            "message", "只有 admins 群組看得到這個",
            "requested_by", jwt.getClaimAsString("preferred_username"),
            "total_users", 2,
            "total_orders", 3
        );
    }
}
