package com.tay.medicalagent.session;

import java.util.Optional;

/**
 * 问诊会话仓储抽象。
 */
public interface ConsultationSessionRepository {

    void save(ConsultationSession consultationSession);

    Optional<ConsultationSession> findBySessionId(String sessionId);
}
