package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.dto.ReportLocationUpdateRequest;
import com.tay.medicalagent.web.dto.ReportQueryResponse;
import com.tay.medicalagent.web.support.ApiResponse;
import com.tay.medicalagent.web.support.MedicalApiViewMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/v1/reports")
/**
 * 医疗报告查询接口。
 */
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final MedicalApp medicalApp;
    private final ConsultationSessionService consultationSessionService;
    private final MedicalApiViewMapper medicalApiViewMapper;

    public ReportController(
            MedicalApp medicalApp,
            ConsultationSessionService consultationSessionService,
            MedicalApiViewMapper medicalApiViewMapper
    ) {
        this.medicalApp = medicalApp;
        this.consultationSessionService = consultationSessionService;
        this.medicalApiViewMapper = medicalApiViewMapper;
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<ReportQueryResponse> getReport(@PathVariable String sessionId) {
        log.info("Report query started. sessionId={}", sessionId);
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(sessionId);
        MedicalReportSnapshot snapshot = medicalApp.getOrCreateReportSnapshot(
                sessionId,
                consultationSession.threadId(),
                consultationSession.userId(),
                consultationSession.latitude(),
                consultationSession.longitude()
        );
        MedicalDiagnosisReport medicalDiagnosisReport = snapshot.report();
        MedicalHospitalPlanningSummary planningSummary = snapshot.planningSummary();
        log.info(
                "Report query finished. sessionId={}, ready={}, hospitalCount={}, routesAvailable={}",
                sessionId,
                medicalDiagnosisReport != null && medicalDiagnosisReport.shouldGenerateReport(),
                planningSummary == null || planningSummary.hospitals() == null ? 0 : planningSummary.hospitals().size(),
                planningSummary != null && planningSummary.routesAvailable()
        );
        return ApiResponse.success(medicalApiViewMapper.toReportQueryResponse(medicalDiagnosisReport, planningSummary));
    }

        @PostMapping(value = "/{sessionId}/location", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ApiResponse<Void> updateLocation(
            @PathVariable String sessionId,
            @Valid @RequestBody ReportLocationUpdateRequest request
        ) {
        consultationSessionService.updateLocation(
            sessionId,
            request.latitude(),
            request.longitude(),
            Boolean.TRUE.equals(request.consentGranted())
        );
        medicalApp.invalidateReportSnapshot(sessionId);
        log.info(
                "Report location updated. sessionId={}, latitude={}, longitude={}",
                sessionId,
                request.latitude(),
                request.longitude()
        );
        return ApiResponse.success(null);
        }

    @GetMapping(value = "/{sessionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadReportPdf(@PathVariable String sessionId) {
        log.info("Report PDF download started. sessionId={}", sessionId);
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(sessionId);
        MedicalReportPdfFile pdfFile = medicalApp.exportReportPdf(
                sessionId,
                consultationSession.threadId(),
            consultationSession.userId(),
            consultationSession.latitude(),
            consultationSession.longitude()
        );
        log.info("Report PDF download finished. sessionId={}, fileName={}, size={}", sessionId, pdfFile.fileName(), pdfFile.content().length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pdfFile.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(pdfFile.fileName()))
                .contentLength(pdfFile.content().length)
                .body(pdfFile.content());
    }

    private String buildContentDisposition(String fileName) {
        String normalizedFileName = (fileName == null || fileName.isBlank()) ? "medical-report.pdf" : fileName.trim();
        if (StandardCharsets.US_ASCII.newEncoder().canEncode(normalizedFileName)) {
            return "attachment; filename=\"" + normalizedFileName.replace("\"", "") + "\"";
        }

        String fallback = normalizedFileName.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (fallback.isBlank()) {
            fallback = "medical-report.pdf";
        }
        String encoded = UriUtils.encode(normalizedFileName, StandardCharsets.UTF_8);
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
