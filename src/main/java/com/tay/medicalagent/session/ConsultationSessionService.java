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
                now,
                null,
                null,
                null
        );
        consultationSessionRepository.save(consultationSession);
        return consultationSession;
    }

    public ConsultationSession updateLocation(String sessionId, double latitude, double longitude, boolean consentGranted) {
        if (!consentGranted) {
            throw new IllegalArgumentException("请先完成位置授权");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude 超出范围");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude 超出范围");
        }

        ConsultationSession consultationSession = getRequiredSession(sessionId);
        ConsultationSession updatedSession = consultationSession.withLocation(latitude, longitude, Instant.now())
                .touch(Instant.now());
        consultationSessionRepository.save(updatedSession);
        return updatedSession;
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
