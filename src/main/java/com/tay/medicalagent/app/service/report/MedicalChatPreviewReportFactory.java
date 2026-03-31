package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为聊天内显式医院路线请求构造轻量预览报告。
 */
@Component
public class MedicalChatPreviewReportFactory {

    private static final String DEFAULT_PATIENT_SUMMARY = "当前主要诉求：规划附近医院路线。";
    private static final String DEFAULT_PRELIMINARY_ASSESSMENT = "当前缺少充分病情信息，已先按通用医院路线需求处理；补充症状后可优化医院匹配。";
    private static final List<String> DEFAULT_MAIN_BASIS = List.of("用户明确请求规划附近医院路线");
    private static final List<String> DEFAULT_NEXT_STEPS = List.of(
            "如已授权定位，可查看附近医院与路线",
            "如有明确症状，请继续补充以优化医院匹配"
    );

    private final UserProfileService userProfileService;

    public MedicalChatPreviewReportFactory(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    public MedicalDiagnosisReport build(
            String threadId,
            String userId,
            String latestUserMessage,
            MedicalChatResult medicalChatResult
    ) {
        StructuredMedicalReply structuredReply = medicalChatResult == null || medicalChatResult.structuredReply() == null
                ? StructuredMedicalReply.empty("")
                : medicalChatResult.structuredReply();
        List<String> mainBasis = safeList(structuredReply.basis());
        List<String> nextSteps = safeList(structuredReply.nextSteps());
        List<String> escalationCriteria = safeList(structuredReply.escalationSignals());
        boolean hasMedicalSignals = !mainBasis.isEmpty()
                || !safeText(structuredReply.summary()).isBlank()
                || !safeText(structuredReply.riskLevel()).isBlank();

        return new MedicalDiagnosisReport(
                resolveReportSubject(userId, threadId) + "的医疗诊断报告",
                true,
                hasMedicalSignals ? "CONFIRMED" : "INSUFFICIENT_INFORMATION",
                safeText(structuredReply.riskLevel()),
                buildPatientSummary(mainBasis, latestUserMessage),
                safeText(structuredReply.summary()).isBlank()
                        ? DEFAULT_PRELIMINARY_ASSESSMENT
                        : safeText(structuredReply.summary()),
                "",
                mainBasis.isEmpty() ? DEFAULT_MAIN_BASIS : mainBasis,
                nextSteps.isEmpty() ? DEFAULT_NEXT_STEPS : nextSteps,
                escalationCriteria,
                medicalChatResult == null ? "" : safeText(medicalChatResult.reply())
        );
    }

    private String buildPatientSummary(List<String> mainBasis, String latestUserMessage) {
        if (mainBasis != null && !mainBasis.isEmpty()) {
            return "已识别症状：" + String.join("；", mainBasis) + "。";
        }
        String sanitizedMessage = sanitizeLatestUserMessage(latestUserMessage);
        if (!sanitizedMessage.isBlank()) {
            return sanitizedMessage;
        }
        return DEFAULT_PATIENT_SUMMARY;
    }

    private String sanitizeLatestUserMessage(String latestUserMessage) {
        String normalized = safeText(latestUserMessage);
        if (normalized.isBlank()) {
            return "";
        }
        String sanitized = normalized
                .replace("帮我规划附近医院路线", "")
                .replace("规划附近医院路线", "")
                .replace("帮我规划路线", "")
                .replace("帮我找医院", "")
                .replace("帮我看看附近医院", "")
                .replace("附近医院路线", "")
                .replace("附近医院", "")
                .replace("路线规划", "")
                .trim();
        return sanitized;
    }

    private String resolveReportSubject(String userId, String fallbackThreadId) {
        String effectiveUserId = userProfileService.normalizeUserId(userId);
        return userProfileService.getUserProfileMemory(effectiveUserId)
                .map(profile -> profile.get("name"))
                .map(this::safeProfileValue)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> normalizeThreadId(fallbackThreadId));
    }

    private String safeProfileValue(Object value) {
        if (value == null) {
            return "";
        }
        return safeText(String.valueOf(value));
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::safeText)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String normalizeThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "当前线程";
        }
        return threadId.trim();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
