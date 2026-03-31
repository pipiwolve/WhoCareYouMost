package com.tay.medicalagent.web.support;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.service.report.ReportExportException;
import com.tay.medicalagent.app.service.report.ReportNotExportableException;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
/**
 * Web 层统一异常处理。
 */
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final DisconnectedClientHelper disconnectedClientHelper =
            new DisconnectedClientHelper(GlobalExceptionHandler.class.getName());

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        log.warn("Request validation failed. request={}, message={}", currentRequestSummary(), message);
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            IllegalArgumentException.class
    })
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "请求参数不合法" : ex.getMessage();
        log.warn("Request validation failed. request={}, message={}", currentRequestSummary(), message);
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Request body unreadable. request={}, reason={}", currentRequestSummary(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "请求体格式不合法");
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleSessionNotFound(SessionNotFoundException ex) {
        log.warn("Session not found. request={}, message={}", currentRequestSummary(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedAttachmentsException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedAttachments(UnsupportedAttachmentsException ex) {
        log.warn("Unsupported attachment request. request={}, message={}", currentRequestSummary(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ReportNotExportableException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleReportNotExportable(ReportNotExportableException ex) {
        log.warn("Report not exportable. request={}, message={}", currentRequestSummary(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(GraphRunnerException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleGraphRunnerException(GraphRunnerException ex) {
        log.error("Graph runner failed. request={}", currentRequestSummary(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "问诊生成失败");
    }

    @ExceptionHandler(TransientAiException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleTransientAiException(TransientAiException ex) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage();
        String normalized = detail.toLowerCase();
        if (normalized.contains("too many requests") || normalized.contains("throttl")) {
            log.warn("Upstream AI service throttled request. request={}, detail={}", currentRequestSummary(), detail);
            return build(HttpStatus.TOO_MANY_REQUESTS, "模型服务繁忙，请稍后重试");
        }
        if (normalized.contains("serviceunavailable") || normalized.contains("503")) {
            log.warn("Upstream AI service unavailable. request={}, detail={}", currentRequestSummary(), detail);
            return build(HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂时不可用，请稍后重试");
        }
        log.warn("Transient AI exception. request={}, detail={}", currentRequestSummary(), detail);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂时波动，请稍后重试");
    }

    @ExceptionHandler(MedicalModelConfigurationException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleMedicalModelConfigurationException(MedicalModelConfigurationException ex) {
        log.error("Medical model configuration error. request={}", currentRequestSummary(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(ReportExportException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleReportExportException(ReportExportException ex) {
        log.error("Report export failed. request={}", currentRequestSummary(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleResourceAccessException(ResourceAccessException ex) {
        log.warn("Upstream access failed. request={}, reason={}", currentRequestSummary(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "模型服务网络异常，请稍后重试");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @Hidden
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
        disconnectedClientHelper.checkAndLogClientDisconnectedException(ex);
    }

    @ExceptionHandler(Exception.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        if (disconnectedClientHelper.checkAndLogClientDisconnectedException(ex)) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        log.error("Unexpected request failure. request={}", currentRequestSummary(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus httpStatus, String message) {
        if (isSseRequest()) {
            return ResponseEntity.status(httpStatus).build();
        }
        return ResponseEntity.status(httpStatus)
                .body(ApiResponse.of(httpStatus.value(), message, null));
    }

    private boolean isSseRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private String currentRequestSummary() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        if (request == null) {
            return "unknown";
        }
        String query = request.getQueryString();
        return request.getMethod() + " " + request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
