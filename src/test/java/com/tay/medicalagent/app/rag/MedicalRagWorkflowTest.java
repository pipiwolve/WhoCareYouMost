package com.tay.medicalagent.app.rag;

import com.tay.medicalagent.app.rag.evaluation.OfflineRagEvaluationService;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeIngestionService;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.model.RagEvaluationSummary;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.junit.jupiter.api.BeforeEach;
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

import jakarta.annotation.Resource;
import java.io.IOException;
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
class MedicalRagWorkflowTest {

    private static final Path TEST_RAG_DIR = createTempDirectory();

    @Resource
    private KnowledgeIngestionService knowledgeIngestionService;

    @Resource
    private MedicalKnowledgeRetriever medicalKnowledgeRetriever;

    @Resource
    private OfflineRagEvaluationService offlineRagEvaluationService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("medical.rag.enabled", () -> "true");
        registry.add("medical.rag.bootstrap-on-startup", () -> "false");
        registry.add("medical.rag.vector-store.type", () -> "simple");
        registry.add("medical.rag.vector-store.simple.store-file",
                () -> TEST_RAG_DIR.resolve("simple-vector-store.json").toString());
        registry.add("medical.rag.vector-store.manifest-file",
                () -> TEST_RAG_DIR.resolve("knowledge-manifest.json").toString());
    }

    @BeforeEach
    void reindexKnowledgeBase() {
        knowledgeIngestionService.reindexKnowledgeBase();
    }

    @Test
    void reindexShouldLoadBundledKnowledgeBase() {
        KnowledgeBaseRefreshResult result = knowledgeIngestionService.reindexKnowledgeBase();

        assertTrue(result.sourceFileCount() >= 4);
        assertTrue(result.documentCount() >= 4);
        assertTrue(result.indexedSourceIds().contains("kb-chest-pain-triage"));
        assertTrue(result.indexedSourceIds().contains("kb-fever-respiratory-care"));
    }

    @Test
    void retrieverShouldReturnChestPainKnowledge() {
        RagContext ragContext = medicalKnowledgeRetriever.retrieve("持续胸痛二十分钟，伴出汗和恶心，现在应该怎么办？");

        assertTrue(ragContext.applied());
        assertFalse(ragContext.contextText().isBlank());
        assertTrue(ragContext.sources().stream()
                .map(KnowledgeSource::sourceId)
                .anyMatch("kb-chest-pain-triage"::equals));
        assertTrue(ragContext.passages().stream()
                .allMatch(passage -> passage.metadata().containsKey("section_summary")));
        assertTrue(ragContext.passages().stream()
                .allMatch(passage -> passage.metadata().containsKey("excerpt_keywords")));
        assertFalse(ragContext.contextText().contains("section_summary"));
        assertFalse(ragContext.contextText().contains("excerpt_keywords"));
        assertFalse(ragContext.contextText().contains("医疗摘要："));
        System.out.println("[RAG_QUERY] " + ragContext.query());
        System.out.println("[RAG_CONTEXT] " + ragContext.contextText());
        System.out.println("[RAG_SOURCES] " + ragContext.sources());
    }

    @Test
    void reindexShouldPersistEnrichedMetadataToSimpleVectorStoreFile() throws IOException {
        Path storeFile = TEST_RAG_DIR.resolve("simple-vector-store.json");
        Path manifestFile = TEST_RAG_DIR.resolve("knowledge-manifest.json");

        assertTrue(Files.exists(storeFile));
        assertTrue(Files.exists(manifestFile));

        String storeJson = Files.readString(storeFile);
        String manifestJson = Files.readString(manifestFile);

        assertTrue(storeJson.contains("\"section_summary\""));
        assertTrue(storeJson.contains("\"excerpt_keywords\""));
        assertTrue(storeJson.contains("\"sourceId\""));
        assertTrue(storeJson.contains("\"title\""));
        assertTrue(storeJson.contains("\"section\""));
        assertTrue(manifestJson.contains("\""));
    }

    @Test
    void offlineEvaluationShouldHitBundledCases() {
        RagEvaluationSummary summary = offlineRagEvaluationService.runDefaultEvaluation();

        assertEquals(3, summary.totalCases());
        assertEquals(3, summary.hitCases());
        assertTrue(summary.hitRate() >= 1.0);
        assertTrue(summary.meanReciprocalRank() > 0);
        assertTrue(summary.averageKeywordCoverage() > 0.5);
    }

    @TestConfiguration
    static class RagWorkflowTestConfiguration {

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
            if (contents.contains("发热") || contents.contains("咳嗽")) {
                return "医疗摘要：发热伴呼吸道症状观察与升级就医";
            }
            if (contents.contains("青霉素") || contents.contains("抗菌药")) {
                return "医疗摘要：抗菌药过敏史与紧急处理";
            }
            if (contents.contains("儿童") || contents.contains("退热")) {
                return "医疗摘要：儿童退热药使用前信息确认";
            }
            return "医疗摘要：通用医疗知识摘要";
        }

        private static String keywordResponse(String contents) {
            if (contents.contains("胸痛")) {
                return "胸痛, 急诊, 出汗, 恶心, 呼吸困难";
            }
            if (contents.contains("发热") || contents.contains("咳嗽")) {
                return "发热, 咳嗽, 气喘, 胸闷, 就医";
            }
            if (contents.contains("青霉素") || contents.contains("抗菌药")) {
                return "青霉素, 过敏, 抗菌药, 荨麻疹, 紧急处理";
            }
            if (contents.contains("儿童") || contents.contains("退热")) {
                return "儿童, 退热药, 体重, 高热, 就医";
            }
            return "医疗, 知识, 就医, 风险, 评估";
        }
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("medical-rag-workflow");
        }
        catch (IOException ex) {
            throw new IllegalStateException("创建测试目录失败", ex);
        }
    }

    private static final class KeywordEmbeddingModel implements EmbeddingModel {

        private static final List<String> FEATURES = List.of(
                "胸痛", "出汗", "恶心", "急诊", "呼吸困难",
                "发热", "咳嗽", "气喘", "胸闷", "三十九",
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
            // Ensure non-zero norm vectors for any document/query text.
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
