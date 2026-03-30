package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class McpMedicalHospitalPlanningGateway implements MedicalHospitalPlanningGateway {

    private static final Logger log = LoggerFactory.getLogger(McpMedicalHospitalPlanningGateway.class);
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final String TOOL_MAPS_AROUND_SEARCH = "maps_around_search";
    private static final String TOOL_MAPS_WALKING_BY_COORDINATES = "maps_direction_walking_by_coordinates";
    private static final String TOOL_MAPS_DRIVING_BY_COORDINATES = "maps_direction_driving_by_coordinates";
    private static final String TOOL_MAPS_TRANSIT_BY_COORDINATES = "maps_direction_transit_integrated_by_coordinates";
    private static final String TOOL_MAPS_REGEOCODE = "maps_regeocode";
    private static final String TOOL_MAPS_SEARCH_DETAIL = "maps_search_detail";

    private final AmapMcpClientFactory amapMcpClientFactory;
    private final ObjectMapper objectMapper;

    public McpMedicalHospitalPlanningGateway(AmapMcpClientFactory amapMcpClientFactory, ObjectMapper objectMapper) {
        this.amapMcpClientFactory = amapMcpClientFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MedicalHospitalPlanningSummary> plan(
            double latitude,
            double longitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        try (AmapMcpClientFactory.AmapMcpClientHandle clientHandle = amapMcpClientFactory.create(mcpProperties)) {
            McpSyncClient client = clientHandle.client();
            if (client == null) {
                return Optional.empty();
            }
            if (!isExpectedServer(client, mcpProperties.getServerName())) {
                log.warn(
                        "Independent MCP planning client serverName mismatch. expected={}, actual={}",
                        mcpProperties.getServerName(),
                        client.getServerInfo() == null ? "" : client.getServerInfo().name()
                );
            }
            return doPlan(client, latitude, longitude, planningIntent, mcpProperties);
        }
        catch (Exception ex) {
            log.warn("MCP sync client unavailable at runtime, fallback will be used. reason={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MedicalHospitalPlanningSummary> doPlan(
            McpSyncClient client,
            double latitude,
            double longitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        try {
            List<McpSchema.Tool> tools = Optional.ofNullable(client.listTools())
                    .map(McpSchema.ListToolsResult::tools)
                    .orElse(List.of());
            if (tools.isEmpty()) {
                log.warn("MCP planning listTools returned empty. serverName={}", mcpProperties.getServerName());
                return Optional.empty();
            }
            log.info(
                    "MCP planning tools discovered. serverName={}, toolCount={}, tools={}",
                    mcpProperties.getServerName(),
                    tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList()
            );

            String nearbyTool = resolveToolName(tools, TOOL_MAPS_AROUND_SEARCH,
                    List.of("around", "nearby", "search", "poi", "hospital", "医院"));
            if (nearbyTool == null) {
                log.warn("MCP planning nearby tool not found. expected={}, serverName={}", TOOL_MAPS_AROUND_SEARCH, mcpProperties.getServerName());
                return Optional.empty();
            }

            String walkTool = resolveToolName(tools, TOOL_MAPS_WALKING_BY_COORDINATES, List.of("walking", "walk", "步行"));
            String driveTool = resolveToolName(tools, TOOL_MAPS_DRIVING_BY_COORDINATES, List.of("driving", "drive", "驾车"));
            String transitTool = resolveToolName(tools, TOOL_MAPS_TRANSIT_BY_COORDINATES, List.of("transit", "公交", "bus"));
            String regeocodeTool = resolveToolName(tools, TOOL_MAPS_REGEOCODE, List.of("regeocode"));
            String searchDetailTool = resolveToolName(tools, TOOL_MAPS_SEARCH_DETAIL, List.of("search_detail", "detail"));
            log.info(
                    "MCP planning tools resolved. walkTool={}, driveTool={}, transitTool={}, regeocodeTool={}, searchDetailTool={}",
                    walkTool,
                    driveTool,
                    transitTool,
                    regeocodeTool,
                    searchDetailTool
            );

            NearbySearchOutcome nearbySearchOutcome = searchHospitalsWithFallbackPlans(
                    client,
                    nearbyTool,
                    latitude,
                    longitude,
                    planningIntent,
                    mcpProperties,
                    searchDetailTool
            );
            List<HospitalCandidate> hospitals = nearbySearchOutcome.hospitals();
            if (hospitals.isEmpty()) {
                log.warn(
                    "MCP planning nearby search exhausted fallback plans with no enrichable hospitals. tool={}, attempts={}",
                    nearbyTool,
                    nearbySearchOutcome.attemptSummaries()
                );
                return Optional.empty();
            }

            boolean routesAvailable = walkTool != null || driveTool != null || transitTool != null;
            CityPair cityPair = resolveCityPair(client, regeocodeTool, longitude, latitude, hospitals);
            RouteExecutionState routeState = new RouteExecutionState();
            List<MedicalHospitalRecommendation> recommendations = hospitals.stream()
                    .sorted(hospitalComparator(planningIntent))
                    .limit(resolveTopK(planningIntent))
                    .map(hospital -> new MedicalHospitalRecommendation(
                            hospital.name(),
                            hospital.address(),
                            hospital.tier3a(),
                            hospital.distanceMeters(),
                            buildRoutes(client, hospital, longitude, latitude, walkTool, driveTool, transitTool, cityPair, routeState)
                    ))
                    .toList();

            boolean hasAnyRoute = recommendations.stream().anyMatch(item -> item.routes() != null && !item.routes().isEmpty());
            routesAvailable = hasAnyRoute;

            String statusCode;
            String statusMessage;
            if (walkTool == null && driveTool == null && transitTool == null) {
                statusCode = "route_unavailable";
                statusMessage = "MCP 已返回医院列表，路线工具暂不可用";
            }
            else if (hasAnyRoute && routeState.hasFailures()) {
                statusCode = "partial_timeout";
                statusMessage = "部分医院路线查询超时，已返回可用路线结果";
            }
            else if (!hasAnyRoute && routeState.timeoutFailures() > 0) {
                statusCode = "route_timeout";
                statusMessage = "路线查询超时，请稍后重试";
            }
            else if (!hasAnyRoute) {
                statusCode = "route_empty";
                statusMessage = "未查询到可用路线，请稍后重试";
            }
            else {
                statusCode = "ok";
                statusMessage = "";
            }

            log.info(
                    "MCP planning finished. profileId={}, routesAvailable={}, statusCode={}, hospitalCount={}, routeStats={}, cityPair={}",
                    planningIntent == null ? "" : planningIntent.profileId(),
                    routesAvailable,
                    statusCode,
                    recommendations.size(),
                    routeState.summary(),
                    cityPair == null ? "null" : cityPair.originCity() + "->" + cityPair.destinationCity()
            );
            return Optional.of(new MedicalHospitalPlanningSummary(recommendations, routesAvailable, statusMessage, statusCode));
        }
        catch (Exception ex) {
            log.warn("MCP hospital planning failed, fallback will be used: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private NearbySearchOutcome searchHospitalsWithFallbackPlans(
            McpSyncClient client,
            String nearbyTool,
            double userLatitude,
            double userLongitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties,
            String searchDetailTool
    ) {
        Map<String, HospitalCandidate> hospitalsByKey = new LinkedHashMap<>();
        List<String> attemptSummaries = new ArrayList<>();
        int targetCandidateCount = resolveSearchTargetCandidateCount(planningIntent);

        for (NearbySearchPlan searchPlan : buildNearbySearchPlans(planningIntent, mcpProperties)) {
            Map<String, Object> nearbyArgs = new HashMap<>();
            nearbyArgs.put("location", formatLocation(userLongitude, userLatitude));
            nearbyArgs.put("radius", String.valueOf(searchPlan.radiusMeters()));
            nearbyArgs.put("keywords", searchPlan.keyword());
            if (!searchPlan.hospitalTypes().isBlank()) {
                nearbyArgs.put("types", searchPlan.hospitalTypes());
            }

            Object nearbyPayload = callTool(client, nearbyTool, nearbyArgs).orElse(null);
            List<HospitalCandidate> hospitals = extractHospitals(client, nearbyPayload, userLatitude, userLongitude, searchDetailTool);
            for (HospitalCandidate hospital : hospitals) {
                hospitalsByKey.putIfAbsent(hospitalKey(hospital), hospital);
            }

            attemptSummaries.add(
                    "args=" + nearbyArgs
                            + ", found=" + hospitals.size()
                            + ", uniqueTotal=" + hospitalsByKey.size()
                            + ", payloadSummary=" + summarizePayload(nearbyPayload)
            );
            if (hospitalsByKey.size() >= targetCandidateCount) {
                break;
            }
        }

        return new NearbySearchOutcome(List.copyOf(hospitalsByKey.values()), List.copyOf(attemptSummaries));
    }

    private boolean isExpectedServer(McpSyncClient client, String expectedServerName) {
        if (expectedServerName == null || expectedServerName.isBlank()) {
            return true;
        }
        String expected = expectedServerName.toLowerCase(Locale.ROOT);
        String actual = Optional.ofNullable(client.getServerInfo())
                .map(McpSchema.Implementation::name)
                .orElse("")
                .toLowerCase(Locale.ROOT);
        return actual.contains(expected);
    }

    private Comparator<HospitalCandidate> hospitalComparator(MedicalPlanningIntent planningIntent) {
        if (planningIntent != null && planningIntent.preferTier3a()) {
            return Comparator.comparing(HospitalCandidate::lowCapabilityFacility)
                    .thenComparing(HospitalCandidate::tier3a, Comparator.reverseOrder())
                    .thenComparing(HospitalCandidate::likelyHospital, Comparator.reverseOrder())
                    .thenComparing(HospitalCandidate::distanceMeters);
        }
        return Comparator.comparing(HospitalCandidate::lowCapabilityFacility)
                .thenComparing(HospitalCandidate::likelyHospital, Comparator.reverseOrder())
                .thenComparing(HospitalCandidate::distanceMeters)
                .thenComparing(HospitalCandidate::tier3a, Comparator.reverseOrder());
    }

    private int resolveTopK(MedicalPlanningIntent planningIntent) {
        if (planningIntent == null) {
            return 1;
        }
        return Math.max(1, planningIntent.topK());
    }

    private int resolveRadiusMeters(
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        if (planningIntent != null && planningIntent.aroundRadiusMeters() > 0) {
            return planningIntent.aroundRadiusMeters();
        }
        return Math.max(1, mcpProperties.getAroundRadiusMeters());
    }

    private int resolveSearchTargetCandidateCount(MedicalPlanningIntent planningIntent) {
        int topK = resolveTopK(planningIntent);
        return Math.max(topK, Math.min(6, topK * 2));
    }

    private String resolveKeyword(
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        String value = planningIntent == null ? "" : planningIntent.hospitalKeyword();
        if (value == null || value.isBlank()) {
            value = mcpProperties.getHospitalKeyword();
        }
        return value == null ? "" : value.trim();
    }

    private String resolveHospitalTypes(
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        String value = planningIntent == null ? "" : planningIntent.hospitalTypes();
        if (value == null || value.isBlank()) {
            value = mcpProperties.getHospitalTypes();
        }
        return value == null ? "" : value.trim();
    }

    private List<NearbySearchPlan> buildNearbySearchPlans(
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        int baseRadius = resolveRadiusMeters(planningIntent, mcpProperties);
        int specialtyRadius = Math.max(baseRadius, resolveSpecialtyFallbackRadius(planningIntent));
        int genericRadius = Math.max(specialtyRadius, 15000);
        String primaryKeyword = resolveKeyword(planningIntent, mcpProperties);
        String specialtyTypes = resolveHospitalTypes(planningIntent, mcpProperties);
        String genericKeyword = safeSearchText(mcpProperties == null ? null : mcpProperties.getHospitalKeyword(), "医院");

        List<NearbySearchPlan> plans = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();

        addSearchPlan(plans, signatures, primaryKeyword, specialtyTypes, baseRadius);
        for (String relaxedKeyword : resolveSpecialtyFallbackKeywords(planningIntent)) {
            addSearchPlan(plans, signatures, relaxedKeyword, specialtyTypes, specialtyRadius);
        }
        if (planningIntent != null && planningIntent.preferTier3a()) {
            addSearchPlan(plans, signatures, "三甲医院", "", genericRadius);
        }
        addSearchPlan(plans, signatures, "综合医院", "", genericRadius);
        addSearchPlan(plans, signatures, genericKeyword, "", genericRadius);
        return plans;
    }

    private int resolveSpecialtyFallbackRadius(MedicalPlanningIntent planningIntent) {
        String profileId = safeSearchText(planningIntent == null ? null : planningIntent.profileId(), "");
        return switch (profileId.toLowerCase(Locale.ROOT)) {
            case "emergency" -> 12000;
            case "cardiac", "neurology", "pediatric", "obstetric" -> 12000;
            default -> 8000;
        };
    }

    private List<String> resolveSpecialtyFallbackKeywords(MedicalPlanningIntent planningIntent) {
        String profileId = safeSearchText(planningIntent == null ? null : planningIntent.profileId(), "");
        return switch (profileId.toLowerCase(Locale.ROOT)) {
            case "emergency" -> List.of("急诊科", "急救中心", "医院");
            case "cardiac" -> List.of("心血管内科", "心内科", "胸痛中心", "医院");
            case "respiratory" -> List.of("呼吸内科", "发热门诊", "医院");
            case "neurology" -> List.of("神经内科", "卒中中心", "医院");
            case "pediatric" -> List.of("儿科医院", "儿童医院", "医院");
            case "obstetric" -> List.of("妇产科医院", "妇幼保健院", "医院");
            case "orthopedic" -> List.of("骨科", "创伤中心", "综合医院", "医院");
            case "dental" -> List.of("口腔科", "综合医院", "医院");
            case "psychiatry" -> List.of("精神科医院", "综合医院", "医院");
            default -> List.of("三甲医院", "综合医院", "医院");
        };
    }

    private void addSearchPlan(
            List<NearbySearchPlan> plans,
            Set<String> signatures,
            String keyword,
            String hospitalTypes,
            int radiusMeters
    ) {
        String normalizedKeyword = safeSearchText(keyword, "");
        if (normalizedKeyword.isBlank()) {
            return;
        }
        String normalizedTypes = safeSearchText(hospitalTypes, "");
        int normalizedRadius = Math.max(1000, radiusMeters);
        String signature = normalizedKeyword.toLowerCase(Locale.ROOT)
                + "|"
                + normalizedTypes.toLowerCase(Locale.ROOT)
                + "|"
                + normalizedRadius;
        if (!signatures.add(signature)) {
            return;
        }
        plans.add(new NearbySearchPlan(normalizedKeyword, normalizedTypes, normalizedRadius));
    }

    private String safeSearchText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private List<MedicalHospitalRouteOption> buildRoutes(
            McpSyncClient client,
            HospitalCandidate hospital,
            double userLongitude,
            double userLatitude,
            String walkTool,
            String driveTool,
            String transitTool,
            CityPair cityPair,
            RouteExecutionState routeState
    ) {
        if (hospital.destinationLocation() == null || hospital.destinationLocation().isBlank()) {
            return List.of();
        }

        List<MedicalHospitalRouteOption> routes = new ArrayList<>();
        maybeAddWalkingRoute(routes, client, walkTool, userLongitude, userLatitude, hospital.destinationLocation(), routeState);
        maybeAddDrivingRoute(routes, client, driveTool, userLongitude, userLatitude, hospital.destinationLocation(), routeState);
        maybeAddTransitRoute(routes, client, transitTool, userLongitude, userLatitude, hospital.destinationLocation(), cityPair, routeState);
        return routes;
    }

    private void maybeAddWalkingRoute(
            List<MedicalHospitalRouteOption> routes,
            McpSyncClient client,
            String toolName,
            double userLongitude,
            double userLatitude,
            String destination,
            RouteExecutionState routeState
    ) {
        if (toolName == null || routeState.isCircuitOpen(RouteMode.WALK)) {
            return;
        }

        Map<String, Object> args = new HashMap<>();
        args.put("origin", formatLocation(userLongitude, userLatitude));
        args.put("destination", destination);

        routeState.recordAttempt(RouteMode.WALK);
        Object payload = callTool(client, toolName, args, RouteMode.WALK, routeState).orElse(null);
        RouteMetric metric = extractRouteMetric(payload, RouteMode.WALK);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.WALK);
            return;
        }
        List<String> steps = extractRouteSteps(payload, RouteMode.WALK);

        routeState.recordSuccess(RouteMode.WALK);
        routes.add(new MedicalHospitalRouteOption(
                "WALK",
                Math.max(0L, metric.distanceMeters()),
                Math.max(1L, metric.durationMinutes()),
                "步行方案",
                steps
        ));
    }

    private void maybeAddDrivingRoute(
            List<MedicalHospitalRouteOption> routes,
            McpSyncClient client,
            String toolName,
            double userLongitude,
            double userLatitude,
            String destination,
            RouteExecutionState routeState
    ) {
        if (toolName == null || routeState.isCircuitOpen(RouteMode.DRIVE)) {
            return;
        }

        Map<String, Object> args = new HashMap<>();
        args.put("origin", formatLocation(userLongitude, userLatitude));
        args.put("destination", destination);

        routeState.recordAttempt(RouteMode.DRIVE);
        Object payload = callTool(client, toolName, args, RouteMode.DRIVE, routeState).orElse(null);
        RouteMetric metric = extractRouteMetric(payload, RouteMode.DRIVE);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.DRIVE);
            return;
        }
        List<String> steps = extractRouteSteps(payload, RouteMode.DRIVE);

        routeState.recordSuccess(RouteMode.DRIVE);
        routes.add(new MedicalHospitalRouteOption(
                "DRIVE",
                Math.max(0L, metric.distanceMeters()),
                Math.max(1L, metric.durationMinutes()),
                "驾车方案",
                steps
        ));
    }

    private void maybeAddTransitRoute(
            List<MedicalHospitalRouteOption> routes,
            McpSyncClient client,
            String toolName,
            double userLongitude,
            double userLatitude,
            String destination,
            CityPair cityPair,
            RouteExecutionState routeState
    ) {
        if (toolName == null
                || routeState.isCircuitOpen(RouteMode.TRANSIT)
                || cityPair == null
                || cityPair.originCity() == null
                || cityPair.destinationCity() == null) {
            log.debug(
                    "MCP transit route skipped. toolName={}, cityPairPresent={}",
                    toolName,
                    cityPair != null
            );
            return;
        }

        Map<String, Object> args = new HashMap<>();
        args.put("origin", formatLocation(userLongitude, userLatitude));
        args.put("destination", destination);
        args.put("city", cityPair.originCity());
        args.put("cityd", cityPair.destinationCity());

        routeState.recordAttempt(RouteMode.TRANSIT);
        Object payload = callTool(client, toolName, args, RouteMode.TRANSIT, routeState).orElse(null);
        RouteMetric metric = extractRouteMetric(payload, RouteMode.TRANSIT);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.TRANSIT);
            log.debug(
                    "MCP transit route metric missing. tool={}, args={}, payloadSummary={}",
                    toolName,
                    args,
                    summarizePayload(payload)
            );
            return;
        }
        List<String> steps = extractRouteSteps(payload, RouteMode.TRANSIT);

        routeState.recordSuccess(RouteMode.TRANSIT);
        routes.add(new MedicalHospitalRouteOption(
                "TRANSIT",
                Math.max(0L, metric.distanceMeters()),
                Math.max(1L, metric.durationMinutes()),
                "公交方案",
                steps
        ));
    }

    private CityPair resolveCityPair(
            McpSyncClient client,
            String regeocodeTool,
            double userLongitude,
            double userLatitude,
            List<HospitalCandidate> hospitals
    ) {
        if (regeocodeTool == null || hospitals == null || hospitals.isEmpty()) {
            log.debug("MCP resolveCityPair skipped. regeocodeTool={}, hospitalCount={}", regeocodeTool, hospitals == null ? 0 : hospitals.size());
            return null;
        }

        String originCity = resolveCity(client, regeocodeTool, formatLocation(userLongitude, userLatitude));
        String destinationCity = resolveCity(client, regeocodeTool, hospitals.get(0).destinationLocation());
        if (originCity == null || destinationCity == null) {
            log.debug("MCP resolveCityPair failed. originCity={}, destinationCity={}", originCity, destinationCity);
            return null;
        }
        return new CityPair(originCity, destinationCity);
    }

    private String resolveCity(McpSyncClient client, String regeocodeTool, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("location", location);
        Object payload = callTool(client, regeocodeTool, args).orElse(null);
        String city = findString(payload, "city");
        if (city == null || city.isBlank()) {
            city = findString(payload, "district");
        }
        return (city == null || city.isBlank()) ? null : city;
    }

    private Optional<Object> callTool(McpSyncClient client, String toolName, Map<String, Object> args) {
        return callTool(client, toolName, args, null, null);
    }

    private Optional<Object> callTool(
            McpSyncClient client,
            String toolName,
            Map<String, Object> args,
            RouteMode routeMode,
            RouteExecutionState routeState
    ) {
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("MCP tool call marked error. tool={}, args={}", toolName, args);
                markRouteFailure(routeMode, routeState, false);
                return Optional.empty();
            }
            if (result.structuredContent() instanceof Map<?, ?> map) {
                if (map.containsKey("error")) {
                    log.warn("MCP tool call returned structured error field. tool={}, args={}, payloadSummary={}", toolName, args, summarizePayload(map));
                    markRouteFailure(routeMode, routeState, false);
                    return Optional.empty();
                }
            }
            if (result.structuredContent() != null) {
                return Optional.of(result.structuredContent());
            }
            if (result.content() == null || result.content().isEmpty()) {
                markRouteFailure(routeMode, routeState, false);
                return Optional.empty();
            }

            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    Object parsed = parseJsonLikeText(textContent.text());
                    if (parsed != null) {
                        return Optional.of(parsed);
                    }
                }
            }
            markRouteFailure(routeMode, routeState, false);
            return Optional.empty();
        }
        catch (Exception ex) {
            boolean timeout = isTimeout(ex);
            markRouteFailure(routeMode, routeState, timeout);
            log.warn("MCP tool call failed. tool={}, args={}, reason={}", toolName, args, ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean isTimeout(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof TimeoutException) {
            return true;
        }
        String message = ex.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
            return true;
        }
        return isTimeout(ex.getCause());
    }

    private void markRouteFailure(RouteMode routeMode, RouteExecutionState routeState, boolean timeout) {
        if (routeMode == null || routeState == null) {
            return;
        }
        routeState.recordFailure(routeMode, timeout);
        if (timeout) {
            routeState.openCircuit(routeMode);
        }
    }

    private List<HospitalCandidate> extractHospitals(
            McpSyncClient client,
            Object payload,
            double userLatitude,
            double userLongitude,
            String searchDetailTool
    ) {
        List<Map<String, Object>> rows = extractRows(payload);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<HospitalCandidate> hospitals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = asString(row.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String poiId = asString(row.get("id"));
            String address = asString(row.get("address"));
            String location = asString(row.get("location"));
            if ((location == null || location.isBlank()) && poiId != null && searchDetailTool != null) {
                location = resolvePoiLocation(client, searchDetailTool, poiId);
            }
            double[] latLon = parseLocation(location);

            long distance = asLong(row.get("distance"), -1L);
            if (distance < 0 && latLon != null) {
                distance = Math.round(haversineMeters(userLatitude, userLongitude, latLon[1], latLon[0]));
            }
            if (distance < 0) {
                log.debug(
                        "Skip hospital due to unresolved distance/location. name={}, poiId={}, hasLocation={}, rowSummary={}",
                        name,
                        poiId,
                        location != null,
                        summarizePayload(row)
                );
                continue;
            }

            String facilityHint = joinText(
                    asString(row.get("type")),
                    asString(row.get("typecode")),
                    asString(row.get("tag")),
                    name
            ).toLowerCase(Locale.ROOT);
            boolean lowCapabilityFacility = isLowCapabilityFacility(facilityHint);
            boolean likelyHospital = isLikelyHospital(facilityHint, lowCapabilityFacility);
            boolean tier3a = isTier3aFacility(facilityHint);

            hospitals.add(new HospitalCandidate(
                    name.trim(),
                    address == null ? "" : address.trim(),
                    tier3a,
                    likelyHospital,
                    lowCapabilityFacility,
                    Math.max(0L, distance),
                    location
            ));
        }
        return hospitals;
    }

    private String joinText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private boolean isLowCapabilityFacility(String facilityHint) {
        if (facilityHint == null || facilityHint.isBlank()) {
            return false;
        }
        return facilityHint.contains("社区卫生服务中心")
                || facilityHint.contains("社区卫生服务站")
                || facilityHint.contains("卫生服务站")
                || facilityHint.contains("卫生室")
                || facilityHint.contains("卫生院")
                || facilityHint.contains("门诊部")
                || facilityHint.contains("诊所")
                || facilityHint.contains("医务室");
    }

    private boolean isLikelyHospital(String facilityHint, boolean lowCapabilityFacility) {
        if (lowCapabilityFacility || facilityHint == null || facilityHint.isBlank()) {
            return false;
        }
        return facilityHint.contains("医院")
                || facilityHint.contains("医学院附属")
                || facilityHint.contains("中心医院")
                || facilityHint.contains("人民医院")
                || facilityHint.contains("总医院")
                || facilityHint.contains("妇幼保健院")
                || facilityHint.contains("儿童医院")
                || facilityHint.contains("急救中心")
                || facilityHint.contains("医疗中心")
                || facilityHint.contains("090101");
    }

    private boolean isTier3aFacility(String facilityHint) {
        if (facilityHint == null || facilityHint.isBlank()) {
            return false;
        }
        return facilityHint.contains("三甲")
                || facilityHint.contains("三级甲等")
                || facilityHint.contains("3a")
                || facilityHint.contains("tertiary");
    }

    private String resolvePoiLocation(McpSyncClient client, String searchDetailTool, String poiId) {
        if (searchDetailTool == null || poiId == null || poiId.isBlank()) {
            return null;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("id", poiId);
        Object payload = callTool(client, searchDetailTool, args).orElse(null);
        String location = findString(payload, "location");
        if (location == null || location.isBlank()) {
            log.debug("MCP search detail location missing. tool={}, poiId={}, payloadSummary={}", searchDetailTool, poiId, summarizePayload(payload));
            return null;
        }
        return location;
    }

    private String hospitalKey(HospitalCandidate hospital) {
        if (hospital == null) {
            return "";
        }
        return safeSearchText(hospital.name(), "")
                + "|"
                + safeSearchText(hospital.address(), "")
                + "|"
                + safeSearchText(hospital.destinationLocation(), "");
    }

    private List<Map<String, Object>> extractRows(Object payload) {
        if (payload instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = asMap(item);
                if (map != null) {
                    rows.add(map);
                }
            }
            return rows;
        }

        Map<String, Object> map = asMap(payload);
        if (map == null) {
            return List.of();
        }

        Object pois = map.get("pois");
        if (pois == null) {
            pois = map.get("data");
        }
        if (pois == null) {
            pois = map.get("items");
        }
        if (pois == null) {
            return List.of(map);
        }

        return extractRows(pois);
    }

    private String resolveToolName(List<McpSchema.Tool> tools, String exactName, List<String> keywords) {
        String exact = tools.stream()
                .map(McpSchema.Tool::name)
                .filter(exactName::equals)
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }

        return tools.stream()
                .map(McpSchema.Tool::name)
                .filter(name -> {
                    String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
                    return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
                })
                .findFirst()
                .orElse(null);
    }

    private Object parseJsonLikeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<Object>() {});
        }
        catch (Exception ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "[]".equals(text) || "{}".equals(text)) {
            return null;
        }
        return text;
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (Exception ignore) {
            return defaultValue;
        }
    }

    private String findString(Object payload, String key) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct != null) {
                String text = asString(direct);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            for (Object value : map.values()) {
                String nested = findString(value, key);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
            return null;
        }
        if (payload instanceof List<?> list) {
            for (Object item : list) {
                String nested = findString(item, key);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private RouteMetric extractRouteMetric(Object payload, RouteMode mode) {
        if (!(payload instanceof Map<?, ?> map)) {
            log.debug("MCP route payload is not map. mode={}, payloadSummary={}", mode, summarizePayload(payload));
            return null;
        }
        Map<String, Object> route = asMap(map.get("route"));
        if (route == null) {
            log.debug("MCP route payload missing route node. mode={}, payloadSummary={}", mode, summarizePayload(payload));
            return null;
        }

        if (mode == RouteMode.TRANSIT) {
            Long distance = asLong(route.get("distance"), 0L);
            Long durationSeconds = null;
            List<?> transits = route.get("transits") instanceof List<?> list ? list : List.of();
            if (!transits.isEmpty()) {
                Map<String, Object> first = asMap(transits.get(0));
                if (first != null) {
                    durationSeconds = asLong(first.get("duration"), 0L);
                }
            }
            if (distance <= 0 && (durationSeconds == null || durationSeconds <= 0)) {
                return null;
            }
            return new RouteMetric(
                    Math.max(0L, distance),
                    Math.max(1L, Math.round((durationSeconds == null ? 60L : durationSeconds) / 60.0))
            );
        }

        List<?> paths = route.get("paths") instanceof List<?> list ? list : List.of();
        if (paths.isEmpty()) {
            log.debug("MCP route payload has empty paths. mode={}, routeSummary={}", mode, summarizePayload(route));
            return null;
        }
        Map<String, Object> firstPath = asMap(paths.get(0));
        if (firstPath == null) {
            return null;
        }

        long distance = asLong(firstPath.get("distance"), 0L);
        long durationSeconds = asLong(firstPath.get("duration"), 0L);
        if (distance <= 0 && durationSeconds <= 0) {
            log.debug("MCP route metric is zero. mode={}, routeSummary={}", mode, summarizePayload(route));
            return null;
        }
        return new RouteMetric(
                Math.max(0L, distance),
                Math.max(1L, Math.round(durationSeconds / 60.0))
        );
    }

    private List<String> extractRouteSteps(Object payload, RouteMode mode) {
        if (!(payload instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> route = asMap(map.get("route"));
        if (route == null) {
            return List.of();
        }
        return mode == RouteMode.TRANSIT
                ? extractTransitRouteSteps(route)
                : extractPathRouteSteps(route, mode);
    }

    private List<String> extractPathRouteSteps(Map<String, Object> route, RouteMode mode) {
        List<?> paths = route.get("paths") instanceof List<?> list ? list : List.of();
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<String, Object> firstPath = asMap(paths.get(0));
        if (firstPath == null) {
            return List.of();
        }
        List<?> rawSteps = firstPath.get("steps") instanceof List<?> list ? list : List.of();
        if (rawSteps.isEmpty()) {
            return List.of();
        }

        List<String> steps = new ArrayList<>();
        for (Object rawStep : rawSteps) {
            Map<String, Object> step = asMap(rawStep);
            if (step != null) {
                addStep(steps, extractPathInstruction(step, mode));
                continue;
            }
            addStep(steps, asString(rawStep));
        }
        return steps.isEmpty() ? List.of() : List.copyOf(steps);
    }

    private List<String> extractTransitRouteSteps(Map<String, Object> route) {
        List<?> transits = route.get("transits") instanceof List<?> list ? list : List.of();
        if (transits.isEmpty()) {
            return List.of();
        }
        Map<String, Object> firstTransit = asMap(transits.get(0));
        if (firstTransit == null) {
            return List.of();
        }
        List<?> segments = firstTransit.get("segments") instanceof List<?> list ? list : List.of();
        if (segments.isEmpty()) {
            return List.of();
        }

        List<String> steps = new ArrayList<>();
        for (Object rawSegment : segments) {
            Map<String, Object> segment = asMap(rawSegment);
            if (segment == null) {
                continue;
            }
            addTransitWalkingStep(steps, asMap(segment.get("walking")));
            addTransitBusSteps(steps, asMap(segment.get("bus")));
            addTransitRailwayStep(steps, asMap(segment.get("railway")));
            addTransitTaxiStep(steps, asMap(segment.get("taxi")));
        }
        return steps.isEmpty() ? List.of() : List.copyOf(steps);
    }

    private String extractPathInstruction(Map<String, Object> step, RouteMode mode) {
        String instruction = asString(step.get("instruction"));
        if (instruction != null && !instruction.isBlank()) {
            return instruction;
        }

        String assistantAction = asString(step.get("assistant_action"));
        String road = asString(step.get("road"));
        String orientation = asString(step.get("orientation"));
        long distance = asLong(step.get("distance"), 0L);

        StringBuilder builder = new StringBuilder();
        if (mode == RouteMode.DRIVE) {
            if (road != null && !road.isBlank()) {
                builder.append("沿").append(road).append("行驶");
            }
            else {
                builder.append("驾车");
            }
        }
        else {
            builder.append("步行");
            if (road != null && !road.isBlank()) {
                builder.append("前往").append(road);
            }
        }

        if (orientation != null && !orientation.isBlank()) {
            builder.append("，向").append(orientation);
        }
        if (assistantAction != null && !assistantAction.isBlank()) {
            builder.append("，").append(assistantAction);
        }
        if (distance > 0) {
            builder.append("，约").append(formatDistance(distance));
        }
        return builder.toString();
    }

    private void addTransitWalkingStep(List<String> steps, Map<String, Object> walking) {
        if (walking == null || walking.isEmpty()) {
            return;
        }
        long distance = asLong(walking.get("distance"), 0L);
        String firstInstruction = firstInstruction(walking.get("steps"), RouteMode.WALK);
        String text;
        if (firstInstruction != null && firstInstruction.startsWith("步行")) {
            text = firstInstruction;
        }
        else if (distance > 0 && firstInstruction != null && !firstInstruction.isBlank()) {
            text = "步行" + formatDistance(distance) + "，" + firstInstruction;
        }
        else if (distance > 0) {
            text = "步行" + formatDistance(distance);
        }
        else {
            text = firstInstruction;
        }
        addStep(steps, text);
    }

    private void addTransitBusSteps(List<String> steps, Map<String, Object> bus) {
        if (bus == null || bus.isEmpty()) {
            return;
        }
        List<?> buslines = bus.get("buslines") instanceof List<?> list ? list : List.of();
        for (Object rawBusline : buslines) {
            Map<String, Object> busline = asMap(rawBusline);
            if (busline == null) {
                continue;
            }
            String name = asString(busline.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String departureStop = extractStopName(busline.get("departure_stop"));
            String arrivalStop = extractStopName(busline.get("arrival_stop"));
            long viaNum = asLong(busline.get("via_num"), 0L);

            StringBuilder builder = new StringBuilder("乘坐 ").append(name);
            if (departureStop != null && !departureStop.isBlank()) {
                builder.append("，从").append(departureStop).append("上车");
            }
            if (arrivalStop != null && !arrivalStop.isBlank()) {
                builder.append("，在").append(arrivalStop).append("下车");
            }
            if (viaNum > 0) {
                builder.append("（").append(viaNum).append("站）");
            }
            addStep(steps, builder.toString());
        }
    }

    private void addTransitRailwayStep(List<String> steps, Map<String, Object> railway) {
        if (railway == null || railway.isEmpty()) {
            return;
        }
        String name = firstNonBlank(
                asString(railway.get("name")),
                asString(railway.get("trip")),
                asString(railway.get("type"))
        );
        String departureStop = extractStopName(railway.get("departure_stop"));
        String arrivalStop = extractStopName(railway.get("arrival_stop"));
        if ((name == null || name.isBlank()) && departureStop == null && arrivalStop == null) {
            return;
        }

        StringBuilder builder = new StringBuilder("乘坐 ").append(firstNonBlank(name, "铁路/城际"));
        if (departureStop != null && !departureStop.isBlank()) {
            builder.append("，从").append(departureStop).append("出发");
        }
        if (arrivalStop != null && !arrivalStop.isBlank()) {
            builder.append("，到").append(arrivalStop).append("下车");
        }
        addStep(steps, builder.toString());
    }

    private void addTransitTaxiStep(List<String> steps, Map<String, Object> taxi) {
        if (taxi == null || taxi.isEmpty()) {
            return;
        }
        long distance = asLong(taxi.get("distance"), 0L);
        long durationSeconds = asLong(taxi.get("duration"), 0L);
        if (distance <= 0 && durationSeconds <= 0) {
            return;
        }

        StringBuilder builder = new StringBuilder("打车");
        if (distance > 0) {
            builder.append("约").append(formatDistance(distance));
        }
        if (durationSeconds > 0) {
            if (distance > 0) {
                builder.append("，");
            }
            builder.append("约").append(Math.max(1L, Math.round(durationSeconds / 60.0))).append("分钟");
        }
        addStep(steps, builder.toString());
    }

    private String firstInstruction(Object rawSteps, RouteMode mode) {
        if (!(rawSteps instanceof List<?> steps) || steps.isEmpty()) {
            return null;
        }
        for (Object rawStep : steps) {
            Map<String, Object> step = asMap(rawStep);
            if (step != null) {
                String instruction = extractPathInstruction(step, mode);
                if (instruction != null && !instruction.isBlank()) {
                    return instruction;
                }
                continue;
            }
            String text = asString(rawStep);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String extractStopName(Object rawStop) {
        if (rawStop == null) {
            return null;
        }
        Map<String, Object> stop = asMap(rawStop);
        if (stop == null) {
            return asString(rawStop);
        }
        return firstNonBlank(
                asString(stop.get("name")),
                asString(stop.get("id"))
        );
    }

    private void addStep(List<String> steps, String candidate) {
        String normalized = asString(candidate);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        if (!steps.isEmpty() && normalized.equals(steps.get(steps.size() - 1))) {
            return;
        }
        steps.add(normalized);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String formatDistance(long distanceMeters) {
        if (distanceMeters >= 1000) {
            return String.format(Locale.ROOT, "%.1f公里", distanceMeters / 1000.0);
        }
        return distanceMeters + "米";
    }

    private String summarizePayload(Object payload) {
        if (payload == null) {
            return "null";
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 500) {
                return json.substring(0, 500) + "...(truncated)";
            }
            return json;
        }
        catch (Exception ex) {
            String text = String.valueOf(payload);
            if (text.length() > 500) {
                return text.substring(0, 500) + "...(truncated)";
            }
            return text;
        }
    }

    private String formatLocation(double longitude, double latitude) {
        return longitude + "," + latitude;
    }

    private double[] parseLocation(String location) {
        if (location == null || !location.contains(",")) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        }
        catch (Exception ignore) {
            return null;
        }
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private record HospitalCandidate(
            String name,
            String address,
            boolean tier3a,
            boolean likelyHospital,
            boolean lowCapabilityFacility,
            long distanceMeters,
            String destinationLocation
    ) {
    }

    private record NearbySearchPlan(
            String keyword,
            String hospitalTypes,
            int radiusMeters
    ) {
    }

    private record NearbySearchOutcome(
            List<HospitalCandidate> hospitals,
            List<String> attemptSummaries
    ) {
    }

    private record CityPair(String originCity, String destinationCity) {
    }

    private enum RouteMode {
        WALK,
        DRIVE,
        TRANSIT
    }

    private record RouteMetric(long distanceMeters, long durationMinutes) {
    }

    private static final class RouteExecutionState {
        private int attempts;
        private int successes;
        private int metricMisses;
        private int failures;
        private int timeoutFailures;
        private boolean walkCircuitOpen;
        private boolean driveCircuitOpen;
        private boolean transitCircuitOpen;

        void recordAttempt(RouteMode mode) {
            if (mode != null) {
                attempts++;
            }
        }

        void recordSuccess(RouteMode mode) {
            if (mode != null) {
                successes++;
            }
        }

        void recordMetricMiss(RouteMode mode) {
            if (mode != null) {
                metricMisses++;
            }
        }

        void recordFailure(RouteMode mode, boolean timeout) {
            if (mode == null) {
                return;
            }
            failures++;
            if (timeout) {
                timeoutFailures++;
            }
        }

        boolean hasFailures() {
            return failures > 0 || metricMisses > 0;
        }

        int timeoutFailures() {
            return timeoutFailures;
        }

        void openCircuit(RouteMode mode) {
            if (mode == RouteMode.WALK) {
                walkCircuitOpen = true;
            }
            else if (mode == RouteMode.DRIVE) {
                driveCircuitOpen = true;
            }
            else if (mode == RouteMode.TRANSIT) {
                transitCircuitOpen = true;
            }
        }

        boolean isCircuitOpen(RouteMode mode) {
            if (mode == RouteMode.WALK) {
                return walkCircuitOpen;
            }
            if (mode == RouteMode.DRIVE) {
                return driveCircuitOpen;
            }
            if (mode == RouteMode.TRANSIT) {
                return transitCircuitOpen;
            }
            return false;
        }

        String summary() {
            return "attempts=" + attempts
                    + ", successes=" + successes
                    + ", metricMisses=" + metricMisses
                    + ", failures=" + failures
                    + ", timeoutFailures=" + timeoutFailures
                    + ", circuitsOpen=[walk=" + walkCircuitOpen
                    + ",drive=" + driveCircuitOpen
                    + ",transit=" + transitCircuitOpen + "]";
        }
    }
}
