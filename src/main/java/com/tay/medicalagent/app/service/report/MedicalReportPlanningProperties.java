package com.tay.medicalagent.app.service.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "medical.report.planning")
public class MedicalReportPlanningProperties {

    private boolean enabled = true;

    private int topK = 3;

    private boolean routePlanningEnabled = true;

    /**
     * 是否启用 MCP 规划链路。
     */
    private boolean mcpEnabled = false;

    /**
     * 是否启用 Agentic MCP 规划链路。
     */
    private boolean agentEnabled = true;

    private final Mcp mcp = new Mcp();

    private final Fallback fallback = new Fallback();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
        return mcpEnabled;
    }

    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }

    public boolean isAgentEnabled() {
        return agentEnabled;
    }

    public void setAgentEnabled(boolean agentEnabled) {
        this.agentEnabled = agentEnabled;
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
}
