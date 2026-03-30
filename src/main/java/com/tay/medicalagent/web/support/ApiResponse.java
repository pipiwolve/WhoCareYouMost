package com.tay.medicalagent.web.support;

/**
 * 统一 JSON 响应包装。
 *
 * @param code HTTP 对齐状态码
 * @param message 响应说明
 * @param data 业务数据
 * @param <T> 数据类型
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> of(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
