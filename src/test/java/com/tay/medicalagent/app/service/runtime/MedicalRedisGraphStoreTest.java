package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MedicalRedisGraphStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldSupportPutGetSearchListAndClear() {
        MedicalRedisGraphStore store = new MedicalRedisGraphStore(redisTemplate(), objectMapper, persistenceProperties());

        store.putItem(StoreItem.of(List.of("user_profiles"), "user_a", Map.of("name", "张三", "age", 28)));
        store.putItem(StoreItem.of(List.of("runtime", "memory"), "thread_a", Map.of("summary", "cached")));

        Map<String, Object> profile = store.getItem(List.of("user_profiles"), "user_a").orElseThrow().getValue();
        assertEquals("张三", profile.get("name"));

        StoreSearchRequest searchRequest = new StoreSearchRequest();
        searchRequest.setNamespace(List.of("user_profiles"));
        searchRequest.setFilter(Map.of());
        searchRequest.setSortFields(List.of());
        searchRequest.setOffset(0);
        searchRequest.setLimit(10);
        var searchResult = store.searchItems(searchRequest);
        assertEquals(1, searchResult.getItems().size());
        assertEquals("user_a", searchResult.getItems().get(0).getKey());

        NamespaceListRequest namespaceRequest = new NamespaceListRequest();
        namespaceRequest.setNamespace(List.of());
        namespaceRequest.setMaxDepth(-1);
        namespaceRequest.setOffset(0);
        namespaceRequest.setLimit(10);
        List<String> namespaces = store.listNamespaces(namespaceRequest);
        assertTrue(namespaces.contains("user_profiles"));
        assertTrue(namespaces.contains("runtime"));
        assertTrue(namespaces.contains("runtime/memory"));

        assertFalse(store.isEmpty());
        assertEquals(2, store.size());

        store.clear();

        assertTrue(store.isEmpty());
        assertTrue(store.getItem(List.of("user_profiles"), "user_a").isEmpty());
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

    private MedicalPersistenceProperties persistenceProperties() {
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
        properties.setStore("redis");
        properties.getRedis().setKeyPrefix("medical-graph-it:");
        properties.getRedis().setProfileTtl(Duration.ofMinutes(30));
        return properties;
    }
}
