package com.prism.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * 控制台 JWT 工具（HS256）。
 * 网关校验签名后将 uid/username 放入 X-User-Id / X-Username 透传给下游，
 * 下游服务信任来自网关的这两个内网头。
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    public static SecretKey toKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String createToken(String secret, long userId, String username, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of("uid", userId, "username", username))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(toKey(secret))
                .compact();
    }

    /**
     * @return 解析后的 Claims；签名不合法或已过期抛出 JwtException / ExpiredJwtException
     */
    public static Claims parse(String secret, String token) {
        return Jwts.parser()
                .verifyWith(toKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
