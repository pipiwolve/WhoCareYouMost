package com.tay.medicalagent.app.repository.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import com.tay.medicalagent.app.repository.ThreadConversationRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "medical.persistence", name = "store", havingValue = "redis")
public class RedisThreadConversationRepository extends RedisJsonValueSupport implements ThreadConversationRepository {

    private static final String CONVERSATION_NAMESPACE = "thread-conversation";

    private final MedicalPersistenceProperties persistenceProperties;
    private final ListOperations<String, String> listOperations;

    public RedisThreadConversationRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MedicalPersistenceProperties persistenceProperties
    ) {
        super(redisTemplate, objectMapper, persistenceProperties);
        this.persistenceProperties = persistenceProperties;
        this.listOperations = redisTemplate.opsForList();
    }

    @Override
    public void append(String threadId, Message message) {
        if (threadId == null || threadId.isBlank() || message == null) {
            return;
        }

        String key = key(CONVERSATION_NAMESPACE, threadId);
        listOperations.rightPush(key, serialize(toStoredMessage(message)));
        expire(key, conversationTtl());
    }

    @Override
    public List<Message> findByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }

        String key = key(CONVERSATION_NAMESPACE, threadId);
        List<String> items = listOperations.range(key, 0, -1);
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        expire(key, conversationTtl());
        List<Message> messages = new ArrayList<>(items.size());
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            messages.add(toMessage(deserialize(item, StoredConversationMessage.class)));
        }
        return List.copyOf(messages);
    }

    @Override
    public void clear() {
        deleteByPattern(pattern(CONVERSATION_NAMESPACE));
    }

    private Duration conversationTtl() {
        return persistenceProperties.getRedis().getConversationTtl();
    }

    private StoredConversationMessage toStoredMessage(Message message) {
        if (message instanceof UserMessage) {
            return new StoredConversationMessage("user", message.getText());
        }
        if (message instanceof SystemMessage) {
            return new StoredConversationMessage("system", message.getText());
        }
        return new StoredConversationMessage("assistant", message.getText());
    }

    private Message toMessage(StoredConversationMessage storedConversationMessage) {
        String role = storedConversationMessage.role() == null ? "" : storedConversationMessage.role().trim();
        String text = storedConversationMessage.text() == null ? "" : storedConversationMessage.text();
        return switch (role) {
            case "user" -> new UserMessage(text);
            case "system" -> new SystemMessage(text);
            default -> AssistantMessage.builder().content(text).build();
        };
    }

    private record StoredConversationMessage(String role, String text) {
    }
}
