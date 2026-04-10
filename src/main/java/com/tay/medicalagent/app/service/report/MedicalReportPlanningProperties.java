package com.tay.medicalagent.app.service.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "medical.report.planning")
public class MedicalReportPlanningProperties {

    private boolean enabled = true;

    /**
     * 规划运行模式：off|local|mcp|agentic。
     */
    private String mode = "";

    /**
     * 运行环境：prod|gray|local。
     * 当 mode 未显式配置时，按环境回落到默认模式。
     */
    private String environment = "prod";

    private boolean modeConfigured;

    private int topK = 3;

    private boolean routePlanningEnabled = true;

    /**
     * 是否启用 MCP 规划链路。
     */
    private Boolean mcpEnabled;

    /**
     * 是否启用 Agentic MCP 规划链路。
     */
    private Boolean agentEnabled;

    private final Mcp mcp = new Mcp();

    private final Fallback fallback = new Fallback();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
        this.modeConfigured = mode != null && !mode.isBlank();
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public PlanningMode getResolvedMode() {
        if (!enabled) {
            return PlanningMode.OFF;
        }
        if (modeConfigured) {
            return parseMode(mode);
        }
        if (mcpEnabled != null || agentEnabled != null) {
            return resolveLegacyMode();
        }
        return defaultModeForEnvironment(environment);
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public boolean isRoutePlanningEnabled() {
        return routePlanningEnabled;
    }

    public void setRoutePlanningEnabled(boolean routePlanningEnabled) {
        this.routePlanningEnabled = routePlanningEnabled;
    }

    public boolean isMcpEnabled() {
        PlanningMode resolvedMode = getResolvedMode();
        return resolvedMode == PlanningMode.MCP || resolvedMode == PlanningMode.AGENTIC;
    }

    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }

    public boolean isAgentEnabled() {
        return getResolvedMode() == PlanningMode.AGENTIC;
    }

    public void setAgentEnabled(boolean agentEnabled) {
        this.agentEnabled = agentEnabled;
    }

    public boolean usesDeprecatedFlags() {
        return !modeConfigured && (mcpEnabled != null || agentEnabled != null);
    }

    private PlanningMode resolveLegacyMode() {
        boolean effectiveMcpEnabled = Boolean.TRUE.equals(mcpEnabled);
        boolean effectiveAgentEnabled = agentEnabled == null || agentEnabled;
        if (!effectiveMcpEnabled && !effectiveAgentEnabled) {
            return PlanningMode.OFF;
        }
        if (!effectiveMcpEnabled) {
            return PlanningMode.LOCAL;
        }
        if (!effectiveAgentEnabled) {
            return PlanningMode.MCP;
        }
        return PlanningMode.AGENTIC;
    }

    private PlanningMode parseMode(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return PlanningMode.OFF;
        }
        try {
            return PlanningMode.valueOf(candidate.trim().toUpperCase());
        }
        catch (IllegalArgumentException ex) {
            return PlanningMode.OFF;
        }
    }

    private PlanningMode defaultModeForEnvironment(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return PlanningMode.MCP;
        }
        return switch (candidate.trim().toLowerCase()) {
            case "local" -> PlanningMode.LOCAL;
            case "gray" -> PlanningMode.AGENTIC;
            case "prod" -> PlanningMode.MCP;
            default -> PlanningMode.MCP;
        };
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Fallback getFallback() {
        return fallback;
    }

    public static class Fallback {

        private List<HospitalCandidate> hospitals = new ArrayList<>();

        public List<HospitalCandidate> getHospitals() {
            return hospitals;
        }

        public void setHospitals(List<HospitalCandidate> hospitals) {
            this.hospitals = hospitals;
        }
    }

    public static class Mcp {

        private String serverName = "amap";

        private int timeoutMs = 6000;

        private int aroundRadiusMeters = 5000;

        private String hospitalKeyword = "医院";

        private String hospitalTypes = "090100|090101|090102|090400";

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getAroundRadiusMeters() {
            return aroundRadiusMeters;
        }

        public void setAroundRadiusMeters(int aroundRadiusMeters) {
            this.aroundRadiusMeters = aroundRadiusMeters;
        }

        public String getHospitalKeyword() {
            return hospitalKeyword;
        }

        public void setHospitalKeyword(String hospitalKeyword) {
            this.hospitalKeyword = hospitalKeyword;
        }

        public String getHospitalTypes() {
            return hospitalTypes;
        }

        public void setHospitalTypes(String hospitalTypes) {
            this.hospitalTypes = hospitalTypes;
        }
    }

    public static class HospitalCandidate {

        private String name;

        private String address;

        private boolean tier3a;

        private double latitude;

        private double longitude;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public boolean isTier3a() {
            return tier3a;
        }

        public void setTier3a(boolean tier3a) {
            this.tier3a = tier3a;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }
    }

    public enum PlanningMode {
        OFF,
        LOCAL,
        MCP,
        AGENTIC
    }
}
