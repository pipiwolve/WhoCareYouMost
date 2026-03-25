package com.tay.medicalagent.app.rag.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 RAG 检索上下文。
 * <p>
 * 既包含原始查询文本，也包含供模型直接注入的上下文文本与原始命中片段。
 */
public record RagContext(
        String query,
        String contextText,
        List<RetrievedPassage> passages
) {

    public static RagContext empty(String query) {
        return new RagContext(query == null ? "" : query, "", List.of());
    }

    public boolean applied() {
        return passages != null && !passages.isEmpty();
    }

    public List<KnowledgeSource> sources() {
        if (!applied()) {
            return List.of();
        }

        Map<String, KnowledgeSource> unique = new LinkedHashMap<>();
        for (RetrievedPassage passage : passages) {
            if (passage == null || passage.source() == null) {
                continue;
            }
            KnowledgeSource source = passage.source();
            String key = buildSourceKey(passage);
            unique.putIfAbsent(key, source);
        }
        return List.copyOf(unique.values());
    }

    private String buildSourceKey(RetrievedPassage passage) {
        KnowledgeSource source = passage.source();
        String sourceId = source == null || source.sourceId() == null ? "" : source.sourceId().trim();
        String section = source == null || source.section() == null ? "" : source.section().trim();
        if (!sourceId.isBlank() || !section.isBlank()) {
            return sourceId + "::" + section;
        }
        return passage.documentId() == null ? "" : passage.documentId().trim();
    }
}
