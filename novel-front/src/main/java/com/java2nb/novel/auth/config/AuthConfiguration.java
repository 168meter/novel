package com.java2nb.novel.auth.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthPasswordProperties.class, AuthSecurityProperties.class})
public class AuthConfiguration {

    @Bean
    SecureRandom authSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    Argon2PasswordEncoder authPasswordEncoder(
        AuthPasswordProperties properties,
        AuthSecurityProperties securityProperties,
        @Value("${jwt.secret:}") String jwtSecret
    ) {
        requireSecret(jwtSecret, "JWT_SECRET");
        requireSecret(securityProperties.getHmacSecret(), "NOVEL_AUTH_HMAC_SECRET");
        properties.validate();
        return new Argon2PasswordEncoder(
            properties.getSaltLength(), properties.getHashLength(), properties.getParallelism(),
            properties.getMemoryKiB(), properties.getIterations()
        );
    }

    @Bean
    AuthMetrics authMetrics(MeterRegistry registry) {
        return new AuthMetrics(registry);
    }

    private static void requireSecret(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + environmentVariable);
        }
    }

    public record AuthMetrics(MeterRegistry registry) { }
}
