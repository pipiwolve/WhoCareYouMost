package com.tay.medicalagent.app.report;

import java.util.List;

/**
 * 结构化医疗诊断报告。
 * <p>
 * 该模型用于把问诊线程整理成稳定字段，便于前端展示、下载或进一步加工。
 */
public record MedicalDiagnosisReport(
        String reportTitle,
        boolean shouldGenerateReport,
        String answerStatus,
        String currentRiskLevel,
        String patientSummary,
        String preliminaryAssessment,
        String uncertaintyReason,
        List<String> mainBasis,
        List<String> nextStepSuggestions,
        List<String> escalationCriteria,
        String assistantReply
) {
}
