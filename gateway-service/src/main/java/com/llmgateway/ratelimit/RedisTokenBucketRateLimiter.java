package com.llmgateway.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final long WINDOW_SECONDS = 60;

    private static final String CHECK_AND_INCREMENT_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local current = tonumber(redis.call('GET', key) or '0')
            if current >= limit then
                local ttl = redis.call('TTL', key)
                return {0, limit - current, ttl}
            end
            current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            local ttl = redis.call('TTL', key)
            return {1, limit - current, ttl}
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> checkScript;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.checkScript = new DefaultRedisScript<>(CHECK_AND_INCREMENT_SCRIPT, List.class);
    }

    @Override
    public RateLimitResult checkRequestLimit(UUID apiKeyId, int maxRequestsPerMinute) {
        String key = "ratelimit:req:" + apiKeyId;

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                checkScript,
                List.of(key),
                String.valueOf(maxRequestsPerMinute),
                String.valueOf(WINDOW_SECONDS)
        );

        if (result == null) {
            return new RateLimitResult(true, maxRequestsPerMinute, WINDOW_SECONDS);
        }

        boolean allowed = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        long resetIn = result.get(2);

        return new RateLimitResult(allowed, Math.max(0, remaining), resetIn);
    }

    @Override
    public void recordTokenUsage(UUID apiKeyId, int tokensUsed) {
        String key = "ratelimit:tok:" + apiKeyId;
        redisTemplate.opsForValue().increment(key, tokensUsed);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public RateLimitInfo getRateLimitInfo(UUID apiKeyId, int maxRpm, int maxTpm) {
        String reqKey = "ratelimit:req:" + apiKeyId;
        String tokKey = "ratelimit:tok:" + apiKeyId;

        String reqCountStr = redisTemplate.opsForValue().get(reqKey);
        String tokCountStr = redisTemplate.opsForValue().get(tokKey);

        int reqCount = reqCountStr != null ? Integer.parseInt(reqCountStr) : 0;
        int tokCount = tokCountStr != null ? Integer.parseInt(tokCountStr) : 0;

        Long reqTtl = redisTemplate.getExpire(reqKey, TimeUnit.SECONDS);
        Long tokTtl = redisTemplate.getExpire(tokKey, TimeUnit.SECONDS);

        return new RateLimitInfo(
                maxRpm, Math.max(0, maxRpm - reqCount),
                reqTtl != null && reqTtl > 0 ? reqTtl : WINDOW_SECONDS,
                maxTpm, Math.max(0, maxTpm - tokCount),
                tokTtl != null && tokTtl > 0 ? tokTtl : WINDOW_SECONDS
        );
    }
}
