package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.support.LiveTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AmapMedicalHospitalPlanningLiveIT {

    @Autowired
    private MedicalHospitalPlanningService medicalHospitalPlanningService;

    @DynamicPropertySource
    static void registerMcpProperties(DynamicPropertyRegistry registry) {
        String mcpCommand = System.getProperty("amap.mcp.command", "npx");
        String apiKey = LiveTestSupport.requireAmapApiKey();

        registry.add("spring.ai.mcp.client.enabled", () -> true);
        registry.add("spring.ai.mcp.client.initialized", () -> true);
        registry.add("spring.ai.mcp.client.type", () -> "SYNC");
        registry.add("spring.ai.mcp.client.toolcallback.enabled", () -> true);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.command", () -> mcpCommand);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[0]", () -> "-y");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[1]", () -> "@amap/amap-maps-mcp-server");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.env.AMAP_MAPS_API_KEY", () -> apiKey);
        registry.add("medical.report.planning.enabled", () -> true);
        registry.add("medical.report.planning.mode", () -> "mcp");
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

    @Test
    void shouldKeepGuangzhouOrthopedicScenarioOnMcpPath() {
        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "骨科医疗诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "踝关节外伤后疼痛肿胀，行走受限",
                "建议尽快线下骨科或急诊评估",
                "",
                List.of("踝关节疼痛", "肿胀", "外伤史"),
                List.of("尽快线下就医"),
                List.of("疼痛迅速加重", "无法负重行走"),
                "建议尽快线下骨科或急诊评估"
        );
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "踝关节外伤场景",
                "orthopedic",
                "骨科医院",
                "090100|090101|090102|090400",
                6000,
                3,
                false
        );

        MedicalHospitalPlanningSummary summary = medicalHospitalPlanningService.plan(
                23.021691207183693,
                113.40741384329999,
                report,
                planningIntent
        );

        assertNotNull(summary);
        assertFalse(summary.hospitals().isEmpty(), "Guangzhou orthopedic planning should return MCP hospitals");
        assertNotEquals("mcp_unavailable", summary.routeStatusCode(), "Guangzhou orthopedic planning should not fall back to local static hospitals");
        assertTrue(
                !"no_hospital_data".equals(summary.routeStatusCode()),
                "Guangzhou orthopedic planning should keep candidates on the MCP path"
        );
    }
}
