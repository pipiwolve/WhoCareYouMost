package com.tay.medicalagent.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 报告规划位置更新请求。
 */
public record ReportLocationUpdateRequest(
        @NotNull(message = "latitude 不能为空")
        @DecimalMin(value = "-90.0", message = "latitude 超出范围")
        @DecimalMax(value = "90.0", message = "latitude 超出范围")
        Double latitude,
        @NotNull(message = "longitude 不能为空")
        @DecimalMin(value = "-180.0", message = "longitude 超出范围")
        @DecimalMax(value = "180.0", message = "longitude 超出范围")
        Double longitude,
        @NotNull(message = "consentGranted 不能为空")
        Boolean consentGranted
) {
}
