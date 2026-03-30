package com.tay.medicalagent.app.service.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalReportPlanningPropertiesBindingTest {

    @Test
    void shouldBindMcpPlanningFields() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "medical.report.planning.enabled", "true",
                "medical.report.planning.top-k", "5",
                "medical.report.planning.route-planning-enabled", "true",
                "medical.report.planning.mcp-enabled", "true",
                "medical.report.planning.agent-enabled", "false",
                "medical.report.planning.mcp.server-name", "amap-prod",
                "medical.report.planning.mcp.timeout-ms", "7000",
                "medical.report.planning.mcp.around-radius-meters", "8000",
                "medical.report.planning.mcp.hospital-keyword", "三甲医院",
                "medical.report.planning.mcp.hospital-types", "090100|090101"
        )));

        MedicalReportPlanningProperties properties = binder
                .bind("medical.report.planning", Bindable.of(MedicalReportPlanningProperties.class))
                .orElseThrow(() -> new IllegalStateException("medical.report.planning binding failed"));

        assertTrue(properties.isEnabled());
        assertEquals(5, properties.getTopK());
        assertTrue(properties.isRoutePlanningEnabled());
        assertTrue(properties.isMcpEnabled());
        assertEquals(false, properties.isAgentEnabled());
        assertEquals("amap-prod", properties.getMcp().getServerName());
        assertEquals(7000, properties.getMcp().getTimeoutMs());
        assertEquals(8000, properties.getMcp().getAroundRadiusMeters());
        assertEquals("三甲医院", properties.getMcp().getHospitalKeyword());
        assertEquals("090100|090101", properties.getMcp().getHospitalTypes());
    }
}
