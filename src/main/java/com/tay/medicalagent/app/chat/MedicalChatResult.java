package com.tay.medicalagent.app.chat;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;

import java.util.List;

/**
 * 医疗聊天统一返回模型。
 *
 * @param threadId 会话线程 ID
 * @param userId 用户唯一标识
 * @param reply 助手回复内容
 * @param reportAvailable 当前轮是否建议生成报告
 * @param reportReason 报告可用性的解释
 * @param reportTriggerLevel 报告触发等级
 * @param reportActionText 报告触发动作文案
 * @param reportGenerated 当前调用是否已直接生成报告
 * @param report 若已生成则返回结构化报告
 * @param ragApplied 本轮是否成功应用了 RAG 检索上下文
 * @param sources 本轮 RAG 使用到的知识来源
 * @param structuredReply 面向前端消息框的结构化回复
 */
public record MedicalChatResult(
        String threadId,
        String userId,
        String reply,
        boolean reportAvailable,
        String reportReason,
        ReportTriggerLevel reportTriggerLevel,
        String reportActionText,
        boolean reportGenerated,
        MedicalDiagnosisReport report,
        boolean ragApplied,
        List<KnowledgeSource> sources,
        StructuredMedicalReply structuredReply
) {

    public boolean effectiveReportAvailable() {
        if (reportTriggerLevel == null) {
            return reportAvailable;
        }
        return reportTriggerLevel.isAvailable();
    }

    public String effectiveReportReason() {
        if (reportTriggerLevel == null) {
            return safeText(reportReason);
        }
        return reportTriggerLevel.isAvailable() ? safeText(reportActionText) : "";
    }

    public String effectiveReportTriggerLevel() {
        if (reportTriggerLevel != null) {
            return reportTriggerLevel.apiValue();
        }
        return reportAvailable ? ReportTriggerLevel.RECOMMENDED.apiValue() : ReportTriggerLevel.NONE.apiValue();
    }

    public String effectiveReportActionText() {
        if (reportTriggerLevel == null) {
            return reportAvailable ? safeText(reportReason) : "";
        }
        return reportTriggerLevel.isAvailable() ? safeText(reportActionText) : "";
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
