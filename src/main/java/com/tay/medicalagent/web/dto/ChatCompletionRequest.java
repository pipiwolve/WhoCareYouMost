package com.tay.medicalagent.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 医疗问答请求。
 */
public record ChatCompletionRequest(
        @NotBlank(message = "sessionId 不能为空")
        String sessionId,
        @NotBlank(message = "message 不能为空")
        String message,
        List<@NotBlank(message = "attachments 不能包含空值") String> attachments
) {
}
