package com.tay.medicalagent.app.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.service.runtime.MedicalRedisCheckpointSaver;
import com.tay.medicalagent.app.service.runtime.MedicalRedisGraphStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
/**
 * Agent 运行所需的基础存储与持久化配置。
 * <p>
 * 基于 `medical.persistence.store` 在内存态与 Redis 之间切换：
 * `memory` 保持轻量本地模式，`redis` 使用真实 Redis 作为 Runtime Store 和 Checkpoint Saver。
 */
public class MedicalAgentStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MedicalAgentStoreConfiguration.class);

    /**
     * 用户长期画像存储。
     *
     * @return Runtime 与仓储共享的统一 Store
     */
    @Bean
    public Store userProfileMemoryStore(
            MedicalPersistenceProperties persistenceProperties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper
    ) {
        if (!persistenceProperties.usesRedis()) {
            return new MemoryStore();
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new IllegalStateException("medical.persistence.store=redis 但缺少 StringRedisTemplate");
        }
        return new MedicalRedisGraphStore(redisTemplate, objectMapper, persistenceProperties);
    }

    @Bean
    public BaseCheckpointSaver medicalCheckpointSaver(
            MedicalPersistenceProperties persistenceProperties,
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        if (!persistenceProperties.usesRedis()) {
            return new MemorySaver();
        }

        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redissonClient == null || redisTemplate == null) {
            throw new IllegalStateException("medical.persistence.store=redis 但缺少 Redis Runtime 依赖");
        }

        RedisSaver delegate = RedisSaver.builder()
                .redisson(redissonClient)
                .build();
        Duration ttl = persistenceProperties.getRedis().getConversationTtl();
        return new MedicalRedisCheckpointSaver(redisTemplate, redissonClient, persistenceProperties, delegate, ttl);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "medical.persistence", name = "store", havingValue = "redis")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(resolveRedisAddress(redisProperties))
                .setDatabase(redisProperties.getDatabase());

        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
            singleServerConfig.setUsername(redisProperties.getUsername());
        }
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }

        Duration timeout = redisProperties.getTimeout();
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            singleServerConfig.setTimeout((int) timeout.toMillis());
            singleServerConfig.setConnectTimeout((int) timeout.toMillis());
        }

        return Redisson.create(config);
    }

    @Bean
    public ApplicationRunner medicalPersistenceStartupLogger(
            MedicalPersistenceProperties persistenceProperties,
            RedisProperties redisProperties
    ) {
        return args -> {
            if (persistenceProperties.usesRedis()) {
                log.info(
                        "Medical persistence started in redis mode. redisDb={}, businessKeyPrefix={}",
                        redisProperties.getDatabase(),
                        persistenceProperties.getRedis().getKeyPrefix()
                );
                log.info(
                        "Medical Agent Runtime uses official RedisSaver baseline from Java2AI graph persistence docs; project keeps custom Redis graph store for shared Store/TTL/key-prefix behavior."
                );
                return;
            }
            log.info("Medical persistence started in memory mode.");
        };
    }

    private String resolveRedisAddress(RedisProperties redisProperties) {
        String scheme = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
        String host = redisProperties.getHost() == null || redisProperties.getHost().isBlank()
                ? "localhost"
                : redisProperties.getHost().trim();
        int port = redisProperties.getPort() <= 0 ? 6379 : redisProperties.getPort();
        return scheme + host + ":" + port;
    }
}
