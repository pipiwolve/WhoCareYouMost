package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMedicalReportPdfExportServiceTest {

    @Test
    void exportServiceShouldInvokeToolPathAndReturnPdfFile() {
        MedicalAiModelProvider provider = mock(MedicalAiModelProvider.class);
        MedicalReportPdfRenderer renderer = mock(MedicalReportPdfRenderer.class);
        MedicalReportPdfToolFactory toolFactory = new MedicalReportPdfToolFactory(renderer);
        when(provider.getChatModel()).thenReturn(new ToolCallingStubChatModel());

        byte[] pdfBytes = "%PDF-service-test".getBytes();
        when(renderer.render(org.mockito.ArgumentMatchers.any())).thenReturn(pdfBytes);

        DefaultMedicalReportPdfExportService service = new DefaultMedicalReportPdfExportService(
                provider,
                toolFactory,
                new ObjectMapper()
        );

        MedicalReportPdfFile pdfFile = service.exportReportPdf(
                "sess_export",
                "thread_export",
                "usr_export",
                new MedicalDiagnosisReport(
                        "thread_export的医疗诊断报告",
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
                )
        );

        assertEquals("medical-report-sess_export.pdf", pdfFile.fileName());
        assertEquals("application/pdf", pdfFile.contentType());
        assertArrayEquals(pdfBytes, pdfFile.content());
        verify(renderer).render(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exportServiceShouldRejectUnavailableReport() {
        MedicalAiModelProvider provider = mock(MedicalAiModelProvider.class);
        MedicalReportPdfRenderer renderer = mock(MedicalReportPdfRenderer.class);
        DefaultMedicalReportPdfExportService service = new DefaultMedicalReportPdfExportService(
                provider,
                new MedicalReportPdfToolFactory(renderer),
                new ObjectMapper()
        );

        assertThrows(ReportNotExportableException.class, () -> service.exportReportPdf(
                "sess_export",
                "thread_export",
                "usr_export",
                new MedicalDiagnosisReport(
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
                )
        ));
    }

    private static final class ToolCallingStubChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_export_1",
                            "function",
                            MedicalReportPdfExportConstants.EXPORT_TOOL_NAME,
                            "{\"purpose\":\"export_current_report\"}"
                    )))
                    .build();
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
    }
}
