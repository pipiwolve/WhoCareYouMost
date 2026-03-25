package com.tay.medicalagent.session;

import java.time.Instant;

/**
 * 前端问诊会话。
 * <p>
 * 对外暴露 sessionId，对内绑定 threadId 与 userId。
 */
public record ConsultationSession(
        String sessionId,
        String threadId,
        String userId,
        Instant createdAt,
        Instant lastActiveAt
) {

    public ConsultationSession touch(Instant timestamp) {
        return new ConsultationSession(sessionId, threadId, userId, createdAt, timestamp);
    }
}
