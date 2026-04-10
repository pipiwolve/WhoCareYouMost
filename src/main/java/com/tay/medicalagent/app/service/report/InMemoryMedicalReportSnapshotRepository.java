package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 冻结报告快照的内存实现。
 */
@Repository
@ConditionalOnProperty(prefix = "medical.persistence", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryMedicalReportSnapshotRepository implements MedicalReportSnapshotRepository {

    private final ConcurrentMap<String, SnapshotEntry> snapshots = new ConcurrentHashMap<>();
    private final MedicalReportSnapshotProperties snapshotProperties;
    private final Clock clock;

    private volatile Instant lastCleanupAt = Instant.EPOCH;

    @Autowired
    public InMemoryMedicalReportSnapshotRepository(MedicalReportSnapshotProperties snapshotProperties) {
        this(snapshotProperties, Clock.systemUTC());
    }

    InMemoryMedicalReportSnapshotRepository(
            MedicalReportSnapshotProperties snapshotProperties,
            Clock clock
    ) {
        this.snapshotProperties = snapshotProperties;
        this.clock = clock;
    }

    @Override
    public void save(MedicalReportSnapshot snapshot) {
        if (snapshot == null || snapshot.sessionId() == null || snapshot.sessionId().isBlank()) {
            return;
        }
        Instant now = clock.instant();
        cleanupIfNeeded(now);
        snapshots.put(snapshot.sessionId(), new SnapshotEntry(snapshot, now));
        evictIfNecessary();
    }

    @Override
    public Optional<MedicalReportSnapshot> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        cleanupIfNeeded(now);
        String normalizedSessionId = sessionId.trim();
        SnapshotEntry entry = snapshots.get(normalizedSessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry, now)) {
            snapshots.remove(normalizedSessionId, entry);
            return Optional.empty();
        }
        entry.touch(now);
        return Optional.of(entry.snapshot());
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        snapshots.remove(sessionId.trim());
    }

    @Override
    public void clear() {
        snapshots.clear();
    }

    int size() {
        return snapshots.size();
    }

    private void cleanupIfNeeded(Instant now) {
        Duration cleanupInterval = safeDuration(snapshotProperties.getCleanupInterval(), Duration.ofMinutes(5));
        if (!lastCleanupAt.equals(Instant.EPOCH) && Duration.between(lastCleanupAt, now).compareTo(cleanupInterval) < 0) {
            return;
        }
        lastCleanupAt = now;
        snapshots.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(SnapshotEntry entry, Instant now) {
        Duration ttl = safeDuration(snapshotProperties.getTtl(), Duration.ofMinutes(30));
        return Duration.between(entry.accessedAt(), now).compareTo(ttl) >= 0;
    }

    private void evictIfNecessary() {
        int maxSessions = Math.max(1, snapshotProperties.getMaxSessions());
        if (snapshots.size() <= maxSessions) {
            return;
        }
        snapshots.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().accessedAt()))
                .limit(Math.max(0, snapshots.size() - maxSessions))
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(snapshots::remove);
    }

    private Duration safeDuration(Duration candidate, Duration fallback) {
        if (candidate == null || candidate.isNegative() || candidate.isZero()) {
            return fallback;
        }
        return candidate;
    }

    private static final class SnapshotEntry {

        private final MedicalReportSnapshot snapshot;
        private final Instant createdAt;
        private volatile Instant accessedAt;

        private SnapshotEntry(MedicalReportSnapshot snapshot, Instant now) {
            this.snapshot = snapshot;
            this.createdAt = now;
            this.accessedAt = now;
        }

        private MedicalReportSnapshot snapshot() {
            return snapshot;
        }

        private Instant accessedAt() {
            return accessedAt;
        }

        @SuppressWarnings("unused")
        private Instant createdAt() {
            return createdAt;
        }

        private void touch(Instant now) {
            this.accessedAt = now;
        }
    }
}
