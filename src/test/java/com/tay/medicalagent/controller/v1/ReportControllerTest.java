package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

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
        when(medicalApp.generateReportFromThread("thread_report_1", "usr_report_1")).thenReturn(report);

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.reason").value("当前会话暂无足够问诊内容"))
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
        when(medicalApp.generateReportFromThread("thread_report_2", "usr_report_2")).thenReturn(report);

        mockMvc.perform(get("/v1/reports/{sessionId}", consultationSession.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.report.title").value("thread_report_2的医疗诊断报告"))
                .andExpect(jsonPath("$.data.report.riskLevel").value("中风险"))
                .andExpect(jsonPath("$.data.report.basis[0]").value("发热"))
                .andExpect(jsonPath("$.data.report.recommendations[0]").value("补液休息"))
                .andExpect(jsonPath("$.data.report.redFlags[0]").value("持续高热"))
                .andExpect(jsonPath("$.data.report.disclaimer").value("本报告由AI生成，仅供参考，不能替代专业医生诊断。"));
    }
}
