package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

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

    private final MedicalReportSnapshotRepository medicalReportSnapshotRepository;
    private final MedicalReportService medicalReportService;
    private final MedicalHospitalPlanningService medicalHospitalPlanningService;
    private final ThreadConversationService threadConversationService;
    private final UserProfileService userProfileService;
    private final MedicalPlanningIntentResolver medicalPlanningIntentResolver;
    private final ConcurrentMap<String, ReentrantLock> snapshotLocks = new ConcurrentHashMap<>();

    public DefaultMedicalReportSnapshotService(
            MedicalReportSnapshotRepository medicalReportSnapshotRepository,
            MedicalReportService medicalReportService,
            MedicalHospitalPlanningService medicalHospitalPlanningService,
            ThreadConversationService threadConversationService,
            UserProfileService userProfileService,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver
    ) {
        this.medicalReportSnapshotRepository = medicalReportSnapshotRepository;
        this.medicalReportService = medicalReportService;
        this.medicalHospitalPlanningService = medicalHospitalPlanningService;
        this.threadConversationService = threadConversationService;
        this.userProfileService = userProfileService;
        this.medicalPlanningIntentResolver = medicalPlanningIntentResolver;
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
        ReentrantLock lock = snapshotLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        lock.lock();
        try {
            List<Message> conversation = threadConversationService.getThreadConversation(threadId);
            String conversationFingerprint = fingerprint(threadConversationService.buildConversationTranscript(conversation));
            String profileFingerprint = fingerprint(userProfileService.buildProfileContext(userId));
            String locationFingerprint = fingerprint(locationKey(latitude, longitude));

            MedicalReportSnapshot existingSnapshot = medicalReportSnapshotRepository.findBySessionId(sessionId).orElse(null);
            if (existingSnapshot != null && existingSnapshot.isFresh(conversationFingerprint, profileFingerprint, locationFingerprint)) {
                return existingSnapshot;
            }

            MedicalDiagnosisReport effectiveReport = report == null
                    ? medicalReportService.generateReportFromThread(threadId, userId)
                    : report;
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
            lock.unlock();
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
                Instant.now(),
                conversationFingerprint,
                profileFingerprint,
                locationFingerprint,
                report,
                planningSummary
        );
        medicalReportSnapshotRepository.save(snapshot);
        return snapshot;
    }

    @Override
    public void invalidate(String sessionId) {
        medicalReportSnapshotRepository.deleteBySessionId(sessionId);
    }

    @Override
    public void clear() {
        medicalReportSnapshotRepository.clear();
        snapshotLocks.clear();
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
}
