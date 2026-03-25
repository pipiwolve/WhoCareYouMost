package com.tay.medicalagent.app;

import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreWorkflowTest {

    @Test
    void memoryStorePutAndGet() {
        MemoryStore store = new MemoryStore();

        List<String> namespace = List.of("users", "profiles");
        String key = "user_001";
        Map<String, Object> value = Map.of(
                "name", "王小明",
                "age", 28,
                "preferences", List.of("喜欢咖啡", "喜欢阅读")
        );

        StoreItem item = StoreItem.of(namespace, key, value);
        store.putItem(item);

        Optional<StoreItem> found = store.getItem(namespace, key);
        assertTrue(found.isPresent());
        assertEquals("王小明", found.get().getValue().get("name"));
    }
}
