package com.tay.medicalagent.web.dto;

/**
 * 用户问诊初始化响应。
 */
public record ProfileInitResponse(
        String userId,
        String sessionId,
        String welcomeMessage
) {
}
