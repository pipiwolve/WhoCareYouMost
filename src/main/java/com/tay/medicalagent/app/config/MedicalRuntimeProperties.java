package com.tay.medicalagent.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "medical.runtime")
public class MedicalRuntimeProperties {

    private String environment = RuntimeEnvironment.PROD.value;

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public RuntimeEnvironment resolvedEnvironment() {
        String candidate = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        for (RuntimeEnvironment runtimeEnvironment : RuntimeEnvironment.values()) {
            if (runtimeEnvironment.value.equals(candidate)) {
                return runtimeEnvironment;
            }
        }
        return RuntimeEnvironment.PROD;
    }

    public boolean isLocalLike() {
        return resolvedEnvironment() == RuntimeEnvironment.LOCAL;
    }

    public enum RuntimeEnvironment {
        LOCAL("local"),
        GRAY("gray"),
        PROD("prod");

        private final String value;

        RuntimeEnvironment(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}

