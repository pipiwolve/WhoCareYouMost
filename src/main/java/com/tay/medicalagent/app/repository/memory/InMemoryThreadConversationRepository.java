package com.tay.medicalagent.app.repository.memory;

import com.tay.medicalagent.app.repository.ThreadConversationRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
/**
 * 线程对话仓储的内存实现。
 * <p>
 * 适合本地开发和测试，进程重启后数据不会保留。
 */
public class InMemoryThreadConversationRepository implements ThreadConversationRepository {

    private final ConcurrentMap<String, List<Message>> threadConversationStore = new ConcurrentHashMap<>();

    @Override
    public void append(String threadId, Message message) {
        if (threadId == null || threadId.isBlank() || message == null) {
            return;
        }

        threadConversationStore.compute(threadId, (key, existing) -> {
            List<Message> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            updated.add(message);
            return updated;
        });
    }

    @Override
    public List<Message> findByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }
        return List.copyOf(threadConversationStore.getOrDefault(threadId, List.of()));
    }

    @Override
    public void clear() {
        threadConversationStore.clear();
    }
}
