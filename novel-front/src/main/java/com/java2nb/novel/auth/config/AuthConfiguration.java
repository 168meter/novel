package com.java2nb.novel.auth.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import com.java2nb.novel.auth.password.Argon2idPasswordAlgorithmHandler;
import com.java2nb.novel.auth.password.DefaultPasswordService;
import com.java2nb.novel.auth.password.Md5PasswordAlgorithmHandler;
import com.java2nb.novel.auth.password.PasswordAlgorithmHandler;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import java.util.List;

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
    PasswordAlgorithmHandler argon2idPasswordAlgorithmHandler(Argon2PasswordEncoder encoder,
                                                              AuthPasswordProperties properties,
                                                              AuthenticationMetrics metrics) {
        return new Argon2idPasswordAlgorithmHandler(encoder, properties.getMemoryKiB(),
            properties.getIterations(), properties.getParallelism(), metrics);
    }

    @Bean
    PasswordAlgorithmHandler md5PasswordAlgorithmHandler() {
        return new Md5PasswordAlgorithmHandler();
    }

    @Bean
    PasswordService passwordService(List<PasswordAlgorithmHandler> handlers) {
        return new DefaultPasswordService(handlers);
    }

    @Bean
    AuthenticationMetrics authenticationMetrics(MeterRegistry registry) {
        return new AuthenticationMetrics(registry);
    }

    private static void requireSecret(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + environmentVariable);
        }
    }
}
