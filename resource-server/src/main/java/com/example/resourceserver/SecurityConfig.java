package com.example.resourceserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Protected Resource 的安全設定（架構圖右下角 "Validate access token"）。
 *
 * 流程：
 *  1. 讀取 Authorization: Bearer <jwt>
 *  2. 用 Keycloak JWKS 的公鑰驗證簽章、檢查 iss / exp / aud（Spring 自動處理）
 *  3. 把 token 裡的 groups / realm roles 轉成 Spring 的 GrantedAuthority（這裡自訂）
 *  4. 依網址規則判斷授權
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // API 不用 session、不用 CSRF（每個請求都自帶 token）
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public").permitAll()
                // 只有 token 的 groups claim 含 "admins" 才可以
                .requestMatchers("/api/admin/**").hasAuthority("GROUP_admins")
                // 其他 /api/** 只要 token 有效即可
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    /**
     * 把 JWT 的 claims 轉成權限：
     *   groups: ["admins","users"]              -> GROUP_admins, GROUP_users
     *   realm_access.roles: ["user", ...]       -> ROLE_user, ...
     *   scope: "openid profile email"           -> SCOPE_openid, SCOPE_profile, SCOPE_email（Spring 預設行為）
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractAuthorities);
        // 讓 Authentication.getName() 回傳 preferred_username 而不是 sub（UUID）
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups != null) {
            groups.forEach(g -> authorities.add(new SimpleGrantedAuthority("GROUP_" + g)));
        }

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            ((List<String>) roles).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }

        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String s : scope.split(" ")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return authorities;
    }
}
