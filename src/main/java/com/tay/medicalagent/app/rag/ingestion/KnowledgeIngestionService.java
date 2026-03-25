package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;

/**
 * 知识库入库服务抽象。
 */
public interface KnowledgeIngestionService {

    /**
     * 重新构建整个知识库索引。
     *
     * @return 重建结果摘要
     */
    KnowledgeBaseRefreshResult reindexKnowledgeBase();
}
