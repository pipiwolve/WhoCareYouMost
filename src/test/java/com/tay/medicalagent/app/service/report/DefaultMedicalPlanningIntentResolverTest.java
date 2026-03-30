package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMedicalPlanningIntentResolverTest {

    @Test
    void shouldTriggerPreviewWhenUserExplicitlyRequestsNearbyHospital() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread_1",
                "usr_1",
                "建议进一步观察。",
                false,
                "",
                ReportTriggerLevel.NONE,
                "",
                false,
                null,
                false,
                List.of(),
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。")
        );

        assertTrue(resolver.shouldPrepareChatPreview("帮我找一下附近医院", chatResult));
    }

    @Test
    void shouldResolveCardiacProfileForChestPainScenario() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "胸闷伴心慌，活动后更明显",
                "警惕心脏相关问题，建议尽快线下评估",
                "",
                List.of("胸闷", "心慌"),
                List.of("尽快线下评估"),
                List.of("胸痛加重"),
                "警惕心脏相关问题"
        );

        MedicalPlanningIntent intent = resolver.resolve(
                report,
                new StructuredMedicalReply(
                        "中风险",
                        "胸闷伴心慌",
                        List.of("活动后加重"),
                        List.of("尽快就医"),
                        List.of("胸痛加重"),
                        List.of(),
                        "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
                ),
                "我胸闷心慌，帮我看看附近医院",
                ReportTriggerLevel.RECOMMENDED
        );

        assertEquals("cardiac", intent.profileId());
        assertEquals("心血管医院", intent.hospitalKeyword());
        assertEquals(12000, intent.aroundRadiusMeters());
        assertTrue(intent.preferTier3a());
        assertTrue(intent.planningRequested());
    }
}
