package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real acceptance check for the hospital planning path with a live AMap MCP server.
 * <p>
 * This test does not require a live DashScope key because the planning service
 * should gracefully fall back from the agentic planner to the deterministic MCP gateway.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "amap.mcp.it", matches = "true")
class AmapMedicalHospitalPlanningIntegrationTest {

    @Autowired
    private MedicalHospitalPlanningService medicalHospitalPlanningService;

    @DynamicPropertySource
    static void registerMcpProperties(DynamicPropertyRegistry registry) {
        String mcpCommand = System.getProperty("amap.mcp.command", "npx");
        String apiKey = System.getProperty("amap.mcp.api-key", "dummy-key");

        registry.add("spring.ai.mcp.client.enabled", () -> true);
        registry.add("spring.ai.mcp.client.initialized", () -> true);
        registry.add("spring.ai.mcp.client.type", () -> "SYNC");
        registry.add("spring.ai.mcp.client.toolcallback.enabled", () -> true);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.command", () -> mcpCommand);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[0]", () -> "-y");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[1]", () -> "@amap/amap-maps-mcp-server");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.env.AMAP_MAPS_API_KEY", () -> apiKey);
        registry.add("medical.report.planning.enabled", () -> true);
        registry.add("medical.report.planning.mcp-enabled", () -> true);
        registry.add("medical.report.planning.agent-enabled", () -> true);
        registry.add("medical.report.planning.top-k", () -> 3);
    }

    @Test
    void shouldReturnHospitalsFromLiveAmapPlanningPath() {
        MedicalHospitalPlanningSummary summary = medicalHospitalPlanningService.plan(
                31.2304,
                121.4737,
                new MedicalDiagnosisReport(
                        "胸痛医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "高风险",
                        "胸痛伴胸闷，活动后加重",
                        "警惕心血管急症，建议尽快线下就医",
                        "",
                        List.of("胸痛", "活动后加重"),
                        List.of("尽快线下就医"),
                        List.of("持续胸痛加重"),
                        "警惕心血管急症"
                )
        );

        assertNotNull(summary);
        assertFalse(summary.hospitals().isEmpty(), "Real AMap planning should return at least one hospital");
        assertNotEquals("mcp_unavailable", summary.routeStatusCode(), "Real AMap planning should not fall back to local static hospitals");
    }

    @Test
    void shouldAvoidLocalFallbackForGuangzhouCardiacScenario() {
        MedicalHospitalPlanningSummary summary = medicalHospitalPlanningService.plan(
                23.02171878809841,
                113.40747816629964,
                new MedicalDiagnosisReport(
                        "胸痛医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "高风险",
                        "胸痛伴胸闷，活动后加重",
                        "警惕心血管急症，建议尽快线下就医",
                        "",
                        List.of("胸痛", "胸闷"),
                        List.of("尽快线下就医"),
                        List.of("持续胸痛加重"),
                        "警惕心血管急症"
                )
        );

        assertNotNull(summary);
        assertFalse(summary.hospitals().isEmpty(), "Guangzhou cardiac planning should return at least one hospital");
        assertNotEquals("mcp_unavailable", summary.routeStatusCode(), "Guangzhou cardiac planning should stay on the AMap path");
    }
}
