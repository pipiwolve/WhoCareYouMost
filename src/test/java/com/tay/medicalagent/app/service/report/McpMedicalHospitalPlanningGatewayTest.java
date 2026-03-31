package com.tay.medicalagent.app.service.report;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpMedicalHospitalPlanningGatewayTest {

    @Test
    void shouldOmitTypesWhenAroundSearchSchemaDoesNotSupportTypes() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(toolWithSchema("maps_around_search", "location", "radius", "keywords")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            assertFalse(request.arguments().containsKey("types"));
            return structuredResult(Map.of(
                    "pois", List.of(Map.of(
                            "id", "hospital-1",
                            "name", "广州市第一人民医院",
                            "address", "盘福路1号",
                            "location", "113.2644,23.1367",
                            "typecode", "090101",
                            "distance", 7800
                    ))
            ));
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                23.02171878809841,
                113.40747816629964,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "骨科周边规划",
                        "orthopedic",
                        "骨科医院",
                        "090101|090100|090102",
                        6000,
                        1,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals("广州市第一人民医院", summary.hospitals().get(0).name());
    }

    @Test
    void shouldIncludeTypesWhenAroundSearchSchemaExplicitlySupportsTypes() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(toolWithSchema("maps_around_search", "location", "radius", "keywords", "types")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            assertEquals("090101|090100|090102", request.arguments().get("types"));
            return structuredResult(Map.of(
                    "pois", List.of(
                            Map.of(
                                    "id", "hospital-1",
                                    "name", "广东省人民医院",
                                    "address", "中山二路106号",
                                    "location", "113.2975,23.1226",
                                    "typecode", "090101",
                                    "distance", 5200
                            ),
                            Map.of(
                                    "id", "hospital-2",
                                    "name", "中山大学附属第一医院",
                                    "address", "中山二路58号",
                                    "location", "113.2862,23.1293",
                                    "typecode", "090101",
                                    "distance", 5300
                            ),
                            Map.of(
                                    "id", "hospital-3",
                                    "name", "广州医科大学附属第一医院",
                                    "address", "沿江西路151号",
                                    "location", "113.2569,23.1154",
                                    "typecode", "090101",
                                    "distance", 5400
                            ),
                            Map.of(
                                    "id", "hospital-4",
                                    "name", "南方医科大学南方医院",
                                    "address", "广州大道北1838号",
                                    "location", "113.3256,23.1909",
                                    "typecode", "090101",
                                    "distance", 5500
                            ),
                            Map.of(
                                    "id", "hospital-5",
                                    "name", "广州市妇女儿童医疗中心",
                                    "address", "金穗路9号",
                                    "location", "113.3248,23.1232",
                                    "typecode", "090101",
                                    "distance", 5600
                            ),
                            Map.of(
                                    "id", "hospital-6",
                                    "name", "广东省第二人民医院",
                                    "address", "新港中路466号",
                                    "location", "113.3195,23.0977",
                                    "typecode", "090101",
                                    "distance", 5700
                            )
                    )
            ));
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
                        1,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals("广东省人民医院", summary.hospitals().get(0).name());
    }

    @Test
    void shouldShortCircuitNearbyFallbackWhenRateLimited() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger aroundCalls = new AtomicInteger();
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(toolWithSchema("maps_around_search", "location", "radius", "keywords", "types")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            aroundCalls.incrementAndGet();
            return errorResult("CUQPS_HAS_EXCEEDED_THE_LIMIT (10021)");
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        assertFalse(gateway.plan(
                23.021691207183693,
                113.40741384329999,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "骨科周边规划",
                        "orthopedic",
                        "骨科医院",
                        "090101|090100|090102",
                        6000,
                        3,
                        false
                ),
                mcpProperties()
        ).isPresent());
        assertEquals(1, aroundCalls.get());
    }

    @Test
    void shouldExitDeterministicPlanningWhenAroundSearchReturnsInvalidParams() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger aroundCalls = new AtomicInteger();
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(
                        toolWithSchema("maps_around_search", "location", "radius", "keywords", "types"),
                        toolWithSchema("maps_text_search", "keywords", "city", "types"),
                        toolWithSchema("maps_regeocode", "location")
                ),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            if (!"maps_around_search".equals(request.name())) {
                throw new IllegalStateException("Unexpected tool call: " + request.name());
            }
            aroundCalls.incrementAndGet();
            return errorResult("INVALID_PARAMS");
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        assertFalse(gateway.plan(
                23.021691207183693,
                113.40741384329999,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "骨科周边规划",
                        "orthopedic",
                        "骨科医院",
                        "090101|090100|090102",
                        6000,
                        3,
                        false
                ),
                mcpProperties()
        ).isPresent());
        assertEquals(1, aroundCalls.get());
    }

    @Test
    void shouldFallbackToCityTextSearchWhenNearbySearchIsRateLimitedBeforeGenericResults() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger aroundCalls = new AtomicInteger();
        AtomicInteger textCalls = new AtomicInteger();
        AtomicInteger detailCalls = new AtomicInteger();
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(
                        toolWithSchema("maps_around_search", "location", "radius", "keywords"),
                        toolWithSchema("maps_text_search", "keywords", "city", "types"),
                        toolWithSchema("maps_search_detail", "id"),
                        toolWithSchema("maps_regeocode", "location")
                ),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            return switch (request.name()) {
                case "maps_around_search" -> {
                    aroundCalls.incrementAndGet();
                    String keyword = String.valueOf(request.arguments().get("keywords"));
                    if ("综合医院".equals(keyword)) {
                        yield errorResult("CUQPS_HAS_EXCEEDED_THE_LIMIT (10021)");
                    }
                    yield structuredResult(Map.of("pois", List.of()));
                }
                case "maps_regeocode" -> structuredResult(Map.of(
                        "regeocode", Map.of(
                                "addressComponent", Map.of(
                                        "city", "广州市",
                                        "district", "天河区"
                                )
                        )
                ));
                case "maps_text_search" -> {
                    textCalls.incrementAndGet();
                    String keyword = String.valueOf(request.arguments().get("keywords"));
                    assertTrue("综合医院".equals(keyword) || "医院".equals(keyword));
                    assertEquals("广州市", request.arguments().get("city"));
                    assertEquals("090100|090101|090102|090400", request.arguments().get("types"));
                    yield structuredResult(Map.of(
                            "pois", List.of(
                                    missingLocationPoi("poi-1", "广州市第一人民医院", 0),
                                    missingLocationPoi("poi-2", "广东省人民医院", 0),
                                    missingLocationPoi("poi-3", "中山大学附属第一医院", 0)
                            )
                    ));
                }
                case "maps_search_detail" -> {
                    detailCalls.incrementAndGet();
                    String id = String.valueOf(request.arguments().get("id"));
                    yield switch (id) {
                        case "poi-1" -> structuredResult(Map.of("poi", Map.of("location", "113.2644,23.1367")));
                        case "poi-2" -> structuredResult(Map.of("poi", Map.of("location", "113.2975,23.1226")));
                        case "poi-3" -> structuredResult(Map.of("poi", Map.of("location", "113.2862,23.1293")));
                        default -> throw new IllegalStateException("Unexpected detail id: " + id);
                    };
                }
                default -> throw new IllegalStateException("Unexpected tool call: " + request.name());
            };
        });

        McpMedicalHospitalPlanningGateway gateway = new McpMedicalHospitalPlanningGateway(factory(client), new ObjectMapper());

        MedicalHospitalPlanningSummary summary = gateway.plan(
                23.021691207183693,
                113.40741384329999,
                new MedicalPlanningIntent(
                        true,
                        false,
                        "骨科周边规划",
                        "orthopedic",
                        "骨科医院",
                        "090100|090101|090102|090400",
                        6000,
                        3,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals(4, aroundCalls.get());
        assertEquals(1, textCalls.get());
        assertEquals(3, detailCalls.get());
        assertEquals(3, summary.hospitals().size());
        assertEquals("route_unavailable", summary.routeStatusCode());
        assertTrue(summary.hospitals().stream()
                .map(hospital -> hospital.name())
                .toList()
                .containsAll(List.of("广州市第一人民医院", "广东省人民医院", "中山大学附属第一医院")));
    }

    @Test
    void shouldKeepCollectedHospitalsWhenLaterNearbyFallbackHitsRateLimit() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger aroundCalls = new AtomicInteger();
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(toolWithSchema("maps_around_search", "location", "radius", "keywords")),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            int callIndex = aroundCalls.incrementAndGet();
            if (callIndex == 1) {
                return structuredResult(Map.of(
                        "pois", List.of(
                                locatedPoi("hospital-1", "广东省人民医院", 5200, "113.2975,23.1226"),
                                locatedPoi("hospital-2", "中山大学附属第一医院", 5300, "113.2862,23.1293"),
                                locatedPoi("hospital-3", "广州医科大学附属第一医院", 5400, "113.2569,23.1154")
                        )
                ));
            }
            assertEquals("心血管内科", request.arguments().get("keywords"));
            return errorResult("CUQPS_HAS_EXCEEDED_THE_LIMIT (10021)");
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
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals(3, summary.hospitals().size());
        assertEquals("route_unavailable", summary.routeStatusCode());
        assertEquals(2, aroundCalls.get());
    }

    @Test
    void shouldLimitDetailEnrichmentToTopWindowCandidates() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger detailCalls = new AtomicInteger();
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(
                        toolWithSchema("maps_around_search", "location", "radius", "keywords", "types"),
                        toolWithSchema("maps_search_detail", "id")
                ),
                null
        ));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            return switch (request.name()) {
                case "maps_around_search" -> structuredResult(Map.of(
                        "pois", List.of(
                                missingLocationPoi("poi-1", "医院1", 100),
                                missingLocationPoi("poi-2", "医院2", 200),
                                missingLocationPoi("poi-3", "医院3", 300),
                                missingLocationPoi("poi-4", "医院4", 400),
                                missingLocationPoi("poi-5", "医院5", 500),
                                missingLocationPoi("poi-6", "医院6", 600),
                                missingLocationPoi("poi-7", "医院7", 700),
                                missingLocationPoi("poi-8", "医院8", 800)
                        )
                ));
                case "maps_search_detail" -> {
                    detailCalls.incrementAndGet();
                    String id = String.valueOf(request.arguments().get("id"));
                    yield structuredResult(Map.of("poi", Map.of("location", "113.30" + id.charAt(id.length() - 1) + ",23.10" + id.charAt(id.length() - 1))));
                }
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
                        "普通就医规划",
                        "default",
                        "医院",
                        "090101|090100|090102",
                        8000,
                        2,
                        false
                ),
                mcpProperties()
        ).orElseThrow();

        assertEquals(2, summary.hospitals().size());
        assertEquals(6, detailCalls.get());
    }

    @Test
    void shouldAggregateDetailEnrichmentFailuresWithoutPerItemWarnLogs() {
        McpSyncClient client = mock(McpSyncClient.class);
        Logger logger = (Logger) LoggerFactory.getLogger(McpMedicalHospitalPlanningGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("mcp-server/amap-maps", "0.1.0"));
            when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                    List.of(
                            toolWithSchema("maps_around_search", "location", "radius", "keywords", "types"),
                            toolWithSchema("maps_search_detail", "id")
                    ),
                    null
            ));
            when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
                McpSchema.CallToolRequest request = invocation.getArgument(0);
                return switch (request.name()) {
                    case "maps_around_search" -> structuredResult(Map.of(
                            "pois", List.of(
                                    missingLocationPoi("poi-1", "医院1", 100),
                                    missingLocationPoi("poi-2", "医院2", 200),
                                    missingLocationPoi("poi-3", "医院3", 300)
                            )
                    ));
                    case "maps_search_detail" -> errorResult("SERVER_IS_BUSY");
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
                            "普通就医规划",
                            "default",
                            "医院",
                            "090101|090100|090102",
                            8000,
                            2,
                            false
                    ),
                    mcpProperties()
            ).orElseThrow();

            assertFalse(summary.hospitals().isEmpty());
            assertTrue(appender.list.stream()
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)
                            && event.getFormattedMessage().contains("tool=maps_search_detail")));
            assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("MCP detail enrichment finished with partial misses")));
        }
        finally {
            logger.detachAppender(appender);
        }
    }

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

    private static McpSchema.Tool toolWithSchema(String name, String... properties) {
        return new McpSchema.Tool(
                name,
                null,
                null,
                new McpSchema.JsonSchema(
                        "object",
                        propertyMap(properties),
                        List.of(),
                        Boolean.FALSE,
                        Map.of(),
                        Map.of()
                ),
                null,
                null,
                null
        );
    }

    private static McpSchema.CallToolResult structuredResult(Object payload) {
        return new McpSchema.CallToolResult(List.of(), false, payload, Map.of());
    }

    private static McpSchema.CallToolResult errorResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), true, null, Map.of());
    }

    private static Map<String, Object> propertyMap(String... names) {
        java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String name : names) {
            properties.put(name, Map.of("type", "string"));
        }
        return properties;
    }

    private static Map<String, Object> missingLocationPoi(String id, String name, int distance) {
        return Map.of(
                "id", id,
                "name", name,
                "address", name + "地址",
                "typecode", "090101",
                "distance", distance
        );
    }

    private static Map<String, Object> locatedPoi(String id, String name, int distance, String location) {
        return Map.of(
                "id", id,
                "name", name,
                "address", name + "地址",
                "typecode", "090101",
                "distance", distance,
                "location", location
        );
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
