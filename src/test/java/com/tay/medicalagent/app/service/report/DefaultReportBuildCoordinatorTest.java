package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportBuildState;
import com.tay.medicalagent.app.report.MedicalReportContextSnapshot;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultReportBuildCoordinatorTest {

    @Test
    void shouldJoinWarmUpAndQueryIntoSingleBuild() throws Exception {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(snapshotProperties());
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);
        MedicalReportBuildProperties properties = buildProperties(1000, 1000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            MedicalDiagnosisReport report = exportableSnapshot().report();
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
            when(threadConversationService.getThreadConversation("thread_build")).thenReturn(List.of());
            when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
            when(userProfileService.buildProfileContext("usr_build")).thenReturn("姓名：测试用户");
            when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);

            DefaultMedicalReportSnapshotService snapshotService = new DefaultMedicalReportSnapshotService(
                    repository,
                    reportService,
                    planningService,
                    threadConversationService,
                    userProfileService,
                    planningIntentResolver,
                    snapshotProperties(),
                    Clock.systemUTC()
            );
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            CountDownLatch buildStarted = new CountDownLatch(1);
            CountDownLatch releaseBuild = new CountDownLatch(1);

            when(reportService.generateReportFromThread("thread_build", "usr_build"))
                    .thenAnswer(invocation -> {
                        buildStarted.countDown();
                        assertTrue(releaseBuild.await(5, TimeUnit.SECONDS));
                        return report;
                    });
            when(planningService.plan(31.23, 121.47, report, planningIntent))
                    .thenReturn(MedicalHospitalPlanningSummary.empty());

            coordinator.warmUpFinalReport("sess_build", "thread_build", "usr_build", 31.23, 121.47, null);
            assertTrue(buildStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, coordinator.inFlightCount());

            CompletableFuture<MedicalReportBuildState> queryFuture = CompletableFuture.supplyAsync(
                    () -> coordinator.getOrStartFinalReport("sess_build", "thread_build", "usr_build", 31.23, 121.47)
            );
            releaseBuild.countDown();

            MedicalReportBuildState buildState = queryFuture.get(1, TimeUnit.SECONDS);

            assertTrue(buildState.ready());
            assertEquals(MedicalReportBuildState.STATUS_READY, buildState.status());
            assertEquals(0, coordinator.inFlightCount());
            verify(reportService, times(1)).generateReportFromThread("thread_build", "usr_build");
            verify(planningService, times(1)).plan(31.23, 121.47, report, planningIntent);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnGeneratingWhenQueryWaitTimeoutIsReached() throws Exception {
        MedicalReportSnapshotService snapshotService = mock(MedicalReportSnapshotService.class);
        MedicalReportBuildProperties properties = buildProperties(50, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            MedicalReportContextSnapshot context = new MedicalReportContextSnapshot("conversation", "profile", "location");
            CountDownLatch releaseBuild = new CountDownLatch(1);

            when(snapshotService.findFreshSnapshot("sess_timeout", "thread_timeout", "usr_timeout", 31.23, 121.47))
                    .thenReturn(Optional.empty());
            when(snapshotService.captureContext("thread_timeout", "usr_timeout", 31.23, 121.47))
                    .thenReturn(context);
            when(snapshotService.getOrCreateSnapshot("sess_timeout", "thread_timeout", "usr_timeout", 31.23, 121.47))
                    .thenAnswer(invocation -> {
                        assertTrue(releaseBuild.await(5, TimeUnit.SECONDS));
                        return exportableSnapshot();
                    });

            MedicalReportBuildState buildState = coordinator.getOrStartFinalReport(
                    "sess_timeout",
                    "thread_timeout",
                    "usr_timeout",
                    31.23,
                    121.47
            );

            assertEquals(MedicalReportBuildState.STATUS_GENERATING, buildState.status());
            assertEquals(MedicalReportBuildState.REASON_CODE_REPORT_WAIT_TIMEOUT, buildState.reasonCode());
            assertEquals(1000, buildState.retryAfterMs());
            releaseBuild.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(0, coordinator.inFlightCount());
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidateSessionShouldDropTrackedInFlightBuilds() throws Exception {
        MedicalReportSnapshotService snapshotService = mock(MedicalReportSnapshotService.class);
        MedicalReportBuildProperties properties = buildProperties(1000, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            MedicalReportContextSnapshot context = new MedicalReportContextSnapshot("conversation", "profile", "location");
            CountDownLatch buildStarted = new CountDownLatch(1);
            CountDownLatch releaseBuild = new CountDownLatch(1);

            when(snapshotService.findFreshSnapshot("sess_invalidate", "thread_invalidate", "usr_invalidate", 31.23, 121.47))
                    .thenReturn(Optional.empty());
            when(snapshotService.captureContext("thread_invalidate", "usr_invalidate", 31.23, 121.47))
                    .thenReturn(context);
            when(snapshotService.getOrCreateSnapshot("sess_invalidate", "thread_invalidate", "usr_invalidate", 31.23, 121.47))
                    .thenAnswer(invocation -> {
                        buildStarted.countDown();
                        assertTrue(releaseBuild.await(5, TimeUnit.SECONDS));
                        return exportableSnapshot();
                    });

            coordinator.warmUpFinalReport("sess_invalidate", "thread_invalidate", "usr_invalidate", 31.23, 121.47, null);
            assertTrue(buildStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, coordinator.inFlightCount());

            coordinator.invalidateSession("sess_invalidate");

            assertEquals(0, coordinator.inFlightCount());
            releaseBuild.countDown();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnFreshSnapshotWithoutStartingBuild() {
        MedicalReportSnapshotService snapshotService = mock(MedicalReportSnapshotService.class);
        MedicalReportBuildProperties properties = buildProperties(1000, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            MedicalReportSnapshot snapshot = exportableSnapshot();

            when(snapshotService.findFreshSnapshot("sess_fresh", "thread_fresh", "usr_fresh", 31.23, 121.47))
                    .thenReturn(Optional.of(snapshot));

            MedicalReportBuildState buildState = coordinator.getOrStartFinalReport(
                    "sess_fresh",
                    "thread_fresh",
                    "usr_fresh",
                    31.23,
                    121.47
            );

            assertTrue(buildState.ready());
            assertEquals(MedicalReportBuildState.STATUS_READY, buildState.status());
            verify(snapshotService, never()).captureContext("thread_fresh", "usr_fresh", 31.23, 121.47);
            verify(snapshotService, never()).getOrCreateSnapshot("sess_fresh", "thread_fresh", "usr_fresh", 31.23, 121.47);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAllowNewBuildAfterInvalidateSession() throws Exception {
        MedicalReportSnapshotService snapshotService = mock(MedicalReportSnapshotService.class);
        MedicalReportBuildProperties properties = buildProperties(1000, 1000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            MedicalReportContextSnapshot context = new MedicalReportContextSnapshot("conversation", "profile", "location");
            CountDownLatch firstBuildStarted = new CountDownLatch(1);
            CountDownLatch releaseFirstBuild = new CountDownLatch(1);
            AtomicInteger buildCalls = new AtomicInteger();

            when(snapshotService.findFreshSnapshot("sess_reset", "thread_reset", "usr_reset", 31.23, 121.47))
                    .thenReturn(Optional.empty(), Optional.empty());
            when(snapshotService.captureContext("thread_reset", "usr_reset", 31.23, 121.47))
                    .thenReturn(context, context);
            when(snapshotService.getOrCreateSnapshot("sess_reset", "thread_reset", "usr_reset", 31.23, 121.47))
                    .thenAnswer(invocation -> {
                        if (buildCalls.incrementAndGet() == 1) {
                            firstBuildStarted.countDown();
                            assertTrue(releaseFirstBuild.await(5, TimeUnit.SECONDS));
                        }
                        return exportableSnapshot();
                    });

            coordinator.warmUpFinalReport("sess_reset", "thread_reset", "usr_reset", 31.23, 121.47, null);
            assertTrue(firstBuildStarted.await(1, TimeUnit.SECONDS));

            coordinator.invalidateSession("sess_reset");
            MedicalReportBuildState buildState = coordinator.getOrStartFinalReport(
                    "sess_reset",
                    "thread_reset",
                    "usr_reset",
                    31.23,
                    121.47
            );

            assertTrue(buildState.ready());
            assertEquals(2, buildCalls.get());
            releaseFirstBuild.countDown();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldClearInFlightBuildsAfterFailure() throws Exception {
        MedicalReportSnapshotService snapshotService = mock(MedicalReportSnapshotService.class);
        MedicalReportBuildProperties properties = buildProperties(1000, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultReportBuildCoordinator coordinator = new DefaultReportBuildCoordinator(snapshotService, properties, executor);
            MedicalReportContextSnapshot context = new MedicalReportContextSnapshot("conversation", "profile", "location");

            when(snapshotService.findFreshSnapshot("sess_failure", "thread_failure", "usr_failure", 31.23, 121.47))
                    .thenReturn(Optional.empty());
            when(snapshotService.captureContext("thread_failure", "usr_failure", 31.23, 121.47))
                    .thenReturn(context);
            when(snapshotService.getOrCreateSnapshot("sess_failure", "thread_failure", "usr_failure", 31.23, 121.47))
                    .thenThrow(new IllegalStateException("build failed"));

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.getOrStartFinalReport("sess_failure", "thread_failure", "usr_failure", 31.23, 121.47)
            );

            assertEquals("build failed", exception.getMessage());
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(0, coordinator.inFlightCount());
        }
        finally {
            executor.shutdownNow();
        }
    }

    private MedicalReportBuildProperties buildProperties(long queryWaitTimeoutMs, int retryAfterMs) {
        MedicalReportBuildProperties properties = new MedicalReportBuildProperties();
        properties.setQueryWaitTimeout(java.time.Duration.ofMillis(queryWaitTimeoutMs));
        properties.setRetryAfterMs(retryAfterMs);
        return properties;
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

    private boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private MedicalReportSnapshot exportableSnapshot() {
        return new MedicalReportSnapshot(
                "sess_build",
                "thread_build",
                "usr_build",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                new MedicalDiagnosisReport(
                        "医疗诊断报告",
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
                ),
                MedicalHospitalPlanningSummary.empty()
        );
    }
}
