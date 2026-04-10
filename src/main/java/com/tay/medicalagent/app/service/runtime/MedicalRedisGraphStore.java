package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.alibaba.cloud.ai.graph.store.StoreSearchResult;
import com.alibaba.cloud.ai.graph.store.stores.BaseStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 真实 Redis 落地的 Graph Store。
 * <p>
 * 不直接使用框架自带 `RedisStore`，以便保留项目所需的 key-prefix、TTL 与清理策略。
 */
public class MedicalRedisGraphStore extends BaseStore {

    private static final int DEFAULT_SCAN_COUNT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MedicalPersistenceProperties persistenceProperties;

    public MedicalRedisGraphStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MedicalPersistenceProperties persistenceProperties
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.persistenceProperties = Objects.requireNonNull(persistenceProperties, "persistenceProperties");
    }

    @Override
    public void putItem(StoreItem item) {
        validatePutItem(item);
        String key = redisKey(item.getNamespace(), item.getKey());
        redisTemplate.opsForValue().set(key, serialize(item));
        expire(key, ttl());
    }

    @Override
    public Optional<StoreItem> getItem(List<String> namespace, String key) {
        validateGetItem(namespace, key);
        String redisKey = redisKey(namespace, key);
        String payload = redisTemplate.opsForValue().get(redisKey);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        expire(redisKey, ttl());
        return Optional.of(deserialize(payload));
    }

    @Override
    public boolean deleteItem(List<String> namespace, String key) {
        validateDeleteItem(namespace, key);
        return Boolean.TRUE.equals(redisTemplate.delete(redisKey(namespace, key)));
    }

    @Override
    public StoreSearchResult searchItems(StoreSearchRequest searchRequest) {
        validateSearchItems(searchRequest);
        StoreSearchRequest normalizedRequest = normalize(searchRequest);
        List<StoreItem> filtered = new ArrayList<>();
        for (StoreItem item : loadAllItems()) {
            if (matchesSearchCriteria(item, normalizedRequest)) {
                filtered.add(item);
            }
        }
        if (!normalizedRequest.getSortFields().isEmpty()) {
            filtered.sort(createComparator(normalizedRequest));
        }
        return page(filtered, normalizedRequest.getOffset(), normalizedRequest.getLimit());
    }

    @Override
    public List<String> listNamespaces(NamespaceListRequest namespaceRequest) {
        validateListNamespaces(namespaceRequest);
        NamespaceListRequest normalizedRequest = normalize(namespaceRequest);
        List<String> prefix = normalizedRequest.getNamespace();
        int maxDepth = normalizedRequest.getMaxDepth();
        int limit = normalizedRequest.getLimit();
        int offset = normalizedRequest.getOffset();

        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        for (StoreItem item : loadAllItems()) {
            List<String> namespace = item.getNamespace() == null ? List.of() : item.getNamespace();
            if (!prefix.isEmpty() && !startsWithPrefix(namespace, prefix)) {
                continue;
            }
            int effectiveDepth = maxDepth < 0 ? namespace.size() : Math.min(maxDepth, namespace.size());
            for (int depth = 1; depth <= effectiveDepth; depth++) {
                namespaces.add(String.join("/", namespace.subList(0, depth)));
            }
        }

        List<String> values = new ArrayList<>(namespaces);
        if (offset >= values.size()) {
            return List.of();
        }
        int toIndex = Math.min(values.size(), offset + limit);
        return List.copyOf(values.subList(offset, toIndex));
    }

    @Override
    public void clear() {
        deleteScannedKeys(pattern());
    }

    @Override
    public long size() {
        return scanKeys(pattern()).size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    private String redisKey(List<String> namespace, String key) {
        return graphStorePrefix() + createStoreKey(namespace, key);
    }

    private String pattern() {
        return graphStorePrefix() + "*";
    }

    private String graphStorePrefix() {
        String keyPrefix = persistenceProperties.getRedis().getKeyPrefix();
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "medical-agent:";
        }
        return (keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":") + "graph-store:";
    }

    private Duration ttl() {
        return persistenceProperties.getRedis().getProfileTtl();
    }

    private void expire(String key, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.expire(key, ttl);
    }

    private String serialize(StoreItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize graph store item", ex);
        }
    }

    private StoreItem deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, StoreItem.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize graph store item", ex);
        }
    }

    private List<StoreItem> loadAllItems() {
        List<String> keys = scanKeys(pattern());
        if (keys.isEmpty()) {
            return List.of();
        }

        List<String> payloads = redisTemplate.opsForValue().multiGet(keys);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }

        List<StoreItem> items = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            if (payload == null || payload.isBlank()) {
                continue;
            }
            items.add(deserialize(payload));
        }
        return items;
    }

    private List<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisConnection connection) -> scanKeys(connection, pattern));
    }

    private List<String> scanKeys(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(DEFAULT_SCAN_COUNT)
                .build();
        List<String> keys = new ArrayList<>();
        try (var cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to scan graph store keys", ex);
        }
        return keys;
    }

    private void deleteScannedKeys(String pattern) {
        List<String> keys = scanKeys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private StoreSearchResult page(List<StoreItem> items, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        int safeLimit = limit > 0 ? limit : Math.max(items.size(), 1);
        if (safeOffset >= items.size()) {
            return StoreSearchResult.of(List.of(), items.size(), safeOffset, safeLimit);
        }
        int toIndex = Math.min(items.size(), safeOffset + safeLimit);
        return StoreSearchResult.of(List.copyOf(items.subList(safeOffset, toIndex)), items.size(), safeOffset, safeLimit);
    }

    private StoreSearchRequest normalize(StoreSearchRequest searchRequest) {
        StoreSearchRequest normalized = new StoreSearchRequest();
        normalized.setNamespace(searchRequest.getNamespace() == null ? List.of() : List.copyOf(searchRequest.getNamespace()));
        normalized.setQuery(searchRequest.getQuery());
        normalized.setFilter(searchRequest.getFilter() == null ? Map.of() : Map.copyOf(searchRequest.getFilter()));
        normalized.setSortFields(searchRequest.getSortFields() == null ? List.of() : List.copyOf(searchRequest.getSortFields()));
        normalized.setAscending(searchRequest.isAscending());
        normalized.setOffset(Math.max(searchRequest.getOffset(), 0));
        normalized.setLimit(searchRequest.getLimit() > 0 ? searchRequest.getLimit() : Integer.MAX_VALUE);
        return normalized;
    }

    private NamespaceListRequest normalize(NamespaceListRequest namespaceRequest) {
        NamespaceListRequest normalized = new NamespaceListRequest();
        normalized.setNamespace(namespaceRequest.getNamespace() == null ? List.of() : List.copyOf(namespaceRequest.getNamespace()));
        normalized.setMaxDepth(namespaceRequest.getMaxDepth());
        normalized.setOffset(Math.max(namespaceRequest.getOffset(), 0));
        normalized.setLimit(namespaceRequest.getLimit() > 0 ? namespaceRequest.getLimit() : Integer.MAX_VALUE);
        return normalized;
    }
}
