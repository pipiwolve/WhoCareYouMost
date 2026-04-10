package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class DefaultMedicalHospitalPlanningService implements MedicalHospitalPlanningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMedicalHospitalPlanningService.class);
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private final MedicalReportPlanningProperties planningProperties;
    private final MedicalHospitalPlanningAgent medicalHospitalPlanningAgent;
    private final MedicalHospitalPlanningGateway medicalHospitalPlanningGateway;
    private final MedicalPlanningIntentResolver medicalPlanningIntentResolver;

    public DefaultMedicalHospitalPlanningService(
            MedicalReportPlanningProperties planningProperties,
            MedicalHospitalPlanningAgent medicalHospitalPlanningAgent,
            MedicalHospitalPlanningGateway medicalHospitalPlanningGateway,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver
    ) {
        this.planningProperties = planningProperties;
        this.medicalHospitalPlanningAgent = medicalHospitalPlanningAgent;
        this.medicalHospitalPlanningGateway = medicalHospitalPlanningGateway;
        this.medicalPlanningIntentResolver = medicalPlanningIntentResolver;
    }

    @Override
    public MedicalHospitalPlanningSummary plan(Double latitude, Double longitude, MedicalDiagnosisReport report) {
        return plan(latitude, longitude, report, medicalPlanningIntentResolver.resolve(report));
    }

    @Override
    public MedicalHospitalPlanningSummary plan(
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent
    ) {
        long startedAt = System.nanoTime();
        MedicalReportPlanningProperties.PlanningMode planningMode = planningProperties.getResolvedMode();
        if (!planningProperties.isEnabled() || planningMode == MedicalReportPlanningProperties.PlanningMode.OFF) {
            log.info(
                    "Medical report planning skipped because service is disabled. mode={}, totalMs={}",
                    planningMode.name().toLowerCase(),
                    elapsedMillis(startedAt, System.nanoTime())
            );
            return new MedicalHospitalPlanningSummary(List.of(), false, "医院规划服务未启用", "disabled");
        }
        if (report == null || !report.shouldGenerateReport()) {
            return MedicalHospitalPlanningSummary.empty();
        }

        MedicalPlanningIntent effectiveIntent = planningIntent == null
                ? medicalPlanningIntentResolver.resolve(report)
                : planningIntent;
        if (!effectiveIntent.planningRequested()) {
            return MedicalHospitalPlanningSummary.empty();
        }

        if (latitude == null || longitude == null) {
            log.info(
                    "Medical report planning skipped because location is missing. profileId={}, totalMs={}",
                    effectiveIntent.profileId(),
                    elapsedMillis(startedAt, System.nanoTime())
            );
            return new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度，无法进行就近医院规划", "location_missing");
        }

        if (planningMode == MedicalReportPlanningProperties.PlanningMode.LOCAL) {
            log.info(
                    "Medical report planning uses local fallback directly. profileId={}, totalMs={}",
                    effectiveIntent.profileId(),
                    elapsedMillis(startedAt, System.nanoTime())
            );
            return buildLocalFallback(latitude, longitude, effectiveIntent, "路线能力未启用", "route_disabled");
        }

        if (planningMode == MedicalReportPlanningProperties.PlanningMode.AGENTIC) {
            MedicalHospitalPlanningSummary agentSummary = medicalHospitalPlanningAgent
                    .plan(latitude, longitude, report, effectiveIntent, planningProperties.getMcp())
                    .orElse(null);
            if (isUsableAgentSummary(agentSummary)) {
                log.info(
                        "Medical report planning uses agentic MCP result. profileId={}, routesAvailable={}, routeStatusCode={}, hospitalCount={}, totalMs={}",
                        effectiveIntent.profileId(),
                        agentSummary.routesAvailable(),
                        agentSummary.routeStatusCode(),
                        agentSummary.hospitals() == null ? 0 : agentSummary.hospitals().size(),
                        elapsedMillis(startedAt, System.nanoTime())
                );
                return agentSummary;
            }
            if (agentSummary != null) {
                log.warn(
                        "Medical report planning agentic MCP returned inconsistent result, deterministic MCP fallback will be used. profileId={}, routeStatusCode={}, hospitalCount={}, totalMs={}",
                        effectiveIntent.profileId(),
                        agentSummary.routeStatusCode(),
                        agentSummary.hospitals() == null ? 0 : agentSummary.hospitals().size(),
                        elapsedMillis(startedAt, System.nanoTime())
                );
            }
            else {
                log.warn(
                        "Medical report planning agentic MCP returned empty result, deterministic MCP fallback will be used. profileId={}, topK={}, totalMs={}",
                        effectiveIntent.profileId(),
                        effectiveIntent.topK(),
                        elapsedMillis(startedAt, System.nanoTime())
                );
            }
        }

        if (planningMode == MedicalReportPlanningProperties.PlanningMode.MCP
                || planningMode == MedicalReportPlanningProperties.PlanningMode.AGENTIC) {
            MedicalHospitalPlanningSummary mcpSummary = medicalHospitalPlanningGateway
                    .plan(latitude, longitude, effectiveIntent, planningProperties.getMcp())
                    .orElse(null);
            if (mcpSummary != null) {
                log.info(
                        "Medical report planning uses MCP result. profileId={}, routesAvailable={}, routeStatusCode={}, hospitalCount={}, totalMs={}",
                        effectiveIntent.profileId(),
                        mcpSummary.routesAvailable(),
                        mcpSummary.routeStatusCode(),
                        mcpSummary.hospitals() == null ? 0 : mcpSummary.hospitals().size(),
                        elapsedMillis(startedAt, System.nanoTime())
                );
                return mcpSummary;
            }

            log.warn(
                    "Medical report planning MCP returned empty result, local fallback will be used. serverName={}, profileId={}, topK={}, hasLocation={}, totalMs={}",
                    planningProperties.getMcp().getServerName(),
                    effectiveIntent.profileId(),
                    effectiveIntent.topK(),
                    true,
                    elapsedMillis(startedAt, System.nanoTime())
            );
        }

        log.info(
                "Medical report planning falls back to local hospital list. profileId={}, routeStatusCode={}, totalMs={}",
                effectiveIntent.profileId(),
                "mcp_unavailable",
                elapsedMillis(startedAt, System.nanoTime())
        );
        return buildLocalFallback(
                latitude,
                longitude,
                effectiveIntent,
                "MCP 路线服务暂不可用，已返回就近医院与距离",
                "mcp_unavailable"
        );
    }

    private MedicalHospitalPlanningSummary buildLocalFallback(
            Double latitude,
            Double longitude,
            MedicalPlanningIntent effectiveIntent,
            String routeStatusMessage,
            String routeStatusCode
    ) {
        List<MedicalHospitalRecommendation> recommendations = planningProperties.getFallback().getHospitals().stream()
                .map(candidate -> toRecommendation(candidate, latitude, longitude))
                .sorted(fallbackComparator(effectiveIntent))
                .limit(Math.max(1, effectiveIntent.topK()))
                .toList();

        if (recommendations.isEmpty()) {
            return new MedicalHospitalPlanningSummary(List.of(), false, "路线服务暂不可用，且暂无可用医院数据", "no_hospital_data");
        }

        List<MedicalHospitalRecommendation> noRouteRecommendations = recommendations.stream()
                .map(h -> new MedicalHospitalRecommendation(
                        h.name(),
                        h.address(),
                        h.tier3a(),
                        h.distanceMeters(),
                        List.of()
                ))
                .toList();
        return new MedicalHospitalPlanningSummary(noRouteRecommendations, false, routeStatusMessage, routeStatusCode);
    }

    private boolean isUsableAgentSummary(MedicalHospitalPlanningSummary summary) {
        if (summary == null || summary.hospitals() == null || summary.hospitals().isEmpty()) {
            return false;
        }
        if ("location_missing".equalsIgnoreCase(summary.routeStatusCode())) {
            return false;
        }
        boolean allZeroDistance = summary.hospitals().stream().allMatch(hospital -> hospital != null && hospital.distanceMeters() <= 0);
        boolean allRouteEmpty = summary.hospitals().stream()
                .allMatch(hospital -> hospital == null || hospital.routes() == null || hospital.routes().isEmpty());
        if (summary.hospitals().size() > 1 && allZeroDistance && allRouteEmpty) {
            return false;
        }
        if (planningProperties.isRoutePlanningEnabled() && !summary.routesAvailable() && allRouteEmpty) {
            return false;
        }
        if (planningProperties.isRoutePlanningEnabled() && summary.routesAvailable() && !hasAnyRouteSteps(summary)) {
            return false;
        }
        return true;
    }

    private boolean hasAnyRouteSteps(MedicalHospitalPlanningSummary summary) {
        if (summary == null || summary.hospitals() == null) {
            return false;
        }
        for (MedicalHospitalRecommendation hospital : summary.hospitals()) {
            if (hospital == null || hospital.routes() == null) {
                continue;
            }
            for (MedicalHospitalRouteOption route : hospital.routes()) {
                if (route != null && route.steps() != null && !route.steps().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Comparator<MedicalHospitalRecommendation> fallbackComparator(MedicalPlanningIntent planningIntent) {
        if (planningIntent != null && planningIntent.preferTier3a()) {
            return Comparator.comparing(MedicalHospitalRecommendation::tier3a).reversed()
                    .thenComparing(MedicalHospitalRecommendation::distanceMeters);
        }
        return Comparator.comparing(MedicalHospitalRecommendation::distanceMeters)
                .thenComparing(MedicalHospitalRecommendation::tier3a, Comparator.reverseOrder());
    }

    private MedicalHospitalRecommendation toRecommendation(
            MedicalReportPlanningProperties.HospitalCandidate candidate,
            double userLatitude,
            double userLongitude
    ) {
        long distanceMeters = Math.round(haversineMeters(
                userLatitude,
                userLongitude,
                candidate.getLatitude(),
                candidate.getLongitude()
        ));

        return new MedicalHospitalRecommendation(
                sanitize(candidate.getName(), "未知医院"),
                sanitize(candidate.getAddress(), ""),
                candidate.isTier3a(),
                Math.max(distanceMeters, 0L),
                List.of()
        );
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

    private String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private long elapsedMillis(long startNanos, long endNanos) {
        return Math.max(0L, (endNanos - startNanos) / 1_000_000L);
    }
}
