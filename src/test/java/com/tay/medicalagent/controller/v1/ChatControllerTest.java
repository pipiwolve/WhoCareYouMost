package com.tay.medicalagent.controller.v1;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.dto.ChatCompletionRequest;
import com.tay.medicalagent.web.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({ControllerTestConfig.class, GlobalExceptionHandler.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatController chatController;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @MockitoBean
    private MedicalApp medicalApp;

    @Test
    void completeJsonShouldReturn404WhenSessionMissing() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_missing",
                                  "message": "我头晕"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void completeJsonShouldValidateRequest() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_xxx",
                                  "message": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("message 不能为空"));
    }

    @Test
    void completeJsonShouldRejectAttachments() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_1", "thread_1");

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我头晕",
                                  "attachments": ["file_1"]
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("暂不支持附件上传"));
    }

    @Test
    void completeJsonShouldReturnMappedResponse() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_2", "thread_2");
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "低风险",
                "建议多休息。",
                List.of("病程较短"),
                List.of("规律作息"),
                List.of("出现胸痛"),
                List.of("头晕持续多久"),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_2",
                "usr_2",
                "建议多休息。",
                true,
                "当前问诊信息较完整，可按需生成诊断报告。",
                ReportTriggerLevel.SUGGESTED,
                "当前问诊信息较完整，可按需生成诊断报告。",
                false,
                null,
                true,
                List.of(new KnowledgeSource(
                        "kb-1",
                        "胸痛处理",
                        "危险信号",
                        "triage",
                        "/tmp/kb.md",
                        0.91
                )),
                structuredReply
        );
        when(medicalApp.doChat("我有点头晕", "thread_2", "usr_2")).thenReturn(medicalChatResult);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我有点头晕"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(consultationSession.sessionId()))
                .andExpect(jsonPath("$.data.reply").value("建议多休息。"))
                .andExpect(jsonPath("$.data.structuredReply.summary").value("建议多休息。"))
                .andExpect(jsonPath("$.data.reportAvailable").value(true))
                .andExpect(jsonPath("$.data.reportReason").value("当前问诊信息较完整，可按需生成诊断报告。"))
                .andExpect(jsonPath("$.data.reportTriggerLevel").value("suggested"))
                .andExpect(jsonPath("$.data.reportActionText").value("当前问诊信息较完整，可按需生成诊断报告。"))
                .andExpect(jsonPath("$.data.reportPreview").doesNotExist())
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("kb-1"))
                .andExpect(jsonPath("$.data.sources[0].title").value("胸痛处理"))
                .andExpect(jsonPath("$.data.sources[0].uri").doesNotExist());
    }

    @Test
    void completeJsonShouldWarmReportPreviewAsyncWhenReportAvailable() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_preview", "thread_preview");
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "低风险",
                "建议观察。",
                List.of("信息较完整"),
                List.of("观察症状"),
                List.of("症状加重"),
                List.of(),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_preview",
                "usr_preview",
                "建议观察。",
                true,
                "当前问诊信息较完整，可按需生成诊断报告。",
                ReportTriggerLevel.SUGGESTED,
                "当前问诊信息较完整，可按需生成诊断报告。",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("请帮我总结一下", "thread_preview", "usr_preview")).thenReturn(medicalChatResult);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "请帮我总结一下"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(medicalApp, org.mockito.Mockito.timeout(1000)).prepareReportPreview(
                consultationSession.sessionId(),
                "请帮我总结一下",
                "thread_preview",
                "usr_preview",
                null,
                null,
                medicalChatResult
        );
    }

    @Test
    void completeJsonShouldReturnInlineReportPreviewForNaturalLanguageHospitalRouteRequest() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_route", "thread_route");
        consultationSessionService.updateLocation(consultationSession.sessionId(), 31.23, 121.47, true);
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "中风险",
                "胸闷伴心慌，建议尽快线下评估。",
                List.of("胸闷", "心慌"),
                List.of("尽快线下评估"),
                List.of("胸痛加重"),
                List.of(),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_route",
                "usr_route",
                "建议尽快线下评估。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("能否帮我规划最新的医院路线", "thread_route", "usr_route")).thenReturn(medicalChatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("能否帮我规划最新的医院路线")).thenReturn(true);
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "能否帮我规划最新的医院路线",
                "thread_route",
                "usr_route",
                31.23,
                121.47,
                medicalChatResult
        )).thenReturn(Optional.of(reportPreviewSnapshot("ok", true)));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "能否帮我规划最新的医院路线"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPreview.title").value("路线预览报告"))
                .andExpect(jsonPath("$.data.reportPreview.routesAvailable").value(true))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].routes[0].steps[0]").value("步行200米到达医院"))
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("已为您规划附近医院路线，请查看下方推荐医院和路线。")));
    }

    @Test
    void completeJsonShouldReturnInlineReportPreviewForSpecializedHospitalLookup() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_psych_route", "thread_psych_route");
        consultationSessionService.updateLocation(consultationSession.sessionId(), 31.23, 121.47, true);
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "中风险",
                "持续失眠伴焦虑，建议尽快线下专科评估。",
                List.of("持续失眠", "焦虑"),
                List.of("尽快线下评估"),
                List.of("出现自伤想法"),
                List.of(),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_psych_route",
                "usr_psych_route",
                "建议尽快线下精神专科评估。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("帮我找最近的心理医院", "thread_psych_route", "usr_psych_route")).thenReturn(medicalChatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("帮我找最近的心理医院")).thenReturn(true);
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "帮我找最近的心理医院",
                "thread_psych_route",
                "usr_psych_route",
                31.23,
                121.47,
                medicalChatResult
        )).thenReturn(Optional.of(reportPreviewSnapshot("ok", true)));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "帮我找最近的心理医院"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPreview.title").value("路线预览报告"))
                .andExpect(jsonPath("$.data.reportPreview.routesAvailable").value(true))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].routes[0].steps[0]").value("步行200米到达医院"))
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("已为您规划附近医院路线，请查看下方推荐医院和路线。")));
    }

    @Test
    void completeJsonShouldReturnLocationMissingPreviewForExplicitHospitalRouteRequestWithoutCoordinates() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_route_missing", "thread_route_missing");
        StructuredMedicalReply structuredReply = StructuredMedicalReply.empty(
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_route_missing",
                "usr_route_missing",
                "可以先为您准备附近医院路线。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("帮我规划附近医院路线", "thread_route_missing", "usr_route_missing")).thenReturn(medicalChatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("帮我规划附近医院路线")).thenReturn(true);
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "帮我规划附近医院路线",
                "thread_route_missing",
                "usr_route_missing",
                null,
                null,
                medicalChatResult
        )).thenReturn(Optional.of(reportPreviewSnapshot("location_missing", false)));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "帮我规划附近医院路线"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPreview.title").value("路线预览报告"))
                .andExpect(jsonPath("$.data.reportPreview.routesAvailable").value(false))
                .andExpect(jsonPath("$.data.reportPreview.routeStatusMessage").value("未上传经纬度，无法进行就近医院规划"))
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("请授权定位后，我会继续为您规划。")));
    }

    @Test
    void completeJsonShouldHandleRuntimeFailure() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_3", "thread_3");
        when(medicalApp.doChat("我胸闷", "thread_3", "usr_3"))
                .thenThrow(new GraphRunnerException("模型调用失败"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我胸闷"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("问诊生成失败"));
    }

    @Test
    void completeJsonShouldExposeModelConfigurationError() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_cfg", "thread_cfg");
        when(medicalApp.doChat("我头晕", "thread_cfg", "usr_cfg"))
                .thenThrow(new MedicalModelConfigurationException(
                        "缺少 DashScope API Key，请配置 DASHSCOPE_API_KEY 或 AI_DASHSCOPE_API_KEY"
                ));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我头晕"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message")
                        .value("缺少 DashScope API Key，请配置 DASHSCOPE_API_KEY 或 AI_DASHSCOPE_API_KEY"));
    }

    @Test
    void completeStreamShouldEmitMetaChunkAndDone() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_4", "thread_4");
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "低风险",
                "根据您的情况，建议多休息。",
                List.of("暂未见红旗信号"),
                List.of("多休息"),
                List.of(),
                List.of("头晕是否持续"),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_4",
                "usr_4",
                "根据您的情况，建议多休息。",
                false,
                "",
                ReportTriggerLevel.NONE,
                "",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("我有点头晕", "thread_4", "usr_4")).thenReturn(medicalChatResult);

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我有点头晕"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(1000);
        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String payload = asyncResult.getResponse().getContentAsString();
        int metaIndex = payload.indexOf("event:meta");
        int chunkIndex = payload.indexOf("event:chunk");
        int doneIndex = payload.indexOf("event:done");

        assertTrue(metaIndex >= 0);
        assertTrue(chunkIndex >= 0);
        assertTrue(doneIndex >= 0);
        assertTrue(metaIndex < chunkIndex);
        assertTrue(chunkIndex < doneIndex);
        assertTrue(!payload.contains("event:error"));
        assertTrue(payload.contains(consultationSession.sessionId()));
    }

    @Test
    void completeStreamShouldIncludeInlinePreviewInDoneForExplicitHospitalRouteRequest() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_stream_route", "thread_stream_route");
        consultationSessionService.updateLocation(consultationSession.sessionId(), 31.23, 121.47, true);
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_stream_route",
                "usr_stream_route",
                "建议尽快线下评估。",
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
                        List.of("胸闷"),
                        List.of("尽快线下评估"),
                        List.of("胸痛加重"),
                        List.of(),
                        "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
                )
        );
        when(medicalApp.doChat("帮我规划附近医院路线", "thread_stream_route", "usr_stream_route")).thenReturn(medicalChatResult);
        when(medicalApp.isExplicitHospitalPlanningRequest("帮我规划附近医院路线")).thenReturn(true);
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "帮我规划附近医院路线",
                "thread_stream_route",
                "usr_stream_route",
                31.23,
                121.47,
                medicalChatResult
        )).thenReturn(Optional.of(reportPreviewSnapshot("ok", true)));

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "帮我规划附近医院路线"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(1000);
        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String payload = new String(asyncResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("event:done"));
        assertTrue(payload.contains("\"reportPreview\""));
        assertTrue(payload.contains("路线预览报告"));
        assertTrue(payload.contains("步行200米到达医院"));
    }

    @Test
    void completeStreamShouldEmitMetaAndErrorWhenSessionMissing() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "sessionId": "sess_missing",
                                  "message": "我头晕"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(1000);
        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:meta")))
                .andExpect(content().string(containsString("event:error")))
                .andReturn();

        String payload = asyncResult.getResponse().getContentAsString();
        assertTrue(payload.indexOf("event:meta") < payload.indexOf("event:error"));
        assertTrue(!payload.contains("event:done"));
        assertTrue(payload.contains("SESSION_NOT_FOUND"));
    }

    @Test
    void completeStreamShouldExposeModelConfigurationError() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_cfg_2", "thread_cfg_2");
        when(medicalApp.doChat("我头晕", "thread_cfg_2", "usr_cfg_2"))
                .thenThrow(new MedicalModelConfigurationException(
                        "缺少 DashScope API Key，请配置 DASHSCOPE_API_KEY 或 AI_DASHSCOPE_API_KEY"
                ));

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我头晕"
                                }
                                """.formatted(consultationSession.sessionId())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(1000);
        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn();

        String payload = asyncResult.getResponse().getContentAsString();
        assertTrue(payload.contains("event:error"));
        assertTrue(payload.contains("MODEL_CONFIG_ERROR"));
        assertTrue(payload.contains("DASHSCOPE_API_KEY"));
        assertTrue(payload.contains("AI_DASHSCOPE_API_KEY"));
    }

    @Test
    void emitStreamResponseShouldOnlyUseChatPathAndFinishWithDone() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_seq", "thread_seq");
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "中风险",
                "建议尽快线下评估。",
                List.of("胸闷反复出现"),
                List.of("监测症状变化"),
                List.of("胸痛加重"),
                List.of("症状持续多久"),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_seq",
                "usr_seq",
                "建议尽快线下评估，并注意观察症状变化。",
                true,
                "当前回复已经形成风险判断或排查方向，可生成诊断报告。",
                ReportTriggerLevel.RECOMMENDED,
                "生成诊断报告",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("我胸闷乏力", "thread_seq", "usr_seq")).thenReturn(medicalChatResult);
        RecordingSink sink = new RecordingSink();
        ChatCompletionRequest request = new ChatCompletionRequest(consultationSession.sessionId(), "我胸闷乏力", null);

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> chatController.emitStreamResponse(request, sink));

        task.get(1, TimeUnit.SECONDS);

        assertEquals(List.of("chunk", "chunk", "done"), sink.eventNames());
        assertTrue(sink.completed());
        verify(medicalApp).doChat("我胸闷乏力", "thread_seq", "usr_seq");
        org.mockito.Mockito.verify(medicalApp, org.mockito.Mockito.timeout(1000)).prepareReportPreview(
                consultationSession.sessionId(),
                "我胸闷乏力",
                "thread_seq",
                "usr_seq",
                null,
                null,
                medicalChatResult
        );
    }

    @Test
    void emitStreamResponseShouldStopWithoutCompleteWhenChunkSendFails() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_chunk_fail", "thread_chunk_fail");
        StructuredMedicalReply structuredReply = new StructuredMedicalReply(
                "低风险",
                "建议多休息。",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "本回答由AI生成，仅供健康信息参考，不能替代医生面诊。"
        );
        MedicalChatResult medicalChatResult = new MedicalChatResult(
                "thread_chunk_fail",
                "usr_chunk_fail",
                "建议多休息并观察。",
                false,
                "",
                ReportTriggerLevel.NONE,
                "",
                false,
                null,
                false,
                List.of(),
                structuredReply
        );
        when(medicalApp.doChat("我有点头晕", "thread_chunk_fail", "usr_chunk_fail")).thenReturn(medicalChatResult);

        FailingSink sink = new FailingSink("chunk", 1);
        ChatCompletionRequest request = new ChatCompletionRequest(consultationSession.sessionId(), "我有点头晕", null);

        chatController.emitStreamResponse(request, sink);

        assertEquals(List.of("chunk"), sink.eventNames());
        assertFalse(sink.completed());
    }

    @Test
    void emitStreamResponseShouldStopWithoutCompleteWhenErrorSendFails() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_error_fail", "thread_error_fail");
        when(medicalApp.doChat("我胸闷", "thread_error_fail", "usr_error_fail"))
                .thenThrow(new GraphRunnerException("模型调用失败"));

        FailingSink sink = new FailingSink("error", 1);
        ChatCompletionRequest request = new ChatCompletionRequest(consultationSession.sessionId(), "我胸闷", null);

        chatController.emitStreamResponse(request, sink);

        assertEquals(List.of("error"), sink.eventNames());
        assertFalse(sink.completed());
    }

    private static final class RecordingSink implements ChatController.ChatSseSink {

        private final List<String> eventNames = new CopyOnWriteArrayList<>();
        private volatile boolean completed;

        @Override
        public boolean send(String eventName, Object payload) {
            eventNames.add(eventName);
            return true;
        }

        @Override
        public void complete() {
            completed = true;
        }

        private List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        private boolean completed() {
            return completed;
        }
    }

    private static final class FailingSink implements ChatController.ChatSseSink {

        private final List<String> eventNames = new CopyOnWriteArrayList<>();
        private final String failingEventName;
        private final AtomicInteger attemptsBeforeFailure;
        private volatile boolean completed;

        private FailingSink(String failingEventName, int failOnAttempt) {
            this.failingEventName = failingEventName;
            this.attemptsBeforeFailure = new AtomicInteger(failOnAttempt);
        }

        @Override
        public boolean send(String eventName, Object payload) {
            eventNames.add(eventName);
            if (failingEventName.equals(eventName) && attemptsBeforeFailure.getAndDecrement() == 1) {
                return false;
            }
            return true;
        }

        @Override
        public void complete() {
            completed = true;
        }

        private List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        private boolean completed() {
            return completed;
        }
    }

    private MedicalReportSnapshot reportPreviewSnapshot(String routeStatusCode, boolean routesAvailable) {
        MedicalHospitalPlanningSummary planningSummary = "location_missing".equals(routeStatusCode)
                ? new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度，无法进行就近医院规划", "location_missing")
                : new MedicalHospitalPlanningSummary(
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
                routesAvailable,
                "",
                routeStatusCode
        );
        return new MedicalReportSnapshot(
                "sess_preview",
                "thread_preview",
                "usr_preview",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                new com.tay.medicalagent.app.report.MedicalDiagnosisReport(
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
