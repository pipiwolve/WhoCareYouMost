package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.repository.redis.RedisJsonValueSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "medical.persistence", name = "store", havingValue = "redis")
public class RedisMedicalReportSnapshotRepository extends RedisJsonValueSupport implements MedicalReportSnapshotRepository {

    private static final String SNAPSHOT_NAMESPACE = "report-snapshot";

    private final MedicalPersistenceProperties persistenceProperties;

    public RedisMedicalReportSnapshotRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MedicalPersistenceProperties persistenceProperties
    ) {
        super(redisTemplate, objectMapper, persistenceProperties);
        this.persistenceProperties = persistenceProperties;
    }

    @Override
    public void save(MedicalReportSnapshot snapshot) {
        if (snapshot == null || snapshot.sessionId() == null || snapshot.sessionId().isBlank()) {
            return;
        }
        writeJson(key(SNAPSHOT_NAMESPACE, snapshot.sessionId()), snapshot, snapshotTtl());
    }

    @Override
    public Optional<MedicalReportSnapshot> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return readJson(key(SNAPSHOT_NAMESPACE, sessionId), MedicalReportSnapshot.class, snapshotTtl());
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate().delete(key(SNAPSHOT_NAMESPACE, sessionId));
    }

    @Override
    public void clear() {
        deleteByPattern(pattern(SNAPSHOT_NAMESPACE));
    }

    private Duration snapshotTtl() {
        return persistenceProperties.getRedis().getSnapshotTtl();
    }
}
