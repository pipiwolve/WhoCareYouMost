package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.chat.NormalizedMedicalReply;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalPlanningIntentResolver;
import com.tay.medicalagent.app.service.report.MedicalReportService;
import com.tay.medicalagent.app.service.report.MedicalReportTriggerPolicy;
import com.tay.medicalagent.app.service.report.ReportTriggerDecision;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import com.tay.medicalagent.app.service.runtime.MedicalAgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMedicalChatServiceTest {

    @Test
    void shouldForceRecommendedReportTriggerForExplicitHospitalRequest() throws Exception {
        MedicalAgentRuntime medicalAgentRuntime = mock(MedicalAgentRuntime.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        MedicalReportService medicalReportService = mock(MedicalReportService.class);
        MedicalReportTriggerPolicy medicalReportTriggerPolicy = mock(MedicalReportTriggerPolicy.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalRagContextHolder medicalRagContextHolder = new MedicalRagContextHolder();
        MedicalReplyFormatter medicalReplyFormatter = mock(MedicalReplyFormatter.class);
        MedicalPlanningIntentResolver medicalPlanningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        DefaultMedicalChatService service = new DefaultMedicalChatService(
                medicalAgentRuntime,
                threadConversationService,
                medicalReportService,
                medicalReportTriggerPolicy,
                userProfileService,
                medicalRagContextHolder,
                medicalReplyFormatter,
                medicalPlanningIntentResolver
        );

        StructuredMedicalReply structuredReply = StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。");
        when(userProfileService.normalizeUserId("usr_route")).thenReturn("usr_route");
        when(medicalReportService.isReportRequest("能否帮我规划最新的医院路线")).thenReturn(false);
        when(medicalAgentRuntime.doChatMessage("能否帮我规划最新的医院路线", "thread_route", "usr_route"))
                .thenReturn(AssistantMessage.builder().content("建议尽快线下评估。").build());
        when(medicalReplyFormatter.normalize("建议尽快线下评估。"))
                .thenReturn(new NormalizedMedicalReply("建议尽快线下评估。", structuredReply));
        when(threadConversationService.getThreadConversation("thread_route")).thenReturn(List.of());
        when(medicalReportTriggerPolicy.evaluate(structuredReply, "建议尽快线下评估。", List.of()))
                .thenReturn(ReportTriggerDecision.none());
        when(medicalPlanningIntentResolver.isExplicitHospitalRequest("能否帮我规划最新的医院路线")).thenReturn(true);

        var result = service.doChat("能否帮我规划最新的医院路线", "thread_route", "usr_route");

        assertTrue(result.reportAvailable());
        assertEquals(ReportTriggerLevel.RECOMMENDED, result.reportTriggerLevel());
        assertEquals("已按您的请求准备附近医院路线，可查看详情。", result.reportActionText());
    }

    @Test
    void shouldForceRecommendedReportTriggerForSpecializedHospitalRequest() throws Exception {
        MedicalAgentRuntime medicalAgentRuntime = mock(MedicalAgentRuntime.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        MedicalReportService medicalReportService = mock(MedicalReportService.class);
        MedicalReportTriggerPolicy medicalReportTriggerPolicy = mock(MedicalReportTriggerPolicy.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalRagContextHolder medicalRagContextHolder = new MedicalRagContextHolder();
        MedicalReplyFormatter medicalReplyFormatter = mock(MedicalReplyFormatter.class);
        MedicalPlanningIntentResolver medicalPlanningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        DefaultMedicalChatService service = new DefaultMedicalChatService(
                medicalAgentRuntime,
                threadConversationService,
                medicalReportService,
                medicalReportTriggerPolicy,
                userProfileService,
                medicalRagContextHolder,
                medicalReplyFormatter,
                medicalPlanningIntentResolver
        );

        StructuredMedicalReply structuredReply = StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。");
        when(userProfileService.normalizeUserId("usr_psych")).thenReturn("usr_psych");
        when(medicalReportService.isReportRequest("帮我找最近的心理医院")).thenReturn(false);
        when(medicalAgentRuntime.doChatMessage("帮我找最近的心理医院", "thread_psych", "usr_psych"))
                .thenReturn(AssistantMessage.builder().content("建议尽快线下精神专科评估。").build());
        when(medicalReplyFormatter.normalize("建议尽快线下精神专科评估。"))
                .thenReturn(new NormalizedMedicalReply("建议尽快线下精神专科评估。", structuredReply));
        when(threadConversationService.getThreadConversation("thread_psych")).thenReturn(List.of());
        when(medicalReportTriggerPolicy.evaluate(structuredReply, "建议尽快线下精神专科评估。", List.of()))
                .thenReturn(ReportTriggerDecision.none());
        when(medicalPlanningIntentResolver.isExplicitHospitalRequest("帮我找最近的心理医院")).thenReturn(true);

        var result = service.doChat("帮我找最近的心理医院", "thread_psych", "usr_psych");

        assertTrue(result.reportAvailable());
        assertEquals(ReportTriggerLevel.RECOMMENDED, result.reportTriggerLevel());
        assertEquals("已按您的请求准备附近医院路线，可查看详情。", result.reportActionText());
    }

    @Test
    void shouldNotForceRecommendedReportTriggerForPharmacyOrClinicRequest() throws Exception {
        MedicalAgentRuntime medicalAgentRuntime = mock(MedicalAgentRuntime.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        MedicalReportService medicalReportService = mock(MedicalReportService.class);
        MedicalReportTriggerPolicy medicalReportTriggerPolicy = mock(MedicalReportTriggerPolicy.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalRagContextHolder medicalRagContextHolder = new MedicalRagContextHolder();
        MedicalReplyFormatter medicalReplyFormatter = mock(MedicalReplyFormatter.class);
        MedicalPlanningIntentResolver medicalPlanningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        DefaultMedicalChatService service = new DefaultMedicalChatService(
                medicalAgentRuntime,
                threadConversationService,
                medicalReportService,
                medicalReportTriggerPolicy,
                userProfileService,
                medicalRagContextHolder,
                medicalReplyFormatter,
                medicalPlanningIntentResolver
        );

        StructuredMedicalReply structuredReply = StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。");
        when(userProfileService.normalizeUserId("usr_pharmacy")).thenReturn("usr_pharmacy");
        when(medicalReportService.isReportRequest("附近药房")).thenReturn(false);
        when(medicalAgentRuntime.doChatMessage("附近药房", "thread_pharmacy", "usr_pharmacy"))
                .thenReturn(AssistantMessage.builder().content("如需买药建议前往正规药房。").build());
        when(medicalReplyFormatter.normalize("如需买药建议前往正规药房。"))
                .thenReturn(new NormalizedMedicalReply("如需买药建议前往正规药房。", structuredReply));
        when(threadConversationService.getThreadConversation("thread_pharmacy")).thenReturn(List.of());
        when(medicalReportTriggerPolicy.evaluate(structuredReply, "如需买药建议前往正规药房。", List.of()))
                .thenReturn(ReportTriggerDecision.none());
        when(medicalPlanningIntentResolver.isExplicitHospitalRequest("附近药房")).thenReturn(false);

        var result = service.doChat("附近药房", "thread_pharmacy", "usr_pharmacy");

        assertFalse(result.reportAvailable());
        assertEquals(ReportTriggerLevel.NONE, result.reportTriggerLevel());
        assertEquals("", result.reportActionText());
    }
}
