package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认独立 AMap MCP client 工厂。
 */
@Component
public class DefaultAmapMcpClientFactory implements AmapMcpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultAmapMcpClientFactory.class);
    private static final String CONNECTION_PREFIX = "spring.ai.mcp.client.stdio.connections.amapproxy";

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public DefaultAmapMcpClientFactory(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @Override
    public AmapMcpClientHandle create(MedicalReportPlanningProperties.Mcp mcpProperties) {
        String command = resolveCommand();
        List<String> args = resolveArgs();
        Map<String, String> env = resolveEnv();

        ServerParameters serverParameters = ServerParameters.builder(command)
                .args(args)
                .env(env)
                .build();
        StdioClientTransport transport = new StdioClientTransport(
                serverParameters,
                new JacksonMcpJsonMapper(objectMapper.copy())
        );
        transport.setStdErrorHandler(message -> log.debug("AMap MCP stderr: {}", message));

        Duration timeout = Duration.ofMillis(Math.max(1000L, mcpProperties == null ? 6000L : mcpProperties.getTimeoutMs()));
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        client.initialize();
        return new DefaultAmapMcpClientHandle(client);
    }

    private String resolveCommand() {
        return normalize(environment.getProperty(CONNECTION_PREFIX + ".command", "node"));
    }

    private List<String> resolveArgs() {
        List<String> args = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String value = environment.getProperty(CONNECTION_PREFIX + ".args[" + index + "]");
            if (value == null || value.isBlank()) {
                if (index == 0) {
                    continue;
                }
                break;
            }
            args.add(normalize(value));
        }
        if (args.isEmpty()) {
            args.add(normalize(environment.getProperty(
                    "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAPPROXY_ARGS_0",
                    System.getProperty("user.dir") + "/scripts/amap-mcp-stdio-proxy.js"
            )));
            args.add(normalize(environment.getProperty(
                    "SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAPPROXY_ARGS_1",
                    "@amap/amap-maps-mcp-server"
            )));
        }
        return args;
    }

    private Map<String, String> resolveEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        String apiKey = environment.getProperty(
                CONNECTION_PREFIX + ".env.AMAP_MAPS_API_KEY",
                environment.getProperty("AMAP_MAPS_API_KEY", System.getenv("AMAP_MAPS_API_KEY"))
        );
        if (apiKey != null && !apiKey.isBlank()) {
            env.put("AMAP_MAPS_API_KEY", normalize(apiKey));
        }
        String npxCommand = environment.getProperty("NPX_COMMAND");
        if (npxCommand != null && !npxCommand.isBlank()) {
            env.put("NPX_COMMAND", normalize(npxCommand));
        }
        return env;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            boolean doubleQuoted = trimmed.startsWith("\"") && trimmed.endsWith("\"");
            boolean singleQuoted = trimmed.startsWith("'") && trimmed.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static final class DefaultAmapMcpClientHandle implements AmapMcpClientHandle {

        private final McpSyncClient client;

        private DefaultAmapMcpClientHandle(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public McpSyncClient client() {
            return client;
        }

        @Override
        public void close() {
            try {
                client.closeGracefully();
            }
            catch (Exception ignore) {
                // ignore graceful-close failures and continue to hard close
            }
            try {
                client.close();
            }
            catch (Exception ignore) {
                // ignore hard-close failures
            }
        }
    }
}
