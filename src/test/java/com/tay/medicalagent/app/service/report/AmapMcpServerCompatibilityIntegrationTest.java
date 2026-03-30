package com.tay.medicalagent.app.service.report;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real compatibility check against @amap/amap-maps-mcp-server.
 *
 * Run with:
 * -Damap.mcp.it=true
 * -Damap.mcp.command=npx
 * -Damap.mcp.api-key=<your-key-or-placeholder>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "amap.mcp.it", matches = "true")
class AmapMcpServerCompatibilityIntegrationTest {

    @Autowired(required = false)
    private List<McpSyncClient> mcpSyncClients;

    @DynamicPropertySource
    static void registerMcpProperties(DynamicPropertyRegistry registry) {
        String mcpCommand = System.getProperty("amap.mcp.command", "npx");
        String apiKey = System.getProperty("amap.mcp.api-key", "dummy-key");

        registry.add("spring.ai.mcp.client.enabled", () -> true);
        registry.add("spring.ai.mcp.client.initialized", () -> true);
        registry.add("spring.ai.mcp.client.type", () -> "SYNC");
        registry.add("spring.ai.mcp.client.toolcallback.enabled", () -> true);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.command", () -> mcpCommand);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[0]", () -> "-y");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[1]", () -> "@amap/amap-maps-mcp-server");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.env.AMAP_MAPS_API_KEY", () -> apiKey);
    }

    @Test
    void shouldDiscoverRequiredToolsFromAmapNpmServer() {
        assertFalse(mcpSyncClients == null || mcpSyncClients.isEmpty(), "MCP sync clients should be initialized");

        List<String> toolNames = mcpSyncClients.get(0)
                .listTools()
                .tools()
                .stream()
                .map(McpSchema.Tool::name)
                .toList();

        assertTrue(containsAny(toolNames, "maps_around_search", "around", "nearby"));
        assertTrue(containsAny(toolNames, "maps_direction_walking_by_coordinates", "walking", "walk"));
        assertTrue(containsAny(toolNames, "maps_direction_driving_by_coordinates", "driving", "drive"));
        assertTrue(containsAny(toolNames, "maps_direction_transit_integrated_by_coordinates", "transit", "bus"));
        assertTrue(containsAny(toolNames, "maps_regeocode", "regeocode", "geocode"));
    }

    private static boolean containsAny(List<String> toolNames, String exactName, String... aliases) {
        if (toolNames.contains(exactName)) {
            return true;
        }
        for (String toolName : toolNames) {
            String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
                if (normalized.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
