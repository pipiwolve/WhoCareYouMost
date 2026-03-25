package com.tay.medicalagent.app.repository.memory;

import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.tay.medicalagent.app.repository.UserProfileRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
/**
 * 用户画像仓储的 {@link MemoryStore} 实现。
 * <p>
 * 当前与 Agent Framework 的内存存储集成，便于后续切换到更正式的持久化方案。
 */
public class MemoryStoreUserProfileRepository implements UserProfileRepository {

    private static final List<String> USER_PROFILE_NAMESPACE = List.of("user_profiles");

    private final MemoryStore memoryStore;

    public MemoryStoreUserProfileRepository(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public Optional<Map<String, Object>> findByUserId(String userId) {
        return memoryStore.getItem(USER_PROFILE_NAMESPACE, userId)
                .map(StoreItem::getValue)
                .map(Map::copyOf);
    }

    @Override
    public void save(String userId, Map<String, Object> profile) {
        memoryStore.putItem(StoreItem.of(USER_PROFILE_NAMESPACE, userId, profile));
    }

    @Override
    public void clear() {
        memoryStore.clear();
    }
}
