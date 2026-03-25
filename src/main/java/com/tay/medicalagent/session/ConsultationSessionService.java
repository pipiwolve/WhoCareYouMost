package com.tay.medicalagent.session;

import com.tay.medicalagent.web.support.SessionNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
/**
 * 问诊会话服务。
 * <p>
 * 负责创建前端 sessionId，并在访问时解析和刷新会话活跃时间。
 */
public class ConsultationSessionService {

    private final ConsultationSessionRepository consultationSessionRepository;

    public ConsultationSessionService(ConsultationSessionRepository consultationSessionRepository) {
        this.consultationSessionRepository = consultationSessionRepository;
    }

    public ConsultationSession createSession(String userId, String threadId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }

        Instant now = Instant.now();
        ConsultationSession consultationSession = new ConsultationSession(
                "sess_" + compactUuid(),
                threadId.trim(),
                userId.trim(),
                now,
                now
        );
        consultationSessionRepository.save(consultationSession);
        return consultationSession;
    }

    public ConsultationSession getRequiredSession(String sessionId) {
        ConsultationSession consultationSession = consultationSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("会话不存在"));
        ConsultationSession touchedSession = consultationSession.touch(Instant.now());
        consultationSessionRepository.save(touchedSession);
        return touchedSession;
    }

    public Optional<ConsultationSession> findSession(String sessionId) {
        return consultationSessionRepository.findBySessionId(sessionId);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
