package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpMedicalHospitalPlanningGatewayTest {

    @Test
    void shouldDeprioritizeCommunityFacilitiesWhenPreferringTier3aHospitals() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("maps_around_search")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            if ("maps_around_search".equals(request.name())) {
                return structuredResult(Map.of(
                        "pois", List.of(
                                Map.of(
                                        "id", "community-1",
                                        "name", "黄浦区社区卫生服务中心",
                                        "address", "成都北路290号",
                                        "location", "121.4738,31.2310",
                                        "typecode", "090102",
                                        "distance", 493
                                ),
                                Map.of(
                                        "id", "hospital-1",
                                        "name", "上海长征医院",
                                        "address", "凤阳路415号",
                                        "location", "121.4700,31.2330",
                                        "typecode", "090101",
                                        "distance", 629
                                ),
                                Map.of(
                                        "id", "hospital-2",
                                        "name", "上海交通大学医学院附属瑞金医院",
                                        "address", "瑞金二路197号",
                                        "location", "121.4680,31.2100",
                                        "typecode", "090101",
                                        "tag", "三级甲等医院",
                                        "distance", 2332
                                )
                        )
                ));
            }
            throw new IllegalStateException("Unexpected tool call: " + request.name());
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                31.2304,
                121.4737,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "高风险胸闷场景",
                        "cardiac",
                        "心血管医院",
                        "090100|090101|090102",
                        8000,
                        3,
                        true
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals(
                List.of("上海交通大学医学院附属瑞金医院", "上海长征医院", "黄浦区社区卫生服务中心"),
                summary.hospitals().stream().map(hospital -> hospital.name()).toList()
        );
        assertFalse(summary.routesAvailable());
        assertEquals("route_unavailable", summary.routeStatusCode());
    }

    @Test
    void shouldFallbackToDistrictWhenRegeocodeCityIsEmptyArray() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(
                        tool("maps_around_search"),
                        tool("maps_regeocode"),
                        tool("maps_direction_transit_integrated")
                ),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            return switch (request.name()) {
                case "maps_around_search" -> structuredResult(Map.of(
                        "pois", List.of(Map.of(
                                "id", "hospital-1",
                                "name", "上海长征医院",
                                "address", "凤阳路415号",
                                "location", "121.4700,31.2330",
                                "typecode", "090101",
                                "distance", 629
                        ))
                ));
                case "maps_regeocode" -> structuredResult(Map.of(
                        "regeocode", Map.of(
                                "addressComponent", Map.of(
                                        "city", List.of(),
                                        "district", "上海市"
                                )
                        )
                ));
                case "maps_direction_transit_integrated" -> {
                    assertEquals("上海市", request.arguments().get("city"));
                    assertEquals("上海市", request.arguments().get("cityd"));
                    yield structuredResult(Map.of(
                            "route", Map.of(
                                    "distance", 1000,
                                    "transits", List.of(Map.of("duration", 600))
                            )
                    ));
                }
                default -> throw new IllegalStateException("Unexpected tool call: " + request.name());
            };
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                31.2304,
                121.4737,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "常规规划",
                        "default",
                        "医院",
                        "090100|090101",
                        5000,
                        1,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertTrue(summary.routesAvailable());
        assertEquals("ok", summary.routeStatusCode());
        assertEquals("TRANSIT", summary.hospitals().get(0).routes().get(0).mode());
    }

    @Test
    void shouldRelaxNearbySearchWhenCardiacKeywordReturnsEmpty() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("maps_around_search")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            if (!"maps_around_search".equals(request.name())) {
                throw new IllegalStateException("Unexpected tool call: " + request.name());
            }
            String keyword = String.valueOf(request.arguments().get("keywords"));
            return switch (keyword) {
                case "心血管医院" -> structuredResult(Map.of("pois", List.of()));
                case "心血管内科" -> structuredResult(Map.of(
                        "pois", List.of(Map.of(
                                "id", "hospital-1",
                                "name", "广东省人民医院",
                                "address", "中山二路106号",
                                "location", "113.2975,23.1226",
                                "typecode", "090101",
                                "distance", 5200
                        ))
                ));
                default -> structuredResult(Map.of("pois", List.of()));
            };
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                23.02171878809841,
                113.40747816629964,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "胸闷胸痛场景",
                        "cardiac",
                        "心血管医院",
                        "090101|090100|090102",
                        8000,
                        3,
                        true
                ),
                mcpProperties()
        ).orElseThrow();

        assertFalse(summary.hospitals().isEmpty());
        assertEquals("广东省人民医院", summary.hospitals().get(0).name());
        assertEquals("route_unavailable", summary.routeStatusCode());
    }

    @Test
    void shouldRelaxToGenericHospitalSearchWithoutTypes() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("maps_around_search")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            if (!"maps_around_search".equals(request.name())) {
                throw new IllegalStateException("Unexpected tool call: " + request.name());
            }
            String keyword = String.valueOf(request.arguments().get("keywords"));
            Object types = request.arguments().get("types");
            if ("医院".equals(keyword) && types == null) {
                return structuredResult(Map.of(
                        "pois", List.of(Map.of(
                                "id", "hospital-2",
                                "name", "广州市第一人民医院",
                                "address", "盘福路1号",
                                "location", "113.2644,23.1367",
                                "typecode", "090101",
                                "distance", 7800
                        ))
                ));
            }
            return structuredResult(Map.of("pois", List.of()));
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                23.02171878809841,
                113.40747816629964,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "口腔症状但附近专科较少",
                        "dental",
                        "口腔医院",
                        "090101|090100|090102",
                        5000,
                        3,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertFalse(summary.hospitals().isEmpty());
        assertEquals("广州市第一人民医院", summary.hospitals().get(0).name());
        assertEquals("route_unavailable", summary.routeStatusCode());
    }

    @Test
    void shouldExtractDetailedStepsFromWalkingAndTransitPayloads() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(
                        tool("maps_around_search"),
                        tool("maps_direction_walking"),
                        tool("maps_direction_transit_integrated"),
                        tool("maps_regeocode")
                ),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            return switch (request.name()) {
                case "maps_around_search" -> structuredResult(Map.of(
                        "pois", List.of(Map.of(
                                "id", "hospital-1",
                                "name", "广东省中医院大学城医院急诊",
                                "address", "大学城内环西路55号",
                                "location", "113.3970,23.0580",
                                "typecode", "090101",
                                "distance", 1800
                        ))
                ));
                case "maps_direction_walking" -> structuredResult(Map.of(
                        "route", Map.of(
                                "paths", List.of(Map.of(
                                        "distance", 1800,
                                        "duration", 1260,
                                        "steps", List.of(
                                                Map.of("instruction", "向西步行120米后右转"),
                                                Map.of("instruction", "沿大学城中路步行1.7公里到达目的地")
                                        )
                                ))
                        )
                ));
                case "maps_regeocode" -> structuredResult(Map.of(
                        "regeocode", Map.of(
                                "addressComponent", Map.of(
                                        "city", "广州市",
                                        "district", "番禺区"
                                )
                        )
                ));
                case "maps_direction_transit_integrated" -> structuredResult(Map.of(
                        "route", Map.of(
                                "distance", 2100,
                                "transits", List.of(Map.of(
                                        "duration", 1500,
                                        "segments", List.of(
                                                Map.of(
                                                        "walking", Map.of(
                                                                "distance", 150,
                                                                "steps", List.of(Map.of("instruction", "步行150米到达新造站"))
                                                        ),
                                                        "bus", Map.of(
                                                                "buslines", List.of(Map.of(
                                                                        "name", "地铁4号线(黄村方向)",
                                                                        "departure_stop", Map.of("name", "新造站"),
                                                                        "arrival_stop", Map.of("name", "大学城北站"),
                                                                        "via_num", "1"
                                                                ))
                                                        )
                                                ),
                                                Map.of(
                                                        "walking", Map.of(
                                                                "distance", 320,
                                                                "steps", List.of(Map.of("instruction", "步行320米到达医院"))
                                                        )
                                                )
                                        )
                                ))
                        )
                ));
                default -> throw new IllegalStateException("Unexpected tool call: " + request.name());
            };
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                23.02171878809841,
                113.40747816629964,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "急诊规划",
                        "emergency",
                        "急诊",
                        "090101|090100|090102",
                        10000,
                        1,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertTrue(summary.routesAvailable());
        assertEquals(1, summary.hospitals().size());
        assertEquals(
                List.of("向西步行120米后右转", "沿大学城中路步行1.7公里到达目的地"),
                summary.hospitals().get(0).routes().stream()
                        .filter(route -> "WALK".equals(route.mode()))
                        .findFirst()
                        .orElseThrow()
                        .steps()
        );
        assertEquals(
                List.of(
                        "步行150米到达新造站",
                        "乘坐 地铁4号线(黄村方向)，从新造站上车，在大学城北站下车（1站）",
                        "步行320米到达医院"
                ),
                summary.hospitals().get(0).routes().stream()
                        .filter(route -> "TRANSIT".equals(route.mode()))
                        .findFirst()
                        .orElseThrow()
                        .steps()
        );
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, null, null, null, null, null);
    }

    private static McpSchema.CallToolResult structuredResult(Object payload) {
        return new McpSchema.CallToolResult(List.of(), false, payload, Map.of());
    }

    private static AmapMcpClientFactory factory(McpSyncClient client) {
        return mcpProperties -> new AmapMcpClientFactory.AmapMcpClientHandle() {
            @Override
            public McpSyncClient client() {
                return client;
            }

            @Override
            public void close() {
                // no-op for mocked handle
            }
        };
    }

    private static MedicalReportPlanningProperties.Mcp mcpProperties() {
        MedicalReportPlanningProperties.Mcp properties = new MedicalReportPlanningProperties.Mcp();
        properties.setServerName("mcp-server/amap-maps");
        properties.setTimeoutMs(6000);
        properties.setAroundRadiusMeters(5000);
        properties.setHospitalKeyword("医院");
        properties.setHospitalTypes("090100|090101|090102|090400");
        return properties;
    }
}
