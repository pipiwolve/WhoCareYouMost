package com.tay.medicalagent.app.rag.retrieval;

/**
 * RAG 触发策略抽象。
 * <p>
 * 用于判断当前问题是否值得进入知识库检索阶段。
 */
public interface RagTriggerPolicy {

    /**
     * 判断是否需要触发检索。
     *
     * @param query 检索候选查询
     * @return 是否应该检索
     */
    boolean shouldRetrieve(String query);
}
