package com.tay.medicalagent.app.service.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 线程级对话服务抽象。
 * <p>
 * 负责维护会话线程中的用户消息与助手消息，为报告生成和上下文查看提供原始材料。
 */
public interface ThreadConversationService {

    /**
     * 向指定线程追加一轮用户问答。
     *
     * @param threadId 会话线程 ID
     * @param userMessage 用户消息
     * @param assistantMessage 助手消息
     */
    void appendExchange(String threadId, UserMessage userMessage, AssistantMessage assistantMessage);

    /**
     * 读取线程中的完整对话历史。
     *
     * @param threadId 会话线程 ID
     * @return 对话消息列表
     */
    List<Message> getThreadConversation(String threadId);

    /**
     * 将消息列表转换为适合报告生成或审阅的文本串。
     *
     * @param conversation 对话消息列表
     * @return 可读文本串
     */
    String buildConversationTranscript(List<Message> conversation);

    /**
     * 找出线程中最近一次助手回复。
     *
     * @param conversation 对话消息列表
     * @return 最近的助手回答
     */
    String findLatestAssistantReply(List<Message> conversation);

    /**
     * 清空全部线程对话。
     */
    void clearConversations();
}
