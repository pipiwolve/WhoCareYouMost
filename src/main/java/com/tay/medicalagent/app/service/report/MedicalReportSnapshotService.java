package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;

/**
 * 冻结报告快照服务。
 */
public interface MedicalReportSnapshotService {

    MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    );

    MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent
    );

    MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalPlanningIntent planningIntent
    );

    void invalidate(String sessionId);

    void clear();
}
