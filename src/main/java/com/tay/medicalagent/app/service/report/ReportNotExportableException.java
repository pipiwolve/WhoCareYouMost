package com.tay.medicalagent.app.service.report;

/**
 * 报告当前不可导出异常。
 */
public class ReportNotExportableException extends RuntimeException {

    public ReportNotExportableException(String message) {
        super(message);
    }
}
