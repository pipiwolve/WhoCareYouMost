package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalChatPreviewReportFactoryTest {

    @Test
    void shouldBuildExportablePreviewReportFromStructuredReply() {
        UserProfileService userProfileService = mock(UserProfileService.class);
        when(userProfileService.normalizeUserId("usr_preview")).thenReturn("usr_preview");
        when(userProfileService.getUserProfileMemory("usr_preview"))
                .thenReturn(Optional.of(Map.of("name", "张三")));
        MedicalChatPreviewReportFactory factory = new MedicalChatPreviewReportFactory(userProfileService);

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread_preview",
                "usr_preview",
                "建议尽快就医。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                new StructuredMedicalReply(
                        "中风险",
                        "胸闷伴心慌，建议尽快线下评估。",
                        List.of("胸闷", "心慌"),
                        List.of("尽快线下评估"),
                        List.of("胸痛加重"),
                        List.of(),
                        "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
                )
        );

        var report = factory.build("thread_preview", "usr_preview", "我胸闷，帮我规划附近医院路线", chatResult);

        assertTrue(report.shouldGenerateReport());
        assertEquals("张三的医疗诊断报告", report.reportTitle());
        assertEquals("CONFIRMED", report.answerStatus());
        assertEquals("已识别症状：胸闷；心慌。", report.patientSummary());
        assertEquals("胸闷伴心慌，建议尽快线下评估。", report.preliminaryAssessment());
        assertEquals(List.of("胸闷", "心慌"), report.mainBasis());
        assertEquals(List.of("尽快线下评估"), report.nextStepSuggestions());
    }

    @Test
    void shouldFallbackToDefaultPreviewWhenMedicalSignalsAreSparse() {
        UserProfileService userProfileService = mock(UserProfileService.class);
        when(userProfileService.normalizeUserId("usr_preview")).thenReturn("usr_preview");
        when(userProfileService.getUserProfileMemory("usr_preview")).thenReturn(Optional.empty());
        MedicalChatPreviewReportFactory factory = new MedicalChatPreviewReportFactory(userProfileService);

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread_preview",
                "usr_preview",
                "可以先为您准备附近医院路线。",
                false,
                "",
                ReportTriggerLevel.RECOMMENDED,
                "",
                false,
                null,
                false,
                List.of(),
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。")
        );

        var report = factory.build("thread_preview", "usr_preview", "帮我规划附近医院路线", chatResult);

        assertTrue(report.shouldGenerateReport());
        assertEquals("INSUFFICIENT_INFORMATION", report.answerStatus());
        assertEquals("当前主要诉求：规划附近医院路线。", report.patientSummary());
        assertEquals("当前缺少充分病情信息，已先按通用医院路线需求处理；补充症状后可优化医院匹配。", report.preliminaryAssessment());
        assertEquals(List.of("用户明确请求规划附近医院路线"), report.mainBasis());
        assertEquals(
                List.of("如已授权定位，可查看附近医院与路线", "如有明确症状，请继续补充以优化医院匹配"),
                report.nextStepSuggestions()
        );
    }
}
