package com.java2nb.novel.core.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.bean.UserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.java2nb.novel.auth.token.TokenVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Date;

/**
 * @author 11797
 */
@Component
@Slf4j
public class JwtTokenUtil {

    private static final ObjectMapper JSON = new ObjectMapper();
    private TokenVersionService versions;
    @Autowired public void setTokenVersionService(TokenVersionService versions) { this.versions = versions; }
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 根据负责生成JWT的token
     */
    public UserDetails getUserDetailsFromToken(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
            UserDetails details;
            if (claims.get("userId") instanceof Number id) {
                details = new UserDetails();
                details.setId(id.longValue());
                details.setNickName(claims.get("nickName", String.class));
            } else {
                details = JSON.readValue(claims.getSubject(), UserDetails.class);
            }
            Object version = claims.get("tokenVersion");
            if (version != null && !(version instanceof Number)) return null;
            details.setTokenVersion(version == null ? 0L : ((Number) version).longValue());
            return details.getId() != null && details.getId() > 0 && details.getTokenVersion() >= 0
                ? details : null;
        } catch (Exception invalid) {
            // Never log a bearer token, claim values, or exception message.
            log.debug("JWT rejected: category={}, requestId={}", invalid.getClass().getSimpleName(),
                MDC.get("requestId"));
            return null;
        }
    }


    /**
     * 判断token是否已经失效
     */
    public String generateToken(UserDetails userDetails) {
        if (userDetails == null || userDetails.getId() == null || userDetails.getTokenVersion() == null) {
            throw new IllegalArgumentException("Complete authenticated identity required");
        }
        return Jwts.builder()
            .claim("userId", userDetails.getId())
            .claim("nickName", userDetails.getNickName())
            .claim("tokenVersion", userDetails.getTokenVersion())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + expiration * 1000))
            .signWith(SignatureAlgorithm.HS512, secret).compact();
    }

    /**
     * 判断token是否可以被刷新
     */
    public boolean canRefresh(String token) {
        return getAuthenticatedUserDetails(token) != null;
    }

    /**
     * 刷新token
     */
    public String refreshToken(String token) {
        UserDetails details = getAuthenticatedUserDetails(token);
        return details == null ? null : generateToken(details);
    }

    public UserDetails getAuthenticatedUserDetails(String token) {
        UserDetails details = getUserDetailsFromToken(token);
        if (details == null || versions == null) return null;
        try {
            return versions.isCurrent(details.getId(), details.getTokenVersion()) ? details : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }


}
