package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
/**
 * 默认医疗报告服务实现。
 * <p>
 * 基于线程历史与用户画像，调用模型生成结构化诊断报告，同时提供报告请求和可生成性判断。
 */
public class DefaultMedicalReportService implements MedicalReportService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMedicalReportService.class);

    private static final Pattern REPORT_REQUEST_PATTERN =
            Pattern.compile(".*(生成|整理|导出|输出|给我|帮我做).{0,8}(诊断报告|医疗报告|分诊报告|报告|总结).*");

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final UserProfileService userProfileService;
    private final ThreadConversationService threadConversationService;

    public DefaultMedicalReportService(
            MedicalAiModelProvider medicalAiModelProvider,
            UserProfileService userProfileService,
            ThreadConversationService threadConversationService
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.userProfileService = userProfileService;
        this.threadConversationService = threadConversationService;
    }

    @Override
    public boolean isReportRequest(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        return REPORT_REQUEST_PATTERN.matcher(prompt.trim()).matches();
    }

    @Override
    public ReportDecision evaluateReportAvailability(String assistantReply) {
        String normalizedReply = safeText(assistantReply);
        if (normalizedReply.isBlank()) {
            return new ReportDecision(false, "");
        }

        if (containsAny(normalizedReply, "无法判断", "不确定", "信息不足", "需要更多信息", "建议线下就医")) {
            return new ReportDecision(true, "当前回复已明确说明信息不足或建议线下就医，可生成诊断报告。");
        }

        if (containsAny(normalizedReply, "立即急诊", "立即就医", "尽快就医", "必须升级就医", "建议急诊")) {
            return new ReportDecision(true, "当前回复已识别较高风险或明确升级就医条件，可生成诊断报告。");
        }

        if (containsAny(normalizedReply, "当前风险等级", "高风险", "中风险", "低风险", "需排查", "警惕", "考虑")) {
            return new ReportDecision(true, "当前回复已经形成风险判断或排查方向，可生成诊断报告。");
        }

        return new ReportDecision(false, "");
    }

    @Override
    public MedicalDiagnosisReport generateReportFromThread(String threadId, String userId) {
        String effectiveThreadId = normalizeThreadId(threadId);
        String effectiveUserId = userProfileService.normalizeUserId(userId);

        List<Message> conversation = threadConversationService.getThreadConversation(effectiveThreadId);
        if (conversation.isEmpty()) {
            return fallbackReport("", effectiveThreadId, "当前线程暂无足够对话内容，无法生成诊断报告。", false);
        }

        return generateMedicalDiagnosisReport(conversation, effectiveThreadId, effectiveUserId);
    }

    private MedicalDiagnosisReport generateMedicalDiagnosisReport(List<Message> conversation, String chatId, String userId) {
        BeanOutputConverter<MedicalDiagnosisReport> outputConverter =
                new BeanOutputConverter<>(MedicalDiagnosisReport.class);

        String profileContext = userProfileService.buildProfileContext(userId);
        if (profileContext.isBlank()) {
            profileContext = MedicalPrompts.NO_PROFILE_CONTEXT;
        }

        String conversationTranscript = threadConversationService.buildConversationTranscript(conversation);
        String latestAssistantReply = threadConversationService.findLatestAssistantReply(conversation);
        String effectiveChatId = normalizeThreadId(chatId);

        String reportPrompt = """
                请根据以下内容判断是否需要生成结构化诊断报告，并严格按指定 JSON 返回。

                当前会话ID：%s
                当前线程完整对话：
                %s

                用户长期资料：
                %s

                最近一轮助手回答：
                %s

                额外要求：
                1. 如果 shouldGenerateReport=true，reportTitle 使用“%s的医疗诊断报告”。
                2. 如果 shouldGenerateReport=false，reportTitle 使用“无需生成诊断报告”。
                3. patientSummary 只总结当前线程里已经明确给出的病情信息。
                4. preliminaryAssessment 总结助手已经给出的判断或排查方向；如果助手明确说无法判断，则写明无法判断。
                5. uncertaintyReason 只有在信息不足或无法判断时填写，否则返回空字符串。
                6. 如果当前线程中用户只是明确要求“生成报告”，不要把这句话当成病情信息。

                %s
                """.formatted(
                effectiveChatId,
                conversationTranscript,
                profileContext,
                latestAssistantReply,
                effectiveChatId,
                outputConverter.getFormat()
        );

        try {
            ChatResponse response = medicalAiModelProvider.getChatModel().call(new Prompt(
                    List.of(
                            new SystemMessage(MedicalPrompts.REPORT_SYSTEM_PROMPT),
                            new UserMessage(reportPrompt)
                    )
            ));
            String responseText = extractAssistantText(response);
            MedicalDiagnosisReport report = outputConverter.convert(responseText);
            return normalizeReport(report, latestAssistantReply, effectiveChatId);
        }
        catch (Exception ex) {
            log.warn("Failed to generate structured medical report for chatId={}", effectiveChatId, ex);
            return fallbackReport(latestAssistantReply, effectiveChatId, "报告生成失败，保留本轮回答作为兜底结果。", true);
        }
    }

    private MedicalDiagnosisReport normalizeReport(
            MedicalDiagnosisReport report,
            String assistantReply,
            String chatId
    ) {
        if (report == null) {
            return fallbackReport(assistantReply, chatId, "模型未返回有效报告。", true);
        }

        String answerStatus = switch (safeText(report.answerStatus())) {
            case "CONFIRMED", "INSUFFICIENT_INFORMATION", "GENERAL_ADVICE_ONLY" -> report.answerStatus();
            default -> report.shouldGenerateReport() ? "CONFIRMED" : "GENERAL_ADVICE_ONLY";
        };

        String reportTitle = safeText(report.reportTitle());
        if (reportTitle.isBlank()) {
            reportTitle = report.shouldGenerateReport() ? chatId + "的医疗诊断报告" : "无需生成诊断报告";
        }

        String normalizedAssistantReply = safeText(report.assistantReply());
        if (normalizedAssistantReply.isBlank()) {
            normalizedAssistantReply = safeText(assistantReply);
        }

        return new MedicalDiagnosisReport(
                reportTitle,
                report.shouldGenerateReport(),
                answerStatus,
                safeText(report.currentRiskLevel()),
                safeText(report.patientSummary()),
                safeText(report.preliminaryAssessment()),
                safeText(report.uncertaintyReason()),
                safeList(report.mainBasis()),
                safeList(report.nextStepSuggestions()),
                safeList(report.escalationCriteria()),
                normalizedAssistantReply
        );
    }

    private MedicalDiagnosisReport fallbackReport(
            String assistantReply,
            String chatId,
            String uncertaintyReason,
            boolean shouldGenerateReport
    ) {
        String effectiveChatId = normalizeThreadId(chatId);
        return new MedicalDiagnosisReport(
                shouldGenerateReport ? effectiveChatId + "的医疗诊断报告" : "无需生成诊断报告",
                shouldGenerateReport,
                shouldGenerateReport ? "INSUFFICIENT_INFORMATION" : "GENERAL_ADVICE_ONLY",
                "",
                "",
                "",
                uncertaintyReason,
                List.of(),
                List.of(),
                List.of(),
                safeText(assistantReply)
        );
    }

    private String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.isEmpty() ? "" : text;
    }

    private String normalizeThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "当前线程";
        }
        return threadId.trim();
    }
}
