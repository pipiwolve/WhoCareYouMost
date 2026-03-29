package com.tay.medicalagent.app.service.report;

/**
 * 报告导出失败异常。
 */
public class ReportExportException extends RuntimeException {

    public ReportExportException(String message) {
        super(message);
    }

    public ReportExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
