package com.example.clientapp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * 純教學用途：把 Spring 產生的 Authorization Request（圖中步驟 3）印出來，
 * 讓你可以在 console 看到 state、code_challenge 等參數。
 */
public class LoggingAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthorizationRequestResolver.class);

    private final OAuth2AuthorizationRequestResolver delegate;

    public LoggingAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return logIfPresent(delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return logIfPresent(delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest logIfPresent(OAuth2AuthorizationRequest authRequest) {
        if (authRequest != null) {
            log.info("""

                ===== [步驟 3] Authorization Request（瀏覽器將被 302 導向這個網址）=====
                state          = {}
                code_challenge = {}
                code_verifier  = {}   <-- 只留在 Client App 的 session，不會送出去
                完整網址:
                {}
                ==================================================================""",
                authRequest.getState(),
                authRequest.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE),
                authRequest.getAttribute(PkceParameterNames.CODE_VERIFIER),
                authRequest.getAuthorizationRequestUri());
        }
        return authRequest;
    }
}
