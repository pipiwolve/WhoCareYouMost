package com.tay.medicalagent.controller.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.support.LiveTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class MedicalReportEndToEndLiveIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerLiveProperties(DynamicPropertyRegistry registry) {
        String mcpCommand = System.getProperty("amap.mcp.command", "npx");
        String amapApiKey = LiveTestSupport.requireAmapApiKey();
        LiveTestSupport.requireDashScopeApiKey();

        registry.add("medical.rag.enabled", () -> false);
        registry.add("spring.ai.mcp.client.enabled", () -> true);
        registry.add("spring.ai.mcp.client.initialized", () -> true);
        registry.add("spring.ai.mcp.client.type", () -> "SYNC");
        registry.add("spring.ai.mcp.client.toolcallback.enabled", () -> true);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.command", () -> mcpCommand);
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[0]", () -> "-y");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.args[1]", () -> "@amap/amap-maps-mcp-server");
        registry.add("spring.ai.mcp.client.stdio.connections.amapproxy.env.AMAP_MAPS_API_KEY", () -> amapApiKey);
        registry.add("medical.report.planning.enabled", () -> true);
        registry.add("medical.report.planning.mode", () -> "mcp");
        registry.add("medical.report.planning.top-k", () -> 2);
    }

    @Test
    void shouldCompleteChatReportQueryAndPdfExportWithRealServices() throws Exception {
        MvcResult profileResult = mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "夜跑用户",
                                  "age": 32,
                                  "gender": "MALE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andReturn();

        JsonNode profileBody = objectMapper.readTree(profileResult.getResponse().getContentAsString());
        String sessionId = profileBody.path("data").path("sessionId").asText();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "%s",
                                  "message": "我32岁，男性，持续胸痛20分钟，伴出汗和恶心，请先判断风险并告诉我是否需要立刻就医。"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());

        mockMvc.perform(post("/v1/reports/{sessionId}/location", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "latitude": 23.02171878809841,
                                  "longitude": 113.40747816629964,
                                  "consentGranted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        MvcResult reportResult = awaitReadyReport(sessionId);

        JsonNode reportBody = objectMapper.readTree(reportResult.getResponse().getContentAsString());
        assertFalse(reportBody.path("data").path("report").path("hospitals").isEmpty());

        mockMvc.perform(get("/v1/reports/{sessionId}/pdf", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().string(containsString("%PDF")));
    }

    private MvcResult awaitReadyReport(String sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        MvcResult lastResult = null;

        while (System.nanoTime() < deadline) {
            lastResult = mockMvc.perform(get("/v1/reports/{sessionId}", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("OK"))
                    .andReturn();

            JsonNode body = objectMapper.readTree(lastResult.getResponse().getContentAsString());
            JsonNode data = body.path("data");
            if (data.path("ready").asBoolean(false)) {
                assertEquals("ready", data.path("status").asText());
                assertTrue(data.path("report").path("title").isTextual());
                assertFalse(data.path("report").path("title").asText().isBlank());
                return lastResult;
            }

            long retryAfterMs = Math.max(500L, data.path("retryAfterMs").asLong(1000L));
            Thread.sleep(Math.min(retryAfterMs, 3000L));
        }

        if (lastResult == null) {
            throw new AssertionError("Report query did not return a response");
        }

        JsonNode lastBody = objectMapper.readTree(lastResult.getResponse().getContentAsString());
        throw new AssertionError("Report was not ready before timeout. lastPayload=" + lastBody);
    }
}
