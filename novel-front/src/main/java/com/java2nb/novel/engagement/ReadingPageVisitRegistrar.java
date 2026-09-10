package com.java2nb.novel.engagement;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReadingPageVisitRegistrar {

    private static final String PAGE_KEY_PREFIX = "reading:page:";
    private static final DefaultRedisScript<Long> PAGE_REGISTRATION_SCRIPT = new DefaultRedisScript<>("""
        redis.call('HSET', KEYS[1],
          'sessionHash', ARGV[1],
          'bookId', ARGV[2],
          'chapterId', ARGV[3],
          'lastSequence', '0')
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
        return 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ReadingIdentityHasher readingIdentityHasher;
    private final ReadingEngagementProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong registrationFailures = new AtomicLong();

    public ReadingPageVisitRegistrar(StringRedisTemplate redisTemplate, ReadingIdentityHasher readingIdentityHasher,
        ReadingEngagementProperties properties, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.readingIdentityHasher = readingIdentityHasher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public Optional<String> register(String userMark, Long bookId, Long chapterId) {
        try {
            String pageVisitId = UUID.randomUUID().toString().replace("-", "");
            Long result = redisTemplate.execute(PAGE_REGISTRATION_SCRIPT,
                Collections.singletonList(PAGE_KEY_PREFIX + "{" + pageVisitId + "}"),
                readingIdentityHasher.sessionHash(userMark), String.valueOf(bookId), String.valueOf(chapterId),
                String.valueOf(properties.pageTtl().toSeconds()));
            if (result == null) {
                return registrationError();
            }
            meterRegistry.counter("novel.reading.page.registration", "result", "success").increment();
            return Optional.of(pageVisitId);
        } catch (RuntimeException exception) {
            return registrationError();
        }
    }

    private Optional<String> registrationError() {
        meterRegistry.counter("novel.reading.page.registration", "result", "error").increment();
        long failureCount = registrationFailures.incrementAndGet();
        if (failureCount == 1 || failureCount % 1_000 == 0) {
            log.warn("Unable to register readable chapter page visit (failure count: {})", failureCount);
        }
        return Optional.empty();
    }
}
