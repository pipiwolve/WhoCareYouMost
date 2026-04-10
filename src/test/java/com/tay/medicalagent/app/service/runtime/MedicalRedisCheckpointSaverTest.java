package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MedicalRedisCheckpointSaverTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedissonClient redissonClient;

    @AfterEach
    void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldPersistCheckpointAndRefreshTtls() throws Exception {
        MedicalPersistenceProperties properties = persistenceProperties();
        MedicalRedisCheckpointSaver saver = new MedicalRedisCheckpointSaver(
                redisTemplate(),
                redissonClient(),
                properties,
                RedisSaver.builder().redisson(redissonClient()).build(),
                properties.getRedis().getConversationTtl()
        );

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId("logical-thread-a")
                .build();
        Checkpoint checkpoint = Checkpoint.builder()
                .id("cp-1")
                .nodeId("model")
                .nextNodeId("done")
                .state(Map.of("messages", List.of("hello")))
                .build();

        RunnableConfig updated = saver.put(runnableConfig, checkpoint);
        assertEquals("cp-1", updated.checkPointId().orElseThrow());
        assertTrue(saver.get(runnableConfig).isPresent());

        String actualThreadId = (String) redissonClient().getMap("graph:thread:meta:logical-thread-a").get("thread_id");
        assertNotNull(actualThreadId);
        assertTrue(redisTemplate().getExpire("graph:thread:meta:logical-thread-a") > 0);
        assertTrue(redisTemplate().getExpire("graph:thread:reverse:" + actualThreadId) > 0);
        assertTrue(redisTemplate().getExpire("graph:checkpoint:content:" + actualThreadId) > 0);
        assertTrue(redisTemplate().getExpire("medical-checkpoint-it:checkpoint-index:logical-thread-a") > 0);
    }

    private StringRedisTemplate redisTemplate() {
        if (redisTemplate == null) {
            connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
            connectionFactory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();
        }
        return redisTemplate;
    }

    private RedissonClient redissonClient() {
        if (redissonClient == null) {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
            redissonClient = Redisson.create(config);
        }
        return redissonClient;
    }

    private MedicalPersistenceProperties persistenceProperties() {
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
        properties.setStore("redis");
        properties.getRedis().setKeyPrefix("medical-checkpoint-it:");
        properties.getRedis().setConversationTtl(Duration.ofMinutes(30));
        return properties;
    }
}
