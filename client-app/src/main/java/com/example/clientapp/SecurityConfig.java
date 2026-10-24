package com.example.clientapp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Client App 的安全設定。
 *
 * 對應架構圖：
 *  - 步驟 1~2：未登入的人打開受保護頁面 -> Spring Security 回 302 導向 Keycloak
 *  - 步驟 3  ：導向的網址由 {@link OAuth2AuthorizationRequestResolver} 產生，這裡加上 PKCE
 *  - 步驟 6~7：Keycloak 把瀏覽器導回 /login/oauth2/code/keycloak，Spring 驗證 state
 *  - 步驟 8~10：Spring 用 code + code_verifier 換 token、驗證 id_token、建立 session
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // 首頁與靜態資源不用登入
                .requestMatchers("/", "/css/**", "/error").permitAll()
                // 其他頁面都要登入（觸發步驟 1 -> 2 的 302 redirect）
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(endpoint -> endpoint
                    // 自訂 Authorization Request 產生器：加入 PKCE + 印出 log
                    .authorizationRequestResolver(pkceResolver(clientRegistrationRepository))
                )
                // 登入成功後回到 /profile
                .defaultSuccessUrl("/profile", true)
            )
            .logout(logout -> logout
                // 登出時連 Keycloak 的 session 一起登出 (OIDC RP-Initiated Logout)
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
            );
        return http.build();
    }

    /**
     * 產生「步驟 3：Authorization request」的網址。
     * OAuth2AuthorizationRequestCustomizers.withPkce() 會：
     *   1. 產生隨機的 code_verifier（存在 session）
     *   2. 計算 code_challenge = BASE64URL(SHA256(code_verifier))
     *   3. 把 code_challenge 與 code_challenge_method=S256 放進 authorization request
     * 之後在步驟 8 換 token 時，Spring 會自動帶上 code_verifier。
     */
    private OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repo) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
            repo, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        // 包一層，把最後產生的網址印到 console，方便初學者觀察
        return new LoggingAuthorizationRequestResolver(resolver);
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repo) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(repo);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
