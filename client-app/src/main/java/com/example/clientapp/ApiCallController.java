package com.example.clientapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 對應架構圖最下方的「App call with Bearer token -> Protected Resource」。
 * Client App 拿步驟 9 取得的 access_token，放在 Authorization header 去呼叫 API。
 */
@Controller
public class ApiCallController {

    private static final Logger log = LoggerFactory.getLogger(ApiCallController.class);

    private final RestClient restClient;
    private final Map<String, String> targets = new LinkedHashMap<>();

    public ApiCallController(RestClient.Builder builder,
                             @Value("${app.resource-server-url}") String resourceServerUrl,
                             @Value("${app.mockoon-url}") String mockoonUrl) {
        this.restClient = builder.build();
        // 可以呼叫的目標清單（key 會出現在網址 /call/{target}）
        targets.put("rs-public", resourceServerUrl + "/api/public");
        targets.put("rs-profile", resourceServerUrl + "/api/profile");
        targets.put("rs-admin", resourceServerUrl + "/api/admin/stats");
        targets.put("mockoon-orders", mockoonUrl + "/orders");
    }

    /**
     * /call/{target}            -> 帶 Bearer token 呼叫
     * /call/{target}/no-token   -> 故意不帶 token，觀察 401
     */
    @GetMapping({"/call/{target}", "/call/{target}/{mode}"})
    public String call(@PathVariable String target,
                       @PathVariable(required = false) String mode,
                       @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
                       Model model) {

        String url = targets.get(target);
        if (url == null) {
            model.addAttribute("error", "未知的目標: " + target);
            return "api";
        }
        boolean withToken = !"no-token".equals(mode);
        String accessToken = client.getAccessToken().getTokenValue();

        model.addAttribute("target", target);
        model.addAttribute("url", url);
        model.addAttribute("withToken", withToken);
        model.addAttribute("targets", targets.keySet());

        try {
            var spec = restClient.get().uri(url);
            if (withToken) {
                // 這一行就是 OAuth 2.0 的精髓：Authorization: Bearer <access_token>
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            }
            var response = spec.retrieve().toEntity(String.class);
            log.debug("呼叫 {} -> HTTP {}", url, response.getStatusCode().value());
            model.addAttribute("status", response.getStatusCode().value());
            model.addAttribute("body", JwtDisplay.pretty(response.getBody()));
        } catch (RestClientResponseException e) {
            // 4xx / 5xx：把狀態碼與內容顯示出來（401 = 沒有/無效 token，403 = 有 token 但權限不足）
            model.addAttribute("status", e.getStatusCode().value());
            model.addAttribute("body", JwtDisplay.pretty(e.getResponseBodyAsString()));
            model.addAttribute("wwwAuthenticate", e.getResponseHeaders() == null ? null
                : e.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        } catch (ResourceAccessException e) {
            model.addAttribute("error", "連不到 " + url + "，請確認該服務已啟動。(" + e.getMessage() + ")");
        }
        return "api";
    }
}
