package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldExposeExplicitHospitalRequestShortcut() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        assertTrue(resolver.isExplicitHospitalRequest("能不能帮我规划出附近医院的路线"));
    }

    @Test
    void shouldDetectNaturalLanguageRouteRequests() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        assertTrue(resolver.isExplicitHospitalRequest("能否帮我规划最新的医院路线"));
        assertTrue(resolver.isExplicitHospitalRequest("最近的医院怎么走"));
        assertTrue(resolver.isExplicitHospitalRequest("能否帮我找最近的心理医院"));
        assertTrue(resolver.isExplicitHospitalRequest("推荐附近儿童医院"));
        assertTrue(resolver.isExplicitHospitalRequest("口腔医院怎么走"));
        assertTrue(resolver.isExplicitHospitalRequest("去哪个妇幼保健院"));
        assertTrue(resolver.isExplicitHospitalRequest("帮我查附近骨科医院"));
        assertTrue(resolver.isExplicitHospitalRequest("最近的心血管医院"));
        assertTrue(resolver.isExplicitHospitalRequest("帮我看看附近的精神卫生中心"));
        assertTrue(resolver.isExplicitHospitalRequest("帮我导航去附近医院"));
        assertTrue(resolver.isExplicitHospitalRequest("去医院怎么走"));
        assertTrue(resolver.isExplicitHospitalRequest("帮我找附近医院"));
    }

    @Test
    void shouldNotTreatGenericHospitalQuestionsAsRouteRequests() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        assertFalse(resolver.isExplicitHospitalRequest("我需要去医院吗"));
        assertFalse(resolver.isExplicitHospitalRequest("最新医院新闻"));
        assertFalse(resolver.isExplicitHospitalRequest("医院报告什么时候生成"));
        assertFalse(resolver.isExplicitHospitalRequest("我最近失眠要不要就医"));
        assertFalse(resolver.isExplicitHospitalRequest("我最近失眠要不要去医院"));
        assertFalse(resolver.isExplicitHospitalRequest("我要不要去心理医院"));
        assertFalse(resolver.isExplicitHospitalRequest("需不需要去儿童医院"));
        assertFalse(resolver.isExplicitHospitalRequest("该不该去口腔医院"));
        assertFalse(resolver.isExplicitHospitalRequest("心理医院怎么样"));
        assertFalse(resolver.isExplicitHospitalRequest("最近心理学新闻"));
        assertFalse(resolver.isExplicitHospitalRequest("附近药房"));
        assertFalse(resolver.isExplicitHospitalRequest("最近诊所"));
    }

    @Test
    void shouldKeepPreviewPreparationConsistentForNaturalLanguageRouteRequest() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread_2",
                "usr_2",
                "建议尽快线下评估。",
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

        String prompt = "能否帮我规划最新的医院路线";
        assertTrue(resolver.isExplicitHospitalRequest(prompt));
        assertTrue(resolver.shouldPrepareChatPreview(prompt, chatResult));
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

    @Test
    void shouldResolvePsychiatryProfileForNearbyPsychHospitalRequest() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "近期失眠和情绪低落，想找最近的心理医院",
                "建议尽快进行心理科或精神卫生专科评估",
                "",
                List.of("失眠", "情绪低落"),
                List.of("尽快线下评估"),
                List.of("出现自伤想法"),
                "建议尽快线下评估"
        );

        MedicalPlanningIntent intent = resolver.resolve(
                report,
                new StructuredMedicalReply(
                        "中风险",
                        "存在持续失眠与情绪问题",
                        List.of("需要心理专科评估"),
                        List.of("尽快就医"),
                        List.of("出现自伤想法"),
                        List.of(),
                        "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
                ),
                "能否帮我找最近的心理医院",
                ReportTriggerLevel.RECOMMENDED
        );

        assertTrue(intent.explicitHospitalRequest());
        assertEquals("psychiatry", intent.profileId());
        assertEquals("精神卫生中心", intent.hospitalKeyword());
        assertTrue(intent.planningRequested());
    }

    @Test
    void shouldResolveObstetricProfileForSpecificFacilityLookup() {
        DefaultMedicalPlanningIntentResolver resolver =
                new DefaultMedicalPlanningIntentResolver(new MedicalReportPlanningProperties());

        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "INSUFFICIENT_INFORMATION",
                "中风险",
                "希望尽快找到合适的妇幼保健院",
                "需要线下妇产专科进一步评估",
                "",
                List.of("需要妇产专科评估"),
                List.of("尽快线下就医"),
                List.of("阴道流血加重"),
                "建议尽快线下评估"
        );

        MedicalPlanningIntent intent = resolver.resolve(
                report,
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"),
                "去哪个妇幼保健院",
                ReportTriggerLevel.RECOMMENDED
        );

        assertTrue(intent.explicitHospitalRequest());
        assertEquals("obstetric", intent.profileId());
        assertEquals("妇产医院", intent.hospitalKeyword());
        assertTrue(intent.planningRequested());
    }
}
