package com.tay.medicalagent.web.support;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.web.dto.ChatCompletionResponse;
import com.tay.medicalagent.web.dto.KnowledgeSourceView;
import com.tay.medicalagent.web.dto.ReportQueryResponse;
import com.tay.medicalagent.web.dto.ReportViewDto;
import com.tay.medicalagent.web.dto.StructuredReplyView;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
/**
 * 前端接口视图模型映射器。
 */
public class MedicalApiViewMapper {

    private static final String DEFAULT_REPORT_UNAVAILABLE_REASON = "当前会话暂无足够问诊内容";
    private static final String DEFAULT_DISCLAIMER = "本报告由AI生成，仅供参考，不能替代专业医生诊断。";

    public ChatCompletionResponse toChatCompletionResponse(String sessionId, MedicalChatResult medicalChatResult) {
        return new ChatCompletionResponse(
                sessionId,
                safeText(medicalChatResult.reply()),
                toStructuredReplyView(medicalChatResult.structuredReply()),
                medicalChatResult.reportAvailable(),
                safeText(medicalChatResult.reportReason()),
                medicalChatResult.reportGenerated(),
                toReportViewNullable(medicalChatResult.report()),
                medicalChatResult.ragApplied(),
                safeSources(medicalChatResult.sources())
        );
    }

    public ReportQueryResponse toReportQueryResponse(MedicalDiagnosisReport medicalDiagnosisReport) {
        if (medicalDiagnosisReport == null) {
            return new ReportQueryResponse(false, DEFAULT_REPORT_UNAVAILABLE_REASON, null);
        }

        if (!medicalDiagnosisReport.shouldGenerateReport()) {
            String reason = safeText(medicalDiagnosisReport.uncertaintyReason());
            if (reason.isBlank()) {
                reason = DEFAULT_REPORT_UNAVAILABLE_REASON;
            }
            return new ReportQueryResponse(false, reason, null);
        }

        return new ReportQueryResponse(true, "", toReportView(medicalDiagnosisReport));
    }

    public ReportViewDto toReportViewNullable(MedicalDiagnosisReport medicalDiagnosisReport) {
        return medicalDiagnosisReport == null ? null : toReportView(medicalDiagnosisReport);
    }

    public ReportViewDto toReportView(MedicalDiagnosisReport medicalDiagnosisReport) {
        return new ReportViewDto(
                safeText(medicalDiagnosisReport.reportTitle()),
                safeText(medicalDiagnosisReport.currentRiskLevel()),
                safeText(medicalDiagnosisReport.patientSummary()),
                safeText(medicalDiagnosisReport.preliminaryAssessment()),
                safeList(medicalDiagnosisReport.mainBasis()),
                safeList(medicalDiagnosisReport.nextStepSuggestions()),
                safeList(medicalDiagnosisReport.escalationCriteria()),
                DEFAULT_DISCLAIMER
        );
    }

    private StructuredReplyView toStructuredReplyView(StructuredMedicalReply structuredMedicalReply) {
        if (structuredMedicalReply == null) {
            return new StructuredReplyView("", "", List.of(), List.of(), List.of(), List.of(), DEFAULT_DISCLAIMER);
        }
        return new StructuredReplyView(
                safeText(structuredMedicalReply.riskLevel()),
                safeText(structuredMedicalReply.summary()),
                safeList(structuredMedicalReply.basis()),
                safeList(structuredMedicalReply.nextSteps()),
                safeList(structuredMedicalReply.escalationSignals()),
                safeList(structuredMedicalReply.followUpQuestions()),
                safeText(structuredMedicalReply.disclaimer()).isBlank() ? DEFAULT_DISCLAIMER : safeText(structuredMedicalReply.disclaimer())
        );
    }

    private List<KnowledgeSourceView> safeSources(List<KnowledgeSource> knowledgeSources) {
        if (knowledgeSources == null || knowledgeSources.isEmpty()) {
            return List.of();
        }
        return knowledgeSources.stream()
                .map(this::toKnowledgeSourceView)
                .toList();
    }

    private KnowledgeSourceView toKnowledgeSourceView(KnowledgeSource knowledgeSource) {
        return new KnowledgeSourceView(
                safeText(knowledgeSource.sourceId()),
                safeText(knowledgeSource.title()),
                safeText(knowledgeSource.section()),
                knowledgeSource.score()
        );
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
