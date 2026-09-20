package com.java2nb.novel.auth.captcha;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public final class RedisCaptchaService implements CaptchaService {
    static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
        if tonumber(redis.call('GET', KEYS[3]) or '0') >= tonumber(ARGV[4]) then return 3 end
        if tonumber(redis.call('GET', KEYS[4]) or '0') >= tonumber(ARGV[5]) then return 4 end
        redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
        redis.call('DEL', KEYS[5])
        redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3]))
        if redis.call('INCR', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], 3600) end
        if redis.call('INCR', KEYS[4]) == 1 then redis.call('EXPIRE', KEYS[4], 3600) end
        return 1
        """, Long.class);

    static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
        local stored = redis.call('GET', KEYS[1])
        if not stored then return 3 end
        if stored == ARGV[1] then
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
        end
        local attempts = redis.call('INCR', KEYS[2])
        if attempts == 1 then redis.call('EXPIRE', KEYS[2], redis.call('TTL', KEYS[1])) end
        if attempts >= 5 then
            redis.call('DEL', KEYS[1], KEYS[2])
            return 4
        end
        return 2
        """, Long.class);

    static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthIdentityHasher hasher;
    private final SecureRandom random;
    private final long ttlSeconds;

    public RedisCaptchaService(StringRedisTemplate redis, AuthIdentityHasher hasher,
                               SecureRandom random, AuthSecurityProperties properties) {
        this.redis = redis;
        this.hasher = hasher;
        this.random = random;
        this.ttlSeconds = properties.getCaptchaTtl().toSeconds();
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Captcha TTL must be positive.");
    }

    @Override
    public CaptchaIssue issue(CaptchaPurpose purpose, String normalizedEmail, String clientAddress) {
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String emailKey = hasher.emailKey(purpose, normalizedEmail);
        String digest = hasher.codeDigest(purpose, normalizedEmail, code);
        Long outcome = redis.execute(ISSUE_SCRIPT,
            List.of(emailKey, cooldownKey(emailKey), hourKey(purpose, emailKey), hasher.ipKey(clientAddress),
                attemptsKey(emailKey)), digest, String.valueOf(ttlSeconds), "60", "5", "30");
        if (outcome == null) throw new IllegalStateException("Captcha issue returned no result.");
        return switch (outcome.intValue()) {
            case 1 -> new CaptchaIssue(CaptchaIssueOutcome.ISSUED, code);
            case 2 -> new CaptchaIssue(CaptchaIssueOutcome.COOLDOWN, null);
            case 3 -> new CaptchaIssue(CaptchaIssueOutcome.EMAIL_LIMITED, null);
            case 4 -> new CaptchaIssue(CaptchaIssueOutcome.IP_LIMITED, null);
            default -> throw new IllegalStateException("Unknown captcha issue result.");
        };
    }

    @Override
    public CaptchaConsumeOutcome consume(CaptchaPurpose purpose, String normalizedEmail, String code) {
        String emailKey = hasher.emailKey(purpose, normalizedEmail);
        String digest = hasher.codeDigest(purpose, normalizedEmail, code);
        Long outcome = redis.execute(CONSUME_SCRIPT, List.of(emailKey, attemptsKey(emailKey)), digest);
        if (outcome == null) throw new IllegalStateException("Captcha consume returned no result.");
        return switch (outcome.intValue()) {
            case 1 -> CaptchaConsumeOutcome.CONSUMED;
            case 2 -> CaptchaConsumeOutcome.INVALID;
            case 3 -> CaptchaConsumeOutcome.EXPIRED;
            case 4 -> CaptchaConsumeOutcome.TOO_MANY_ATTEMPTS;
            default -> throw new IllegalStateException("Unknown captcha consume result.");
        };
    }

    @Override
    public void revoke(CaptchaPurpose purpose, String normalizedEmail, String code) {
        String emailKey = hasher.emailKey(purpose, normalizedEmail);
        Long result = redis.execute(REVOKE_SCRIPT,
            List.of(emailKey, cooldownKey(emailKey), attemptsKey(emailKey)),
            hasher.codeDigest(purpose, normalizedEmail, code));
        if (result == null) throw new IllegalStateException("Captcha revoke returned no result.");
    }

    private static String suffix(String emailKey) { return emailKey.substring(emailKey.lastIndexOf(':') + 1); }
    private static String cooldownKey(String emailKey) {
        return "auth:captcha:cooldown:" + suffix(emailKey);
    }
    private static String hourKey(CaptchaPurpose purpose, String emailKey) {
        return "auth:captcha:hour:" + purpose.keyPart() + ":" + suffix(emailKey);
    }
    private static String attemptsKey(String emailKey) {
        return "auth:captcha:attempts:" + suffix(emailKey);
    }
}
