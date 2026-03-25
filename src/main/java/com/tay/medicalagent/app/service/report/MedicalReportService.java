package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;

/**
 * 医疗报告服务抽象。
 */
public interface MedicalReportService {

    /**
     * 判断当前输入是否明确是在请求生成报告。
     *
     * @param prompt 用户输入
     * @return 是否为报告请求
     */
    boolean isReportRequest(String prompt);

    /**
     * 根据助手回复判断是否应该向用户提示可生成报告。
     *
     * @param assistantReply 助手回复
     * @return 报告建议决策
     */
    ReportDecision evaluateReportAvailability(String assistantReply);

    /**
     * 基于线程历史生成结构化医疗报告。
     *
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 结构化医疗报告
     */
    MedicalDiagnosisReport generateReportFromThread(String threadId, String userId);
}
