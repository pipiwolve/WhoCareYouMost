package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.model.RetrievedPassage;
import com.tay.medicalagent.app.rag.vectorstore.ElasticsearchBackedVectorStore;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 默认医疗知识检索器。
 * <p>
 * 基于 {@link VectorStore} 执行相似度搜索，并将命中结果整理成可注入模型的 RAG 上下文。
 */
public class DefaultMedicalKnowledgeRetriever implements MedicalKnowledgeRetriever {

    private final VectorStore vectorStore;
    private final MedicalQueryBuilder medicalQueryBuilder;
    private final MedicalRagProperties medicalRagProperties;
    private final ElasticsearchHybridSearchClient elasticsearchHybridSearchClient;

    public DefaultMedicalKnowledgeRetriever(
            VectorStore vectorStore,
            MedicalQueryBuilder medicalQueryBuilder,
            MedicalRagProperties medicalRagProperties,
            ElasticsearchHybridSearchClient elasticsearchHybridSearchClient
    ) {
        this.vectorStore = vectorStore;
        this.medicalQueryBuilder = medicalQueryBuilder;
        this.medicalRagProperties = medicalRagProperties;
        this.elasticsearchHybridSearchClient = elasticsearchHybridSearchClient;
    }

    @Override
    public RagContext retrieve(String query) {
        String normalizedQuery = medicalQueryBuilder.normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return RagContext.empty("");
        }

        List<Document> documents = searchDocuments(normalizedQuery);

        List<RetrievedPassage> passages = new ArrayList<>();
        for (Document document : documents) {
            passages.add(toPassage(document));
        }
        List<RetrievedPassage> deduplicatedPassages = deduplicatePassages(passages);

        return new RagContext(normalizedQuery, buildContext(deduplicatedPassages), List.copyOf(deduplicatedPassages));
    }

    @Override
    public RagContext retrieve(List<Message> messages) {
        return retrieve(medicalQueryBuilder.buildSearchQuery(messages));
    }

    private List<Document> searchDocuments(String normalizedQuery) {
        String strategy = medicalRagProperties.getRetrieval()
                .resolveStrategy(medicalRagProperties.getVectorStore().getType());

        if ("elasticsearch_hybrid".equalsIgnoreCase(strategy) && isElasticsearchBackedStore()) {
            return elasticsearchHybridSearchClient.search(normalizedQuery);
        }

        return vectorSimilaritySearch(normalizedQuery);
    }

    private List<Document> vectorSimilaritySearch(String normalizedQuery) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(normalizedQuery)
                .topK(medicalRagProperties.getRetrieval().getTopK())
                .similarityThreshold(medicalRagProperties.getRetrieval().getSimilarityThreshold())
                .build());
    }

    private boolean isElasticsearchBackedStore() {
        return vectorStore instanceof ElasticsearchVectorStore
                || vectorStore instanceof ElasticsearchBackedVectorStore;
    }

    private RetrievedPassage toPassage(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : Map.copyOf(document.getMetadata());
        KnowledgeSource source = new KnowledgeSource(
                getMetadata(metadata, "sourceId"),
                getMetadata(metadata, "title"),
                getMetadata(metadata, "section"),
                getMetadata(metadata, "documentType"),
                getMetadata(metadata, "uri"),
                document.getScore()
        );
        return new RetrievedPassage(
                document.getId(),
                safeText(document.getText()),
                document.getScore(),
                source,
                metadata
        );
    }

    private String buildContext(List<RetrievedPassage> passages) {
        if (passages.isEmpty()) {
            return "";
        }

        int maxContextChars = medicalRagProperties.getRetrieval().getMaxContextChars();
        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < passages.size(); index++) {
            RetrievedPassage passage = passages.get(index);
            KnowledgeSource source = passage.source();

            String block = """
                    [%d] 来源ID：%s
                    标题：%s
                    章节：%s
                    内容：%s
                    """.formatted(
                    index + 1,
                    safeText(source.sourceId()),
                    safeText(source.title()),
                    safeText(source.section()),
                    safeText(passage.text())
            );

            if (builder.length() + block.length() > maxContextChars && builder.length() > 0) {
                break;
            }

            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(block.trim());
        }

        return builder.toString();
    }

    private List<RetrievedPassage> deduplicatePassages(List<RetrievedPassage> passages) {
        if (passages == null || passages.isEmpty()) {
            return List.of();
        }

        Map<String, RetrievedPassage> uniquePassages = new LinkedHashMap<>();
        for (RetrievedPassage passage : passages) {
            if (passage == null) {
                continue;
            }
            uniquePassages.putIfAbsent(buildDedupeKey(passage), passage);
        }
        return List.copyOf(uniquePassages.values());
    }

    private String buildDedupeKey(RetrievedPassage passage) {
        KnowledgeSource source = passage.source();
        String sourceId = source == null ? "" : safeText(source.sourceId());
        String section = source == null ? "" : safeText(source.section());
        if (!sourceId.isBlank() || !section.isBlank()) {
            return sourceId + "::" + section;
        }
        return safeText(passage.documentId());
    }

    private String getMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.isEmpty() ? "" : text;
    }
}
