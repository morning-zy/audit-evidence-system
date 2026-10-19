package com.maning.audit.util;

import com.maning.audit.config.AuditProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类（jjwt 0.12.x 新 API）
 *
 * <p>0.12.x 与 0.9.x 的差异（网上老教程多是 0.9 的写法，照抄会编译不过）：
 * ① Jwts.parser() -> Jwts.parser().verifyWith(key).build()
 * ② setSubject/setExpiration -> subject()/expiration()
 * ③ 签名需要 SecretKey 对象，不能直接传字符串
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final AuditProperties props;

    /** HS256 要求密钥至少 256 bit（32 字节），配置里的 secret 必须够长 */
    private SecretKey key() {
        return Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token
     *
     * @param userId   用户 ID
     * @param username 登录名
     * @param realName 真实姓名
     * @param role     角色
     */
    public String generate(Long userId, String username, String realName, String role) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("realName", realName);
        claims.put("role", role);

        Date now = new Date();
        Date expire = new Date(now.getTime() + props.getJwt().getExpireMinutes() * 60 * 1000);

        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expire)
                .signWith(key())
                .compact();
    }

    /**
     * 解析 token，失败返回 null。
     * 这里不抛异常是因为"解析失败"是鉴权流程的正常分支，交给拦截器决定响应什么。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("token 已过期");
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 单独判断是否过期，用于给前端区分"过期"和"无效" */
    public boolean isExpired(String token) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
