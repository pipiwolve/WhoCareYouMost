package com.tay.medicalagent.controller.v1;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
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

import static org.hamcrest.Matchers.containsString;
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
}
