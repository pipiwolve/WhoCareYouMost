package com.tay.medicalagent.web.support;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.service.report.ReportExportException;
import com.tay.medicalagent.app.service.report.ReportNotExportableException;
import com.tay.medicalagent.app.service.model.MedicalModelConfigurationException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
/**
 * Web 层统一异常处理。
 */
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
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
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "请求体格式不合法");
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleSessionNotFound(SessionNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedAttachmentsException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedAttachments(UnsupportedAttachmentsException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ReportNotExportableException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleReportNotExportable(ReportNotExportableException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(GraphRunnerException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleGraphRunnerException(GraphRunnerException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "问诊生成失败");
    }

    @ExceptionHandler(MedicalModelConfigurationException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleMedicalModelConfigurationException(MedicalModelConfigurationException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(ReportExportException.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleReportExportException(ReportExportException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @Hidden
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus httpStatus, String message) {
        return ResponseEntity.status(httpStatus)
                .body(ApiResponse.of(httpStatus.value(), message, null));
    }
}
