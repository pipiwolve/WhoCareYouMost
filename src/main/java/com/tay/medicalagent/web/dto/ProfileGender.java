package com.tay.medicalagent.web.dto;

/**
 * 前端 onboarding 使用的性别枚举。
 */
public enum ProfileGender {
    MALE,
    FEMALE,
    OTHER;

    public String toInternalValue() {
        return switch (this) {
            case MALE -> "男";
            case FEMALE -> "女";
            case OTHER -> "其他";
        };
    }
}
