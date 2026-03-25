package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.rag.model.RagContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 医疗知识检索器抽象。
 */
public interface MedicalKnowledgeRetriever {

    /**
     * 根据文本查询进行相似度检索。
     *
     * @param query 查询文本
     * @return 检索上下文
     */
    RagContext retrieve(String query);

    /**
     * 根据当前消息列表构造查询并执行检索。
     *
     * @param messages 当前对话消息
     * @return 检索上下文
     */
    RagContext retrieve(List<Message> messages);
}
