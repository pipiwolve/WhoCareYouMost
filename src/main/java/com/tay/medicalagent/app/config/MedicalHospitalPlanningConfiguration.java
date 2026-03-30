package com.tay.medicalagent.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

@Configuration
public class MedicalHospitalPlanningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MedicalHospitalPlanningConfiguration.class);
    private static final List<String> ALLOWED_TOOL_NAMES = List.of(
            "maps_around_search",
            "maps_search_detail",
            "maps_regeocode",
            "maps_direction_walking",
            "maps_direction_driving",
            "maps_direction_transit_integrated"
    );
    private static final List<String> LEGACY_ENV_VARS = List.of(
            "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_COMMAND",
            "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_0",
            "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_1",
            "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_2",
            "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ENV_AMAP_MAPS_API_KEY"
    );

    @Bean
    public McpToolFilter medicalAmapPlanningMcpToolFilter() {
        return (connectionInfo, tool) -> {
            String toolName = tool == null || tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
            for (String allowedToolName : ALLOWED_TOOL_NAMES) {
                if (toolName.contains(allowedToolName.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        };
    }

    @Bean
    public McpToolNamePrefixGenerator medicalAmapPlanningToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }

    @Bean
    public ApplicationRunner legacyAmapEnvironmentWarningRunner() {
        return args -> {
            boolean foundLegacyEnv = false;
            for (String legacyEnvVar : LEGACY_ENV_VARS) {
                String value = System.getenv(legacyEnvVar);
                if (value == null || value.isBlank()) {
                    continue;
                }
                foundLegacyEnv = true;
                log.warn("Detected legacy AMap MCP env var: {}. It has been replaced by the AMAPPROXY npx configuration.", legacyEnvVar);
            }
            if (foundLegacyEnv) {
                log.warn("Legacy AMap MCP env vars are ignored. Prefer SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAPPROXY_* or AMAP_MAPS_API_KEY.");
            }
        };
    }
}
