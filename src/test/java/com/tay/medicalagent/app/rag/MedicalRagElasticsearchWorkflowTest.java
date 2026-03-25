package com.tay.medicalagent.app.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeIngestionService;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MedicalRagElasticsearchWorkflowTest {

    private static final Path TEST_RAG_DIR = createTempDirectory();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INDEX_NAME = "medical-rag-it";

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node");

    @Resource
    private KnowledgeIngestionService knowledgeIngestionService;

    @Resource
    private MedicalKnowledgeRetriever medicalKnowledgeRetriever;

    @Resource
    private RestClient restClient;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("medical.rag.enabled", () -> "true");
        registry.add("medical.rag.bootstrap-on-startup", () -> "false");
        registry.add("medical.rag.vector-store.type", () -> "elasticsearch");
        registry.add("medical.rag.vector-store.manifest-file",
                () -> TEST_RAG_DIR.resolve("knowledge-manifest.json").toString());
        registry.add("medical.rag.retrieval.strategy", () -> "elasticsearch_hybrid");
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
        registry.add("spring.ai.vectorstore.elasticsearch.initialize-schema", () -> "true");
        registry.add("spring.ai.vectorstore.elasticsearch.index-name", () -> INDEX_NAME);
        registry.add("spring.ai.vectorstore.elasticsearch.dimensions", () -> "16");
        registry.add("spring.ai.vectorstore.elasticsearch.similarity", () -> "cosine");
    }

    @Test
    void reindexShouldWriteDocumentsToElasticsearchAndPersistManifest() throws IOException {
        KnowledgeBaseRefreshResult result = knowledgeIngestionService.reindexKnowledgeBase();
        refreshIndex();

        assertEquals("elasticsearch", result.vectorStoreType());
        assertEquals(INDEX_NAME, result.storeLocation());
        assertTrue(result.documentCount() > 0);
        assertTrue(Files.exists(TEST_RAG_DIR.resolve("knowledge-manifest.json")));
        assertEquals(result.documentCount(), countIndexedDocuments());
    }

    @Test
    void reindexShouldDeletePreviousDocumentsBeforeRewrite() throws IOException {
        KnowledgeBaseRefreshResult first = knowledgeIngestionService.reindexKnowledgeBase();
        KnowledgeBaseRefreshResult second = knowledgeIngestionService.reindexKnowledgeBase();
        refreshIndex();

        assertEquals(first.documentCount(), second.deletedDocumentCount());
        assertEquals(second.documentCount(), countIndexedDocuments());
    }

    @Test
    void reindexShouldCreateExpectedContentAndMetadataMappings() throws IOException {
        knowledgeIngestionService.reindexKnowledgeBase();

        JsonNode mapping = getIndexMapping()
                .path(INDEX_NAME)
                .path("mappings")
                .path("properties");

        assertEquals("text", mapping.path("content").path("type").asText());
        assertTrue(mapping.path("metadata").path("properties").isObject());
        assertEquals("text", mapping.path("metadata").path("properties").path("title").path("type").asText());
        assertEquals("text", mapping.path("metadata").path("properties").path("section").path("type").asText());
    }

    @Test
    void hybridRetrieverShouldReturnChestPainKnowledge() throws IOException {
        knowledgeIngestionService.reindexKnowledgeBase();
        refreshIndex();

        RagContext ragContext = medicalKnowledgeRetriever.retrieve("持续胸痛20分钟，伴出汗和恶心，现在应该怎么办？");

        assertTrue(ragContext.applied());
        assertFalse(ragContext.contextText().isBlank());
        assertTrue(ragContext.sources().stream()
                .map(KnowledgeSource::sourceId)
                .anyMatch("kb-chest-pain-triage"::equals));
        assertTrue(ragContext.passages().stream()
                .allMatch(passage -> passage.metadata().containsKey("section_summary")));
        assertTrue(ragContext.passages().stream()
                .allMatch(passage -> passage.metadata().containsKey("excerpt_keywords")));
    }

    @Test
    void hybridRetrieverShouldUseTitleAndSectionLexicalSignals() throws IOException {
        knowledgeIngestionService.reindexKnowledgeBase();
        refreshIndex();

        RagContext ragContext = medicalKnowledgeRetriever.retrieve("发热合并呼吸道症状 居家处理");

        assertTrue(ragContext.applied());
        assertTrue(ragContext.sources().stream()
                .map(KnowledgeSource::sourceId)
                .anyMatch("kb-fever-respiratory-care"::equals));
    }

    @Test
    void directLexicalSearchShouldMatchMetadataTitleAndSection() throws IOException {
        knowledgeIngestionService.reindexKnowledgeBase();
        refreshIndex();

        JsonNode hits = searchByTitleAndSection("发热合并呼吸道症状 居家处理");

        assertTrue(hits.isArray());
        assertTrue(hits.size() > 0);
        assertTrue(hasSourceId(hits, "kb-fever-respiratory-care"));
    }

    private int countIndexedDocuments() throws IOException {
        Response response = restClient.performRequest(new Request("GET", "/" + INDEX_NAME + "/_count"));
        byte[] body = response.getEntity().getContent().readAllBytes();
        JsonNode root = OBJECT_MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        return root.path("count").asInt();
    }

    private void refreshIndex() throws IOException {
        restClient.performRequest(new Request("POST", "/" + INDEX_NAME + "/_refresh"));
    }

    private JsonNode getIndexMapping() throws IOException {
        Response response = restClient.performRequest(new Request("GET", "/" + INDEX_NAME + "/_mapping"));
        byte[] body = response.getEntity().getContent().readAllBytes();
        return OBJECT_MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
    }

    private JsonNode searchByTitleAndSection(String query) throws IOException {
        Request request = new Request("POST", "/" + INDEX_NAME + "/_search");
        request.setJsonEntity("""
                {
                  "size": 10,
                  "query": {
                    "multi_match": {
                      "query": %s,
                      "type": "best_fields",
                      "fields": [
                        "metadata.title^2.5",
                        "metadata.section^2.0"
                      ]
                    }
                  }
                }
                """.formatted(OBJECT_MAPPER.writeValueAsString(query)));
        Response response = restClient.performRequest(request);
        byte[] body = response.getEntity().getContent().readAllBytes();
        JsonNode root = OBJECT_MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        return root.path("hits").path("hits");
    }

    private boolean hasSourceId(JsonNode hits, String sourceId) {
        for (JsonNode hit : hits) {
            if (sourceId.equals(hit.path("_source").path("metadata").path("sourceId").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("medical-rag-es-workflow");
        }
        catch (IOException ex) {
            throw new IllegalStateException("创建 Elasticsearch workflow 测试目录失败", ex);
        }
    }

    @TestConfiguration
    static class ElasticsearchWorkflowTestConfiguration {

        @Bean
        @Primary
        EmbeddingModel testEmbeddingModel() {
            return new KeywordEmbeddingModel();
        }

        @Bean
        @Primary
        MedicalAiModelProvider testMedicalAiModelProvider() {
            MedicalAiModelProvider medicalAiModelProvider = Mockito.mock(MedicalAiModelProvider.class);
            ChatModel chatModel = Mockito.mock(ChatModel.class);
            when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
            when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                String contents = prompt.getContents();
                String responseText = contents.contains("Keywords:")
                        ? keywordResponse(contents)
                        : summaryResponse(contents);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
            });
            return medicalAiModelProvider;
        }

        private static String summaryResponse(String contents) {
            if (contents.contains("胸痛")) {
                return "医疗摘要：胸痛危险信号与急诊指征";
            }
            if (contents.contains("发热") || contents.contains("呼吸道")) {
                return "医疗摘要：发热伴呼吸道症状观察与升级就医";
            }
            if (contents.contains("青霉素") || contents.contains("抗菌药")) {
                return "医疗摘要：抗菌药过敏史与紧急处理";
            }
            return "医疗摘要：通用医疗知识摘要";
        }

        private static String keywordResponse(String contents) {
            if (contents.contains("胸痛")) {
                return "胸痛, 急诊, 出汗, 恶心, 呼吸困难";
            }
            if (contents.contains("发热") || contents.contains("呼吸道")) {
                return "发热, 呼吸道, 咳嗽, 居家处理, 就医";
            }
            if (contents.contains("青霉素") || contents.contains("抗菌药")) {
                return "青霉素, 过敏, 抗菌药, 荨麻疹, 紧急处理";
            }
            return "医疗, 知识, 就医, 风险, 评估";
        }
    }

    private static final class KeywordEmbeddingModel implements EmbeddingModel {

        private static final List<String> FEATURES = List.of(
                "胸痛", "出汗", "恶心", "急诊", "呼吸困难",
                "发热", "咳嗽", "呼吸道", "居家处理", "三十九",
                "青霉素", "过敏", "抗菌药", "儿童", "体重",
                "__BIAS__"
        );

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            List<String> instructions = request.getInstructions() == null ? List.of() : request.getInstructions();
            for (int index = 0; index < instructions.size(); index++) {
                results.add(new Embedding(embedText(instructions.get(index)), index));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document.getText());
        }

        @Override
        public float[] embed(String text) {
            return embedText(text);
        }

        @Override
        public int dimensions() {
            return FEATURES.size();
        }

        private float[] embedText(String text) {
            String normalized = normalize(text);
            float[] vector = new float[FEATURES.size()];
            for (int i = 0; i < FEATURES.size(); i++) {
                String feature = FEATURES.get(i);
                if (!"__BIAS__".equals(feature) && normalized.contains(feature)) {
                    vector[i] = 1.0f;
                }
            }
            vector[FEATURES.size() - 1] = 1.0f;
            return vector;
        }

        private String normalize(String text) {
            if (text == null) {
                return "";
            }
            return text.replace("发烧", "发热")
                    .replace("抗生素", "抗菌药")
                    .replace("二十分钟", "20分钟")
                    .replace("二十", "20")
                    .replace("39度", "三十九")
                    .replaceAll("\\s+", "");
        }
    }
}
