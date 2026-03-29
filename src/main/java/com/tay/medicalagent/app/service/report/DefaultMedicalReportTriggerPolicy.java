package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认报告触发策略。
 */
@Component
public class DefaultMedicalReportTriggerPolicy implements MedicalReportTriggerPolicy {

    private static final String URGENT_ACTION_TEXT = "风险较高，可立即生成诊断报告协助就医。";
    private static final String RECOMMENDED_ACTION_TEXT = "已形成较完整的医疗判断，可生成诊断报告。";
    private static final String SUGGESTED_ACTION_TEXT = "当前问诊信息较完整，可按需生成诊断报告。";

    private static final List<String> URGENT_PHRASES = List.of(
            "立即急诊",
            "立即就医",
            "尽快就医",
            "建议急诊",
            "马上急诊",
            "必须升级就医"
    );

    private static final List<String> INSUFFICIENT_PHRASES = List.of(
            "信息不足",
            "无法判断",
            "无法明确",
            "需要更多信息",
            "还需补充",
            "需要补充",
            "请补充",
            "需进一步明确",
            "建议补充"
    );

    @Override
    public ReportTriggerDecision evaluate(
            StructuredMedicalReply structuredMedicalReply,
            String assistantReply,
            List<Message> conversation
    ) {
        StructuredMedicalReply reply = structuredMedicalReply == null
                ? StructuredMedicalReply.empty("")
                : structuredMedicalReply;
        String normalizedReply = assistantReply == null ? "" : assistantReply.trim();

        if (isHighRisk(reply, normalizedReply)) {
            return new ReportTriggerDecision(ReportTriggerLevel.URGENT, URGENT_ACTION_TEXT);
        }

        boolean hasSummary = hasText(reply.summary());
        boolean hasBasis = hasItems(reply.basis());
        boolean hasNextSteps = hasItems(reply.nextSteps());
        boolean hasEscalation = hasItems(reply.escalationSignals());
        boolean completeAssessment = hasSummary && hasBasis && hasNextSteps && hasEscalation;
        boolean looksIncomplete = looksIncomplete(reply, normalizedReply, completeAssessment);

        if (isRiskLevel(reply, "中风险") && completeAssessment && !looksIncomplete) {
            return new ReportTriggerDecision(ReportTriggerLevel.RECOMMENDED, RECOMMENDED_ACTION_TEXT);
        }

        if (!hasText(reply.riskLevel()) && completeAssessment && !looksIncomplete) {
            return new ReportTriggerDecision(ReportTriggerLevel.RECOMMENDED, RECOMMENDED_ACTION_TEXT);
        }

        if (isRiskLevel(reply, "低风险")
                && hasSummary
                && hasBasis
                && hasNextSteps
                && countUserTurns(conversation) >= 2
                && !looksIncomplete) {
            return new ReportTriggerDecision(ReportTriggerLevel.SUGGESTED, SUGGESTED_ACTION_TEXT);
        }

        return ReportTriggerDecision.none();
    }

    private boolean isHighRisk(StructuredMedicalReply reply, String assistantReply) {
        return isRiskLevel(reply, "高风险") || containsAny(assistantReply, URGENT_PHRASES);
    }

    private boolean looksIncomplete(StructuredMedicalReply reply, String assistantReply, boolean completeAssessment) {
        if (containsAny(assistantReply, INSUFFICIENT_PHRASES)) {
            return true;
        }
        return !completeAssessment && hasItems(reply.followUpQuestions());
    }

    private int countUserTurns(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Message message : conversation) {
            if (message instanceof UserMessage) {
                count++;
            }
        }
        return count;
    }

    private boolean isRiskLevel(StructuredMedicalReply reply, String expectedRiskLevel) {
        if (reply == null || expectedRiskLevel == null) {
            return false;
        }
        return expectedRiskLevel.equals(normalizeText(reply.riskLevel()));
    }

    private boolean containsAny(String text, List<String> phrases) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return false;
        }
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return !normalizeText(value).isBlank();
    }

    private boolean hasItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
