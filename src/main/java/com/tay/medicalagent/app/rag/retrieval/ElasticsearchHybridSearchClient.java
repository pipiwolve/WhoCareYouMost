package com.tay.medicalagent.app.rag.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tay.medicalagent.app.rag.config.MedicalRagElasticsearchProperties;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@Component
/**
 * Elasticsearch 混合检索客户端。
 * <p>
 * 通过向量召回与 BM25 词面召回的应用层 RRF 融合，提升医疗知识库检索稳定性。
 */
public class ElasticsearchHybridSearchClient {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHybridSearchClient.class);

    private final VectorStore vectorStore;
    private final ObjectProvider<RestClient> restClientProvider;
    private final MedicalRagProperties medicalRagProperties;
    private final MedicalRagElasticsearchProperties elasticsearchProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ElasticsearchHybridSearchClient(
            VectorStore vectorStore,
            ObjectProvider<RestClient> restClientProvider,
            MedicalRagProperties medicalRagProperties,
            MedicalRagElasticsearchProperties elasticsearchProperties
    ) {
        this.vectorStore = vectorStore;
        this.restClientProvider = restClientProvider;
        this.medicalRagProperties = medicalRagProperties;
        this.elasticsearchProperties = elasticsearchProperties;
    }

    public List<Document> search(String normalizedQuery) {
        RestClient restClient = restClientProvider.getIfAvailable();
        List<Document> vectorDocuments = List.of();
        try {
            vectorDocuments = vectorSearch(normalizedQuery);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Hybrid retrieval vector phase completed. query={}, candidateCount={}, candidates={}",
                        normalizedQuery,
                        vectorDocuments.size(),
                        summarizeDocuments(vectorDocuments)
                );
            }
        }
        catch (RuntimeException ex) {
            log.warn("Elasticsearch hybrid vector search failed, fallback to lexical-only retrieval", ex);
        }

        try {
            if (restClient == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Hybrid retrieval lexical phase skipped because RestClient is unavailable. query={}", normalizedQuery);
                }
                return limit(vectorDocuments);
            }

            List<Document> lexicalDocuments = lexicalSearch(restClient, normalizedQuery);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Hybrid retrieval lexical phase completed. query={}, candidateCount={}, candidates={}",
                        normalizedQuery,
                        lexicalDocuments.size(),
                        summarizeDocuments(lexicalDocuments)
                );
            }

            List<Document> fusedDocuments = ReciprocalRankFusion.fuse(
                    vectorDocuments,
                    lexicalDocuments,
                    medicalRagProperties.getRetrieval().getTopK(),
                    medicalRagProperties.getRetrieval().getElasticsearchHybrid().getRankConstant()
            );
            if (log.isDebugEnabled()) {
                log.debug(
                        "Hybrid retrieval fused result completed. query={}, topK={}, resultCount={}, results={}",
                        normalizedQuery,
                        medicalRagProperties.getRetrieval().getTopK(),
                        fusedDocuments.size(),
                        summarizeDocuments(fusedDocuments)
                );
            }
            return fusedDocuments;
        }
        catch (IOException ex) {
            log.warn("Elasticsearch hybrid lexical search failed, fallback to vector-only retrieval", ex);
            return limit(vectorDocuments);
        }
    }

    private List<Document> vectorSearch(String normalizedQuery) {
        int vectorTopK = Math.max(
                medicalRagProperties.getRetrieval().getTopK(),
                medicalRagProperties.getRetrieval().getElasticsearchHybrid().getVectorTopK()
        );
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(normalizedQuery)
                .topK(vectorTopK)
                .similarityThresholdAll()
                .build());
    }

    private List<Document> lexicalSearch(RestClient restClient, String normalizedQuery) throws IOException {
        Request request = new Request("POST", "/" + elasticsearchProperties.getIndexName() + "/_search");
        request.setJsonEntity(buildLexicalQuery(normalizedQuery));

        Response response = restClient.performRequest(request);
        byte[] body = response.getEntity().getContent().readAllBytes();
        JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));

        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray()) {
            return List.of();
        }

        return toDocuments((ArrayNode) hits);
    }

    private String buildLexicalQuery(String normalizedQuery) throws IOException {
        MedicalRagProperties.Retrieval.ElasticsearchHybrid hybrid = medicalRagProperties.getRetrieval().getElasticsearchHybrid();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", Math.max(medicalRagProperties.getRetrieval().getTopK(), hybrid.getLexicalTopK()));

        ObjectNode query = root.putObject("query");
        ObjectNode multiMatch = query.putObject("multi_match");
        multiMatch.put("query", normalizedQuery);
        multiMatch.put("type", "best_fields");

        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("metadata.title^" + hybrid.getTitleBoost());
        fields.add("metadata.section^" + hybrid.getSectionBoost());
        fields.add("content^" + hybrid.getContentBoost());

        return objectMapper.writeValueAsString(root);
    }

    private List<Document> toDocuments(ArrayNode hits) {
        return StreamSupport.stream(hits.spliterator(), false)
                .map(this::toDocument)
                .toList();
    }

    private Document toDocument(JsonNode hit) {
        JsonNode source = hit.path("_source");
        String id = source.path("id").asText(hit.path("_id").asText(""));
        String text = source.path("content").asText("");
        Map<String, Object> metadata = source.path("metadata").isMissingNode()
                ? Map.of()
                : objectMapper.convertValue(source.path("metadata"), new TypeReference<>() {
                });

        Document document = Document.builder()
                .id(id)
                .text(text)
                .metadata(metadata)
                .score(hit.path("_score").asDouble(0d))
                .build();
        return document;
    }

    private List<Document> limit(List<Document> documents) {
        int limit = Math.max(1, medicalRagProperties.getRetrieval().getTopK());
        return documents.stream().limit(limit).toList();
    }

    private String summarizeDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "[]";
        }

        return documents.stream()
                .limit(8)
                .map(document -> "{id=%s,score=%.4f,sourceId=%s,section=%s}".formatted(
                        safeText(document.getId()),
                        document.getScore(),
                        readMetadata(document, "sourceId"),
                        readMetadata(document, "section")
                ))
                .toList()
                .toString();
    }

    private String readMetadata(Document document, String key) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : safeText(value.toString());
    }

    private String safeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }
}
