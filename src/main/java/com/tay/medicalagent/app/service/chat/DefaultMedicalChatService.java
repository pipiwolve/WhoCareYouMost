package com.tay.medicalagent.app.service.chat;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.MedicalChatStreamingSession;
import com.tay.medicalagent.app.chat.NormalizedMedicalReply;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalPlanningIntentResolver;
import com.tay.medicalagent.app.service.report.MedicalReportService;
import com.tay.medicalagent.app.service.report.MedicalReportTriggerPolicy;
import com.tay.medicalagent.app.service.report.ReportTriggerDecision;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import com.tay.medicalagent.app.service.runtime.MedicalAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
/**
 * 默认医疗聊天服务实现。
 * <p>
 * 负责在普通问答、报告请求和 RAG 结果回传之间做统一编排，是上层门面的主要业务入口。
 */
public class DefaultMedicalChatService implements MedicalChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMedicalChatService.class);
    private static final String REPORT_REQUEST_REPLY = "已根据当前线程对话生成医疗诊断报告。";
    private static final String NO_REPORT_CONTEXT_REPLY = "当前线程暂无足够对话内容，无法生成诊断报告。";
    private static final String EXPLICIT_HOSPITAL_ROUTE_ACTION_TEXT = "已按您的请求准备附近医院路线，可查看详情。";

    private final MedicalAgentRuntime medicalAgentRuntime;
    private final ThreadConversationService threadConversationService;
    private final MedicalReportService medicalReportService;
    private final MedicalReportTriggerPolicy medicalReportTriggerPolicy;
    private final UserProfileService userProfileService;
    private final MedicalRagContextHolder medicalRagContextHolder;
    private final MedicalReplyFormatter medicalReplyFormatter;
    private final MedicalPlanningIntentResolver medicalPlanningIntentResolver;

    public DefaultMedicalChatService(
            MedicalAgentRuntime medicalAgentRuntime,
            ThreadConversationService threadConversationService,
            MedicalReportService medicalReportService,
            MedicalReportTriggerPolicy medicalReportTriggerPolicy,
            UserProfileService userProfileService,
            MedicalRagContextHolder medicalRagContextHolder,
            MedicalReplyFormatter medicalReplyFormatter,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver
    ) {
        this.medicalAgentRuntime = medicalAgentRuntime;
        this.threadConversationService = threadConversationService;
        this.medicalReportService = medicalReportService;
        this.medicalReportTriggerPolicy = medicalReportTriggerPolicy;
        this.userProfileService = userProfileService;
        this.medicalRagContextHolder = medicalRagContextHolder;
        this.medicalReplyFormatter = medicalReplyFormatter;
        this.medicalPlanningIntentResolver = medicalPlanningIntentResolver;
    }

    @Override
    public MedicalChatResult doChat(String prompt, String threadId, String userId) throws GraphRunnerException {
        long startedAt = System.nanoTime();
        String effectiveThreadId = normalizeThreadId(threadId);
        String effectiveUserId = userProfileService.normalizeUserId(userId);

        if (medicalReportService.isReportRequest(prompt)) {
            MedicalChatResult reportResult = buildReportResult(effectiveThreadId, effectiveUserId);
            logChatCompletion(prompt, reportResult, startedAt, startedAt);
            return reportResult;
        }

        long modelStartedAt = System.nanoTime();
        AssistantMessage assistantMessage = medicalAgentRuntime.doChatMessage(prompt, effectiveThreadId, effectiveUserId);
        MedicalChatResult result = buildChatResult(prompt, effectiveThreadId, effectiveUserId, assistantMessage.getText());
        logChatCompletion(prompt, result, startedAt, modelStartedAt);
        return result;
    }

    @Override
    public MedicalChatStreamingSession streamChat(String prompt, String threadId, String userId) throws GraphRunnerException {
        long startedAt = System.nanoTime();
        String effectiveThreadId = normalizeThreadId(threadId);
        String effectiveUserId = userProfileService.normalizeUserId(userId);

        if (medicalReportService.isReportRequest(prompt)) {
            MedicalChatResult reportResult = buildReportResult(effectiveThreadId, effectiveUserId);
            Flux<String> syntheticReply = reportResult.reply() == null || reportResult.reply().isBlank()
                    ? Flux.empty()
                    : Flux.just(reportResult.reply());
            logChatCompletion(prompt, reportResult, startedAt, startedAt);
            return new MedicalChatStreamingSession(syntheticReply, Mono.just(reportResult));
        }

        long modelStartedAt = System.nanoTime();
        Flux<String> sharedDeltas = medicalAgentRuntime.streamChatText(prompt, effectiveThreadId, effectiveUserId)
                .filter(delta -> delta != null && !delta.isBlank())
                .replay()
                .autoConnect(1);

        Mono<MedicalChatResult> finalResult = sharedDeltas
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .defaultIfEmpty("")
                .map(reply -> buildChatResult(prompt, effectiveThreadId, effectiveUserId, reply))
                .doOnSuccess(result -> logChatCompletion(prompt, result, startedAt, modelStartedAt))
                .cache();

        return new MedicalChatStreamingSession(sharedDeltas, finalResult);
    }

    @Override
    public MedicalDiagnosisReport doChatWithReport(String message, String chatId, String userId)
            throws GraphRunnerException {
        MedicalChatResult chatResult = doChat(message, chatId, userId);
        if (chatResult.reportGenerated() && chatResult.report() != null) {
            return chatResult.report();
        }
        return medicalReportService.generateReportFromThread(chatResult.threadId(), chatResult.userId());
    }

    @Override
    public AssistantMessage doChatMessage(String prompt, String threadId, String userId) throws GraphRunnerException {
        return medicalAgentRuntime.doChatMessage(
                prompt,
                normalizeThreadId(threadId),
                userProfileService.normalizeUserId(userId)
        );
    }

    @Override
    public MedicalDiagnosisReport generateReportFromThread(String threadId, String userId) {
        return medicalReportService.generateReportFromThread(normalizeThreadId(threadId), userProfileService.normalizeUserId(userId));
    }

    @Override
    public String createThreadId() {
        return medicalAgentRuntime.createThreadId();
    }

    @Override
    public void resetRuntime() {
        medicalAgentRuntime.reset();
    }

    private MedicalChatResult buildChatResult(
            String prompt,
            String threadId,
            String userId,
            String rawReply
    ) {
        NormalizedMedicalReply normalizedReply = medicalReplyFormatter.normalize(rawReply);
        AssistantMessage normalizedAssistantMessage = AssistantMessage.builder()
                .content(normalizedReply.reply())
                .build();
        threadConversationService.appendExchange(threadId, new UserMessage(prompt), normalizedAssistantMessage);
        List<Message> conversation = threadConversationService.getThreadConversation(threadId);
        RagContext ragContext = medicalRagContextHolder.get(threadId).orElse(RagContext.empty(prompt));
        ReportTriggerDecision triggerDecision = medicalReportTriggerPolicy.evaluate(
                normalizedReply.structuredReply(),
                normalizedReply.reply(),
                conversation
        );
        ReportTriggerDecision effectiveTriggerDecision = medicalPlanningIntentResolver.isExplicitHospitalRequest(prompt)
                ? new ReportTriggerDecision(ReportTriggerLevel.RECOMMENDED, EXPLICIT_HOSPITAL_ROUTE_ACTION_TEXT)
                : triggerDecision;
        return new MedicalChatResult(
                threadId,
                userId,
                normalizedReply.reply(),
                effectiveTriggerDecision.reportAvailable(),
                effectiveTriggerDecision.reportReason(),
                effectiveTriggerDecision.level(),
                effectiveTriggerDecision.actionText(),
                false,
                null,
                ragContext.applied(),
                ragContext.sources(),
                normalizedReply.structuredReply()
        );
    }

    private void logChatCompletion(
            String prompt,
            MedicalChatResult medicalChatResult,
            long startedAt,
            long modelStartedAt
    ) {
        long finishedAt = System.nanoTime();
        boolean explicitHospitalRequest = medicalPlanningIntentResolver.isExplicitHospitalRequest(prompt);
        log.info(
                "Medical chat completed. threadId={}, userId={}, modelMs={}, totalMs={}, reportAvailable={}, ragApplied={}, explicitHospitalRequest={}",
                medicalChatResult == null ? "" : medicalChatResult.threadId(),
                medicalChatResult == null ? "" : medicalChatResult.userId(),
                elapsedMillis(modelStartedAt, finishedAt),
                elapsedMillis(startedAt, finishedAt),
                medicalChatResult != null && medicalChatResult.effectiveReportAvailable(),
                medicalChatResult != null && medicalChatResult.ragApplied(),
                explicitHospitalRequest
        );
    }

    private MedicalChatResult buildReportResult(String threadId, String userId) {
        List<Message> conversation = threadConversationService.getThreadConversation(threadId);
        if (conversation.isEmpty()) {
            ReportTriggerDecision triggerDecision = ReportTriggerDecision.none();
            return new MedicalChatResult(
                    threadId,
                    userId,
                    NO_REPORT_CONTEXT_REPLY,
                    triggerDecision.reportAvailable(),
                    triggerDecision.reportReason(),
                    triggerDecision.level(),
                    triggerDecision.actionText(),
                    false,
                    null,
                    false,
                    List.of(),
                    StructuredMedicalReply.empty(MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER)
            );
        }

        MedicalDiagnosisReport report = medicalReportService.generateReportFromThread(threadId, userId);
        ReportTriggerDecision triggerDecision = report != null && report.shouldGenerateReport()
                ? new ReportTriggerDecision(ReportTriggerLevel.RECOMMENDED, "已按用户请求基于当前线程生成诊断报告。")
                : ReportTriggerDecision.none();
        return new MedicalChatResult(
                threadId,
                userId,
                REPORT_REQUEST_REPLY,
                triggerDecision.reportAvailable(),
                triggerDecision.reportReason(),
                triggerDecision.level(),
                triggerDecision.actionText(),
                true,
                report,
                false,
                List.of(),
                StructuredMedicalReply.empty(MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER)
        );
    }

    private String normalizeThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return medicalAgentRuntime.createThreadId();
        }
        return threadId.trim();
    }

    private long elapsedMillis(long startNanos, long endNanos) {
        return Math.max(0L, (endNanos - startNanos) / 1_000_000L);
    }
}
