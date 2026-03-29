package com.tay.medicalagent.app.service.report;

/**
 * 报告触发决策结果。
 *
 * @param level 触发等级
 * @param actionText 面向前端的动作文案
 */
public record ReportTriggerDecision(
        ReportTriggerLevel level,
        String actionText
) {

    public ReportTriggerDecision {
        level = level == null ? ReportTriggerLevel.NONE : level;
        actionText = actionText == null ? "" : actionText.trim();
        if (!level.isAvailable()) {
            actionText = "";
        }
    }

    public static ReportTriggerDecision none() {
        return new ReportTriggerDecision(ReportTriggerLevel.NONE, "");
    }

    public boolean reportAvailable() {
        return level.isAvailable();
    }

    public String reportReason() {
        return reportAvailable() ? actionText : "";
    }
}
