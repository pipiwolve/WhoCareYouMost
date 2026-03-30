package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 冻结报告快照的内存实现。
 */
@Repository
public class InMemoryMedicalReportSnapshotRepository implements MedicalReportSnapshotRepository {

    private final ConcurrentMap<String, MedicalReportSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(MedicalReportSnapshot snapshot) {
        if (snapshot == null || snapshot.sessionId() == null || snapshot.sessionId().isBlank()) {
            return;
        }
        snapshots.put(snapshot.sessionId(), snapshot);
    }

    @Override
    public Optional<MedicalReportSnapshot> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(sessionId.trim()));
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
}
