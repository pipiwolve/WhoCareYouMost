package com.tay.medicalagent.web.support;

/**
 * 会话不存在异常。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String message) {
        super(message);
    }
}
