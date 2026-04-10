package com.tay.medicalagent.app.repository.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/**
 * Redis JSON 读写辅助基类。
 */
public abstract class RedisJsonValueSupport {

    private static final int DEFAULT_SCAN_COUNT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MedicalPersistenceProperties persistenceProperties;

    protected RedisJsonValueSupport(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MedicalPersistenceProperties persistenceProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.persistenceProperties = persistenceProperties;
    }

    protected StringRedisTemplate redisTemplate() {
        return redisTemplate;
    }

    protected String key(String namespace, String id) {
        return redisPrefix() + namespace + ":" + id.trim();
    }

    protected String pattern(String namespace) {
        return redisPrefix() + namespace + ":*";
    }

    protected void writeJson(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, serialize(value));
        expire(key, ttl);
    }

    protected <T> Optional<T> readJson(String key, Class<T> type, Duration ttl) {
        String payload = redisTemplate.opsForValue().get(key);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        expire(key, ttl);
        return Optional.of(deserialize(payload, type));
    }

    protected String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize redis payload", ex);
        }
    }

    protected <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize redis payload", ex);
        }
    }

    protected void expire(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.expire(key, ttl);
    }

    protected void deleteByPattern(String pattern) {
        Set<String> keys = scanKeys(pattern);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
    }

    protected Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisConnection connection) -> scanKeys(connection, pattern));
    }

    private String redisPrefix() {
        String configuredPrefix = persistenceProperties.getRedis().getKeyPrefix();
        if (configuredPrefix == null || configuredPrefix.isBlank()) {
            return "medical-agent:";
        }
        return configuredPrefix.endsWith(":") ? configuredPrefix : configuredPrefix + ":";
    }

    private Set<String> scanKeys(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(DEFAULT_SCAN_COUNT)
                .build();
        Set<String> keys = new java.util.LinkedHashSet<>();
        try (var cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to scan redis keys", ex);
        }
        return keys;
    }
}
