package com.backend.backend.AOP;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RateLimiter {
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or n == tonumber(ARGV[2]) or redis.call('TTL', KEYS[1]) == -1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    public long hit(String key, long ttlSeconds, long refreshAt) {
        return redis.execute(HIT, List.of(key), String.valueOf(ttlSeconds), String.valueOf(refreshAt));
    }

    private static final DefaultRedisScript<Long> REFUND = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('DECR', KEYS[1])
            end
            return 0
            """, Long.class);

    public void refund(String key) {
        redis.execute(REFUND, List.of(key));
    }

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    public boolean compareAndDelete(String key, String expected) {
        Long r = redis.execute(COMPARE_AND_DELETE, List.of(key), expected);
        return r != null && r == 1L;
    }

    private static final DefaultRedisScript<Long> SET_EX_AND_DEL = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            for i = 2, #KEYS do
                redis.call('DEL', KEYS[i])
            end
            return 1
            """, Long.class);

    public void setExAndDelete(String setKey, String value, Duration ttl, String... deleteKeys) {
        List<String> keys = new ArrayList<>();
        keys.add(setKey);
        keys.addAll(Arrays.asList(deleteKeys));
        redis.execute(SET_EX_AND_DEL, keys, value, String.valueOf(ttl.toSeconds()));
    }

    public String key(String expression, ProceedingJoinPoint pjp) {
        String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
        Object[] args = pjp.getArgs();
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < names.length; i++) {
            context.setVariable(names[i], args[i]);
        }
        return PARSER.parseExpression(expression).getValue(context, String.class);
    }
}
