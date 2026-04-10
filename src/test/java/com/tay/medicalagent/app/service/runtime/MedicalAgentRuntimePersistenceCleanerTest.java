package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.store.Store;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalAgentRuntimePersistenceCleanerTest {

    @Test
    void shouldClearStoreOnlyInMemoryMode() {
        Store store = mock(Store.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();

        MedicalAgentRuntimePersistenceCleaner cleaner = new MedicalAgentRuntimePersistenceCleaner(store, properties, redisTemplate);
        cleaner.clearAll();

        verify(store).clear();
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
        verify(redisTemplate, never()).delete(any(List.class));
    }

    @Test
    void shouldClearStoreAndCheckpointPatternsInRedisMode() {
        Store store = mock(Store.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
        properties.setStore("redis");
        properties.getRedis().setKeyPrefix("medical-cleaner-it:");

        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(List.of("graph:key"));

        MedicalAgentRuntimePersistenceCleaner cleaner = new MedicalAgentRuntimePersistenceCleaner(store, properties, redisTemplate);
        cleaner.clearAll();

        verify(store).clear();
        verify(redisTemplate, times(5)).execute(any(RedisCallback.class));
        verify(redisTemplate, times(5)).delete(eq(List.of("graph:key")));
    }
}
