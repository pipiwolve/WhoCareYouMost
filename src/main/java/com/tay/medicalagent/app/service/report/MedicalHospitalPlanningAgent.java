package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;

import java.util.Optional;

/**
 * 基于 MCP Tool Calling 的医院规划 Agent。
 */
public interface MedicalHospitalPlanningAgent {

    Optional<MedicalHospitalPlanningSummary> plan(
            double latitude,
            double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    );
}
