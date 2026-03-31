package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.support.LiveTestSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AmapMcpServerCompatibilityLiveIT {

    private static final Logger log = LoggerFactory.getLogger(AmapMcpServerCompatibilityLiveIT.class);

    @Autowired(required = false)
    private List<McpSyncClient> mcpSyncClients;

    @DynamicPropertySource
    static void registerMcpProperties(DynamicPropertyRegistry registry) {
        String mcpCommand = System.getProperty("amap.mcp.command", "npx");
        String apiKey = LiveTestSupport.requireAmapApiKey();

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

        McpSchema.Tool aroundSearchTool = mcpSyncClients.get(0)
                .listTools()
                .tools()
                .stream()
                .filter(tool -> "maps_around_search".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(
                aroundSearchTool.inputSchema() != null
                        && aroundSearchTool.inputSchema().properties() != null
                        && aroundSearchTool.inputSchema().properties().containsKey("location"),
                "maps_around_search schema should expose location property"
        );
        log.info(
                "Live MCP around-search schema properties: {}",
                aroundSearchTool.inputSchema().properties().keySet()
        );
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
