package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.repository.ThreadConversationRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 默认线程对话服务实现。
 * <p>
 * 负责对线程消息做追加、查询与文本转录，供报告生成和调试查看使用。
 */
public class DefaultThreadConversationService implements ThreadConversationService {

    private final ThreadConversationRepository threadConversationRepository;

    public DefaultThreadConversationService(ThreadConversationRepository threadConversationRepository) {
        this.threadConversationRepository = threadConversationRepository;
    }

    @Override
    public void appendExchange(String threadId, UserMessage userMessage, AssistantMessage assistantMessage) {
        threadConversationRepository.append(threadId, userMessage);
        threadConversationRepository.append(threadId, assistantMessage);
    }

    @Override
    public List<Message> getThreadConversation(String threadId) {
        return threadConversationRepository.findByThreadId(threadId);
    }

    @Override
    public String buildConversationTranscript(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return "(暂无对话记录)";
        }

        StringBuilder builder = new StringBuilder();
        for (Message message : conversation) {
            builder.append(resolveRole(message))
                    .append("：")
                    .append(message.getText())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    @Override
    public String findLatestAssistantReply(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return "";
        }

        for (int index = conversation.size() - 1; index >= 0; index--) {
            Message message = conversation.get(index);
            if (message instanceof AssistantMessage) {
                return message.getText();
            }
        }
        return "";
    }

    @Override
    public void clearConversations() {
        threadConversationRepository.clear();
    }

    private String resolveRole(Message message) {
        if (message instanceof SystemMessage) {
            return "system";
        }
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof ToolResponseMessage) {
            return "tool";
        }
        return message == null ? "unknown" : message.getClass().getSimpleName();
    }
}
