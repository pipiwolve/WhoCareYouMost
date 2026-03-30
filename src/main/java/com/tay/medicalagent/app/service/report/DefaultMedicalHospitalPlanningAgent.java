package com.tay.medicalagent.app.service.report;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 使用 MCP Tool Calling 的医院规划 Agent。
 */
@Service
public class DefaultMedicalHospitalPlanningAgent implements MedicalHospitalPlanningAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultMedicalHospitalPlanningAgent.class);

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final AmapMcpClientFactory amapMcpClientFactory;
    private final McpToolFilter medicalAmapPlanningMcpToolFilter;
    private final McpToolNamePrefixGenerator medicalAmapPlanningToolNamePrefixGenerator;

    public DefaultMedicalHospitalPlanningAgent(
            MedicalAiModelProvider medicalAiModelProvider,
            AmapMcpClientFactory amapMcpClientFactory,
            McpToolFilter medicalAmapPlanningMcpToolFilter,
            McpToolNamePrefixGenerator medicalAmapPlanningToolNamePrefixGenerator
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.amapMcpClientFactory = amapMcpClientFactory;
        this.medicalAmapPlanningMcpToolFilter = medicalAmapPlanningMcpToolFilter;
        this.medicalAmapPlanningToolNamePrefixGenerator = medicalAmapPlanningToolNamePrefixGenerator;
    }

    @Override
    public Optional<MedicalHospitalPlanningSummary> plan(
            double latitude,
            double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        if (report == null || planningIntent == null || !planningIntent.planningRequested()) {
            return Optional.empty();
        }

        BeanOutputConverter<MedicalHospitalPlanningSummary> outputConverter =
                new BeanOutputConverter<>(MedicalHospitalPlanningSummary.class);

        String prompt = buildPrompt(latitude, longitude, report, planningIntent, outputConverter);

        try (AmapMcpClientFactory.AmapMcpClientHandle clientHandle = amapMcpClientFactory.create(mcpProperties)) {
            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(
                    medicalAmapPlanningMcpToolFilter,
                    medicalAmapPlanningToolNamePrefixGenerator,
                    clientHandle.client()
            );
            if (provider.getToolCallbacks().length == 0) {
                return Optional.empty();
            }
            ReactAgent agent = ReactAgent.builder()
                    .name("medical_hospital_planning_agent")
                    .model(medicalAiModelProvider.getChatModel())
                    .systemPrompt(MedicalPrompts.HOSPITAL_PLANNING_AGENT_SYSTEM_PROMPT)
                    .toolCallbackProviders(provider)
                    .toolExecutionTimeout(Duration.ofMillis(Math.max(1000L, mcpProperties.getTimeoutMs())))
                    .saver(new MemorySaver())
                    .build();
            String content = agent.call(prompt).getText();
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            MedicalHospitalPlanningSummary summary = outputConverter.convert(content);
            return Optional.of(normalizeSummary(summary, planningIntent));
        }
        catch (GraphRunnerException ex) {
            log.warn("Medical hospital planning agent execution failed: {}", ex.getMessage());
            return Optional.empty();
        }
        catch (Exception ex) {
            log.warn("Medical hospital planning agent returned invalid payload: {}", ex.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Planning agent invalid payload detail", ex);
            }
            return Optional.empty();
        }
    }

    private MedicalHospitalPlanningSummary normalizeSummary(
            MedicalHospitalPlanningSummary summary,
            MedicalPlanningIntent planningIntent
    ) {
        if (summary == null) {
            return MedicalHospitalPlanningSummary.empty();
        }
        List<MedicalHospitalRecommendation> hospitals = safeHospitals(summary.hospitals(), planningIntent);
        boolean routesAvailable = hospitals.stream().anyMatch(h -> h.routes() != null && !h.routes().isEmpty());
        String statusCode = safeStatusCode(summary.routeStatusCode(), routesAvailable, hospitals.isEmpty());
        String statusMessage = normalizeStatusMessage(summary.routeStatusMessage(), statusCode);
        return new MedicalHospitalPlanningSummary(hospitals, routesAvailable, statusMessage, statusCode);
    }

    String buildPrompt(
            double latitude,
            double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent,
            BeanOutputConverter<MedicalHospitalPlanningSummary> outputConverter
    ) {
        String coordinate = formatCoordinate(longitude, latitude);
        return """
                请使用地图工具为用户规划附近医院与路线，并严格返回 JSON。

                用户坐标 location：%s
                坐标格式要求：所有地图工具中的 location、origin、destination 都必须使用“经度,纬度”顺序，例如 `%s`，绝不能写成“纬度,经度”。
                已明确提供有效坐标，因此除非地图工具明确返回坐标缺失或完全不可解析，否则不要返回 `location_missing`。
                风险等级：%s
                患者概述：%s
                初步判断：%s
                搜索 profile：%s
                搜索关键词 keywords：%s
                医院类型 types：%s
                搜索半径 radius：%s
                返回数量 topK：%s
                是否优先三甲：%s

                输出要求：
                1. 如果路线工具可用，尽量返回步行、驾车、公交三种路线。
                2. routesAvailable=true 表示至少一条路线成功返回。
                3. routeStatusCode 只能返回 ok、partial_timeout、route_timeout、route_empty、route_unavailable、location_missing。
                4. routeStatusMessage 在 statusCode=ok 时返回空字符串。
                5. hospitals 按优先级排序，最多 topK 家。
                6. 每条 routes 元素除了 summary，还要尽量返回 steps 数组；步行/驾车按路线步骤拆分，公交按“步行 -> 地铁/公交 -> 步行/换乘”高层步骤拆分。
                7. 如果第一次附近医院检索为空，不要立刻返回空结果；请保持坐标不变，按“专科别名 -> 三甲医院 -> 医院”的顺序逐步放宽关键词，并把半径逐步放宽到 12000~15000 米后再下结论。

                %s
                """.formatted(
                coordinate,
                coordinate,
                safeText(report.currentRiskLevel()),
                safeText(report.patientSummary()),
                safeText(report.preliminaryAssessment()),
                safeText(planningIntent.profileId()),
                safeText(planningIntent.hospitalKeyword()),
                safeText(planningIntent.hospitalTypes()),
                planningIntent.aroundRadiusMeters(),
                planningIntent.topK(),
                planningIntent.preferTier3a(),
                outputConverter.getFormat()
        );
    }

    private List<MedicalHospitalRecommendation> safeHospitals(
            List<MedicalHospitalRecommendation> hospitals,
            MedicalPlanningIntent planningIntent
    ) {
        if (hospitals == null || hospitals.isEmpty()) {
            return List.of();
        }
        Comparator<MedicalHospitalRecommendation> comparator = planningIntent.preferTier3a()
                ? Comparator.comparing(MedicalHospitalRecommendation::tier3a).reversed()
                        .thenComparing(MedicalHospitalRecommendation::distanceMeters)
                : Comparator.comparing(MedicalHospitalRecommendation::distanceMeters)
                        .thenComparing(MedicalHospitalRecommendation::tier3a, Comparator.reverseOrder());
        return hospitals.stream()
                .map(this::normalizeHospital)
                .sorted(comparator)
                .limit(Math.max(1, planningIntent.topK()))
                .toList();
    }

    private MedicalHospitalRecommendation normalizeHospital(MedicalHospitalRecommendation hospital) {
        if (hospital == null) {
            return new MedicalHospitalRecommendation("未知医院", "", false, 0L, List.of());
        }
        List<MedicalHospitalRouteOption> routes = hospital.routes() == null
                ? List.of()
                : hospital.routes().stream().map(this::normalizeRoute).toList();
        return new MedicalHospitalRecommendation(
                safeText(hospital.name()).isBlank() ? "未知医院" : safeText(hospital.name()),
                safeText(hospital.address()),
                hospital.tier3a(),
                Math.max(0L, hospital.distanceMeters()),
                routes
        );
    }

    private MedicalHospitalRouteOption normalizeRoute(MedicalHospitalRouteOption route) {
        if (route == null) {
            return new MedicalHospitalRouteOption("WALK", 0L, 1L, "步行方案", List.of());
        }
        String normalizedMode = normalizeRouteMode(route.mode());
        List<String> steps = normalizeSteps(route.steps());
        return new MedicalHospitalRouteOption(
                normalizedMode,
                Math.max(0L, route.distanceMeters()),
                Math.max(1L, route.durationMinutes()),
                safeText(route.summary()).isBlank() ? defaultRouteSummary(normalizedMode, steps) : safeText(route.summary()),
                steps
        );
    }

    private String normalizeRouteMode(String mode) {
        String normalized = safeText(mode).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "步行", "WALKING", "WALK" -> "WALK";
            case "驾车", "DRIVING", "DRIVE" -> "DRIVE";
            case "公交", "TRANSIT", "BUS" -> "TRANSIT";
            default -> normalized.isBlank() ? "WALK" : normalized;
        };
    }

    private String safeStatusCode(String routeStatusCode, boolean routesAvailable, boolean hospitalsEmpty) {
        String normalized = safeText(routeStatusCode);
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (routesAvailable) {
            return "ok";
        }
        return hospitalsEmpty ? "route_empty" : "route_unavailable";
    }

    private String normalizeStatusMessage(String routeStatusMessage, String routeStatusCode) {
        String normalized = safeText(routeStatusMessage);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return switch (safeText(routeStatusCode)) {
            case "ok" -> "";
            case "partial_timeout" -> "部分医院路线查询超时，已返回可用路线结果";
            case "route_timeout" -> "路线查询超时，请稍后重试";
            case "route_empty" -> "未查询到可用路线，请稍后重试";
            case "location_missing" -> "未上传经纬度，无法进行就近医院规划";
            default -> "MCP 路线服务暂不可用，已返回就近医院与距离";
        };
    }

    private List<String> normalizeSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(this::safeText)
                .filter(step -> !step.isBlank())
                .toList();
    }

    private String defaultRouteSummary(String mode, List<String> steps) {
        if (steps != null && !steps.isEmpty()) {
            return steps.get(0);
        }
        return switch (mode) {
            case "DRIVE" -> "驾车方案";
            case "TRANSIT" -> "公交方案";
            default -> "步行方案";
        };
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String formatCoordinate(double longitude, double latitude) {
        return longitude + "," + latitude;
    }
}
