package com.tay.medicalagent.app;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeIngestionService;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MedicalAppTest {

    private static final Path TEST_RAG_DIR = createTempDirectory();

    @Resource
    private MedicalApp medicalApp;

    @Resource
    private KnowledgeIngestionService knowledgeIngestionService;

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
    void clearMemory() {
        medicalApp.clearMemory();
    }

    @Test
    void doChatWithRag() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());
        KnowledgeBaseRefreshResult refreshResult = ensureKnowledgeBaseReady();
        assertTrue(refreshResult.documentCount() > 0);
        assertTrue(refreshResult.sourceFileCount() > 0);

        String threadId = "chat-demo-test";
        String userId = "xiaoma-test";

        MedicalChatResult first = medicalApp.doChat(
                "我叫小马，今年23岁，男性。我现在持续胸痛20分钟，同时头晕、一直冒冷汗、恶心、浑身无力，我对青霉素过敏，也对酒精过敏。",
                threadId,
                userId
        );
        assertNotNull(first);
        assertEquals(threadId, first.threadId());
        assertEquals(userId, first.userId());
        assertTrue(first.ragApplied());
        assertFalse(first.sources().isEmpty());
        assertTrue(first.sources().stream()
                .anyMatch(source -> "kb-chest-pain-triage".equals(source.sourceId())));
        assertFalse(first.reply().isBlank());

        Map<String, Object> profile = medicalApp.getUserProfileMemory(userId).orElse(Map.of());
        assertFalse(profile.isEmpty());

        MedicalChatResult second = medicalApp.doChat(
                "如果接下来10分钟还是持续胸痛并且冒冷汗，我现在最应该立刻做什么？请结合我们刚才的对话回答。",
                threadId,
                userId
        );
        assertNotNull(second);
        assertEquals(threadId, second.threadId());
        assertEquals(userId, second.userId());
        assertFalse(second.reply().isBlank());
        assertFalse(second.sources().isEmpty());

        MedicalChatResult reportResult = medicalApp.doChat(
                "请基于当前线程对话生成诊断报告",
                threadId,
                userId
        );
        assertNotNull(reportResult);
        assertEquals(threadId, reportResult.threadId());
        assertEquals(userId, reportResult.userId());
        assertTrue(reportResult.reportGenerated());
        assertNotNull(reportResult.report());
        assertFalse(reportResult.report().reportTitle().isBlank());
        assertFalse(reportResult.report().assistantReply().isBlank());
        assertFalse(reportResult.report().mainBasis().isEmpty());
        assertFalse(reportResult.report().nextStepSuggestions().isEmpty());

        System.out.println();
        System.out.println("[RAG_REFRESH]");
        System.out.println(refreshResult);
        System.out.println("[TURN_1_REPLY]");
        System.out.println(first.reply());
        System.out.println("[TURN_1_SOURCES]");
        System.out.println(first.sources());
        System.out.println("[TURN_2_REPLY]");
        System.out.println(second.reply());
        System.out.println("[TURN_2_SOURCES]");
        System.out.println(second.sources());
        System.out.println("[REPORT]");
        System.out.println(reportResult.report());
    }

    @Test
    void doChatWithRagShouldReindexFreshKnowledgeBaseForDebugging() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());
        KnowledgeBaseRefreshResult refreshResult = ensureKnowledgeBaseReady();

        assertTrue(refreshResult.documentCount() > 0);
        assertTrue(refreshResult.deletedDocumentCount() >= 0);
        assertFalse(refreshResult.indexedSourceIds().isEmpty());

        MedicalChatResult result = medicalApp.doChat(
                "持续胸痛20分钟，伴出汗和恶心，现在应该怎么办？",
                "chat-demo-debug",
                "chat-demo-debug-user"
        );

        assertNotNull(result);
        assertTrue(result.ragApplied());
        assertFalse(result.sources().isEmpty());
    }


    @Test
    void saveUserProfileMemoryShouldMergeStableFacts() {
        medicalApp.saveUserProfileMemory("user_001", Map.of(
                "name", "小张",
                "age", 30
        ));
        medicalApp.saveUserProfileMemory("user_001", Map.of(
                "allergies", List.of("青霉素"),
                "gender", "男"
        ));

        Map<String, Object> profile = medicalApp.getUserProfileMemory("user_001").orElseThrow();
        assertEquals("小张", profile.get("name"));
        assertEquals(30, profile.get("age"));
        assertEquals("男", profile.get("gender"));
        assertEquals(List.of("青霉素"), profile.get("allergies"));
    }

    @Test
    void differentUsersShouldHaveIsolatedLongTermMemory() {
        medicalApp.saveUserProfileMemory("user_a", Map.of("name", "小王"));
        medicalApp.saveUserProfileMemory("user_b", Map.of("name", "小李"));

        Map<String, Object> userAProfile = medicalApp.getUserProfileMemory("user_a").orElseThrow();
        Map<String, Object> userBProfile = medicalApp.getUserProfileMemory("user_b").orElseThrow();

        assertEquals("小王", userAProfile.get("name"));
        assertEquals("小李", userBProfile.get("name"));
        assertFalse(userAProfile.equals(userBProfile));
    }

    @Test
    void longTermMemoryShouldBeReadableAcrossThreads() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());

        String userId = "user_memory_demo";

        MedicalChatResult first = medicalApp.doChat(
                "我叫小张，今年30岁，对青霉素过敏。请记住这些长期信息。",
                "medical-thread-1",
                userId
        );
        assertNotNull(first);

        Map<String, Object> profile = medicalApp.getUserProfileMemory(userId).orElseThrow();
        assertEquals("小张", profile.get("name"));
        assertEquals(30, profile.get("age"));
        assertEquals(List.of("青霉素"), profile.get("allergies"));

        MedicalChatResult second = medicalApp.doChat(
                "请根据你记住的长期信息，只回答我的名字。",
                "medical-thread-2",
                userId
        );
        assertNotNull(second);
        assertFalse(second.reply().isBlank());
        assertTrue(second.reply().contains("小张"));
    }

    @Test
    void ragChatShouldReturnSourcesAndReply() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());
        ensureKnowledgeBaseReady();

        String threadId = "rag-answer-thread";
        String userId = "rag-answer-user";

        MedicalChatResult result = medicalApp.doChat(
                "持续胸痛20分钟，伴出汗和恶心，现在应该怎么办？",
//                "我脑子有点问题",
                threadId,
                userId
        );

        assertNotNull(result);
        assertTrue(result.ragApplied());
        assertFalse(result.sources().isEmpty());
        assertTrue(result.sources().stream()
                .anyMatch(source -> "kb-chest-pain-triage".equals(source.sourceId())));
        assertFalse(result.reply().isBlank());

        System.out.println();
        System.out.println("[RAG_APPLIED]");
        System.out.println(result.ragApplied());
        System.out.println("[RAG_SOURCES]");
        System.out.println(result.sources());
        System.out.println("[RAG_REPLY]");
        System.out.println(result.reply());
    }

    @Test
    void greetingShouldNotTriggerRag() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());
        ensureKnowledgeBaseReady();

        MedicalChatResult result = medicalApp.doChat(
                "你好",
                "rag-no-hit-thread",
                "rag-no-hit-user"
        );

        assertNotNull(result);
        assertFalse(result.ragApplied());
        assertTrue(result.sources().isEmpty());
        assertFalse(result.reply().isBlank());

        System.out.println();
        System.out.println("[RAG_APPLIED]");
        System.out.println(result.ragApplied());
        System.out.println("[RAG_SOURCES]");
        System.out.println(result.sources());
        System.out.println("[RAG_REPLY]");
        System.out.println(result.reply());
    }

    @Test
    void chatDemoShouldPrintConversation() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());

        String threadId = "medical-demo-thread";
        String userId = "medical-demo-user";

        chatAndPrint(
                "我32岁，女性，发烧39度伴咳嗽2天，没有胸痛，也没有明显呼吸困难。我现在最需要注意什么？",
                threadId,
                userId
        );
        chatAndPrint(
                "如果今晚开始出现气喘加重或者胸闷，我应该什么时候立刻去医院？",
                threadId,
                userId
        );
        chatAndPrint(
                "请基于我们刚才的对话，用“当前风险等级、主要依据、下一步建议、何时必须升级就医”四部分重新整理给我。",
                threadId,
                userId
        );
    }

    @Test
    void doChatWithReportShouldReturnStructuredReport() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());

        MedicalDiagnosisReport report = medicalApp.doChatWithReport(
                "我持续胸痛20分钟，伴随出汗和恶心，现在应该怎么办？",
                "medical-report-thread",
                "medical-report-user"
        );

        assertNotNull(report);
        assertFalse(report.assistantReply().isBlank());
        assertFalse(report.reportTitle().isBlank());

        System.out.println();
        System.out.println("[MEDICAL_REPORT]");
        System.out.println(report);
    }

    @Test
    void reportRequestShouldGenerateReportFromCurrentThread() throws GraphRunnerException {
        Assumptions.assumeTrue(hasDashScopeApiKey());

        String threadId = "medical-report-request-thread";
        String userId = "medical-report-request-user";

        MedicalChatResult first = medicalApp.doChat(
                "我持续胸痛20分钟，伴随出汗和恶心，现在应该怎么办？",
                threadId,
                userId
        );
        assertNotNull(first);
        assertFalse(first.reply().isBlank());

        MedicalChatResult second = medicalApp.doChat("请生成诊断报告", threadId, userId);
        assertNotNull(second);
        assertTrue(second.reportGenerated());
        assertNotNull(second.report());
        assertFalse(second.report().reportTitle().isBlank());

        System.out.println();
        System.out.println("[REPORT_REQUEST_RESULT]");
        System.out.println(second.report());
    }

    private boolean hasDashScopeApiKey() {
        String apiKey = System.getProperty("DASHSCOPE_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }

        apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }

        apiKey = System.getenv("AI_DASHSCOPE_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    private MedicalChatResult chatAndPrint(String prompt, String threadId, String userId) throws GraphRunnerException {
        MedicalChatResult response = medicalApp.doChat(prompt, threadId, userId);
        assertNotNull(response);
        assertFalse(response.reply().isBlank());

        System.out.println();
        System.out.println("[USER]");
        System.out.println(prompt);
        System.out.println("[ASSISTANT]");
        System.out.println(response.reply());
        if (response.reportAvailable()) {
            System.out.println("[REPORT_HINT]");
            System.out.println(response.reportReason());
        }

        return response;
    }

    private KnowledgeBaseRefreshResult ensureKnowledgeBaseReady() {
        return knowledgeIngestionService.reindexKnowledgeBase();
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("medical-app-test-rag");
        }
        catch (IOException ex) {
            throw new IllegalStateException("创建 MedicalAppTest 临时 RAG 目录失败", ex);
        }
    }
}
