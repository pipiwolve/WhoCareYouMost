package com.tay.medicalagent.app.rag.model;

/**
 * RAG 返回给上层的知识来源信息。
 */
public record KnowledgeSource(
        String sourceId,
        String title,
        String section,
        String documentType,
        String uri,
        Double score
) {
}
