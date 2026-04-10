package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.report.MedicalReportBuildState;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.app.service.report.ReportNotExportableException;
import com.tay.medicalagent.web.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({ControllerTestConfig.class, GlobalExceptionHandler.class})
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @MockitoBean
    private MedicalApp medicalApp;

    @Test
    void getReportShouldReturn404WhenSessionMissing() throws Exception {
        mockMvc.perform(get("/v1/reports/sess_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void getReportShouldReturnReadyFalseWhenReportNotAvailable() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_report_1", "thread_report_1");
        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "无需生成诊断报告",
                false,
                "GENERAL_ADVICE_ONLY",
                "",
                "",
                "",
                "当前会话暂无足够问诊内容",
                List.of(),
                List.of(),
                List.of(),
                ""
        );
        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                consultationSession.sessionId(),
                "thread_report_1",
                "usr_report_1",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                report,
                MedicalHospitalPlanningSummary.empty()
        );
        when(medicalApp.getFinalReportStatus(
                consultationSession.sessionId(),
                "thread_report_1",
                "usr_report_1",
                null,
                null
        )).thenReturn(MedicalReportBuildState.notReady(
                snapshot,
                "当前会话暂无足够问诊内容",
                MedicalReportBuildState.REASON_CODE_INSUFFICIENT_CONTEXT
        ));

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.reason").value("当前会话暂无足够问诊内容"))
                .andExpect(jsonPath("$.data.status").value("not_ready"))
                .andExpect(jsonPath("$.data.reasonCode").value("insufficient_context"))
                .andExpect(jsonPath("$.data.report").value(nullValue()));
    }

    @Test
    void getReportShouldReturnReadyTrueWhenReportAvailable() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_report_2", "thread_report_2");
        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "thread_report_2的医疗诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "发热伴咳嗽 2 天",
                "考虑呼吸道感染",
                "",
                List.of("发热", "咳嗽"),
                List.of("补液休息", "观察体温"),
                List.of("持续高热", "呼吸困难"),
                "建议先观察"
        );
        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                consultationSession.sessionId(),
                "thread_report_2",
                "usr_report_2",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                report,
                MedicalHospitalPlanningSummary.empty()
        );
        when(medicalApp.getFinalReportStatus(
                consultationSession.sessionId(),
                "thread_report_2",
                "usr_report_2",
                null,
                null
        )).thenReturn(MedicalReportBuildState.ready(snapshot));

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.reason").value(""))
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.reasonCode").value(""))
                .andExpect(jsonPath("$.data.report.title").value("thread_report_2的医疗诊断报告"))
                .andExpect(jsonPath("$.data.report.riskLevel").value("中风险"))
                .andExpect(jsonPath("$.data.report.basis[0]").value("发热"))
                .andExpect(jsonPath("$.data.report.recommendations[0]").value("补液休息"))
                .andExpect(jsonPath("$.data.report.redFlags[0]").value("持续高热"))
                .andExpect(jsonPath("$.data.report.routeStatusCode").value("none"))
                .andExpect(jsonPath("$.data.report.disclaimer").value("本报告由AI生成，仅供参考，不能替代专业医生诊断。"));
    }

    @Test
    void getReportShouldReturnGeneratingStatusWhenBuildTimesOut() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_report_3", "thread_report_3");
        when(medicalApp.getFinalReportStatus(
                consultationSession.sessionId(),
                "thread_report_3",
                "usr_report_3",
                null,
                null
        )).thenReturn(MedicalReportBuildState.generating(
                "报告生成中，请稍后重试",
                MedicalReportBuildState.REASON_CODE_REPORT_WAIT_TIMEOUT,
                3000
        ));

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.status").value("generating"))
                .andExpect(jsonPath("$.data.reason").value("报告生成中，请稍后重试"))
                .andExpect(jsonPath("$.data.reasonCode").value("report_wait_timeout"))
                .andExpect(jsonPath("$.data.retryAfterMs").value(3000))
                .andExpect(jsonPath("$.data.report").value(nullValue()));
    }

    @Test
    void getReportShouldKeepTopLevelReadyWhenLocationIsMissingInsideRouteState() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_report_4", "thread_report_4");
        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "thread_report_4的医疗诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "胸闷",
                "建议尽快线下评估",
                "",
                List.of("胸闷"),
                List.of("尽快线下评估"),
                List.of("胸痛加重"),
                "建议尽快线下评估"
        );
        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                consultationSession.sessionId(),
                "thread_report_4",
                "usr_report_4",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                report,
                new MedicalHospitalPlanningSummary(List.of(), false, "未上传经纬度，无法进行就近医院规划", "location_missing")
        );
        when(medicalApp.getFinalReportStatus(
                consultationSession.sessionId(),
                "thread_report_4",
                "usr_report_4",
                null,
                null
        )).thenReturn(MedicalReportBuildState.ready(snapshot));

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.report.routeStatusCode").value("location_missing"))
                .andExpect(jsonPath("$.data.report.routeStatusMessage").value("未上传经纬度，无法进行就近医院规划"));
    }

    @Test
    void downloadReportPdfShouldReturn404WhenSessionMissing() throws Exception {
        mockMvc.perform(get("/v1/reports/sess_missing/pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void downloadReportPdfShouldReturn409WhenReportNotReady() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_pdf_1", "thread_pdf_1");
        when(medicalApp.exportReportPdf(
                eq(consultationSession.sessionId()),
                eq("thread_pdf_1"),
                eq("usr_pdf_1"),
                isNull(),
                isNull()
        )).thenThrow(new ReportNotExportableException("当前会话暂无可导出的诊断报告"));

        mockMvc.perform(get("/v1/reports/{sessionId}/pdf", consultationSession.sessionId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("当前会话暂无可导出的诊断报告"));
    }

    @Test
    void downloadReportPdfShouldReturnPdfBytes() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_pdf_2", "thread_pdf_2");
        when(medicalApp.exportReportPdf(
                eq(consultationSession.sessionId()),
                eq("thread_pdf_2"),
                eq("usr_pdf_2"),
                isNull(),
                isNull()
        )).thenReturn(new MedicalReportPdfFile(
                "medical-report-" + consultationSession.sessionId() + ".pdf",
                "application/pdf",
                "%PDF-test".getBytes()
        ));

        mockMvc.perform(get("/v1/reports/{sessionId}/pdf", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().string(startsWith("%PDF")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "attachment; filename=\"medical-report-" + consultationSession.sessionId() + ".pdf\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void downloadReportPdfShouldEncodeUnicodeFileName() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_pdf_3", "thread_pdf_3");
        when(medicalApp.exportReportPdf(
                eq(consultationSession.sessionId()),
                eq("thread_pdf_3"),
                eq("usr_pdf_3"),
                isNull(),
                isNull()
        )).thenReturn(new MedicalReportPdfFile(
                "张三的医疗诊断报告.pdf",
                "application/pdf",
                "%PDF-test".getBytes()
        ));

        mockMvc.perform(get("/v1/reports/{sessionId}/pdf", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition",
                                "attachment; filename=\"pdf\"; filename*=UTF-8''%E5%BC%A0%E4%B8%89%E7%9A%84%E5%8C%BB%E7%96%97%E8%AF%8A%E6%96%AD%E6%8A%A5%E5%91%8A.pdf"));
    }

    @Test
    void updateLocationShouldPersistCoordinatesWhenConsentGranted() throws Exception {
        ConsultationSession consultationSession = consultationSessionService.createSession("usr_loc_1", "thread_loc_1");

        mockMvc.perform(post("/v1/reports/{sessionId}/location", consultationSession.sessionId())
                        .contentType("application/json")
                        .content("""
                                {
                                  "latitude": 31.23,
                                  "longitude": 121.47,
                                  "consentGranted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("OK"));

        ConsultationSession updatedSession = consultationSessionService.getRequiredSession(consultationSession.sessionId());
        assertEquals(31.23, updatedSession.latitude());
        assertEquals(121.47, updatedSession.longitude());
        assertNotNull(updatedSession.locationAuthorizedAt());
        verify(medicalApp).invalidateReportSnapshot(consultationSession.sessionId());
    }
}
