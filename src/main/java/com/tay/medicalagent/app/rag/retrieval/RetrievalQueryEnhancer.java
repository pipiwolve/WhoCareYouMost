package com.tay.medicalagent.app.rag.retrieval;

/**
 * 检索查询增强器。
 * <p>
 * 在真正向量检索前，对查询做一次轻量重写或补充，以提升知识库召回效果。
 */
public interface RetrievalQueryEnhancer {

    /**
     * 增强检索查询。
     *
     * @param query  原始检索查询
     * @param userId 当前用户标识
     * @return 增强后的查询；如果增强失败，应返回原查询
     */
    String enhanceQuery(String query, String userId);
}
