package com.tay.medicalagent.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "medical.web")
/**
 * Web 层公共配置。
 * <p>
 * 当前主要承载前后端联调需要的 CORS 白名单配置。
 */
public class MedicalWebProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://localhost:5173"
    ));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
}
