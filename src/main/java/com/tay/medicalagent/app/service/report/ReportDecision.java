package com.tay.medicalagent.app.service.report;

/**
 * 报告建议判断结果。
 *
 * @param available 是否建议向用户提供生成报告入口
 * @param reason 建议原因
 */
public record ReportDecision(boolean available, String reason) {
}
