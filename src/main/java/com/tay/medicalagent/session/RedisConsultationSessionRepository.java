package com.tay.medicalagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import com.tay.medicalagent.app.repository.redis.RedisJsonValueSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "medical.persistence", name = "store", havingValue = "redis")
public class RedisConsultationSessionRepository extends RedisJsonValueSupport implements ConsultationSessionRepository {

    private static final String SESSION_NAMESPACE = "session";

    private final MedicalPersistenceProperties persistenceProperties;

    public RedisConsultationSessionRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MedicalPersistenceProperties persistenceProperties
    ) {
        super(redisTemplate, objectMapper, persistenceProperties);
        this.persistenceProperties = persistenceProperties;
    }

    @Override
    public void save(ConsultationSession consultationSession) {
        if (consultationSession == null || consultationSession.sessionId() == null || consultationSession.sessionId().isBlank()) {
            return;
        }
        writeJson(key(SESSION_NAMESPACE, consultationSession.sessionId()), consultationSession, sessionTtl());
    }

    @Override
    public Optional<ConsultationSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return readJson(key(SESSION_NAMESPACE, sessionId), ConsultationSession.class, sessionTtl());
    }

    private Duration sessionTtl() {
        return persistenceProperties.getRedis().getSessionTtl();
    }
}
