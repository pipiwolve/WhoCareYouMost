package com.tay.medicalagent.app.service.chat;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.NormalizedMedicalReply;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalReportService;
import com.tay.medicalagent.app.service.report.ReportDecision;
import com.tay.medicalagent.app.service.runtime.MedicalAgentRuntime;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 默认医疗聊天服务实现。
 * <p>
 * 负责在普通问答、报告请求和 RAG 结果回传之间做统一编排，是上层门面的主要业务入口。
 */
public class DefaultMedicalChatService implements MedicalChatService {

    private static final String REPORT_REQUEST_REPLY = "已根据当前线程对话生成医疗诊断报告。";
    private static final String NO_REPORT_CONTEXT_REPLY = "当前线程暂无足够对话内容，无法生成诊断报告。";

    private final MedicalAgentRuntime medicalAgentRuntime;
    private final ThreadConversationService threadConversationService;
    private final MedicalReportService medicalReportService;
    private final UserProfileService userProfileService;
    private final MedicalRagContextHolder medicalRagContextHolder;
    private final MedicalReplyFormatter medicalReplyFormatter;

    public DefaultMedicalChatService(
            MedicalAgentRuntime medicalAgentRuntime,
            ThreadConversationService threadConversationService,
            MedicalReportService medicalReportService,
            UserProfileService userProfileService,
            MedicalRagContextHolder medicalRagContextHolder,
            MedicalReplyFormatter medicalReplyFormatter
    ) {
        this.medicalAgentRuntime = medicalAgentRuntime;
        this.threadConversationService = threadConversationService;
        this.medicalReportService = medicalReportService;
        this.userProfileService = userProfileService;
        this.medicalRagContextHolder = medicalRagContextHolder;
        this.medicalReplyFormatter = medicalReplyFormatter;
    }

    @Override
    public MedicalChatResult doChat(String prompt, String threadId, String userId) throws GraphRunnerException {
        String effectiveThreadId = normalizeThreadId(threadId);
        String effectiveUserId = userProfileService.normalizeUserId(userId);

        if (medicalReportService.isReportRequest(prompt)) {
            return buildReportResult(effectiveThreadId, effectiveUserId);
        }

        AssistantMessage assistantMessage = medicalAgentRuntime.doChatMessage(prompt, effectiveThreadId, effectiveUserId);
        NormalizedMedicalReply normalizedReply = medicalReplyFormatter.normalize(assistantMessage.getText());
        AssistantMessage normalizedAssistantMessage = AssistantMessage.builder()
                .content(normalizedReply.reply())
                .properties(assistantMessage.getMetadata())
                .toolCalls(assistantMessage.getToolCalls())
                .media(assistantMessage.getMedia())
                .build();
        threadConversationService.appendExchange(effectiveThreadId, new UserMessage(prompt), normalizedAssistantMessage);
        RagContext ragContext = medicalRagContextHolder.get(effectiveThreadId).orElse(RagContext.empty(prompt));

        ReportDecision reportDecision = medicalReportService.evaluateReportAvailability(normalizedReply.reply());
        return new MedicalChatResult(
                effectiveThreadId,
                effectiveUserId,
                normalizedReply.reply(),
                reportDecision.available(),
                reportDecision.reason(),
                false,
                null,
                ragContext.applied(),
                ragContext.sources(),
                normalizedReply.structuredReply()
        );
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

    private MedicalChatResult buildReportResult(String threadId, String userId) {
        List<Message> conversation = threadConversationService.getThreadConversation(threadId);
        if (conversation.isEmpty()) {
            return new MedicalChatResult(
                    threadId,
                    userId,
                    NO_REPORT_CONTEXT_REPLY,
                    false,
                    "当前线程暂无可用于整理的问诊内容。",
                    false,
                    null,
                    false,
                    List.of(),
                    StructuredMedicalReply.empty(MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER)
            );
        }

        MedicalDiagnosisReport report = medicalReportService.generateReportFromThread(threadId, userId);
        return new MedicalChatResult(
                threadId,
                userId,
                REPORT_REQUEST_REPLY,
                true,
                "已按用户请求基于当前线程生成诊断报告。",
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
}
