package com.tay.medicalagent.session;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
/**
 * 内存态问诊会话仓储。
 * <p>
 * 适合本地联调，应用重启后会话会丢失。
 */
public class InMemoryConsultationSessionRepository implements ConsultationSessionRepository {

    private final ConcurrentMap<String, ConsultationSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(ConsultationSession consultationSession) {
        if (consultationSession == null || consultationSession.sessionId() == null || consultationSession.sessionId().isBlank()) {
            return;
        }
        sessions.put(consultationSession.sessionId(), consultationSession);
    }

    @Override
    public Optional<ConsultationSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId.trim()));
    }
}
