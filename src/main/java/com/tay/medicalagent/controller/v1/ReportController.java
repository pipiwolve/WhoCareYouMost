package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.dto.ReportQueryResponse;
import com.tay.medicalagent.web.support.ApiResponse;
import com.tay.medicalagent.web.support.MedicalApiViewMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reports")
/**
 * 医疗报告查询接口。
 */
public class ReportController {

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
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(sessionId);
        MedicalDiagnosisReport medicalDiagnosisReport = medicalApp.generateReportFromThread(
                consultationSession.threadId(),
                consultationSession.userId()
        );
        return ApiResponse.success(medicalApiViewMapper.toReportQueryResponse(medicalDiagnosisReport));
    }

    @GetMapping(value = "/{sessionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadReportPdf(@PathVariable String sessionId) {
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(sessionId);
        MedicalReportPdfFile pdfFile = medicalApp.exportReportPdf(
                sessionId,
                consultationSession.threadId(),
                consultationSession.userId()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pdfFile.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdfFile.fileName() + "\"")
                .contentLength(pdfFile.content().length)
                .body(pdfFile.content());
    }
}
