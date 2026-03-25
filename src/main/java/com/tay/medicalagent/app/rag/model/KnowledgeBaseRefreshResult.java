package com.tay.medicalagent.app.rag.model;

import java.util.List;

/**
 * 知识库重建结果摘要。
 */
public record KnowledgeBaseRefreshResult(
        int sourceFileCount,
        int documentCount,
        int deletedDocumentCount,
        List<String> indexedSourceIds,
        String vectorStoreType,
        String manifestLocation,
        String storeLocation
) {
}
