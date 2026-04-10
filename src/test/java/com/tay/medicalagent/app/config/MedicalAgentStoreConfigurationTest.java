package com.tay.medicalagent.app.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.service.runtime.MedicalRedisCheckpointSaver;
import com.tay.medicalagent.app.service.runtime.MedicalRedisGraphStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MedicalAgentStoreConfigurationTest {

    @Test
    void shouldUseMemoryStoreAndMemorySaverByDefault() {
        new ApplicationContextRunner()
                .withBean(MedicalPersistenceProperties.class, MedicalPersistenceProperties::new)
                .withBean(RedisProperties.class, RedisProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(MedicalAgentStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Store.class);
                    assertThat(context).hasSingleBean(BaseCheckpointSaver.class);
                    assertThat(context.getBean(Store.class)).isInstanceOf(MemoryStore.class);
                    assertThat(context.getBean(BaseCheckpointSaver.class)).isInstanceOf(MemorySaver.class);
                });
    }

    @Test
    void shouldUseRedisStoreAndRedisSaverWhenRedisModeEnabled() {
        new ApplicationContextRunner()
                .withBean(MedicalPersistenceProperties.class, () -> {
                    MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
                    properties.setStore("redis");
                    return properties;
                })
                .withBean(RedisProperties.class, RedisProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withUserConfiguration(MedicalAgentStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Store.class);
                    assertThat(context).hasSingleBean(BaseCheckpointSaver.class);
                    assertThat(context.getBean(Store.class)).isInstanceOf(MedicalRedisGraphStore.class);
                    assertThat(context.getBean(BaseCheckpointSaver.class)).isInstanceOf(MedicalRedisCheckpointSaver.class);
                });
    }
}
