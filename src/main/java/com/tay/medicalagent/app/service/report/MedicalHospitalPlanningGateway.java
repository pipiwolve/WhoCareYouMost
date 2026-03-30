package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;

import java.util.Optional;

/**
 * 医院规划网关抽象，MCP/其他远端实现都通过该接口接入。
 */
public interface MedicalHospitalPlanningGateway {

    Optional<MedicalHospitalPlanningSummary> plan(
            double latitude,
            double longitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    );
}
