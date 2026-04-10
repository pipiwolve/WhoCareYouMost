package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportContextSnapshot;
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
import java.util.Optional;
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
    private final ConcurrentMap<String, Long> sessionGenerations = new ConcurrentHashMap<>();
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
    public Optional<MedicalReportSnapshot> findFreshSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        MedicalReportSnapshot existingSnapshot = medicalReportSnapshotRepository.findBySessionId(sessionId).orElse(null);
        if (existingSnapshot == null) {
            return Optional.empty();
        }
        MedicalReportContextSnapshot contextSnapshot = captureContext(threadId, userId, latitude, longitude);
        boolean fresh = existingSnapshot.isFresh(
                contextSnapshot.conversationFingerprint(),
                contextSnapshot.profileFingerprint(),
                contextSnapshot.locationFingerprint()
        );
        if (!fresh) {
            return Optional.empty();
        }
        if (shouldRetryDegradedPlanning(existingSnapshot, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(existingSnapshot);
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
        long sessionGeneration = currentSessionGeneration(lockKey);
        Instant now = clock.instant();
        cleanupStaleLocksIfNeeded(now);
        SnapshotLockState lockState = snapshotLocks.computeIfAbsent(lockKey, key -> new SnapshotLockState(new ReentrantLock(), now));
        lockState.touch(now);
        ReentrantLock lock = lockState.lock();
        lock.lock();
        try {
            MedicalReportContextSnapshot contextSnapshot = captureContext(threadId, userId, latitude, longitude);
            String conversationFingerprint = contextSnapshot.conversationFingerprint();
            String profileFingerprint = contextSnapshot.profileFingerprint();
            String locationFingerprint = contextSnapshot.locationFingerprint();

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
                            existingSnapshot,
                            sessionGeneration
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
                    effectiveReport,
                    sessionGeneration
            );
        }
        finally {
            lockState.touch(clock.instant());
            lock.unlock();
            cleanupStaleLocksIfNeeded(clock.instant());
        }
    }

    @Override
    public MedicalReportContextSnapshot captureContext(
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        List<Message> conversation = threadConversationService.getThreadConversation(threadId);
        return new MedicalReportContextSnapshot(
                fingerprint(threadConversationService.buildConversationTranscript(conversation)),
                fingerprint(userProfileService.buildProfileContext(userId)),
                fingerprint(locationKey(latitude, longitude))
        );
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
            MedicalDiagnosisReport report,
            long sessionGeneration
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
        return saveIfSessionCurrent(sessionId, sessionGeneration, snapshot);
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
            MedicalReportSnapshot existingSnapshot,
            long sessionGeneration
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
        MedicalReportSnapshot effectiveSnapshot = saveIfSessionCurrent(sessionId, sessionGeneration, refreshedSnapshot);
        log.info(
                "Retried degraded medical report planning on fresh snapshot. sessionId={}, previousStatus={}, newStatus={}",
                normalize(sessionId, "unknown-session"),
                existingSnapshot.planningSummary() == null ? "" : existingSnapshot.planningSummary().routeStatusCode(),
                planningSummary.routeStatusCode()
        );
        return effectiveSnapshot;
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
        String normalizedSessionId = normalize(sessionId, "unknown-session");
        sessionGenerations.merge(normalizedSessionId, 1L, Long::sum);
        medicalReportSnapshotRepository.deleteBySessionId(normalizedSessionId);
        snapshotLocks.remove(normalizedSessionId);
    }

    @Override
    public void clear() {
        medicalReportSnapshotRepository.clear();
        snapshotLocks.clear();
        sessionGenerations.clear();
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

    private MedicalReportSnapshot saveIfSessionCurrent(
            String sessionId,
            long sessionGeneration,
            MedicalReportSnapshot snapshot
    ) {
        String normalizedSessionId = normalize(sessionId, "unknown-session");
        if (currentSessionGeneration(normalizedSessionId) != sessionGeneration) {
            return medicalReportSnapshotRepository.findBySessionId(normalizedSessionId).orElse(snapshot);
        }
        medicalReportSnapshotRepository.save(snapshot);
        return snapshot;
    }

    private long currentSessionGeneration(String sessionId) {
        return sessionGenerations.getOrDefault(normalize(sessionId, "unknown-session"), 0L);
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
