package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMedicalReportSnapshotServiceTest {

    @Test
    void shouldReuseFreshSnapshotWithoutRebuildingReportAndPlanning() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        MedicalHospitalPlanningSummary planningSummary = new MedicalHospitalPlanningSummary(List.of(), false, "location_missing", "location_missing");

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent)).thenReturn(planningSummary);

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties(),
                Clock.systemUTC()
        );

        var first = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        var second = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        assertSame(first, second);
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void shouldReuseExistingReportWhenOnlyLocationChanges() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), false, "location_missing", "location_missing"));
        when(planningService.plan(31.23, 121.47, report, planningIntent))
                .thenReturn(MedicalHospitalPlanningSummary.empty());

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties(),
                Clock.systemUTC()
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.23, 121.47);

        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
        verify(planningService, times(1)).plan(31.23, 121.47, report, planningIntent);
    }

    @Test
    void shouldRebuildSnapshotWhenConversationFingerprintChanges() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        MedicalHospitalPlanningSummary planningSummary = MedicalHospitalPlanningSummary.empty();

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of()))
                .thenReturn("user:胸痛\nassistant:建议尽快就医")
                .thenReturn("user:胸痛加重\nassistant:建议立即急诊");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent)).thenReturn(planningSummary);

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties(),
                Clock.systemUTC()
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        verify(reportService, times(2)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(2)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void shouldRetryOnlyPlanningWhenFreshSnapshotIsDegradedAfterRetryWindow() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties snapshotProperties = snapshotProperties();
        snapshotProperties.setDegradedPlanningRetryAfter(Duration.ofSeconds(30));

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), false, "MCP 路线服务暂不可用", "mcp_unavailable"))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), false, "路线查询超时", "route_timeout"));

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties,
                clock
        );

        var first = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        clock.plus(Duration.ofSeconds(31));
        var second = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        assertEquals("mcp_unavailable", first.planningSummary().routeStatusCode());
        assertEquals("route_timeout", second.planningSummary().routeStatusCode());
        assertNotSame(first, second);
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(2)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void shouldNotRetryPlanningForNonRetryableDegradedStatuses() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties snapshotProperties = snapshotProperties();
        snapshotProperties.setDegradedPlanningRetryAfter(Duration.ofSeconds(30));

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度", "location_missing"));

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties,
                clock
        );

        var first = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        clock.plus(Duration.ofSeconds(31));
        var second = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        assertSame(first, second);
        assertEquals("location_missing", second.planningSummary().routeStatusCode());
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void findFreshSnapshotShouldReturnEmptyWhenDegradedPlanningNeedsRetry() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties snapshotProperties = snapshotProperties();
        snapshotProperties.setDegradedPlanningRetryAfter(Duration.ofSeconds(30));

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), false, "MCP 路线服务暂不可用", "mcp_unavailable"));

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties,
                clock
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        clock.plus(Duration.ofSeconds(31));

        Optional<com.tay.medicalagent.app.report.MedicalReportSnapshot> freshSnapshot = service.findFreshSnapshot(
                "sess_snapshot",
                "thread_snapshot",
                "usr_snapshot",
                31.2,
                121.4
        );

        assertTrue(freshSnapshot.isEmpty());
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void findFreshSnapshotShouldReturnPresentWhenSnapshotIsFreshAndUsable() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenReturn(MedicalHospitalPlanningSummary.empty());

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties(),
                Clock.systemUTC()
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        Optional<com.tay.medicalagent.app.report.MedicalReportSnapshot> freshSnapshot = service.findFreshSnapshot(
                "sess_snapshot",
                "thread_snapshot",
                "usr_snapshot",
                31.2,
                121.4
        );

        assertFalse(freshSnapshot.isEmpty());
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void shouldCleanupStaleLocksAfterCleanupWindow() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );

        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties snapshotProperties = snapshotProperties();
        snapshotProperties.setCleanupInterval(Duration.ofSeconds(1));
        snapshotProperties.setStaleLockTtl(Duration.ofSeconds(1));

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent)).thenReturn(MedicalHospitalPlanningSummary.empty());

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties,
                clock
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        assertTrue(service.snapshotLockCount() >= 1);

        clock.plus(Duration.ofSeconds(2));
        service.cleanupStaleLocksForTesting();

        assertEquals(0, service.snapshotLockCount());
    }

    @Test
    void invalidateShouldPreventOlderBuildFromOverwritingNewerSnapshot() throws Exception {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        CountDownLatch firstPlanningStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPlanning = new CountDownLatch(1);

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent))
                .thenAnswer(invocation -> {
                    firstPlanningStarted.countDown();
                    assertTrue(releaseFirstPlanning.await(1, TimeUnit.SECONDS));
                    return new MedicalHospitalPlanningSummary(List.of(), false, "旧路线结果", "route_timeout");
                });
        when(planningService.plan(31.23, 121.47, report, planningIntent))
                .thenReturn(new MedicalHospitalPlanningSummary(List.of(), true, "", "ok"));

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver,
                snapshotProperties(),
                Clock.systemUTC()
        );

        CompletableFuture<MedicalReportSnapshot> oldBuild = CompletableFuture.supplyAsync(
                () -> service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4)
        );
        assertTrue(firstPlanningStarted.await(1, TimeUnit.SECONDS));

        service.invalidate("sess_snapshot");
        MedicalReportSnapshot newSnapshot = service.getOrCreateSnapshot(
                "sess_snapshot",
                "thread_snapshot",
                "usr_snapshot",
                31.23,
                121.47
        );
        releaseFirstPlanning.countDown();
        oldBuild.get(1, TimeUnit.SECONDS);

        MedicalReportSnapshot storedSnapshot = repository.findBySessionId("sess_snapshot").orElseThrow();
        assertEquals("ok", newSnapshot.planningSummary().routeStatusCode());
        assertEquals("ok", storedSnapshot.planningSummary().routeStatusCode());
        assertEquals(newSnapshot.locationFingerprint(), storedSnapshot.locationFingerprint());
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
        verify(planningService, times(1)).plan(31.23, 121.47, report, planningIntent);
    }

    private MedicalReportSnapshotProperties snapshotProperties() {
        MedicalReportSnapshotProperties properties = new MedicalReportSnapshotProperties();
        properties.setTtl(Duration.ofMinutes(30));
        properties.setCleanupInterval(Duration.ofMinutes(5));
        properties.setStaleLockTtl(Duration.ofSeconds(60));
        properties.setDegradedPlanningRetryAfter(Duration.ofSeconds(30));
        properties.setMaxSessions(500);
        return properties;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plus(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private MedicalDiagnosisReport exportableReport() {
        return new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "胸痛",
                "建议尽快线下评估",
                "",
                List.of("胸痛"),
                List.of("尽快就医"),
                List.of("胸痛持续加重"),
                "建议尽快线下评估"
        );
    }
}
