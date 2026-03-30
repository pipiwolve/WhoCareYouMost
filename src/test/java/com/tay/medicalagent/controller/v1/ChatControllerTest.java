package com.tay.medicalagent.controller.v1;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
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

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "我有点头晕",
                "thread_2",
                "usr_2",
                null,
                null,
                medicalChatResult
        )).thenReturn(Optional.of(new MedicalReportSnapshot(
                consultationSession.sessionId(),
                "thread_2",
                "usr_2",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                new MedicalDiagnosisReport(
                        "thread_2的医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "低风险",
                        "轻度头晕",
                        "考虑疲劳相关不适",
                        "",
                        List.of("病程较短"),
                        List.of("多休息"),
                        List.of("出现胸痛"),
                        "建议多休息。"
                ),
                new MedicalHospitalPlanningSummary(
                        List.of(new MedicalHospitalRecommendation(
                                "测试医院",
                                "测试地址",
                                true,
                                800L,
                                List.of(new MedicalHospitalRouteOption(
                                        "WALK",
                                        900L,
                                        12L,
                                        "步行方案",
                                        List.of("步行200米前往地铁站", "步行700米到达医院")
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
                .andExpect(jsonPath("$.data.reportPreview.title").value("thread_2的医疗诊断报告"))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].name").value("测试医院"))
                .andExpect(jsonPath("$.data.reportPreview.hospitals[0].routes[0].steps[0]").value("步行200米前往地铁站"))
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("kb-1"))
                .andExpect(jsonPath("$.data.sources[0].title").value("胸痛处理"))
                .andExpect(jsonPath("$.data.sources[0].uri").doesNotExist());
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
    void emitStreamResponseShouldPushChunksBeforePreparingReportPreview() throws Exception {
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
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch releasePreview = new CountDownLatch(1);

        when(medicalApp.doChat("我胸闷乏力", "thread_seq", "usr_seq")).thenReturn(medicalChatResult);
        when(medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                "我胸闷乏力",
                "thread_seq",
                "usr_seq",
                null,
                null,
                medicalChatResult
        )).thenAnswer(invocation -> {
            assertEquals(0L, firstChunkSent.getCount(), "应先把 chunk 推给前端，再准备报告预览");
            assertTrue(releasePreview.await(1, TimeUnit.SECONDS));
            return Optional.empty();
        });

        RecordingSink sink = new RecordingSink(firstChunkSent);
        ChatCompletionRequest request = new ChatCompletionRequest(consultationSession.sessionId(), "我胸闷乏力", null);

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> chatController.emitStreamResponse(request, sink));

        assertTrue(firstChunkSent.await(500, TimeUnit.MILLISECONDS));
        releasePreview.countDown();
        task.get(1, TimeUnit.SECONDS);

        assertEquals(List.of("chunk", "chunk", "done"), sink.eventNames());
        assertTrue(sink.completed());
    }

    private static final class RecordingSink implements ChatController.ChatSseSink {

        private final CountDownLatch firstChunkSent;
        private final List<String> eventNames = new CopyOnWriteArrayList<>();
        private volatile boolean completed;

        private RecordingSink(CountDownLatch firstChunkSent) {
            this.firstChunkSent = firstChunkSent;
        }

        @Override
        public boolean send(String eventName, Object payload) {
            eventNames.add(eventName);
            if ("chunk".equals(eventName)) {
                Map<?, ?> chunkPayload = (Map<?, ?>) payload;
                if (Integer.valueOf(0).equals(chunkPayload.get("index"))) {
                    firstChunkSent.countDown();
                }
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
}
