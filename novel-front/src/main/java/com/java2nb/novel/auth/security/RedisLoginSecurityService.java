package com.java2nb.novel.auth.security;

import com.java2nb.novel.auth.captcha.AuthIdentityHasher;
import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public final class RedisLoginSecurityService implements LoginSecurityService {
    static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
        local requests = redis.call('INCR', KEYS[1])
        if requests == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
        if requests > tonumber(ARGV[2]) then return 4 end
        if tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[3]) then return 2 end
        if tonumber(redis.call('GET', KEYS[3]) or '0') >= tonumber(ARGV[4]) then return 3 end
        return 1
        """, Long.class);
    static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
        local account = redis.call('INCR', KEYS[1])
        if account == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
        local ip = redis.call('INCR', KEYS[2])
        if ip == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[1])) end
        return account
        """, Long.class);
    static final DefaultRedisScript<Long> CONSUME_IMAGE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthIdentityHasher hasher;
    private final long windowSeconds;
    private final long rateSeconds;
    private final int accountLimit;
    private final int ipThreshold;
    private final int rateLimit;
    private final Duration imageTtl;

    public RedisLoginSecurityService(StringRedisTemplate redis, AuthIdentityHasher hasher,
                                     AuthSecurityProperties properties) {
        this.redis = redis;
        this.hasher = hasher;
        this.windowSeconds = positive(properties.getLoginWindow(), "Login window");
        this.rateSeconds = positive(properties.getLoginRateWindow(), "Login rate window");
        this.imageTtl = properties.getImageCaptchaTtl();
        if (imageTtl == null || imageTtl.isZero() || imageTtl.isNegative())
            throw new IllegalArgumentException("Image captcha TTL must be positive");
        this.accountLimit = properties.getAccountFailureLimit();
        this.ipThreshold = properties.getIpCaptchaThreshold();
        this.rateLimit = properties.getIpRequestLimit();
        if (accountLimit <= 0 || ipThreshold <= 0 || rateLimit <= 0)
            throw new IllegalArgumentException("Login security thresholds must be positive");
    }

    @Override public LoginSecurityDecision check(String account, String clientAddress) {
        try {
            Long result = redis.execute(CHECK_SCRIPT, List.of(hasher.loginRateKey(clientAddress),
                hasher.loginAccountKey(account), hasher.loginIpKey(clientAddress)),
                String.valueOf(rateSeconds), String.valueOf(rateLimit), String.valueOf(accountLimit),
                String.valueOf(ipThreshold));
            if (result == null) return LoginSecurityDecision.DEPENDENCY_ERROR;
            return switch (result.intValue()) {
                case 1 -> LoginSecurityDecision.ALLOWED;
                case 2 -> LoginSecurityDecision.ACCOUNT_LIMITED;
                case 3 -> LoginSecurityDecision.CAPTCHA_REQUIRED;
                case 4 -> LoginSecurityDecision.RATE_LIMITED;
                default -> LoginSecurityDecision.DEPENDENCY_ERROR;
            };
        } catch (RuntimeException unavailable) {
            return LoginSecurityDecision.DEPENDENCY_ERROR;
        }
    }

    @Override public void recordFailure(String account, String clientAddress) {
        Long result = redis.execute(FAILURE_SCRIPT,
            List.of(hasher.loginAccountKey(account), hasher.loginIpKey(clientAddress)),
            String.valueOf(windowSeconds));
        if (result == null) throw new IllegalStateException("Login failure record returned no result");
    }

    @Override public void clearAccountFailures(String account) {
        redis.delete(hasher.loginAccountKey(account));
    }

    @Override public void storeImageCaptcha(String clientAddress, String code) {
        redis.opsForValue().set(hasher.imageCaptchaKey(clientAddress),
            hasher.imageCaptchaDigest(clientAddress, code), imageTtl);
    }

    @Override public boolean consumeImageCaptcha(String clientAddress, String code) {
        try {
            Long result = redis.execute(CONSUME_IMAGE_SCRIPT, List.of(hasher.imageCaptchaKey(clientAddress)),
                hasher.imageCaptchaDigest(clientAddress, code));
            return result != null && result == 1L;
        } catch (RuntimeException invalidOrUnavailable) {
            return false;
        }
    }

    @Override public long retryAfterSeconds() { return rateSeconds; }

    private static long positive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.toSeconds() < 1)
            throw new IllegalArgumentException(name + " must be positive");
        return duration.toSeconds();
    }
}
