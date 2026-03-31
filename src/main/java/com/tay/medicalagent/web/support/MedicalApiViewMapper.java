package com.tay.medicalagent.web.support;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.web.dto.ChatCompletionResponse;
import com.tay.medicalagent.web.dto.HospitalPlanView;
import com.tay.medicalagent.web.dto.HospitalRouteOptionView;
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
    private static final String DEFAULT_DISCLAIMER = MedicalPrompts.DEFAULT_REPORT_DISCLAIMER;
    private static final String DEFAULT_ROUTE_UNAVAILABLE_MESSAGE = "路线服务暂不可用";

    public ChatCompletionResponse toChatCompletionResponse(String sessionId, MedicalChatResult medicalChatResult) {
        return toChatCompletionResponse(sessionId, medicalChatResult, null);
    }

    public ChatCompletionResponse toChatCompletionResponse(
            String sessionId,
            MedicalChatResult medicalChatResult,
            MedicalReportSnapshot reportPreviewSnapshot
    ) {
        ReportViewDto generatedReport = medicalChatResult.report() == null
                ? null
                : toReportView(medicalChatResult.report(), MedicalHospitalPlanningSummary.empty());
        return new ChatCompletionResponse(
                sessionId,
                safeText(medicalChatResult.reply()),
                toStructuredReplyView(medicalChatResult.structuredReply()),
                medicalChatResult.effectiveReportAvailable(),
                safeText(medicalChatResult.effectiveReportReason()),
                safeText(medicalChatResult.effectiveReportTriggerLevel()),
                safeText(medicalChatResult.effectiveReportActionText()),
                medicalChatResult.reportGenerated(),
                generatedReport,
                toReportPreview(reportPreviewSnapshot),
                medicalChatResult.ragApplied(),
                safeSources(medicalChatResult.sources())
        );
    }

    public ReportQueryResponse toReportQueryResponse(MedicalDiagnosisReport medicalDiagnosisReport) {
        return toReportQueryResponse(medicalDiagnosisReport, MedicalHospitalPlanningSummary.empty());
    }

    public ReportQueryResponse toReportQueryResponse(
            MedicalDiagnosisReport medicalDiagnosisReport,
            MedicalHospitalPlanningSummary planningSummary
    ) {
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

        return new ReportQueryResponse(true, "", toReportView(medicalDiagnosisReport, planningSummary));
    }

    public ReportViewDto toReportViewNullable(MedicalDiagnosisReport medicalDiagnosisReport) {
        return medicalDiagnosisReport == null
                ? null
                : toReportView(medicalDiagnosisReport, MedicalHospitalPlanningSummary.empty());
    }

    public ReportViewDto toReportView(MedicalDiagnosisReport medicalDiagnosisReport) {
        return toReportView(medicalDiagnosisReport, MedicalHospitalPlanningSummary.empty());
    }

    public ReportViewDto toReportView(
            MedicalDiagnosisReport medicalDiagnosisReport,
            MedicalHospitalPlanningSummary planningSummary
    ) {
        MedicalHospitalPlanningSummary effectivePlanning = planningSummary == null
                ? MedicalHospitalPlanningSummary.empty()
                : planningSummary;
        return new ReportViewDto(
                safeText(medicalDiagnosisReport.reportTitle()),
                safeText(medicalDiagnosisReport.currentRiskLevel()),
                safeText(medicalDiagnosisReport.patientSummary()),
                safeText(medicalDiagnosisReport.preliminaryAssessment()),
                safeList(medicalDiagnosisReport.mainBasis()),
                safeList(medicalDiagnosisReport.nextStepSuggestions()),
                safeList(medicalDiagnosisReport.escalationCriteria()),
                toHospitalPlans(effectivePlanning.hospitals()),
                effectivePlanning.routesAvailable(),
                safeText(effectivePlanning.routeStatusMessage()).isBlank()
                        ? (effectivePlanning.routesAvailable() ? "" : DEFAULT_ROUTE_UNAVAILABLE_MESSAGE)
                        : safeText(effectivePlanning.routeStatusMessage()),
                DEFAULT_DISCLAIMER
        );
    }

    private ReportViewDto toReportPreview(MedicalReportSnapshot reportPreviewSnapshot) {
        if (reportPreviewSnapshot == null || reportPreviewSnapshot.report() == null) {
            return null;
        }
        if (!reportPreviewSnapshot.report().shouldGenerateReport()) {
            return null;
        }
        return toReportView(reportPreviewSnapshot.report(), reportPreviewSnapshot.planningSummary());
    }

    private List<HospitalPlanView> toHospitalPlans(List<MedicalHospitalRecommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return recommendations.stream().map(this::toHospitalPlanView).toList();
    }

    private HospitalPlanView toHospitalPlanView(MedicalHospitalRecommendation recommendation) {
        return new HospitalPlanView(
                safeText(recommendation.name()),
                safeText(recommendation.address()),
                recommendation.tier3a(),
                recommendation.distanceMeters(),
                toRouteOptionViews(recommendation.routes())
        );
    }

    private List<HospitalRouteOptionView> toRouteOptionViews(List<MedicalHospitalRouteOption> routeOptions) {
        if (routeOptions == null || routeOptions.isEmpty()) {
            return List.of();
        }
        return routeOptions.stream()
                .map(option -> new HospitalRouteOptionView(
                        safeText(option.mode()),
                        option.distanceMeters(),
                        option.durationMinutes(),
                        safeText(option.summary()),
                        safeTextList(option.steps())
                ))
                .toList();
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

    private List<String> safeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::safeText)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
