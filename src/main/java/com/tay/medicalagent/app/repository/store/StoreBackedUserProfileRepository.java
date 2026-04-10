package com.tay.medicalagent.app.repository.store;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.tay.medicalagent.app.repository.UserProfileRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于共享 Graph Store 的用户画像仓储实现。
 * <p>
 * 让业务画像与 Runtime 使用同一份底层 Store，避免 memory/redis 双份状态漂移。
 */
@Repository
public class StoreBackedUserProfileRepository implements UserProfileRepository {

    private static final List<String> USER_PROFILE_NAMESPACE = List.of("user_profiles");
    private static final int DELETE_BATCH_SIZE = 200;

    private final Store store;

    public StoreBackedUserProfileRepository(Store store) {
        this.store = store;
    }

    @Override
    public Optional<Map<String, Object>> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return store.getItem(USER_PROFILE_NAMESPACE, userId.trim())
                .map(StoreItem::getValue)
                .map(Map::copyOf);
    }

    @Override
    public void save(String userId, Map<String, Object> profile) {
        if (userId == null || userId.isBlank() || profile == null || profile.isEmpty()) {
            return;
        }
        store.putItem(StoreItem.of(USER_PROFILE_NAMESPACE, userId.trim(), profile));
    }

    @Override
    public void clear() {
        while (true) {
            StoreSearchRequest request = new StoreSearchRequest();
            request.setNamespace(USER_PROFILE_NAMESPACE);
            request.setFilter(Map.of());
            request.setSortFields(List.of());
            request.setOffset(0);
            request.setLimit(DELETE_BATCH_SIZE);
            var result = store.searchItems(request);
            if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
                return;
            }
            for (StoreItem item : result.getItems()) {
                store.deleteItem(item.getNamespace(), item.getKey());
            }
        }
    }
}
