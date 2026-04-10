package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportBuildState;
import com.tay.medicalagent.app.report.MedicalReportContextSnapshot;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DefaultReportBuildCoordinator implements ReportBuildCoordinator {

    private static final String BUILD_TARGET_FINAL_REPORT = "FINAL_REPORT";
    private static final String GENERATING_REASON = "报告生成中，请稍后重试";
    private static final String DEFAULT_NOT_READY_REASON = "当前会话暂无足够问诊内容";

    private final MedicalReportSnapshotService medicalReportSnapshotService;
    private final MedicalReportBuildProperties medicalReportBuildProperties;
    private final Executor medicalReportBuildExecutor;
    private final ConcurrentMap<BuildKey, CompletableFuture<MedicalReportSnapshot>> inFlightBuilds = new ConcurrentHashMap<>();

    public DefaultReportBuildCoordinator(
            MedicalReportSnapshotService medicalReportSnapshotService,
            MedicalReportBuildProperties medicalReportBuildProperties,
            @Qualifier("medicalReportBuildExecutor") Executor medicalReportBuildExecutor
    ) {
        this.medicalReportSnapshotService = medicalReportSnapshotService;
        this.medicalReportBuildProperties = medicalReportBuildProperties;
        this.medicalReportBuildExecutor = medicalReportBuildExecutor;
    }

    @Override
    public void warmUpFinalReport(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport providedReport
    ) {
        if (hasFreshSnapshot(sessionId, threadId, userId, latitude, longitude)) {
            return;
        }
        startOrJoinBuild(sessionId, threadId, userId, latitude, longitude, providedReport);
    }

    @Override
    public MedicalReportBuildState getOrStartFinalReport(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        Optional<MedicalReportSnapshot> existingSnapshot = medicalReportSnapshotService.findFreshSnapshot(
                sessionId,
                threadId,
                userId,
                latitude,
                longitude
        );
        if (existingSnapshot.isPresent()) {
            return toBuildState(existingSnapshot.get());
        }

        CompletableFuture<MedicalReportSnapshot> future = startOrJoinBuild(
                sessionId,
                threadId,
                userId,
                latitude,
                longitude,
                null
        );
        try {
            MedicalReportSnapshot snapshot = future.get(resolveQueryWaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return toBuildState(snapshot);
        }
        catch (TimeoutException ex) {
            return MedicalReportBuildState.generating(
                    GENERATING_REASON,
                    MedicalReportBuildState.REASON_CODE_REPORT_WAIT_TIMEOUT,
                    Math.max(1, medicalReportBuildProperties.getRetryAfterMs())
            );
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("报告生成被中断", ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("报告生成失败", cause == null ? ex : cause);
        }
    }

    @Override
    public void invalidateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String normalizedSessionId = sessionId.trim();
        inFlightBuilds.keySet().removeIf(buildKey -> normalizedSessionId.equals(buildKey.sessionId()));
    }

    @Override
    public void clear() {
        inFlightBuilds.clear();
    }

    int inFlightCount() {
        return inFlightBuilds.size();
    }

    private boolean hasFreshSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        return medicalReportSnapshotService.findFreshSnapshot(sessionId, threadId, userId, latitude, longitude).isPresent();
    }

    private CompletableFuture<MedicalReportSnapshot> startOrJoinBuild(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport providedReport
    ) {
        BuildKey buildKey = buildKey(sessionId, threadId, userId, latitude, longitude);
        return inFlightBuilds.computeIfAbsent(buildKey, key -> createBuildFuture(
                key,
                threadId,
                userId,
                latitude,
                longitude,
                providedReport
        ));
    }

    private CompletableFuture<MedicalReportSnapshot> createBuildFuture(
            BuildKey buildKey,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport providedReport
    ) {
        CompletableFuture<MedicalReportSnapshot> future = CompletableFuture.supplyAsync(() -> {
            if (providedReport != null) {
                return medicalReportSnapshotService.getOrCreateSnapshot(
                        buildKey.sessionId(),
                        threadId,
                        userId,
                        latitude,
                        longitude,
                        providedReport,
                        null
                );
            }
            return medicalReportSnapshotService.getOrCreateSnapshot(
                    buildKey.sessionId(),
                    threadId,
                    userId,
                    latitude,
                    longitude
            );
        }, medicalReportBuildExecutor);
        future.whenComplete((ignored, throwable) -> inFlightBuilds.remove(buildKey, future));
        return future;
    }

    private BuildKey buildKey(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        MedicalReportContextSnapshot contextSnapshot = medicalReportSnapshotService.captureContext(threadId, userId, latitude, longitude);
        return new BuildKey(
                normalizeSessionId(sessionId),
                contextSnapshot == null ? "" : safeText(contextSnapshot.conversationFingerprint()),
                contextSnapshot == null ? "" : safeText(contextSnapshot.profileFingerprint()),
                contextSnapshot == null ? "" : safeText(contextSnapshot.locationFingerprint()),
                BUILD_TARGET_FINAL_REPORT
        );
    }

    private MedicalReportBuildState toBuildState(MedicalReportSnapshot snapshot) {
        if (snapshot == null || snapshot.report() == null) {
            return MedicalReportBuildState.notReady(
                    snapshot,
                    DEFAULT_NOT_READY_REASON,
                    MedicalReportBuildState.REASON_CODE_INSUFFICIENT_CONTEXT
            );
        }

        MedicalDiagnosisReport report = snapshot.report();
        if (!report.shouldGenerateReport()) {
            String reason = safeText(report.uncertaintyReason()).isBlank()
                    ? DEFAULT_NOT_READY_REASON
                    : safeText(report.uncertaintyReason());
            return MedicalReportBuildState.notReady(
                    snapshot,
                    reason,
                    MedicalReportBuildState.REASON_CODE_INSUFFICIENT_CONTEXT
            );
        }
        return MedicalReportBuildState.ready(snapshot);
    }

    private Duration resolveQueryWaitTimeout() {
        Duration candidate = medicalReportBuildProperties.getQueryWaitTimeout();
        if (candidate == null || candidate.isZero() || candidate.isNegative()) {
            return Duration.ofMillis(5000);
        }
        return candidate;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "unknown-session";
        }
        return sessionId.trim();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private record BuildKey(
            String sessionId,
            String conversationFingerprint,
            String profileFingerprint,
            String locationFingerprint,
            String targetType
    ) {
    }
}
