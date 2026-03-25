package com.tay.medicalagent.app.rag.model;

import java.util.Map;

/**
 * 单条检索命中的知识片段。
 */
public record RetrievedPassage(
        String documentId,
        String text,
        Double score,
        KnowledgeSource source,
        Map<String, Object> metadata
) {
}
