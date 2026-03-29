package com.tay.medicalagent.app.service.model;

/**
 * 模型配置缺失或非法时抛出的异常。
 */
public class MedicalModelConfigurationException extends RuntimeException {

    public MedicalModelConfigurationException(String message) {
        super(message);
    }
}
