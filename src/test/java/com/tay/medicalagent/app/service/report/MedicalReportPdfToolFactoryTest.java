package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import com.tay.medicalagent.app.report.MedicalReportPdfToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalReportPdfToolFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolShouldReturnNonEmptyBase64PdfPayload() throws Exception {
        MedicalReportPdfRenderer renderer = mock(MedicalReportPdfRenderer.class);
        MedicalReportPdfToolFactory factory = new MedicalReportPdfToolFactory(renderer);
        ToolCallback toolCallback = factory.createToolCallback();

        MedicalReportPdfPayload payload = new MedicalReportPdfPayload(
                "sess_test",
                "thread_test",
                "usr_test",
                OffsetDateTime.now(),
                "medical-report-sess_test.pdf",
                "本报告由AI生成，仅供参考，不能替代专业医生诊断。",
                new MedicalDiagnosisReport(
                        "thread_test的医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "中风险",
                        "发热咳嗽",
                        "考虑呼吸道感染",
                        "",
                        List.of("发热"),
                        List.of("补液休息"),
                        List.of("持续高热"),
                        "建议观察"
                ),
                MedicalHospitalPlanningSummary.empty()
        );
        byte[] pdfBytes = "%PDF-tool-test".getBytes();
        when(renderer.render(payload)).thenReturn(pdfBytes);

        String rawResult = toolCallback.call(
                "{\"purpose\":\"export\"}",
                new ToolContext(Map.of(MedicalReportPdfExportConstants.REPORT_PAYLOAD_CONTEXT_KEY, payload))
        );

        MedicalReportPdfToolResult result = objectMapper.readValue(rawResult, MedicalReportPdfToolResult.class);
        assertEquals("medical-report-sess_test.pdf", result.fileName());
        assertEquals("application/pdf", result.contentType());
        assertFalse(result.base64Content().isBlank());
        assertEquals(pdfBytes.length, result.byteSize());
        assertArrayEquals(pdfBytes, Base64.getDecoder().decode(result.base64Content()));
    }
}
