package com.tay.medicalagent.app.service.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "medical.report.build")
public class MedicalReportBuildProperties {

    private Duration queryWaitTimeout = Duration.ofMillis(5000);

    private int retryAfterMs = 3000;

    private final Executor executor = new Executor();

    public Duration getQueryWaitTimeout() {
        return queryWaitTimeout;
    }

    public void setQueryWaitTimeout(Duration queryWaitTimeout) {
        this.queryWaitTimeout = queryWaitTimeout;
    }

    public int getRetryAfterMs() {
        return retryAfterMs;
    }

    public void setRetryAfterMs(int retryAfterMs) {
        this.retryAfterMs = retryAfterMs;
    }

    public Executor getExecutor() {
        return executor;
    }

    public static class Executor {

        private String threadNamePrefix = "medical-report-build-";

        private int corePoolSize = 2;

        private int maxPoolSize = 4;

        private int queueCapacity = 100;

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
