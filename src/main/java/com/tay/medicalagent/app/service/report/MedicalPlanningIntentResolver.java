package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;

/**
 * 医院规划意图解析器。
 */
public interface MedicalPlanningIntentResolver {

    boolean shouldPrepareChatPreview(String latestUserMessage, MedicalChatResult medicalChatResult);

    MedicalPlanningIntent resolve(
            MedicalDiagnosisReport report,
            StructuredMedicalReply structuredMedicalReply,
            String latestUserMessage,
            ReportTriggerLevel reportTriggerLevel
    );

    MedicalPlanningIntent resolve(MedicalDiagnosisReport report);
}
