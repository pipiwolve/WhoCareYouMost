package com.tay.medicalagent.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@HookPositions({HookPosition.BEFORE_MODEL})
/**
 * Re-reading 消息增强 Hook。
 * <p>
 * 在模型调用前重写最后一条用户消息，让模型再次聚焦当前问题本身。
 */
public class ReReadingMessagesHook extends MessagesModelHook {

    static final String RE_READING_APPLIED_METADATA_KEY = "re_reading_applied";

    private static final String DEFAULT_RE_READING_TEMPLATE = """
            {re2_input_query}
            Read the question again: {re2_input_query}
            """;

    private final String reReadingTemplate;

    private int order = 100;

    public ReReadingMessagesHook() {
        this(DEFAULT_RE_READING_TEMPLATE);
    }

    public ReReadingMessagesHook(String reReadingTemplate) {
        this.reReadingTemplate = reReadingTemplate;
    }

    @Override
    public String getName() {
        return "re_reading_messages";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public ReReadingMessagesHook withOrder(int order) {
        this.order = order;
        return this;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        return new AgentCommand(rewriteMessages(previousMessages), UpdatePolicy.REPLACE);
    }

    List<Message> rewriteMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int lastUserMessageIndex = findLastUserMessageIndex(messages);
        if (lastUserMessageIndex < 0) {
            return messages;
        }

        UserMessage lastUserMessage = (UserMessage) messages.get(lastUserMessageIndex);
        String originalText = lastUserMessage.getText();
        if (originalText == null || originalText.isBlank() || isAlreadyAugmented(lastUserMessage)) {
            return messages;
        }

        String augmentedText = PromptTemplate.builder()
                .template(this.reReadingTemplate)
                .variables(Map.of("re2_input_query", originalText))
                .build()
                .render();

        if (augmentedText.equals(originalText)) {
            return messages;
        }

        List<Message> rewrittenMessages = new ArrayList<>(messages);
        rewrittenMessages.set(lastUserMessageIndex, createAugmentedUserMessage(lastUserMessage, augmentedText));
        return rewrittenMessages;
    }

    private int findLastUserMessageIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private boolean isAlreadyAugmented(UserMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get(RE_READING_APPLIED_METADATA_KEY));
    }

    private UserMessage createAugmentedUserMessage(UserMessage source, String augmentedText) {
        Map<String, Object> metadata = new LinkedHashMap<>(source.getMetadata());
        metadata.put(RE_READING_APPLIED_METADATA_KEY, true);
        return source.mutate()
                .text(augmentedText)
                .metadata(metadata)
                .build();
    }
}
