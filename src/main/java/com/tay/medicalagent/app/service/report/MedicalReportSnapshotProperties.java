package com.tay.medicalagent.app.service.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "medical.report.snapshot")
public class MedicalReportSnapshotProperties {

    private Duration ttl = Duration.ofMinutes(30);

    private int maxSessions = 500;

    private Duration cleanupInterval = Duration.ofMinutes(5);

    private Duration staleLockTtl = Duration.ofSeconds(60);

    private Duration degradedPlanningRetryAfter = Duration.ofSeconds(30);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public Duration getStaleLockTtl() {
        return staleLockTtl;
    }

    public void setStaleLockTtl(Duration staleLockTtl) {
        this.staleLockTtl = staleLockTtl;
    }

    public Duration getDegradedPlanningRetryAfter() {
        return degradedPlanningRetryAfter;
    }

    public void setDegradedPlanningRetryAfter(Duration degradedPlanningRetryAfter) {
        this.degradedPlanningRetryAfter = degradedPlanningRetryAfter;
    }
}
