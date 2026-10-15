package com.prism.user.auth;

import com.prism.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 创建/解析往返（网关与 user 服务共用同一 secret 与工具类）
 */
class JwtUtilTest {

    private static final String SECRET = "prism-jwt-secret-please-change-in-prod-0123456789";

    @Test
    @DisplayName("createToken → parse 往返：uid/username 一致")
    void roundTripShouldKeepClaims() {
        String token = JwtUtil.createToken(SECRET, 42L, "admin", 3600_000L);
        Claims claims = JwtUtil.parse(SECRET, token);
        assertEquals(42L, ((Number) claims.get("uid")).longValue());
        assertEquals("admin", claims.get("username"));
        assertEquals("42", claims.getSubject());
    }

    @Test
    @DisplayName("过期 token 解析抛 ExpiredJwtException")
    void expiredTokenShouldBeRejected() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", -1000L);
        assertThrows(ExpiredJwtException.class, () -> JwtUtil.parse(SECRET, token));
    }

    @Test
    @DisplayName("密钥不匹配的 token 解析抛 JwtException")
    void wrongSecretShouldBeRejected() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", 3600_000L);
        assertThrows(JwtException.class,
                () -> JwtUtil.parse("another-secret-0123456789-0123456789-0123456789", token));
    }

    @Test
    @DisplayName("篡改 payload 的 token 解析抛 JwtException")
    void tamperedTokenShouldBeRejected() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", 3600_000L);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 10);
        assertThrows(JwtException.class, () -> JwtUtil.parse(SECRET, tampered));
    }
}
