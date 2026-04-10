package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportBuildState;

public interface ReportBuildCoordinator {

    void warmUpFinalReport(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport providedReport
    );

    MedicalReportBuildState getOrStartFinalReport(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    );

    void invalidateSession(String sessionId);

    void clear();
}
