package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 默认网关实现：当尚未接入真实 MCP server 时，返回空并交给上层降级。
 */
public class NoopMedicalHospitalPlanningGateway implements MedicalHospitalPlanningGateway {

    private static final Logger log = LoggerFactory.getLogger(NoopMedicalHospitalPlanningGateway.class);

    @Override
    public Optional<MedicalHospitalPlanningSummary> plan(
            double latitude,
            double longitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        log.warn(
                "NoopMedicalHospitalPlanningGateway invoked directly. serverName={}, profileId={}, topK={}, location={},{}",
                mcpProperties == null ? null : mcpProperties.getServerName(),
                planningIntent == null ? null : planningIntent.profileId(),
                planningIntent == null ? null : planningIntent.topK(),
                latitude,
                longitude
        );
        return Optional.empty();
    }
}
