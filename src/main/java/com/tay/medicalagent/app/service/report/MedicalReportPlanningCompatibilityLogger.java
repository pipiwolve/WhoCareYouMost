package com.tay.medicalagent.app.service.report;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class MedicalReportPlanningCompatibilityLogger {

    private static final Logger log = LoggerFactory.getLogger(MedicalReportPlanningCompatibilityLogger.class);

    private final Environment environment;
    private final MedicalReportPlanningProperties planningProperties;

    MedicalReportPlanningCompatibilityLogger(
            Environment environment,
            MedicalReportPlanningProperties planningProperties
    ) {
        this.environment = environment;
        this.planningProperties = planningProperties;
    }

    @PostConstruct
    void logDeprecatedFlagsIfNeeded() {
        log.info(
                "Medical report planning mode resolved. environment={}, configuredMode={}, resolvedMode={}, routePlanningEnabled={}, mcpServerName={}",
                planningProperties.getEnvironment(),
                planningProperties.getMode(),
                planningProperties.getResolvedMode().name().toLowerCase(),
                planningProperties.isRoutePlanningEnabled(),
                planningProperties.getMcp().getServerName()
        );
        if (!hasDeprecatedPlanningFlags()) {
            return;
        }
        log.warn(
                "Deprecated planning flags detected: medical.report.planning.mcp-enabled / agent-enabled. "
                        + "Please migrate to medical.report.planning.mode=off|local|mcp|agentic. resolvedMode={}",
                planningProperties.getResolvedMode().name().toLowerCase()
        );
    }

    private boolean hasDeprecatedPlanningFlags() {
        return environment.containsProperty("medical.report.planning.mcp-enabled")
                || environment.containsProperty("medical.report.planning.agent-enabled")
                || environment.containsProperty("MEDICAL_REPORT_PLANNING_MCP_ENABLED")
                || environment.containsProperty("MEDICAL_REPORT_PLANNING_AGENT_ENABLED")
                || planningProperties.usesDeprecatedFlags();
    }
}
