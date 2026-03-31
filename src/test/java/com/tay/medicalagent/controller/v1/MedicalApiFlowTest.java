package com.tay.medicalagent.controller.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
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
import java.time.Instant;

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
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.reply").value("建议先休息并补充水分。"))
                .andExpect(jsonPath("$.data.structuredReply.summary").value("建议先休息并补充水分。"))
                .andExpect(jsonPath("$.data.reportTriggerLevel").value("suggested"))
                .andExpect(jsonPath("$.data.reportActionText").value("当前问诊信息较完整，可按需生成诊断报告。"))
                .andExpect(jsonPath("$.data.reportPreview").doesNotExist());

        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
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
        );
        when(medicalApp.getOrCreateReportSnapshot(sessionId, "thread-flow-1", userId, null, null)).thenReturn(
                new MedicalReportSnapshot(
                        sessionId,
                        "thread-flow-1",
                        userId,
                        Instant.now(),
                        "conversation",
                        "profile",
                        "location",
                        report,
                        MedicalHospitalPlanningSummary.empty()
                )
        );

        mockMvc.perform(get("/v1/reports/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.reason").value(""))
                .andExpect(jsonPath("$.data.report.riskLevel").value("低风险"));

        verify(medicalApp).doChat("我头晕", "thread-flow-1", userId);
        verify(medicalApp).getOrCreateReportSnapshot(sessionId, "thread-flow-1", userId, null, null);
    }

    @Test
    void naturalLanguageHospitalRouteRequestShouldReturnInlinePreviewAndRefreshAfterLocationUpload() throws Exception {
        when(medicalApp.createThreadId()).thenReturn("thread-flow-route");

        MvcResult profileResult = mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "李四",
                                  "age": 31,
                                  "gender": "MALE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode profileBody = objectMapper.readTree(profileResult.getResponse().getContentAsString());
        String userId = profileBody.path("data").path("userId").asText();
        String sessionId = profileBody.path("data").path("sessionId").asText();

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread-flow-route",
                userId,
                "可以先为您准备附近医院路线。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。")
        );
        when(medicalApp.doChat("能否帮我规划最新的医院路线", "thread-flow-route", userId)).thenReturn(chatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("能否帮我规划最新的医院路线")).thenReturn(true);
        when(medicalApp.prepareReportPreview(sessionId, "能否帮我规划最新的医院路线", "thread-flow-route", userId, null, null, chatResult))
                .thenReturn(java.util.Optional.of(previewSnapshot(
                        sessionId,
                        userId,
                        new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度，无法进行就近医院规划", "location_missing")
                )));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "能否帮我规划最新的医院路线"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPreview.title").value("路线预览报告"))
                .andExpect(jsonPath("$.data.reportPreview.routesAvailable").value(false))
                .andExpect(jsonPath("$.data.reportPreview.routeStatusMessage").value("未上传经纬度，无法进行就近医院规划"));

        mockMvc.perform(post("/v1/reports/{sessionId}/location", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "latitude": 31.23,
                                  "longitude": 121.47,
                                  "consentGranted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        when(medicalApp.getOrCreateReportSnapshot(sessionId, "thread-flow-route", userId, 31.23, 121.47)).thenReturn(
                previewSnapshot(
                        sessionId,
                        userId,
                        new MedicalHospitalPlanningSummary(
                                List.of(new com.tay.medicalagent.app.report.MedicalHospitalRecommendation(
                                        "上海市第一人民医院",
                                        "上海市虹口区武进路85号",
                                        true,
                                        1200,
                                        List.of(new com.tay.medicalagent.app.report.MedicalHospitalRouteOption(
                                                "WALK",
                                                1200,
                                                18,
                                                "步行方案",
                                                List.of("步行200米到达医院")
                                        ))
                                )),
                                true,
                                "",
                                "ok"
                        )
                )
        );

        mockMvc.perform(get("/v1/reports/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.report.routesAvailable").value(true))
                .andExpect(jsonPath("$.data.report.hospitals[0].routes[0].steps[0]").value("步行200米到达医院"));
    }

    @Test
    void specializedHospitalLookupShouldReturnInlinePreviewInChatResponse() throws Exception {
        when(medicalApp.createThreadId()).thenReturn("thread-flow-psych");

        MvcResult profileResult = mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "赵六",
                                  "age": 29,
                                  "gender": "FEMALE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode profileBody = objectMapper.readTree(profileResult.getResponse().getContentAsString());
        String userId = profileBody.path("data").path("userId").asText();
        String sessionId = profileBody.path("data").path("sessionId").asText();

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread-flow-psych",
                userId,
                "建议尽快线下精神专科评估。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。")
        );
        when(medicalApp.doChat("帮我找最近的心理医院", "thread-flow-psych", userId)).thenReturn(chatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("帮我找最近的心理医院")).thenReturn(true);
        when(medicalApp.prepareReportPreview(sessionId, "帮我找最近的心理医院", "thread-flow-psych", userId, null, null, chatResult))
                .thenReturn(java.util.Optional.of(previewSnapshot(
                        sessionId,
                        userId,
                        new MedicalHospitalPlanningSummary(
                                List.of(new com.tay.medicalagent.app.report.MedicalHospitalRecommendation(
                                        "上海市精神卫生中心",
                                        "上海市徐汇区宛平南路600号",
                                        true,
                                        2100,
                                        List.of(new com.tay.medicalagent.app.report.MedicalHospitalRouteOption(
                                                "DRIVE",
                                                2400,
                                                12,
                                                "驾车路线",
                                                List.of("沿高架行驶后到达医院")
                                        ))
                                )),
                                true,
                                "",
                                "ok"
                        )
                )));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "帮我找最近的心理医院"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPreview.title").value("路线预览报告"))
                .andExpect(jsonPath("$.data.reportPreview.routesAvailable").value(true))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].name").value("上海市精神卫生中心"))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].routes[0].steps[0]").value("沿高架行驶后到达医院"));
    }

    private MedicalReportSnapshot previewSnapshot(
            String sessionId,
            String userId,
            MedicalHospitalPlanningSummary planningSummary
    ) {
        return new MedicalReportSnapshot(
                sessionId,
                "thread-flow-route",
                userId,
                Instant.now(),
                "conversation",
                "profile",
                "location",
                new MedicalDiagnosisReport(
                        "路线预览报告",
                        true,
                        "CONFIRMED",
                        "中风险",
                        "胸闷",
                        "建议尽快线下评估。",
                        "",
                        List.of("胸闷"),
                        List.of("尽快线下评估"),
                        List.of("胸痛加重"),
                        "建议尽快线下评估。"
                ),
                planningSummary
        );
    }
}
