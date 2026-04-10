package com.tay.medicalagent.app.report;

/**
 * 最终报告构建状态。
 * <p>
 * 用于把“已就绪 / 生成中 / 当前不可生成”这些状态以统一形式返回给 Web 层。
 */
public record MedicalReportBuildState(
        MedicalReportSnapshot snapshot,
        String status,
        String reason,
        String reasonCode,
        Integer retryAfterMs
) {

    public static final String STATUS_READY = "ready";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_NOT_READY = "not_ready";

    public static final String REASON_CODE_INSUFFICIENT_CONTEXT = "insufficient_context";
    public static final String REASON_CODE_REPORT_GENERATING = "report_generating";
    public static final String REASON_CODE_REPORT_WAIT_TIMEOUT = "report_wait_timeout";

    public static MedicalReportBuildState ready(MedicalReportSnapshot snapshot) {
        return new MedicalReportBuildState(snapshot, STATUS_READY, "", "", null);
    }

    public static MedicalReportBuildState notReady(MedicalReportSnapshot snapshot, String reason, String reasonCode) {
        return new MedicalReportBuildState(snapshot, STATUS_NOT_READY, safeText(reason), safeText(reasonCode), null);
    }

    public static MedicalReportBuildState generating(String reason, String reasonCode, Integer retryAfterMs) {
        return new MedicalReportBuildState(null, STATUS_GENERATING, safeText(reason), safeText(reasonCode), retryAfterMs);
    }

    public boolean ready() {
        return STATUS_READY.equals(status);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
