package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalReportSnapshot;

import java.util.Optional;

/**
 * 冻结报告快照仓储。
 */
public interface MedicalReportSnapshotRepository {

    void save(MedicalReportSnapshot snapshot);

    Optional<MedicalReportSnapshot> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    void clear();
}
