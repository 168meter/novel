package com.java2nb.novel.core.utils;

import com.java2nb.novel.core.bean.UserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import com.java2nb.novel.auth.token.TokenVersionService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtTokenUtilSecurityTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-characters-long";

    private JwtTokenUtil tokens() {
        JwtTokenUtil tokens = new JwtTokenUtil();
        ReflectionTestUtils.setField(tokens, "secret", SECRET);
        ReflectionTestUtils.setField(tokens, "expiration", 3600L);
        return tokens;
    }

    @Test void issuesMinimalClaimsAndReadsVersion() {
        UserDetails details = new UserDetails();
        details.setId(7L);
        details.setUsername("private@example.com");
        details.setNickName("Reader");
        details.setTokenVersion(3L);
        String token = tokens().generateToken(details);
        var claims = Jwts.parser().setSigningKey(SECRET).parseClaimsJws(token).getBody();
        assertThat(claims.keySet()).contains("userId", "nickName", "tokenVersion", "iat", "exp")
            .doesNotContain("username", "email", "password", "created");
        assertThat(token).doesNotContain("private@example.com");
        assertThat(tokens().getUserDetailsFromToken(token).getTokenVersion()).isEqualTo(3L);
    }

    @Test void oldSignedTokenWithoutVersionDefaultsToZero() {
        String legacy = Jwts.builder().setSubject("{\"id\":7,\"username\":\"old\",\"nickName\":\"Reader\"}")
            .setExpiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(SignatureAlgorithm.HS512, SECRET).compact();
        assertThat(tokens().getUserDetailsFromToken(legacy).getTokenVersion()).isZero();
    }

    @Test void invalidTokenIsRejected() {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtTokenUtil.class);
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThat(tokens().getUserDetailsFromToken("invalid-secret-bearing-token")).isNull();
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("invalid-secret-bearing-token"));
        } finally { logger.detachAppender(appender); logger.setLevel(oldLevel); }
    }

    @Test void oldTokensAreRevokedAndCannotBeRefreshed() {
        JwtTokenUtil tokens = tokens();
        TokenVersionService versions = mock(TokenVersionService.class);
        tokens.setTokenVersionService(versions);
        UserDetails old = new UserDetails(); old.setId(7L); old.setTokenVersion(0L);
        UserDetails fresh = new UserDetails(); fresh.setId(7L); fresh.setTokenVersion(1L);
        when(versions.isCurrent(7L, 0L)).thenReturn(false);
        when(versions.isCurrent(7L, 1L)).thenReturn(true);
        assertThat(tokens.getAuthenticatedUserDetails(tokens.generateToken(old))).isNull();
        assertThat(tokens.refreshToken(tokens.generateToken(old))).isNull();
        assertThat(tokens.getAuthenticatedUserDetails(tokens.generateToken(fresh)).getTokenVersion()).isEqualTo(1L);
    }
}
