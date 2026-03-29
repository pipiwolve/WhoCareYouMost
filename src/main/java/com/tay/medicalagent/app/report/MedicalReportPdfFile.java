package com.tay.medicalagent.app.report;

/**
 * PDF 报告文件结果。
 *
 * @param fileName 文件名
 * @param contentType MIME 类型
 * @param content 文件内容
 */
public record MedicalReportPdfFile(
        String fileName,
        String contentType,
        byte[] content
) {
}
