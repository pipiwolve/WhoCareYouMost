package com.tay.medicalagent.app.service.report;

/**
 * 报告触发等级。
 */
public enum ReportTriggerLevel {

    NONE("none"),
    SUGGESTED("suggested"),
    RECOMMENDED("recommended"),
    URGENT("urgent");

    private final String apiValue;

    ReportTriggerLevel(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public boolean isAvailable() {
        return this != NONE;
    }
}
