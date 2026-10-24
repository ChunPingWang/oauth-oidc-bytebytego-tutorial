package com.example.clientapp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 純教學用途的小工具：把 JWT 拆成 header / payload 給網頁顯示。
 * 注意：這裡「只是 Base64 解碼」，完全沒有驗證簽章。
 * 真正的驗證在 Spring Security（id_token）與 resource-server（access_token）裡完成。
 */
public final class JwtDisplay {

    private JwtDisplay() {
    }

    public record Parts(String raw, String header, String payload, String signature) {
    }

    public static Parts split(String jwt) {
        if (jwt == null || jwt.chars().filter(c -> c == '.').count() != 2) {
            return new Parts(jwt, "(not a JWT)", "(not a JWT)", "");
        }
        String[] p = jwt.split("\\.");
        return new Parts(jwt, pretty(decode(p[0])), pretty(decode(p[1])), p[2]);
    }

    /** 把 claims Map 轉成好讀的字串（時間戳記順便轉成可讀格式）。 */
    public static Map<String, String> readable(Map<String, Object> claims) {
        Map<String, String> out = new LinkedHashMap<>();
        claims.forEach((k, v) -> {
            String s = String.valueOf(v);
            if (v instanceof Instant i) {
                s = i.toString();
            } else if ((k.equals("exp") || k.equals("iat") || k.equals("auth_time")) && v instanceof Number n) {
                s = n + " (" + Instant.ofEpochSecond(n.longValue()) + ")";
            }
            out.put(k, s);
        });
        return out;
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    /** 極簡 JSON 排版器（只為了顯示，不處理所有邊界情況）。 */
    public static String pretty(String json) {
        if (json == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> { inString = true; sb.append(c); }
                case '{', '[' -> { sb.append(c).append('\n'); indent++; sb.append("  ".repeat(indent)); }
                case '}', ']' -> { sb.append('\n'); indent--; sb.append("  ".repeat(indent)).append(c); }
                case ',' -> sb.append(c).append('\n').append("  ".repeat(indent));
                case ':' -> sb.append(": ");
                case ' ', '\n', '\t', '\r' -> { }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
