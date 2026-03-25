package com.tay.medicalagent.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用户问诊初始化请求。
 */
public record ProfileInitRequest(
        String userId,
        @NotBlank(message = "name 不能为空")
        String name,
        @NotNull(message = "age 不能为空")
        @Min(value = 0, message = "age 不能小于 0")
        @Max(value = 150, message = "age 不能大于 150")
        Integer age,
        @NotNull(message = "gender 不能为空")
        ProfileGender gender,
        String avatarId
) {
}
