package com.tay.medicalagent.app.repository;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 线程对话仓储抽象。
 */
public interface ThreadConversationRepository {

    /**
     * 向线程追加一条消息。
     *
     * @param threadId 会话线程 ID
     * @param message 对话消息
     */
    void append(String threadId, Message message);

    /**
     * 查询线程内全部消息。
     *
     * @param threadId 会话线程 ID
     * @return 线程消息列表
     */
    List<Message> findByThreadId(String threadId);

    /**
     * 清空全部线程消息。
     */
    void clear();
}
