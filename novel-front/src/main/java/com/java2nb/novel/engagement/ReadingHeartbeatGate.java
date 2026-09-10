package com.java2nb.novel.engagement;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ReadingHeartbeatGate {

    static final DefaultRedisScript<Long> GATE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then return 3 end
        if redis.call('HGET', KEYS[1], 'sessionHash') ~= ARGV[1] then return 3 end
        if redis.call('HGET', KEYS[1], 'bookId') ~= ARGV[2] then return 3 end
        if redis.call('HGET', KEYS[1], 'chapterId') ~= ARGV[3] then return 3 end

        local sequence = tonumber(ARGV[4])
        local lastSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence') or '0')
        if sequence <= lastSequence then return 2 end

        local now = tonumber(ARGV[5])
        local windowStart = tonumber(ARGV[6])
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', windowStart)
        if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[7]) then return 4 end
        redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', windowStart)
        if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[8]) then return 5 end

        local credited = tonumber(redis.call('GET', KEYS[4]) or '0')
        local creditSeconds = tonumber(ARGV[10])
        if credited + creditSeconds > tonumber(ARGV[9]) then return 6 end

        redis.call('HSET', KEYS[1], 'lastSequence', sequence)
        redis.call('ZADD', KEYS[2], now, ARGV[13])
        redis.call('EXPIRE', KEYS[2], tonumber(ARGV[11]))
        redis.call('ZADD', KEYS[3], now, ARGV[13])
        redis.call('EXPIRE', KEYS[3], tonumber(ARGV[11]))
        redis.call('SET', KEYS[4], credited + creditSeconds, 'EX', tonumber(ARGV[12]))
        return 1
        """, Long.class);

    private static final String PAGE_KEY_PREFIX = "reading:page:";
    private static final String SESSION_RATE_KEY_PREFIX = "reading:rate:session:";
    private static final String IP_RATE_KEY_PREFIX = "reading:rate:ip:";
    private static final String CREDIT_KEY_PREFIX = "reading:credit:";

    private final StringRedisTemplate redisTemplate;
    private final ReadingEngagementProperties properties;
    private final Timer timer;

    public ReadingHeartbeatGate(StringRedisTemplate redisTemplate, ReadingEngagementProperties properties,
        MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.timer = meterRegistry.timer("novel.reading.redis.gate");
    }

    public ReadingHeartbeatOutcome evaluate(ReadingHeartbeatCommand command) {
        try {
            return timer.record(() -> execute(command));
        } catch (RuntimeException exception) {
            return ReadingHeartbeatOutcome.REDIS_ERROR;
        }
    }

    private ReadingHeartbeatOutcome execute(ReadingHeartbeatCommand command) {
        List<String> keys = List.of(
            PAGE_KEY_PREFIX + "{" + command.pageVisitId() + "}",
            SESSION_RATE_KEY_PREFIX + command.sessionHash(),
            IP_RATE_KEY_PREFIX + command.ipHmac(),
            CREDIT_KEY_PREFIX + command.statDate() + ":" + command.sessionHash() + ":" + command.bookId() + ":"
                + command.chapterId());
        Long result = redisTemplate.execute(GATE_SCRIPT, keys,
            command.sessionHash(),
            String.valueOf(command.bookId()),
            String.valueOf(command.chapterId()),
            String.valueOf(command.sequence()),
            String.valueOf(command.nowEpochMillis()),
            String.valueOf(command.nowEpochMillis() - properties.rateWindow().toMillis()),
            String.valueOf(properties.sessionLimit()),
            String.valueOf(properties.ipLimit()),
            String.valueOf(properties.dailyCapSeconds()),
            String.valueOf(properties.creditedSeconds()),
            String.valueOf(properties.rateKeyTtl().toSeconds()),
            String.valueOf(properties.creditKeyTtl().toSeconds()),
            command.pageVisitId() + ":" + command.sequence());
        return map(result);
    }

    private ReadingHeartbeatOutcome map(Long result) {
        if (result == null) {
            return ReadingHeartbeatOutcome.REDIS_ERROR;
        }
        if (result == 1L) {
            return ReadingHeartbeatOutcome.ACCEPTED;
        }
        if (result == 2L) {
            return ReadingHeartbeatOutcome.DUPLICATE;
        }
        if (result == 3L) {
            return ReadingHeartbeatOutcome.INVALID_PAGE;
        }
        if (result == 4L) {
            return ReadingHeartbeatOutcome.SESSION_RATE_LIMITED;
        }
        if (result == 5L) {
            return ReadingHeartbeatOutcome.IP_RATE_LIMITED;
        }
        if (result == 6L) {
            return ReadingHeartbeatOutcome.DAILY_CAP_REACHED;
        }
        return ReadingHeartbeatOutcome.REDIS_ERROR;
    }
}
