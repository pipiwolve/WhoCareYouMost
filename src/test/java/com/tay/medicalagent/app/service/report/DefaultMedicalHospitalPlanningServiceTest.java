package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMedicalHospitalPlanningServiceTest {

    @Test
    void shouldUseAgenticMcpResultWhenEnabledAndAgentReturnsData() {
        MedicalReportPlanningProperties properties = defaultProperties();
        properties.setMcpEnabled(true);

        MedicalHospitalPlanningSummary mcpSummary = new MedicalHospitalPlanningSummary(
                List.of(new MedicalHospitalRecommendation(
                        "测试医院",
                        "测试地址",
                        true,
                        1200,
                        List.of(new MedicalHospitalRouteOption("步行", 1200, 18, "步行方案", List.of("步行200米到达医院")))
                )),
                true,
                "",
                "ok"
        );

        MedicalHospitalPlanningAgent planningAgent = (latitude, longitude, report, planningIntent, mcpProperties) -> Optional.of(mcpSummary);
        MedicalHospitalPlanningGateway gateway = (latitude, longitude, planningIntent, mcpProperties) -> Optional.empty();
        MedicalPlanningIntentResolver resolver = new DefaultMedicalPlanningIntentResolver(properties);

        DefaultMedicalHospitalPlanningService service = new DefaultMedicalHospitalPlanningService(
                properties,
                planningAgent,
                gateway,
                resolver
        );

        MedicalHospitalPlanningSummary result = service.plan(31.2, 121.4, exportableReport());

        assertEquals("ok", result.routeStatusCode());
        assertTrue(result.routesAvailable());
        assertEquals(1, result.hospitals().size());
        assertEquals("测试医院", result.hospitals().get(0).name());
    }

    @Test
    void shouldFallbackWithoutRoutesWhenMcpEnabledButGatewayUnavailable() {
        MedicalReportPlanningProperties properties = defaultProperties();
        properties.setMcpEnabled(true);

        MedicalHospitalPlanningAgent planningAgent = (latitude, longitude, report, planningIntent, mcpProperties) -> Optional.empty();
        MedicalHospitalPlanningGateway gateway = (latitude, longitude, planningIntent, mcpProperties) -> Optional.empty();
        MedicalPlanningIntentResolver resolver = new DefaultMedicalPlanningIntentResolver(properties);

        DefaultMedicalHospitalPlanningService service = new DefaultMedicalHospitalPlanningService(
                properties,
                planningAgent,
                gateway,
                resolver
        );

        MedicalHospitalPlanningSummary result = service.plan(31.2, 121.4, exportableReport());

        assertFalse(result.routesAvailable());
        assertEquals("mcp_unavailable", result.routeStatusCode());
        assertFalse(result.hospitals().isEmpty());
        assertTrue(result.hospitals().stream().allMatch(h -> h.routes().isEmpty()));
    }

    @Test
    void shouldFallbackToDeterministicMcpWhenAgentReturnsLocationMissingWithHospitals() {
        MedicalReportPlanningProperties properties = defaultProperties();
        properties.setMcpEnabled(true);

        MedicalHospitalPlanningSummary invalidAgentSummary = new MedicalHospitalPlanningSummary(
                List.of(
                        new MedicalHospitalRecommendation("编造医院A", "地址A", true, 0, List.of()),
                        new MedicalHospitalRecommendation("编造医院B", "地址B", true, 0, List.of())
                ),
                false,
                "未上传经纬度，无法进行就近医院规划",
                "location_missing"
        );
        MedicalHospitalPlanningSummary deterministicSummary = new MedicalHospitalPlanningSummary(
                List.of(new MedicalHospitalRecommendation(
                        "真实医院",
                        "真实地址",
                        true,
                        1800,
                        List.of(new MedicalHospitalRouteOption("DRIVE", 2100, 9, "驾车方案"))
                )),
                true,
                "",
                "ok"
        );

        MedicalHospitalPlanningAgent planningAgent = (latitude, longitude, report, planningIntent, mcpProperties) -> Optional.of(invalidAgentSummary);
        MedicalHospitalPlanningGateway gateway = (latitude, longitude, planningIntent, mcpProperties) -> Optional.of(deterministicSummary);
        MedicalPlanningIntentResolver resolver = new DefaultMedicalPlanningIntentResolver(properties);

        DefaultMedicalHospitalPlanningService service = new DefaultMedicalHospitalPlanningService(
                properties,
                planningAgent,
                gateway,
                resolver
        );

        MedicalHospitalPlanningSummary result = service.plan(31.2, 121.4, exportableReport());

        assertEquals("ok", result.routeStatusCode());
        assertTrue(result.routesAvailable());
        assertEquals(1, result.hospitals().size());
        assertEquals("真实医院", result.hospitals().get(0).name());
    }

    @Test
    void shouldFallbackToDeterministicMcpWhenAgentReturnsHospitalsWithoutRoutes() {
        MedicalReportPlanningProperties properties = defaultProperties();
        properties.setMcpEnabled(true);

        MedicalHospitalPlanningSummary weakAgentSummary = new MedicalHospitalPlanningSummary(
                List.of(
                        new MedicalHospitalRecommendation("医院A", "地址A", true, 1200, List.of()),
                        new MedicalHospitalRecommendation("医院B", "地址B", true, 1800, List.of())
                ),
                false,
                "MCP 路线服务暂不可用，已返回就近医院与距离",
                "route_unavailable"
        );
        MedicalHospitalPlanningSummary deterministicSummary = new MedicalHospitalPlanningSummary(
                List.of(new MedicalHospitalRecommendation(
                        "真实医院",
                        "真实地址",
                        true,
                        1800,
                        List.of(new MedicalHospitalRouteOption("DRIVE", 2100, 9, "驾车方案"))
                )),
                true,
                "",
                "ok"
        );

        MedicalHospitalPlanningAgent planningAgent = (latitude, longitude, report, planningIntent, mcpProperties) -> Optional.of(weakAgentSummary);
        MedicalHospitalPlanningGateway gateway = (latitude, longitude, planningIntent, mcpProperties) -> Optional.of(deterministicSummary);
        MedicalPlanningIntentResolver resolver = new DefaultMedicalPlanningIntentResolver(properties);

        DefaultMedicalHospitalPlanningService service = new DefaultMedicalHospitalPlanningService(
                properties,
                planningAgent,
                gateway,
                resolver
        );

        MedicalHospitalPlanningSummary result = service.plan(31.2, 121.4, exportableReport());

        assertEquals("ok", result.routeStatusCode());
        assertTrue(result.routesAvailable());
        assertEquals(1, result.hospitals().size());
        assertEquals("真实医院", result.hospitals().get(0).name());
    }

    @Test
    void shouldFallbackToDeterministicMcpWhenAgentReturnsRoutesWithoutSteps() {
        MedicalReportPlanningProperties properties = defaultProperties();
        properties.setMcpEnabled(true);

        MedicalHospitalPlanningSummary weakAgentSummary = new MedicalHospitalPlanningSummary(
                List.of(new MedicalHospitalRecommendation(
                        "医院A",
                        "地址A",
                        true,
                        1200,
                        List.of(new MedicalHospitalRouteOption("DRIVE", 1500, 10, "驾车约10分钟"))
                )),
                true,
                "",
                "ok"
        );
        MedicalHospitalPlanningSummary deterministicSummary = new MedicalHospitalPlanningSummary(
                List.of(new MedicalHospitalRecommendation(
                        "真实医院",
                        "真实地址",
                        true,
                        1800,
                        List.of(new MedicalHospitalRouteOption(
                                "DRIVE",
                                2100,
                                9,
                                "驾车方案",
                                List.of("沿内环高架路行驶", "到达真实医院")
                        ))
                )),
                true,
                "",
                "ok"
        );

        MedicalHospitalPlanningAgent planningAgent = (latitude, longitude, report, planningIntent, mcpProperties) -> Optional.of(weakAgentSummary);
        MedicalHospitalPlanningGateway gateway = (latitude, longitude, planningIntent, mcpProperties) -> Optional.of(deterministicSummary);
        MedicalPlanningIntentResolver resolver = new DefaultMedicalPlanningIntentResolver(properties);

        DefaultMedicalHospitalPlanningService service = new DefaultMedicalHospitalPlanningService(
                properties,
                planningAgent,
                gateway,
                resolver
        );

        MedicalHospitalPlanningSummary result = service.plan(31.2, 121.4, exportableReport());

        assertEquals("ok", result.routeStatusCode());
        assertTrue(result.routesAvailable());
        assertEquals("真实医院", result.hospitals().get(0).name());
        assertEquals(List.of("沿内环高架路行驶", "到达真实医院"), result.hospitals().get(0).routes().get(0).steps());
    }

    private MedicalReportPlanningProperties defaultProperties() {
        MedicalReportPlanningProperties properties = new MedicalReportPlanningProperties();
        MedicalReportPlanningProperties.HospitalCandidate hospital = new MedicalReportPlanningProperties.HospitalCandidate();
        hospital.setName("瑞金医院");
        hospital.setAddress("上海市黄浦区瑞金二路197号");
        hospital.setTier3a(true);
        hospital.setLatitude(31.2100);
        hospital.setLongitude(121.4680);
        properties.getFallback().setHospitals(List.of(hospital));
        return properties;
    }

    private MedicalDiagnosisReport exportableReport() {
        return new MedicalDiagnosisReport(
                "报告",
                true,
                "ok",
                "LOW",
                "summary",
                "assessment",
                "",
                List.of("basis"),
                List.of("next"),
                List.of("escalation"),
                "reply"
        );
    }
}
