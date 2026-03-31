package com.tay.medicalagent.controller.v1;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.MedicalChatPreviewReplyDecorator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/v1/chat")
/**
 * 医疗聊天接口。
 * <p>
 * 同一路径同时支持 JSON 同步返回与 SSE 流式返回。
 */
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_CHUNK_LENGTH = 12;

    private final MedicalApp medicalApp;
    private final ConsultationSessionService consultationSessionService;
    private final MedicalApiViewMapper medicalApiViewMapper;
    private final MedicalChatPreviewReplyDecorator medicalChatPreviewReplyDecorator;
    private final Executor medicalSseExecutor;

    public ChatController(
            MedicalApp medicalApp,
            ConsultationSessionService consultationSessionService,
            MedicalApiViewMapper medicalApiViewMapper,
            MedicalChatPreviewReplyDecorator medicalChatPreviewReplyDecorator,
            @Qualifier("medicalSseExecutor") Executor medicalSseExecutor
    ) {
        this.medicalApp = medicalApp;
        this.consultationSessionService = consultationSessionService;
        this.medicalApiViewMapper = medicalApiViewMapper;
        this.medicalChatPreviewReplyDecorator = medicalChatPreviewReplyDecorator;
        this.medicalSseExecutor = medicalSseExecutor;
    }

    @PostMapping(value = "/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatCompletionResponse> completeJson(@Valid @RequestBody ChatCompletionRequest request)
            throws GraphRunnerException {
        validateAttachments(request.attachments());
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(request.sessionId());
        String trimmedMessage = request.message().trim();
        MedicalChatResult medicalChatResult = medicalApp.doChat(
                trimmedMessage,
                consultationSession.threadId(),
                consultationSession.userId()
        );
        ChatPreviewResolution previewResolution = resolveChatPreview(consultationSession, trimmedMessage, medicalChatResult);
        if (!previewResolution.inlinePreviewApplied()) {
            prepareReportPreviewAsync(consultationSession, trimmedMessage, medicalChatResult);
        }
        return ApiResponse.success(medicalApiViewMapper.toChatCompletionResponse(
                consultationSession.sessionId(),
                previewResolution.chatResult(),
                previewResolution.reportPreview()
        ));
    }

    @PostMapping(value = "/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completeStream(@Valid @RequestBody ChatCompletionRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        ChatSseSink sink = new SseEmitterChatSseSink(emitter);
        if (!sink.send("meta", Map.of("sessionId", request.sessionId()))) {
            return emitter;
        }

        medicalSseExecutor.execute(() -> emitStreamResponse(request, sink));

        return emitter;
    }

    void emitStreamResponse(ChatCompletionRequest request, ChatSseSink sink) {
        long startedAt = System.nanoTime();
        try {
            validateAttachments(request.attachments());
            ConsultationSession consultationSession = consultationSessionService.getRequiredSession(request.sessionId());
            String trimmedMessage = request.message().trim();
            MedicalChatResult medicalChatResult = medicalApp.doChat(
                    trimmedMessage,
                    consultationSession.threadId(),
                    consultationSession.userId()
            );
            long chatReadyAt = System.nanoTime();

            if (!emitReplyChunks(sink, medicalChatResult.reply())) {
                return;
            }
            long chunksEmittedAt = System.nanoTime();

            ChatPreviewResolution previewResolution = resolveChatPreview(consultationSession, trimmedMessage, medicalChatResult);
            ChatCompletionResponse response = medicalApiViewMapper.toChatCompletionResponse(
                    consultationSession.sessionId(),
                    previewResolution.chatResult(),
                    previewResolution.reportPreview()
            );

            if (!sink.send("done", response)) {
                return;
            }

            if (!previewResolution.inlinePreviewApplied()) {
                prepareReportPreviewAsync(consultationSession, trimmedMessage, medicalChatResult);
            }
            long finishedAt = System.nanoTime();
            if (log.isDebugEnabled()) {
                log.debug(
                        "SSE chat finished. sessionId={}, chatReadyMs={}, chunkEmitMs={}, totalMs={}",
                        consultationSession.sessionId(),
                        elapsedMillis(startedAt, chatReadyAt),
                        elapsedMillis(chatReadyAt, chunksEmittedAt),
                        elapsedMillis(startedAt, finishedAt)
                );
            }
            sink.complete();
        }
        catch (Exception ex) {
            if (sink.send("error", buildErrorPayload(ex))) {
                sink.complete();
            }
        }
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
        if (ex instanceof ResourceAccessException) {
            return "MODEL_UPSTREAM_UNAVAILABLE";
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
        if (ex instanceof ResourceAccessException) {
            return "模型服务网络异常，请稍后重试";
        }
        return "系统内部错误";
    }

    private boolean emitReplyChunks(ChatSseSink sink, String reply) {
        List<String> chunks = splitText(reply);
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("delta", chunks.get(index));
            payload.put("index", index);
            if (!sink.send("chunk", payload)) {
                return false;
            }
        }
        return true;
    }

    private void prepareReportPreviewAsync(
            ConsultationSession consultationSession,
            String prompt,
            MedicalChatResult medicalChatResult
    ) {
        if (consultationSession == null || medicalChatResult == null) {
            return;
        }
        medicalSseExecutor.execute(() -> {
            try {
                medicalApp.prepareReportPreview(
                        consultationSession.sessionId(),
                        prompt,
                        consultationSession.threadId(),
                        consultationSession.userId(),
                        consultationSession.latitude(),
                        consultationSession.longitude(),
                        medicalChatResult
                );
            }
            catch (Exception ex) {
                log.debug(
                        "Report preview warm-up skipped. sessionId={}, reason={}",
                        consultationSession.sessionId(),
                        ex.getMessage()
                );
            }
        });
    }

    private ChatPreviewResolution resolveChatPreview(
            ConsultationSession consultationSession,
            String prompt,
            MedicalChatResult medicalChatResult
    ) {
        if (consultationSession == null || medicalChatResult == null) {
            return new ChatPreviewResolution(medicalChatResult, null, false);
        }
        if (!medicalApp.isExplicitHospitalPlanningRequest(prompt)) {
            return new ChatPreviewResolution(medicalChatResult, null, false);
        }

        MedicalReportSnapshot reportPreview = medicalApp.prepareReportPreview(
                consultationSession.sessionId(),
                prompt,
                consultationSession.threadId(),
                consultationSession.userId(),
                consultationSession.latitude(),
                consultationSession.longitude(),
                medicalChatResult
        ).orElse(null);
        String decoratedReply = medicalChatPreviewReplyDecorator.decorateExplicitHospitalRequestReply(
                medicalChatResult.reply(),
                reportPreview
        );
        return new ChatPreviewResolution(overrideReply(medicalChatResult, decoratedReply), reportPreview, true);
    }

    private MedicalChatResult overrideReply(MedicalChatResult medicalChatResult, String reply) {
        if (medicalChatResult == null) {
            return null;
        }
        return new MedicalChatResult(
                medicalChatResult.threadId(),
                medicalChatResult.userId(),
                reply,
                medicalChatResult.reportAvailable(),
                medicalChatResult.reportReason(),
                medicalChatResult.reportTriggerLevel(),
                medicalChatResult.reportActionText(),
                medicalChatResult.reportGenerated(),
                medicalChatResult.report(),
                medicalChatResult.ragApplied(),
                medicalChatResult.sources(),
                medicalChatResult.structuredReply()
        );
    }

    private boolean sendSafely(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        }
        catch (IOException | IllegalStateException ex) {
            log.debug("SSE send skipped due to closed/broken connection. eventName={}, reason={}", eventName, ex.getMessage());
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        }
        catch (Exception ex) {
            log.debug("SSE complete skipped due to closed connection. reason={}", ex.getMessage());
        }
    }

    private long elapsedMillis(long startNanos, long endNanos) {
        return Math.max(0L, (endNanos - startNanos) / 1_000_000L);
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

    interface ChatSseSink {

        boolean send(String eventName, Object payload);

        void complete();
    }

    private final class SseEmitterChatSseSink implements ChatSseSink {

        private final SseEmitter emitter;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private SseEmitterChatSseSink(SseEmitter emitter) {
            this.emitter = emitter;
            this.emitter.onCompletion(() -> active.set(false));
            this.emitter.onTimeout(() -> active.set(false));
            this.emitter.onError(ex -> active.set(false));
        }

        @Override
        public boolean send(String eventName, Object payload) {
            if (!active.get()) {
                return false;
            }
            boolean sent = sendSafely(emitter, eventName, payload);
            if (!sent) {
                active.set(false);
            }
            return sent;
        }

        @Override
        public void complete() {
            if (active.compareAndSet(true, false)) {
                safeComplete(emitter);
            }
        }
    }

    private record ChatPreviewResolution(
            MedicalChatResult chatResult,
            MedicalReportSnapshot reportPreview,
            boolean inlinePreviewApplied
    ) {
    }
}
