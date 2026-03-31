package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 默认医疗报告 PDF 导出服务。
 */
@Service
public class DefaultMedicalReportPdfExportService implements MedicalReportPdfExportService {

    private final MedicalReportPdfRenderer medicalReportPdfRenderer;

    public DefaultMedicalReportPdfExportService(
            MedicalReportPdfRenderer medicalReportPdfRenderer
    ) {
        this.medicalReportPdfRenderer = medicalReportPdfRenderer;
    }

    @Override
    public MedicalReportPdfFile exportReportPdf(
            String sessionId,
            String threadId,
            String userId,
            MedicalDiagnosisReport report,
            MedicalHospitalPlanningSummary planningSummary
    ) {
        if (report == null || !report.shouldGenerateReport()) {
            throw new ReportNotExportableException("当前会话暂无可导出的诊断报告");
        }

        MedicalReportPdfPayload payload = new MedicalReportPdfPayload(
                normalizeSessionId(sessionId),
                normalizeText(threadId, "当前线程"),
                normalizeText(userId, "anonymous"),
                OffsetDateTime.now(),
                buildPdfFileName(report, sessionId),
                MedicalPrompts.DEFAULT_REPORT_DISCLAIMER,
                report,
                planningSummary == null ? MedicalHospitalPlanningSummary.empty() : planningSummary
        );

        try {
            byte[] content = medicalReportPdfRenderer.render(payload);
            if (content.length == 0) {
                throw new ReportExportException("报告导出失败");
            }
            return new MedicalReportPdfFile(payload.fileName(), MediaType.APPLICATION_PDF_VALUE, content);
        }
        catch (ReportNotExportableException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ReportExportException("报告导出失败", ex);
        }
    }

    private String normalizeSessionId(String sessionId) {
        return normalizeText(sessionId, "unknown-session");
    }

    private String buildPdfFileName(MedicalDiagnosisReport report, String sessionId) {
        String reportTitle = report == null ? "" : normalizeText(report.reportTitle(), "");
        String sanitizedTitle = sanitizeFileName(reportTitle);
        if (!sanitizedTitle.isBlank()) {
            return sanitizedTitle + ".pdf";
        }
        return "medical-report-" + normalizeSessionId(sessionId) + ".pdf";
    }

    private String sanitizeFileName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        String sanitized = candidate.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-")
                .replaceAll("\\s+", " ")
                .replaceAll("^[.\\s-]+|[.\\s-]+$", "");
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 120).trim();
        }
        return sanitized;
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
