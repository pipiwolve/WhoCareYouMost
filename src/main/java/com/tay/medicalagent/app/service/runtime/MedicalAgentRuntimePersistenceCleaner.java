package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.store.Store;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 清理 Runtime 持久化状态，包括共享 Store 与 Redis Checkpoint。
 */
@Component
public class MedicalAgentRuntimePersistenceCleaner {

    private static final int DEFAULT_SCAN_COUNT = 200;

    private final Store userProfileMemoryStore;
    private final MedicalPersistenceProperties persistenceProperties;
    private final StringRedisTemplate redisTemplate;

    public MedicalAgentRuntimePersistenceCleaner(
            Store userProfileMemoryStore,
            MedicalPersistenceProperties persistenceProperties,
            StringRedisTemplate redisTemplate
    ) {
        this.userProfileMemoryStore = userProfileMemoryStore;
        this.persistenceProperties = persistenceProperties;
        this.redisTemplate = redisTemplate;
    }

    public void clearAll() {
        userProfileMemoryStore.clear();
        if (!persistenceProperties.usesRedis()) {
            return;
        }

        deletePattern("graph:thread:meta:*");
        deletePattern("graph:thread:reverse:*");
        deletePattern("graph:checkpoint:content:*");
        deletePattern("graph:checkpoint:lock:*");
        deletePattern(checkpointIndexPattern());
    }

    private void deletePattern(String pattern) {
        List<String> keys = redisTemplate.execute((RedisConnection connection) -> scanKeys(connection, pattern));
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private List<String> scanKeys(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(DEFAULT_SCAN_COUNT).build();
        List<String> keys = new ArrayList<>();
        try (var cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to scan redis keys for runtime cleanup", ex);
        }
        return keys;
    }

    private String checkpointIndexPattern() {
        String prefix = persistenceProperties.getRedis().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "medical-agent:";
        }
        return (prefix.endsWith(":") ? prefix : prefix + ":") + "checkpoint-index:*";
    }
}
