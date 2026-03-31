package com.tay.medicalagent.app;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.support.LiveTestSupport;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "medical.rag.enabled=false",
        "medical.report.planning.mode=off",
        "spring.ai.mcp.client.enabled=false"
})
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class MedicalAppDashScopeLiveIT {

    @Resource
    private MedicalApp medicalApp;

    @BeforeEach
    void clearMemory() {
        LiveTestSupport.requireDashScopeApiKey();
        medicalApp.clearMemory();
    }

    @Test
    void shouldChatAndGenerateReportWithRealDashScope() throws GraphRunnerException {
        String threadId = "live-dashscope-thread";
        String userId = "live-dashscope-user";

        MedicalChatResult first = medicalApp.doChat(
                "我32岁，男性，持续胸痛20分钟，伴出汗和恶心，请判断风险并告诉我是否需要立刻就医。",
                threadId,
                userId
        );
        assertNotNull(first);
        assertFalse(first.reply().isBlank());

        MedicalChatResult second = medicalApp.doChat(
                "请基于当前线程直接生成诊断报告。",
                threadId,
                userId
        );
        assertNotNull(second);
        assertTrue(second.reportGenerated());
        assertNotNull(second.report());
        assertFalse(second.report().reportTitle().isBlank());
        assertFalse(second.report().assistantReply().isBlank());
    }

    @Test
    void shouldGenerateStructuredReportWithRealDashScope() throws GraphRunnerException {
        LiveTestSupport.requireDashScopeApiKey();

        MedicalDiagnosisReport report = medicalApp.doChatWithReport(
                "我持续胸痛20分钟，伴随出汗和恶心，现在应该怎么办？",
                "live-medical-report-thread",
                "live-medical-report-user"
        );

        assertNotNull(report);
        assertFalse(report.reportTitle().isBlank());
        assertFalse(report.assistantReply().isBlank());
        assertFalse(report.mainBasis().isEmpty());
    }
}
