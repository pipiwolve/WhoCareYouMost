package com.tay.medicalagent.app.report;

import java.time.OffsetDateTime;

/**
 * PDF 导出上下文载荷。
 */
public record MedicalReportPdfPayload(
        String sessionId,
        String threadId,
        String userId,
        OffsetDateTime generatedAt,
        String fileName,
        String disclaimer,
        MedicalDiagnosisReport report,
        MedicalHospitalPlanningSummary planningSummary
) {
}
