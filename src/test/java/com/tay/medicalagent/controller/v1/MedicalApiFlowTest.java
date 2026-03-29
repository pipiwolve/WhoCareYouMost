package com.tay.medicalagent.controller.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import com.tay.medicalagent.web.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({UserProfileController.class, ChatController.class, ReportController.class})
@Import({ControllerTestConfig.class, GlobalExceptionHandler.class})
class MedicalApiFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MedicalApp medicalApp;

    @Test
    void profileChatAndReportFlowShouldUseOnlyUserIdAndSessionId() throws Exception {
        when(medicalApp.createThreadId()).thenReturn("thread-flow-1");

        MvcResult profileResult = mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "王五",
                                  "age": 25,
                                  "gender": "OTHER"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode profileBody = objectMapper.readTree(profileResult.getResponse().getContentAsString());
        String userId = profileBody.path("data").path("userId").asText();
        String sessionId = profileBody.path("data").path("sessionId").asText();

        when(medicalApp.doChat("我头晕", "thread-flow-1", userId)).thenReturn(new MedicalChatResult(
                "thread-flow-1",
                userId,
                "建议先休息并补充水分。",
                true,
                "当前问诊信息较完整，可按需生成诊断报告。",
                ReportTriggerLevel.SUGGESTED,
                "当前问诊信息较完整，可按需生成诊断报告。",
                false,
                null,
                false,
                List.of(),
                new StructuredMedicalReply(
                        "低风险",
                        "建议先休息并补充水分。",
                        List.of("目前信息有限"),
                        List.of("补充水分"),
                        List.of("头晕明显加重"),
                        List.of("是否伴随发热"),
                        "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
                )
        ));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我头晕"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.reply").value("建议先休息并补充水分。"))
                .andExpect(jsonPath("$.data.structuredReply.summary").value("建议先休息并补充水分。"))
                .andExpect(jsonPath("$.data.reportTriggerLevel").value("suggested"))
                .andExpect(jsonPath("$.data.reportActionText").value("当前问诊信息较完整，可按需生成诊断报告。"));

        when(medicalApp.generateReportFromThread("thread-flow-1", userId)).thenReturn(new MedicalDiagnosisReport(
                "thread-flow-1的医疗诊断报告",
                true,
                "CONFIRMED",
                "低风险",
                "头晕",
                "考虑疲劳或轻度脱水",
                "",
                List.of("头晕"),
                List.of("充分休息"),
                List.of("意识模糊"),
                "建议观察"
        ));

        mockMvc.perform(get("/v1/reports/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.report.riskLevel").value("低风险"));

        verify(medicalApp).doChat("我头晕", "thread-flow-1", userId);
        verify(medicalApp).generateReportFromThread("thread-flow-1", userId);
    }
}
