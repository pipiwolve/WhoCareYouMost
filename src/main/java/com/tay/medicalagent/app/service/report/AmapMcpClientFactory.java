package com.tay.medicalagent.app.service.report;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 独立 AMap MCP client 工厂。
 * <p>
 * 为规划 Agent 与 deterministic gateway 提供短生命周期 client，
 * 避免共享 stdio transport 带来的并发订阅问题。
 */
public interface AmapMcpClientFactory {

    AmapMcpClientHandle create(MedicalReportPlanningProperties.Mcp mcpProperties);

    interface AmapMcpClientHandle extends AutoCloseable {

        McpSyncClient client();

        @Override
        void close();
    }
}
