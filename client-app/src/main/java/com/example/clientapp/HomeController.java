package com.example.clientapp;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 網頁入口。
 *  - GET /        公開首頁（圖中步驟 1 的「User clicks Sign in」按鈕在這裡）
 *  - GET /profile 登入後才能看：顯示 ID Token（OIDC）與 Access Token（OAuth 2.0）
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String index(@AuthenticationPrincipal OidcUser user, Model model) {
        model.addAttribute("user", user);
        return "index";
    }

    @GetMapping("/profile")
    public String profile(@AuthenticationPrincipal OidcUser user,
                          @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
                          Model model) {
        // ---- OIDC 的部分：ID Token = 「你是誰」 ----
        // OidcUser 是 Spring 在步驟 10 驗證完 id_token 後建立的使用者物件
        model.addAttribute("user", user);
        model.addAttribute("idTokenClaims", JwtDisplay.readable(user.getIdToken().getClaims()));
        model.addAttribute("idToken", JwtDisplay.split(user.getIdToken().getTokenValue()));

        // ---- OAuth 2.0 的部分：Access Token = 「可以做什麼」 ----
        // OAuth2AuthorizedClient 保存了步驟 9 拿到的 access_token / refresh_token
        model.addAttribute("accessToken", JwtDisplay.split(client.getAccessToken().getTokenValue()));
        model.addAttribute("accessTokenExpiresAt", client.getAccessToken().getExpiresAt());
        model.addAttribute("scopes", client.getAccessToken().getScopes());
        model.addAttribute("hasRefreshToken", client.getRefreshToken() != null);
        return "profile";
    }
}
