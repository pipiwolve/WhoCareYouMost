package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * 官方 RedisSaver 的项目包装器。
 * <p>
 * 追加逻辑线程索引与 TTL 刷新，避免 Redis Checkpoint 无限增长。
 */
public class MedicalRedisCheckpointSaver implements BaseCheckpointSaver {

    private static final String THREAD_META_PREFIX = "graph:thread:meta:";
    private static final String THREAD_REVERSE_PREFIX = "graph:thread:reverse:";
    private static final String CHECKPOINT_CONTENT_PREFIX = "graph:checkpoint:content:";
    private static final String CHECKPOINT_LOCK_PREFIX = "graph:checkpoint:lock:";
    private static final String INDEX_NAMESPACE = "checkpoint-index";
    private static final String META_THREAD_ID_FIELD = "thread_id";

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MedicalPersistenceProperties persistenceProperties;
    private final BaseCheckpointSaver delegate;
    private final Duration ttl;

    public MedicalRedisCheckpointSaver(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            MedicalPersistenceProperties persistenceProperties,
            BaseCheckpointSaver delegate,
            Duration ttl
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.persistenceProperties = Objects.requireNonNull(persistenceProperties, "persistenceProperties");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttl = ttl;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig runnableConfig) {
        Collection<Checkpoint> checkpoints = delegate.list(runnableConfig);
        refreshCheckpointKeys(runnableConfig);
        return checkpoints;
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig runnableConfig) {
        Optional<Checkpoint> checkpoint = delegate.get(runnableConfig);
        refreshCheckpointKeys(runnableConfig);
        return checkpoint;
    }

    @Override
    public RunnableConfig put(RunnableConfig runnableConfig, Checkpoint checkpoint) throws Exception {
        RunnableConfig updatedConfig = delegate.put(runnableConfig, checkpoint);
        refreshCheckpointKeys(updatedConfig);
        return updatedConfig;
    }

    @Override
    public Tag release(RunnableConfig runnableConfig) throws Exception {
        Tag tag = delegate.release(runnableConfig);
        refreshCheckpointKeys(runnableConfig);
        return tag;
    }

    private void refreshCheckpointKeys(RunnableConfig runnableConfig) {
        String logicalThreadName = runnableConfig == null
                ? null
                : runnableConfig.threadId().map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
        if (logicalThreadName == null) {
            return;
        }

        expire(THREAD_META_PREFIX + logicalThreadName);
        expire(CHECKPOINT_LOCK_PREFIX + logicalThreadName);

        String actualThreadId = resolveActualThreadId(logicalThreadName);
        if (actualThreadId == null || actualThreadId.isBlank()) {
            return;
        }

        redisTemplate.opsForValue().set(indexKey(logicalThreadName), actualThreadId, ttl);
        expire(THREAD_REVERSE_PREFIX + actualThreadId);
        expire(CHECKPOINT_CONTENT_PREFIX + actualThreadId);
        expire(indexKey(logicalThreadName));
    }

    private String resolveActualThreadId(String logicalThreadName) {
        RMap<String, String> meta = redissonClient.getMap(THREAD_META_PREFIX + logicalThreadName);
        Object threadId = meta.get(META_THREAD_ID_FIELD);
        if (threadId != null) {
            return threadId.toString();
        }
        return redisTemplate.opsForValue().get(indexKey(logicalThreadName));
    }

    private String indexKey(String logicalThreadName) {
        String prefix = persistenceProperties.getRedis().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "medical-agent:";
        }
        return (prefix.endsWith(":") ? prefix : prefix + ":") + INDEX_NAMESPACE + ":" + logicalThreadName;
    }

    private void expire(String key) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.expire(key, ttl);
    }
}
