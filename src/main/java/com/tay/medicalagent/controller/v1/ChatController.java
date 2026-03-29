package com.tay.medicalagent.controller.v1;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.dto.ChatCompletionRequest;
import com.tay.medicalagent.web.dto.ChatCompletionResponse;
import com.tay.medicalagent.web.support.ApiResponse;
import com.tay.medicalagent.web.support.MedicalApiViewMapper;
import com.tay.medicalagent.web.support.SessionNotFoundException;
import com.tay.medicalagent.web.support.UnsupportedAttachmentsException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/v1/chat")
/**
 * 医疗聊天接口。
 * <p>
 * 同一路径同时支持 JSON 同步返回与 SSE 流式返回。
 */
public class ChatController {

    private static final int MAX_CHUNK_LENGTH = 12;

    private final MedicalApp medicalApp;
    private final ConsultationSessionService consultationSessionService;
    private final MedicalApiViewMapper medicalApiViewMapper;
    private final Executor medicalSseExecutor;

    public ChatController(
            MedicalApp medicalApp,
            ConsultationSessionService consultationSessionService,
            MedicalApiViewMapper medicalApiViewMapper,
            @Qualifier("medicalSseExecutor") Executor medicalSseExecutor
    ) {
        this.medicalApp = medicalApp;
        this.consultationSessionService = consultationSessionService;
        this.medicalApiViewMapper = medicalApiViewMapper;
        this.medicalSseExecutor = medicalSseExecutor;
    }

    @PostMapping(value = "/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatCompletionResponse> completeJson(@Valid @RequestBody ChatCompletionRequest request)
            throws GraphRunnerException {
        validateAttachments(request.attachments());
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(request.sessionId());
        MedicalChatResult medicalChatResult = medicalApp.doChat(
                request.message().trim(),
                consultationSession.threadId(),
                consultationSession.userId()
        );
        return ApiResponse.success(medicalApiViewMapper.toChatCompletionResponse(
                consultationSession.sessionId(),
                medicalChatResult
        ));
    }

    @PostMapping(value = "/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completeStream(@Valid @RequestBody ChatCompletionRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        sendSafely(emitter, "meta", Map.of("sessionId", request.sessionId()));

        medicalSseExecutor.execute(() -> {
            try {
                validateAttachments(request.attachments());
                ConsultationSession consultationSession = consultationSessionService.getRequiredSession(request.sessionId());
                MedicalChatResult medicalChatResult = medicalApp.doChat(
                        request.message().trim(),
                        consultationSession.threadId(),
                        consultationSession.userId()
                );
                ChatCompletionResponse response = medicalApiViewMapper.toChatCompletionResponse(
                        consultationSession.sessionId(),
                        medicalChatResult
                );

                List<String> chunks = splitText(response.reply());
                for (int index = 0; index < chunks.size(); index++) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("delta", chunks.get(index));
                    payload.put("index", index);
                    sendSafely(emitter, "chunk", payload);
                }

                sendSafely(emitter, "done", response);
                emitter.complete();
            }
            catch (Exception ex) {
                sendSafely(emitter, "error", buildErrorPayload(ex));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void validateAttachments(List<String> attachments) {
        if (attachments != null && !attachments.isEmpty()) {
            throw new UnsupportedAttachmentsException("暂不支持附件上传");
        }
    }

    private Map<String, String> buildErrorPayload(Exception ex) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("code", resolveErrorCode(ex));
        payload.put("message", resolveErrorMessage(ex));
        return payload;
    }

    private String resolveErrorCode(Exception ex) {
        if (ex instanceof SessionNotFoundException) {
            return "SESSION_NOT_FOUND";
        }
        if (ex instanceof UnsupportedAttachmentsException) {
            return "UNSUPPORTED_ATTACHMENTS";
        }
        if (ex instanceof MedicalModelConfigurationException) {
            return "MODEL_CONFIG_ERROR";
        }
        if (ex instanceof GraphRunnerException) {
            return "CHAT_RUNTIME_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    private String resolveErrorMessage(Exception ex) {
        if (ex instanceof SessionNotFoundException || ex instanceof UnsupportedAttachmentsException) {
            return ex.getMessage();
        }
        if (ex instanceof MedicalModelConfigurationException) {
            return ex.getMessage();
        }
        if (ex instanceof GraphRunnerException) {
            return "问诊生成失败";
        }
        return "系统内部错误";
    }

    private void sendSafely(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        }
        catch (IOException ioException) {
            throw new IllegalStateException("发送 SSE 事件失败", ioException);
        }
    }

    private List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            current.append(character);
            if (isBoundary(character) || current.length() >= MAX_CHUNK_LENGTH) {
                chunks.add(current.toString());
                current.setLength(0);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private boolean isBoundary(char character) {
        return switch (character) {
            case '，', '。', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':', '\n' -> true;
            default -> false;
        };
    }
}
