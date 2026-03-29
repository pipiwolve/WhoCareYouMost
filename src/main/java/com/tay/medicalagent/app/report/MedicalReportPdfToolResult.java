package com.tay.medicalagent.app.report;

/**
 * PDF 导出工具输出。
 */
public record MedicalReportPdfToolResult(
        String fileName,
        String contentType,
        String base64Content,
        long byteSize
) {
}
