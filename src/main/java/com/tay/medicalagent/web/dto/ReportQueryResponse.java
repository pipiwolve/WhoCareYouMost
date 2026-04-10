package com.tay.medicalagent.web.dto;

/**
 * 医疗报告查询响应。
 */
public record ReportQueryResponse(
        boolean ready,
        String reason,
        ReportViewDto report,
        String status,
        String reasonCode,
        Integer retryAfterMs
) {
}
