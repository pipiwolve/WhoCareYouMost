package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalChatPreviewReplyDecoratorTest {

    private final MedicalChatPreviewReplyDecorator decorator = new MedicalChatPreviewReplyDecorator();

    @Test
    void shouldAppendRouteReadySuffixWhenRoutesAreAvailable() {
        String reply = decorator.decorateExplicitHospitalRequestReply(
                "建议尽快线下评估。",
                snapshotWithPlanning(new MedicalHospitalPlanningSummary(List.of(), true, "", "ok"))
        );

        assertTrue(reply.endsWith("已为您规划附近医院路线，请查看下方推荐医院和路线。"));
    }

    @Test
    void shouldAppendLocationMissingSuffixWhenCoordinatesAreMissing() {
        String reply = decorator.decorateExplicitHospitalRequestReply(
                "建议尽快线下评估。",
                snapshotWithPlanning(new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度，无法进行就近医院规划", "location_missing"))
        );

        assertTrue(reply.endsWith("已识别到您需要附近医院路线。请授权定位后，我会继续为您规划。"));
    }

    @Test
    void shouldAppendFallbackSuffixWhenRoutesAreUnavailable() {
        String reply = decorator.decorateExplicitHospitalRequestReply(
                "建议尽快线下评估。",
                snapshotWithPlanning(new MedicalHospitalPlanningSummary(List.of(), false, "MCP 路线服务暂不可用", "mcp_unavailable"))
        );

        assertTrue(reply.endsWith("已为您准备附近医院候选，路线结果以卡片内状态为准。"));
    }

    private MedicalReportSnapshot snapshotWithPlanning(MedicalHospitalPlanningSummary planningSummary) {
        return new MedicalReportSnapshot(
                "sess_preview",
                "thread_preview",
                "usr_preview",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                new MedicalDiagnosisReport("报告", true, "CONFIRMED", "", "", "", "", List.of(), List.of(), List.of(), "reply"),
                planningSummary
        );
    }
}
