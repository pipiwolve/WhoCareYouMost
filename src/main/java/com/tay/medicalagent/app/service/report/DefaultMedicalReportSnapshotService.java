package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认冻结报告快照服务实现。
 */
@Service
public class DefaultMedicalReportSnapshotService implements MedicalReportSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMedicalReportSnapshotService.class);

    private final MedicalReportSnapshotRepository medicalReportSnapshotRepository;
    private final MedicalReportService medicalReportService;
    private final MedicalHospitalPlanningService medicalHospitalPlanningService;
    private final ThreadConversationService threadConversationService;
    private final UserProfileService userProfileService;
    private final MedicalPlanningIntentResolver medicalPlanningIntentResolver;
    private final MedicalReportSnapshotProperties snapshotProperties;
    private final Clock clock;
    private final ConcurrentMap<String, SnapshotLockState> snapshotLocks = new ConcurrentHashMap<>();
    private volatile Instant lastLockCleanupAt = Instant.EPOCH;

    @Autowired
    public DefaultMedicalReportSnapshotService(
            MedicalReportSnapshotRepository medicalReportSnapshotRepository,
            MedicalReportService medicalReportService,
            MedicalHospitalPlanningService medicalHospitalPlanningService,
            ThreadConversationService threadConversationService,
            UserProfileService userProfileService,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver,
            MedicalReportSnapshotProperties snapshotProperties
    ) {
        this(
                medicalReportSnapshotRepository,
                medicalReportService,
                medicalHospitalPlanningService,
                threadConversationService,
                userProfileService,
                medicalPlanningIntentResolver,
                snapshotProperties,
                Clock.systemUTC()
        );
    }

    DefaultMedicalReportSnapshotService(
            MedicalReportSnapshotRepository medicalReportSnapshotRepository,
            MedicalReportService medicalReportService,
            MedicalHospitalPlanningService medicalHospitalPlanningService,
            ThreadConversationService threadConversationService,
            UserProfileService userProfileService,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver,
            MedicalReportSnapshotProperties snapshotProperties,
            Clock clock
    ) {
        this.medicalReportSnapshotRepository = medicalReportSnapshotRepository;
        this.medicalReportService = medicalReportService;
        this.medicalHospitalPlanningService = medicalHospitalPlanningService;
        this.threadConversationService = threadConversationService;
        this.userProfileService = userProfileService;
        this.medicalPlanningIntentResolver = medicalPlanningIntentResolver;
        this.snapshotProperties = snapshotProperties;
        this.clock = clock;
    }

    @Override
    public MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        return getOrCreateSnapshot(sessionId, threadId, userId, latitude, longitude, null, null);
    }

    @Override
    public MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalPlanningIntent planningIntent
    ) {
        return getOrCreateSnapshot(sessionId, threadId, userId, latitude, longitude, null, planningIntent);
    }

    @Override
    public MedicalReportSnapshot getOrCreateSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent
    ) {
        String lockKey = normalize(sessionId, "unknown-session");
        Instant now = clock.instant();
        cleanupStaleLocksIfNeeded(now);
        SnapshotLockState lockState = snapshotLocks.computeIfAbsent(lockKey, key -> new SnapshotLockState(new ReentrantLock(), now));
        lockState.touch(now);
        ReentrantLock lock = lockState.lock();
        lock.lock();
        try {
            List<Message> conversation = threadConversationService.getThreadConversation(threadId);
            String conversationFingerprint = fingerprint(threadConversationService.buildConversationTranscript(conversation));
            String profileFingerprint = fingerprint(userProfileService.buildProfileContext(userId));
            String locationFingerprint = fingerprint(locationKey(latitude, longitude));

            MedicalReportSnapshot existingSnapshot = medicalReportSnapshotRepository.findBySessionId(sessionId).orElse(null);
            if (existingSnapshot != null && existingSnapshot.isFresh(conversationFingerprint, profileFingerprint, locationFingerprint)) {
                if (shouldRetryDegradedPlanning(existingSnapshot, now)) {
                    return retryPlanningForFreshSnapshot(
                            sessionId,
                            threadId,
                            userId,
                            latitude,
                            longitude,
                            planningIntent,
                            conversationFingerprint,
                            profileFingerprint,
                            locationFingerprint,
                            existingSnapshot
                    );
                }
                return existingSnapshot;
            }

            MedicalDiagnosisReport effectiveReport = resolveEffectiveReport(
                    report,
                    existingSnapshot,
                    threadId,
                    userId,
                    conversationFingerprint,
                    profileFingerprint
            );
            return buildAndSaveSnapshot(
                    sessionId,
                    threadId,
                    userId,
                    latitude,
                    longitude,
                    planningIntent,
                    conversationFingerprint,
                    profileFingerprint,
                    locationFingerprint,
                    effectiveReport
            );
        }
        finally {
            lockState.touch(clock.instant());
            lock.unlock();
            cleanupStaleLocksIfNeeded(clock.instant());
        }
    }

    private MedicalReportSnapshot buildAndSaveSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalPlanningIntent planningIntent,
            String conversationFingerprint,
            String profileFingerprint,
            String locationFingerprint,
            MedicalDiagnosisReport report
    ) {
        MedicalPlanningIntent effectiveIntent = planningIntent == null
                ? medicalPlanningIntentResolver.resolve(report)
                : planningIntent;
        MedicalHospitalPlanningSummary planningSummary = (report != null && report.shouldGenerateReport())
                ? medicalHospitalPlanningService.plan(latitude, longitude, report, effectiveIntent)
                : MedicalHospitalPlanningSummary.empty();

        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                normalize(sessionId, "unknown-session"),
                normalize(threadId, "current-thread"),
                normalize(userId, "anonymous"),
                clock.instant(),
                conversationFingerprint,
                profileFingerprint,
                locationFingerprint,
                report,
                planningSummary
        );
        medicalReportSnapshotRepository.save(snapshot);
        return snapshot;
    }

    private MedicalReportSnapshot retryPlanningForFreshSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalPlanningIntent planningIntent,
            String conversationFingerprint,
            String profileFingerprint,
            String locationFingerprint,
            MedicalReportSnapshot existingSnapshot
    ) {
        MedicalDiagnosisReport report = existingSnapshot.report();
        if (report == null) {
            return existingSnapshot;
        }
        MedicalPlanningIntent effectiveIntent = planningIntent == null
                ? medicalPlanningIntentResolver.resolve(report)
                : planningIntent;
        MedicalHospitalPlanningSummary planningSummary = report.shouldGenerateReport()
                ? medicalHospitalPlanningService.plan(latitude, longitude, report, effectiveIntent)
                : MedicalHospitalPlanningSummary.empty();
        MedicalReportSnapshot refreshedSnapshot = new MedicalReportSnapshot(
                normalize(sessionId, "unknown-session"),
                normalize(threadId, "current-thread"),
                normalize(userId, "anonymous"),
                clock.instant(),
                conversationFingerprint,
                profileFingerprint,
                locationFingerprint,
                report,
                planningSummary
        );
        medicalReportSnapshotRepository.save(refreshedSnapshot);
        log.info(
                "Retried degraded medical report planning on fresh snapshot. sessionId={}, previousStatus={}, newStatus={}",
                normalize(sessionId, "unknown-session"),
                existingSnapshot.planningSummary() == null ? "" : existingSnapshot.planningSummary().routeStatusCode(),
                planningSummary.routeStatusCode()
        );
        return refreshedSnapshot;
    }

    private MedicalDiagnosisReport resolveEffectiveReport(
            MedicalDiagnosisReport providedReport,
            MedicalReportSnapshot existingSnapshot,
            String threadId,
            String userId,
            String conversationFingerprint,
            String profileFingerprint
    ) {
        if (providedReport != null) {
            return providedReport;
        }
        if (canReuseReport(existingSnapshot, conversationFingerprint, profileFingerprint)) {
            return existingSnapshot.report();
        }
        return medicalReportService.generateReportFromThread(threadId, userId);
    }

    private boolean canReuseReport(
            MedicalReportSnapshot existingSnapshot,
            String conversationFingerprint,
            String profileFingerprint
    ) {
        if (existingSnapshot == null || existingSnapshot.report() == null) {
            return false;
        }
        return sameFingerprint(existingSnapshot.conversationFingerprint(), conversationFingerprint)
                && sameFingerprint(existingSnapshot.profileFingerprint(), profileFingerprint);
    }

    private boolean sameFingerprint(String left, String right) {
        return normalize(left, "").equals(normalize(right, ""));
    }

    private boolean shouldRetryDegradedPlanning(MedicalReportSnapshot existingSnapshot, Instant now) {
        if (existingSnapshot == null || existingSnapshot.generatedAt() == null || existingSnapshot.planningSummary() == null) {
            return false;
        }
        String routeStatusCode = normalize(existingSnapshot.planningSummary().routeStatusCode(), "");
        if (!"mcp_unavailable".equals(routeStatusCode) && !"route_timeout".equals(routeStatusCode)) {
            return false;
        }
        Duration retryAfter = safeDuration(snapshotProperties.getDegradedPlanningRetryAfter(), Duration.ofSeconds(30));
        return Duration.between(existingSnapshot.generatedAt(), now).compareTo(retryAfter) >= 0;
    }

    @Override
    public void invalidate(String sessionId) {
        medicalReportSnapshotRepository.deleteBySessionId(sessionId);
        snapshotLocks.remove(normalize(sessionId, "unknown-session"));
    }

    @Override
    public void clear() {
        medicalReportSnapshotRepository.clear();
        snapshotLocks.clear();
    }

    int snapshotLockCount() {
        return snapshotLocks.size();
    }

    void cleanupStaleLocksForTesting() {
        cleanupStaleLocksIfNeeded(clock.instant());
    }

    private String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalize(value, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }
        catch (Exception ex) {
            return Integer.toHexString(normalize(value, "").hashCode());
        }
    }

    private String locationKey(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return "";
        }
        return latitude + "," + longitude;
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private void cleanupStaleLocksIfNeeded(Instant now) {
        Duration cleanupInterval = safeDuration(snapshotProperties.getCleanupInterval(), Duration.ofMinutes(5));
        if (!lastLockCleanupAt.equals(Instant.EPOCH) && Duration.between(lastLockCleanupAt, now).compareTo(cleanupInterval) < 0) {
            return;
        }
        lastLockCleanupAt = now;
        Duration staleLockTtl = safeDuration(snapshotProperties.getStaleLockTtl(), Duration.ofSeconds(60));
        snapshotLocks.entrySet().removeIf(entry -> entry.getValue().isRemovable(now, staleLockTtl));
    }

    private Duration safeDuration(Duration candidate, Duration fallback) {
        if (candidate == null || candidate.isNegative() || candidate.isZero()) {
            return fallback;
        }
        return candidate;
    }

    private static final class SnapshotLockState {

        private final ReentrantLock lock;
        private volatile Instant lastTouchedAt;

        private SnapshotLockState(ReentrantLock lock, Instant now) {
            this.lock = lock;
            this.lastTouchedAt = now;
        }

        private ReentrantLock lock() {
            return lock;
        }

        private void touch(Instant now) {
            this.lastTouchedAt = now;
        }

        private boolean isRemovable(Instant now, Duration staleLockTtl) {
            if (lock.isLocked() || lock.hasQueuedThreads()) {
                return false;
            }
            return Duration.between(lastTouchedAt, now).compareTo(staleLockTtl) >= 0;
        }
    }
}
