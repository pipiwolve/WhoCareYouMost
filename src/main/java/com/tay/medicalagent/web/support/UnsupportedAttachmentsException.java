package com.tay.medicalagent.web.support;

/**
 * 不支持附件上传异常。
 */
public class UnsupportedAttachmentsException extends RuntimeException {

    public UnsupportedAttachmentsException(String message) {
        super(message);
    }
}
