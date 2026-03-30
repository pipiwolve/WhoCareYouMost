package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMedicalReportServiceTest {

    @Test
    void shouldUseProfileNameAsReportTitleWhenGeneratingReport() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(userProfileService.normalizeUserId("usr_zhangsan")).thenReturn("usr_zhangsan");
        when(userProfileService.buildProfileContext("usr_zhangsan")).thenReturn("[MEDICAL_LONG_TERM_MEMORY]\n姓名：张三");
        when(userProfileService.getUserProfileMemory("usr_zhangsan")).thenReturn(Optional.of(Map.of("name", "张三")));

        List<Message> conversation = List.of(
                new UserMessage("我这两天头晕。"),
                new AssistantMessage("建议先休息观察。")
        );
        when(threadConversationService.getThreadConversation("thread-report-1")).thenReturn(conversation);
        when(threadConversationService.buildConversationTranscript(conversation))
                .thenReturn("用户：我这两天头晕。\n助手：建议先休息观察。");
        when(threadConversationService.findLatestAssistantReply(conversation)).thenReturn("建议先休息观察。");
        String responseJson = """
                {
                  "reportTitle": "thread-report-1的医疗诊断报告",
                  "shouldGenerateReport": true,
                  "answerStatus": "CONFIRMED",
                  "currentRiskLevel": "低风险",
                  "patientSummary": "头晕两天",
                  "preliminaryAssessment": "考虑疲劳或睡眠不足",
                  "uncertaintyReason": "",
                  "mainBasis": ["症状较轻"],
                  "nextStepSuggestions": ["休息补水"],
                  "escalationCriteria": ["症状持续加重"],
                  "assistantReply": "建议先休息观察。"
                }
                """;
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(responseJson))
        )));

        DefaultMedicalReportService service = new DefaultMedicalReportService(
                medicalAiModelProvider,
                userProfileService,
                threadConversationService
        );

        var report = service.generateReportFromThread("thread-report-1", "usr_zhangsan");

        assertEquals("张三的医疗诊断报告", report.reportTitle());
    }
}
