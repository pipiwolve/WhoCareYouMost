package com.tay.medicalagent.app.rag.retrieval;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 医疗检索查询构建器抽象。
 */
public interface MedicalQueryBuilder {

    /**
     * 从对话消息中提炼出适合向量检索的查询语句。
     *
     * @param messages 当前对话消息
     * @return 检索查询文本
     */
    String buildSearchQuery(List<Message> messages);

    /**
     * 对查询文本做轻量标准化处理。
     *
     * @param query 原始查询
     * @return 标准化后的查询
     */
    String normalizeQuery(String query);
}
