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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final long UNRESOLVED_DISTANCE_METERS = Long.MAX_VALUE;
    private static final String TOOL_MAPS_AROUND_SEARCH = "maps_around_search";
    private static final String TOOL_MAPS_WALKING_BY_COORDINATES = "maps_direction_walking_by_coordinates";
    private static final String TOOL_MAPS_DRIVING_BY_COORDINATES = "maps_direction_driving_by_coordinates";
    private static final String TOOL_MAPS_TRANSIT_BY_COORDINATES = "maps_direction_transit_integrated_by_coordinates";
    private static final String TOOL_MAPS_REGEOCODE = "maps_regeocode";
    private static final String TOOL_MAPS_SEARCH_DETAIL = "maps_search_detail";
    private static final String TOOL_MAPS_TEXT_SEARCH = "maps_text_search";

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

            ToolDescriptor nearbyTool = resolveToolDescriptor(tools, TOOL_MAPS_AROUND_SEARCH,
                    List.of("around", "nearby", "search", "poi", "hospital", "医院"));
            if (nearbyTool == null) {
                log.warn("MCP planning nearby tool not found. expected={}, serverName={}", TOOL_MAPS_AROUND_SEARCH, mcpProperties.getServerName());
                return Optional.empty();
            }

            ToolDescriptor walkTool = resolveToolDescriptor(tools, TOOL_MAPS_WALKING_BY_COORDINATES, List.of("walking", "walk", "步行"));
            ToolDescriptor driveTool = resolveToolDescriptor(tools, TOOL_MAPS_DRIVING_BY_COORDINATES, List.of("driving", "drive", "驾车"));
            ToolDescriptor transitTool = resolveToolDescriptor(tools, TOOL_MAPS_TRANSIT_BY_COORDINATES, List.of("transit", "公交", "bus"));
            ToolDescriptor regeocodeTool = resolveToolDescriptor(tools, TOOL_MAPS_REGEOCODE, List.of("regeocode"));
            ToolDescriptor searchDetailTool = resolveToolDescriptor(tools, TOOL_MAPS_SEARCH_DETAIL, List.of("search_detail", "detail"));
            ToolDescriptor textSearchTool = resolveToolDescriptor(tools, TOOL_MAPS_TEXT_SEARCH, List.of("text_search", "text", "keyword"));
            log.info(
                    "MCP planning tools resolved. walkTool={}, driveTool={}, transitTool={}, regeocodeTool={}, searchDetailTool={}, textSearchTool={}",
                    toolName(walkTool),
                    toolName(driveTool),
                    toolName(transitTool),
                    toolName(regeocodeTool),
                    toolName(searchDetailTool),
                    toolName(textSearchTool)
            );

            NearbySearchOutcome nearbySearchOutcome = searchHospitalsWithFallbackPlans(
                    client,
                    nearbyTool,
                    latitude,
                    longitude,
                    planningIntent,
                    mcpProperties,
                    searchDetailTool,
                    textSearchTool,
                    regeocodeTool
            );
            if (nearbySearchOutcome.shouldFallbackToLocal()) {
                log.warn(
                        "MCP planning nearby search terminated early. tool={}, errorCategory={}, attempts={}",
                        toolName(nearbyTool),
                        nearbySearchOutcome.terminalErrorCategory(),
                        nearbySearchOutcome.attemptSummaries()
                );
                return Optional.empty();
            }
            List<HospitalCandidate> hospitals = nearbySearchOutcome.hospitals();
            if (nearbySearchOutcome.terminalErrorCategory() != null && !hospitals.isEmpty()) {
                log.info(
                        "MCP planning nearby search terminated early but will use collected hospitals. tool={}, errorCategory={}, hospitalCount={}, attempts={}",
                        toolName(nearbyTool),
                        nearbySearchOutcome.terminalErrorCategory(),
                        hospitals.size(),
                        nearbySearchOutcome.attemptSummaries()
                );
            }
            if (hospitals.isEmpty()) {
                log.warn(
                    "MCP planning nearby search exhausted fallback plans with no enrichable hospitals. tool={}, attempts={}",
                    toolName(nearbyTool),
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
            ToolDescriptor nearbyTool,
            double userLatitude,
            double userLongitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties,
            ToolDescriptor searchDetailTool,
            ToolDescriptor textSearchTool,
            ToolDescriptor regeocodeTool
    ) {
        Map<String, HospitalCandidate> hospitalsByKey = new LinkedHashMap<>();
        List<String> attemptSummaries = new ArrayList<>();
        int targetCandidateCount = resolveSearchTargetCandidateCount(planningIntent);
        ToolErrorCategory terminalErrorCategory = null;
        List<NearbySearchPlan> nearbySearchPlans = buildNearbySearchPlans(planningIntent, mcpProperties);
        int terminalPlanIndex = -1;

        for (int index = 0; index < nearbySearchPlans.size(); index++) {
            NearbySearchPlan searchPlan = nearbySearchPlans.get(index);
            Map<String, Object> nearbyArgs = new HashMap<>();
            putToolArg(nearbyTool, nearbyArgs, "location", formatLocation(userLongitude, userLatitude));
            putToolArg(nearbyTool, nearbyArgs, "radius", String.valueOf(searchPlan.radiusMeters()));
            putToolArg(nearbyTool, nearbyArgs, "keywords", searchPlan.keyword());
            if (!searchPlan.hospitalTypes().isBlank()) {
                putToolArg(nearbyTool, nearbyArgs, "types", searchPlan.hospitalTypes(), true);
            }

            ToolCallOutcome nearbyOutcome = callTool(client, nearbyTool, nearbyArgs);
            Object nearbyPayload = nearbyOutcome.payload();
            List<HospitalCandidate> hospitals = nearbyOutcome.success()
                    ? extractHospitals(nearbyPayload, userLatitude, userLongitude)
                    : List.of();
            for (HospitalCandidate hospital : hospitals) {
                hospitalsByKey.putIfAbsent(hospitalKey(hospital), hospital);
            }

            attemptSummaries.add(
                    "args=" + nearbyArgs
                            + ", found=" + hospitals.size()
                            + ", uniqueTotal=" + hospitalsByKey.size()
                            + ", errorCategory=" + nearbyOutcome.errorCategory()
                            + ", errorText=" + summarizePayload(nearbyOutcome.errorText())
                            + ", payloadSummary=" + nearbyOutcome.rawSummary()
            );
            if (!nearbyOutcome.success()) {
                terminalErrorCategory = nearbyOutcome.errorCategory();
                terminalPlanIndex = index;
                break;
            }
            if (hospitalsByKey.size() >= targetCandidateCount) {
                break;
            }
        }

        if (hospitalsByKey.isEmpty()
                && shouldAttemptTextSearchFallback(terminalErrorCategory)
                && textSearchTool != null
                && regeocodeTool != null) {
            CityTextSearchOutcome textSearchOutcome = searchHospitalsWithCityTextFallback(
                    client,
                    textSearchTool,
                    regeocodeTool,
                    userLatitude,
                    userLongitude,
                    planningIntent,
                    mcpProperties,
                    nearbySearchPlans,
                    terminalPlanIndex,
                    attemptSummaries
            );
            for (HospitalCandidate hospital : textSearchOutcome.hospitals()) {
                hospitalsByKey.putIfAbsent(hospitalKey(hospital), hospital);
            }
            if (textSearchOutcome.terminalErrorCategory() != null) {
                terminalErrorCategory = textSearchOutcome.terminalErrorCategory();
            }
        }

        List<HospitalCandidate> rankedHospitals = hospitalsByKey.values().stream()
                .sorted(hospitalComparator(planningIntent))
                .toList();
        List<HospitalCandidate> enrichedHospitals = enrichHospitalLocations(
                client,
                rankedHospitals,
                searchDetailTool,
                planningIntent,
                userLatitude,
                userLongitude
        );
        List<HospitalCandidate> usableHospitals = enrichedHospitals.stream()
                .filter(hospital -> hospital.distanceMeters() != UNRESOLVED_DISTANCE_METERS)
                .toList();
        return new NearbySearchOutcome(List.copyOf(usableHospitals), List.copyOf(attemptSummaries), terminalErrorCategory);
    }

    private boolean shouldAttemptTextSearchFallback(ToolErrorCategory terminalErrorCategory) {
        return terminalErrorCategory == ToolErrorCategory.RATE_LIMIT
                || terminalErrorCategory == ToolErrorCategory.SERVER_BUSY
                || terminalErrorCategory == ToolErrorCategory.TIMEOUT;
    }

    private CityTextSearchOutcome searchHospitalsWithCityTextFallback(
            McpSyncClient client,
            ToolDescriptor textSearchTool,
            ToolDescriptor regeocodeTool,
            double userLatitude,
            double userLongitude,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties,
            List<NearbySearchPlan> nearbySearchPlans,
            int startPlanIndex,
            List<String> attemptSummaries
    ) {
        String city = resolveCity(client, regeocodeTool, formatLocation(userLongitude, userLatitude));
        if (city == null || city.isBlank()) {
            attemptSummaries.add("textSearchSkipped=missing_city");
            return CityTextSearchOutcome.empty(null);
        }

        Map<String, HospitalCandidate> hospitalsByKey = new LinkedHashMap<>();
        ToolErrorCategory terminalErrorCategory = null;
        int targetCandidateCount = resolveTopK(planningIntent);
        for (CityTextSearchPlan searchPlan : buildCityTextSearchPlans(nearbySearchPlans, startPlanIndex, planningIntent, mcpProperties)) {
            Map<String, Object> args = new HashMap<>();
            putToolArg(textSearchTool, args, "keywords", searchPlan.keyword());
            putToolArg(textSearchTool, args, "city", city);
            if (!searchPlan.hospitalTypes().isBlank()) {
                putToolArg(textSearchTool, args, "types", searchPlan.hospitalTypes(), true);
            }

            ToolCallOutcome outcome = callTool(client, textSearchTool, args);
            List<HospitalCandidate> hospitals = outcome.success()
                    ? extractHospitals(outcome.payload(), userLatitude, userLongitude)
                    : List.of();
            for (HospitalCandidate hospital : hospitals) {
                hospitalsByKey.putIfAbsent(hospitalKey(hospital), hospital);
            }
            attemptSummaries.add(
                    "textArgs=" + args
                            + ", found=" + hospitals.size()
                            + ", uniqueTotal=" + hospitalsByKey.size()
                            + ", errorCategory=" + outcome.errorCategory()
                            + ", errorText=" + summarizePayload(outcome.errorText())
                            + ", payloadSummary=" + outcome.rawSummary()
            );
            if (!outcome.success()) {
                terminalErrorCategory = outcome.errorCategory();
                break;
            }
            if (hospitalsByKey.size() >= targetCandidateCount) {
                break;
            }
        }
        return new CityTextSearchOutcome(List.copyOf(hospitalsByKey.values()), terminalErrorCategory);
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

    private List<CityTextSearchPlan> buildCityTextSearchPlans(
            List<NearbySearchPlan> nearbySearchPlans,
            int startPlanIndex,
            MedicalPlanningIntent planningIntent,
            MedicalReportPlanningProperties.Mcp mcpProperties
    ) {
        List<NearbySearchPlan> sourcePlans = nearbySearchPlans == null || nearbySearchPlans.isEmpty()
                ? buildNearbySearchPlans(planningIntent, mcpProperties)
                : nearbySearchPlans;
        int effectiveStart = startPlanIndex >= 0 && startPlanIndex < sourcePlans.size() ? startPlanIndex : 0;

        List<CityTextSearchPlan> textPlans = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        for (int index = effectiveStart; index < sourcePlans.size(); index++) {
            NearbySearchPlan sourcePlan = sourcePlans.get(index);
            addCityTextSearchPlan(textPlans, signatures, sourcePlan.keyword(), sourcePlan.hospitalTypes());
        }
        return textPlans;
    }

    private void addCityTextSearchPlan(
            List<CityTextSearchPlan> plans,
            Set<String> signatures,
            String keyword,
            String hospitalTypes
    ) {
        String normalizedKeyword = safeSearchText(keyword, "");
        if (normalizedKeyword.isBlank()) {
            return;
        }
        String normalizedTypes = safeSearchText(hospitalTypes, "");
        String signature = normalizedKeyword.toLowerCase(Locale.ROOT)
                + "|"
                + normalizedTypes.toLowerCase(Locale.ROOT);
        if (!signatures.add(signature)) {
            return;
        }
        plans.add(new CityTextSearchPlan(normalizedKeyword, normalizedTypes));
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
            ToolDescriptor walkTool,
            ToolDescriptor driveTool,
            ToolDescriptor transitTool,
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
            ToolDescriptor tool,
            double userLongitude,
            double userLatitude,
            String destination,
            RouteExecutionState routeState
    ) {
        if (tool == null || routeState.isCircuitOpen(RouteMode.WALK)) {
            return;
        }

        Map<String, Object> args = new HashMap<>();
        putToolArg(tool, args, "origin", formatLocation(userLongitude, userLatitude));
        putToolArg(tool, args, "destination", destination);

        routeState.recordAttempt(RouteMode.WALK);
        ToolCallOutcome outcome = callTool(client, tool, args, true, RouteMode.WALK, routeState);
        if (!outcome.success()) {
            return;
        }
        RouteMetric metric = extractRouteMetric(outcome.payload(), RouteMode.WALK);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.WALK);
            return;
        }
        List<String> steps = extractRouteSteps(outcome.payload(), RouteMode.WALK);

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
            ToolDescriptor tool,
            double userLongitude,
            double userLatitude,
            String destination,
            RouteExecutionState routeState
    ) {
        if (tool == null || routeState.isCircuitOpen(RouteMode.DRIVE)) {
            return;
        }

        Map<String, Object> args = new HashMap<>();
        putToolArg(tool, args, "origin", formatLocation(userLongitude, userLatitude));
        putToolArg(tool, args, "destination", destination);

        routeState.recordAttempt(RouteMode.DRIVE);
        ToolCallOutcome outcome = callTool(client, tool, args, true, RouteMode.DRIVE, routeState);
        if (!outcome.success()) {
            return;
        }
        RouteMetric metric = extractRouteMetric(outcome.payload(), RouteMode.DRIVE);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.DRIVE);
            return;
        }
        List<String> steps = extractRouteSteps(outcome.payload(), RouteMode.DRIVE);

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
            ToolDescriptor tool,
            double userLongitude,
            double userLatitude,
            String destination,
            CityPair cityPair,
            RouteExecutionState routeState
    ) {
        if (tool == null
                || routeState.isCircuitOpen(RouteMode.TRANSIT)
                || cityPair == null
                || cityPair.originCity() == null
                || cityPair.destinationCity() == null) {
            log.debug(
                    "MCP transit route skipped. toolName={}, cityPairPresent={}",
                    toolName(tool),
                    cityPair != null
            );
            return;
        }

        Map<String, Object> args = new HashMap<>();
        putToolArg(tool, args, "origin", formatLocation(userLongitude, userLatitude));
        putToolArg(tool, args, "destination", destination);
        putToolArg(tool, args, "city", cityPair.originCity());
        putToolArg(tool, args, "cityd", cityPair.destinationCity());

        routeState.recordAttempt(RouteMode.TRANSIT);
        ToolCallOutcome outcome = callTool(client, tool, args, true, RouteMode.TRANSIT, routeState);
        if (!outcome.success()) {
            return;
        }
        RouteMetric metric = extractRouteMetric(outcome.payload(), RouteMode.TRANSIT);
        if (metric == null) {
            routeState.recordMetricMiss(RouteMode.TRANSIT);
            log.debug(
                    "MCP transit route metric missing. tool={}, args={}, payloadSummary={}",
                    toolName(tool),
                    args,
                    summarizePayload(outcome.payload())
            );
            return;
        }
        List<String> steps = extractRouteSteps(outcome.payload(), RouteMode.TRANSIT);

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
            ToolDescriptor regeocodeTool,
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

    private String resolveCity(McpSyncClient client, ToolDescriptor regeocodeTool, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        Map<String, Object> args = new HashMap<>();
        putToolArg(regeocodeTool, args, "location", location);
        ToolCallOutcome outcome = callTool(client, regeocodeTool, args);
        if (!outcome.success()) {
            return null;
        }
        String city = findString(outcome.payload(), "city");
        if (city == null || city.isBlank()) {
            city = findString(outcome.payload(), "district");
        }
        return (city == null || city.isBlank()) ? null : city;
    }

    private ToolCallOutcome callTool(McpSyncClient client, ToolDescriptor tool, Map<String, Object> args) {
        return callTool(client, tool, args, true, null, null);
    }

    private ToolCallOutcome callTool(
            McpSyncClient client,
            ToolDescriptor tool,
            Map<String, Object> args,
            boolean logFailures,
            RouteMode routeMode,
            RouteExecutionState routeState
    ) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            markRouteFailure(routeMode, routeState, false);
            return ToolCallOutcome.failure(null, "Tool descriptor missing", ToolErrorCategory.UNKNOWN, false, "null");
        }
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool.name(), args));
            Object payload = result.structuredContent();
            String contentText = collectTextContent(result.content());
            if (payload == null) {
                payload = parseJsonLikeText(contentText);
            }
            String payloadSummary = payload == null ? summarizePayload(contentText) : summarizePayload(payload);
            String structuredErrorText = extractStructuredErrorText(payload);
            if (Boolean.TRUE.equals(result.isError())) {
                String errorText = firstNonBlank(structuredErrorText, normalizeErrorText(contentText), payloadSummary);
                ToolErrorCategory errorCategory = classifyErrorCategory(errorText, false);
                markRouteFailure(routeMode, routeState, errorCategory == ToolErrorCategory.TIMEOUT);
                maybeLogToolFailure(logFailures, tool.name(), args, errorText, errorCategory, payloadSummary);
                return ToolCallOutcome.failure(payload, errorText, errorCategory, errorCategory == ToolErrorCategory.TIMEOUT, payloadSummary);
            }
            if (structuredErrorText != null) {
                ToolErrorCategory errorCategory = classifyErrorCategory(structuredErrorText, false);
                markRouteFailure(routeMode, routeState, errorCategory == ToolErrorCategory.TIMEOUT);
                maybeLogToolFailure(logFailures, tool.name(), args, structuredErrorText, errorCategory, payloadSummary);
                return ToolCallOutcome.failure(payload, structuredErrorText, errorCategory, errorCategory == ToolErrorCategory.TIMEOUT, payloadSummary);
            }
            return ToolCallOutcome.success(payload, payloadSummary);
        }
        catch (Exception ex) {
            boolean timeout = isTimeout(ex);
            ToolErrorCategory errorCategory = classifyErrorCategory(ex.getMessage(), timeout);
            markRouteFailure(routeMode, routeState, timeout);
            maybeLogToolFailure(logFailures, tool.name(), args, ex.getMessage(), errorCategory, "null");
            return ToolCallOutcome.failure(null, ex.getMessage(), errorCategory, timeout, "null");
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

    private List<HospitalCandidate> extractHospitals(Object payload, double userLatitude, double userLongitude) {
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
            double[] latLon = parseLocation(location);

            long distance = asLong(row.get("distance"), -1L);
            if (distance < 0 && latLon != null) {
                distance = Math.round(haversineMeters(userLatitude, userLongitude, latLon[1], latLon[0]));
            }
            if (distance < 0) {
                if (poiId != null && !poiId.isBlank()) {
                    distance = UNRESOLVED_DISTANCE_METERS;
                }
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
                    location,
                    poiId
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

    private String hospitalKey(HospitalCandidate hospital) {
        if (hospital == null) {
            return "";
        }
        return safeSearchText(hospital.name(), "")
                + "|"
                + safeSearchText(hospital.address(), "")
                + "|"
                + safeSearchText(hospital.poiId(), safeSearchText(hospital.destinationLocation(), ""));
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

    private ToolDescriptor resolveToolDescriptor(List<McpSchema.Tool> tools, String exactName, List<String> keywords) {
        McpSchema.Tool exact = tools.stream()
                .filter(tool -> exactName.equals(tool.name()))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return toToolDescriptor(exact);
        }

        return tools.stream()
                .filter(tool -> {
                    String normalized = tool == null || tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
                    return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
                })
                .findFirst()
                .map(this::toToolDescriptor)
                .orElse(null);
    }

    private ToolDescriptor toToolDescriptor(McpSchema.Tool tool) {
        Map<String, Object> properties = tool == null || tool.inputSchema() == null ? null : tool.inputSchema().properties();
        Set<String> supportedProperties = new LinkedHashSet<>();
        if (properties != null) {
            for (String propertyName : properties.keySet()) {
                if (propertyName != null && !propertyName.isBlank()) {
                    supportedProperties.add(propertyName.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ToolDescriptor(
                tool == null ? null : tool.name(),
                properties != null,
                Set.copyOf(supportedProperties)
        );
    }

    private void putToolArg(ToolDescriptor tool, Map<String, Object> args, String property, Object value) {
        putToolArg(tool, args, property, value, false);
    }

    private void putToolArg(
            ToolDescriptor tool,
            Map<String, Object> args,
            String property,
            Object value,
            boolean requireExplicitSupport
    ) {
        if (tool == null || args == null || property == null || property.isBlank() || value == null) {
            return;
        }
        if (value instanceof String stringValue && stringValue.isBlank()) {
            return;
        }
        if (!tool.supportsProperty(property, requireExplicitSupport)) {
            return;
        }
        args.put(property, value);
    }

    private List<HospitalCandidate> enrichHospitalLocations(
            McpSyncClient client,
            List<HospitalCandidate> hospitals,
            ToolDescriptor searchDetailTool,
            MedicalPlanningIntent planningIntent,
            double userLatitude,
            double userLongitude
    ) {
        if (searchDetailTool == null || hospitals == null || hospitals.isEmpty()) {
            return hospitals == null ? List.of() : hospitals;
        }
        int enrichmentLimit = Math.max(resolveTopK(planningIntent) * 2, 6);
        List<HospitalCandidate> enrichedHospitals = new ArrayList<>(hospitals);
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        EnumSet<ToolErrorCategory> errorCategories = EnumSet.noneOf(ToolErrorCategory.class);

        for (int index = 0; index < Math.min(enrichedHospitals.size(), enrichmentLimit); index++) {
            HospitalCandidate hospital = enrichedHospitals.get(index);
            if (hospital == null
                    || (hospital.destinationLocation() != null && !hospital.destinationLocation().isBlank())
                    || hospital.poiId() == null
                    || hospital.poiId().isBlank()) {
                continue;
            }
            attempted++;
            Map<String, Object> args = new HashMap<>();
            putToolArg(searchDetailTool, args, "id", hospital.poiId());
            ToolCallOutcome outcome = callTool(client, searchDetailTool, args, false, null, null);
            String resolvedLocation = findString(outcome.payload(), "location");
            if (outcome.success() && resolvedLocation != null && !resolvedLocation.isBlank()) {
                long resolvedDistance = hospital.distanceMeters();
                if (resolvedDistance == UNRESOLVED_DISTANCE_METERS) {
                    double[] latLon = parseLocation(resolvedLocation);
                    if (latLon != null) {
                        resolvedDistance = Math.round(haversineMeters(userLatitude, userLongitude, latLon[1], latLon[0]));
                    }
                }
                enrichedHospitals.set(index, hospital.withResolvedLocation(resolvedLocation, resolvedDistance));
                succeeded++;
                continue;
            }
            failed++;
            if (outcome.errorCategory() != null) {
                errorCategories.add(outcome.errorCategory());
            }
        }

        if (attempted > 0) {
            if (failed > 0) {
                log.info(
                        "MCP detail enrichment finished with partial misses. tool={}, attempted={}, succeeded={}, failed={}, errorCategories={}",
                        toolName(searchDetailTool),
                        attempted,
                        succeeded,
                        failed,
                        errorCategories
                );
            }
            else {
                log.debug(
                        "MCP detail enrichment finished. tool={}, attempted={}, succeeded={}, failed={}",
                        toolName(searchDetailTool),
                        attempted,
                        succeeded,
                        failed
                );
            }
        }
        return List.copyOf(enrichedHospitals);
    }

    private void maybeLogToolFailure(
            boolean logFailures,
            String toolName,
            Map<String, Object> args,
            String errorText,
            ToolErrorCategory errorCategory,
            String payloadSummary
    ) {
        if (!logFailures) {
            return;
        }
        log.warn(
                "MCP tool call failed. tool={}, args={}, errorText={}, errorCategory={}, payloadSummary={}",
                toolName,
                args,
                errorText == null ? "" : errorText,
                errorCategory,
                payloadSummary
        );
    }

    private String collectTextContent(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (textContent.text() != null && !textContent.text().isBlank()) {
                    texts.add(textContent.text().trim());
                }
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        return String.join("\n", texts);
    }

    private String extractStructuredErrorText(Object payload) {
        Map<String, Object> map = asMap(payload);
        if (map == null || !map.containsKey("error")) {
            return null;
        }
        Object errorPayload = map.get("error");
        String direct = asString(errorPayload);
        if (direct != null) {
            return direct;
        }
        String nested = firstNonBlank(
                findString(errorPayload, "message"),
                findString(errorPayload, "msg"),
                findString(errorPayload, "info"),
                findString(errorPayload, "detail"),
                findString(errorPayload, "error")
        );
        if (nested != null) {
            return nested;
        }
        return summarizePayload(errorPayload);
    }

    private String normalizeErrorText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) + "...(truncated)" : normalized;
    }

    private ToolErrorCategory classifyErrorCategory(String errorText, boolean timeout) {
        if (timeout) {
            return ToolErrorCategory.TIMEOUT;
        }
        if (errorText == null || errorText.isBlank()) {
            return ToolErrorCategory.UNKNOWN;
        }
        String normalized = errorText.toUpperCase(Locale.ROOT);
        if (normalized.contains("QPS")
                || normalized.contains("ACCESS_TOO_FREQUENT")
                || normalized.contains("CUQPS_HAS_EXCEEDED_THE_LIMIT")
                || normalized.contains("CKQPS_HAS_EXCEEDED_THE_LIMIT")) {
            return ToolErrorCategory.RATE_LIMIT;
        }
        if (normalized.contains("INVALID_PARAMS")
                || normalized.contains("MISSING_REQUIRED_PARAMS")
                || normalized.contains("INVALID_USER_KEY")
                || normalized.contains("ILLEGAL_USER_KEY")) {
            return ToolErrorCategory.INVALID_PARAMS;
        }
        if (normalized.contains("SERVER_IS_BUSY")) {
            return ToolErrorCategory.SERVER_BUSY;
        }
        if (normalized.contains("TIMEOUT")) {
            return ToolErrorCategory.TIMEOUT;
        }
        return ToolErrorCategory.TOOL_ERROR;
    }

    private String toolName(ToolDescriptor descriptor) {
        return descriptor == null ? null : descriptor.name();
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
            String destinationLocation,
            String poiId
    ) {
        private HospitalCandidate withResolvedLocation(String destinationLocation, long distanceMeters) {
            return new HospitalCandidate(
                    name,
                    address,
                    tier3a,
                    likelyHospital,
                    lowCapabilityFacility,
                    distanceMeters,
                    destinationLocation,
                    poiId
            );
        }
    }

    private record NearbySearchPlan(
            String keyword,
            String hospitalTypes,
            int radiusMeters
    ) {
    }

    private record CityTextSearchPlan(
            String keyword,
            String hospitalTypes
    ) {
    }

    private record NearbySearchOutcome(
            List<HospitalCandidate> hospitals,
            List<String> attemptSummaries,
            ToolErrorCategory terminalErrorCategory
    ) {
        private boolean shouldFallbackToLocal() {
            return hospitals.isEmpty()
                    && (terminalErrorCategory == ToolErrorCategory.RATE_LIMIT
                    || terminalErrorCategory == ToolErrorCategory.SERVER_BUSY
                    || terminalErrorCategory == ToolErrorCategory.TIMEOUT
                    || terminalErrorCategory == ToolErrorCategory.INVALID_PARAMS);
        }
    }

    private record CityTextSearchOutcome(
            List<HospitalCandidate> hospitals,
            ToolErrorCategory terminalErrorCategory
    ) {
        private static CityTextSearchOutcome empty(ToolErrorCategory terminalErrorCategory) {
            return new CityTextSearchOutcome(List.of(), terminalErrorCategory);
        }
    }

    private record ToolDescriptor(
            String name,
            boolean schemaPropertiesKnown,
            Set<String> supportedProperties
    ) {
        private boolean supportsProperty(String property, boolean requireExplicitSupport) {
            if (property == null || property.isBlank()) {
                return false;
            }
            if (!schemaPropertiesKnown) {
                return !requireExplicitSupport;
            }
            return supportedProperties.contains(property.toLowerCase(Locale.ROOT));
        }
    }

    private record ToolCallOutcome(
            boolean success,
            Object payload,
            String errorText,
            ToolErrorCategory errorCategory,
            boolean timeout,
            String rawSummary
    ) {
        private static ToolCallOutcome success(Object payload, String rawSummary) {
            return new ToolCallOutcome(true, payload, null, null, false, rawSummary);
        }

        private static ToolCallOutcome failure(
                Object payload,
                String errorText,
                ToolErrorCategory errorCategory,
                boolean timeout,
                String rawSummary
        ) {
            return new ToolCallOutcome(false, payload, errorText, errorCategory, timeout, rawSummary);
        }
    }

    private enum ToolErrorCategory {
        RATE_LIMIT,
        INVALID_PARAMS,
        SERVER_BUSY,
        TIMEOUT,
        TOOL_ERROR,
        UNKNOWN
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
