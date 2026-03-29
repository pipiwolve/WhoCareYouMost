package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;

/**
 * 医疗报告 PDF 导出服务。
 */
public interface MedicalReportPdfExportService {

    /**
     * 导出 PDF 报告。
     *
     * @param sessionId 前端会话 ID
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @param report 结构化报告
     * @return PDF 文件结果
     */
    MedicalReportPdfFile exportReportPdf(String sessionId, String threadId, String userId, MedicalDiagnosisReport report);
}
