package com.tay.medicalagent.app.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMedicalReportPdfExportServiceIntegrationTest {

    @Test
    void exportServiceShouldGenerateRealPdfThroughToolCallingPath() throws Exception {
        MedicalAiModelProvider provider = mock(MedicalAiModelProvider.class);
        when(provider.getChatModel()).thenReturn(new ToolCallingStubChatModel());

        DefaultMedicalReportPdfExportService service = new DefaultMedicalReportPdfExportService(
                provider,
                new MedicalReportPdfToolFactory(new MedicalReportPdfRenderer("")),
                new ObjectMapper()
        );

        MedicalReportPdfFile pdfFile = service.exportReportPdf(
                "sess_integration",
                "thread_integration",
                "usr_integration",
                new MedicalDiagnosisReport(
                        "thread-integration的医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "高风险",
                        "胸痛伴呼吸困难",
                        "警惕急性冠脉综合征等严重情况",
                        "",
                        List.of("胸痛", "呼吸困难"),
                        List.of("立即急诊"),
                        List.of("持续胸痛加重"),
                        "建议立即就医"
                )
        );

        assertEquals("medical-report-sess_integration.pdf", pdfFile.fileName());
        assertEquals("application/pdf", pdfFile.contentType());
        assertTrue(pdfFile.content().length > 0);
        assertTrue(new String(pdfFile.content(), 0, 4).startsWith("%PDF"));

        try (PdfReader reader = new PdfReader(pdfFile.content())) {
            String extractedText = readAllText(reader);
            assertTrue(extractedText.contains("thread-integration的医疗诊断报告"));
            assertTrue(extractedText.contains("高风险"));
            assertTrue(extractedText.contains("立即急诊"));
        }
    }

    private String readAllText(PdfReader reader) throws IOException {
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder builder = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            builder.append(extractor.getTextFromPage(page)).append('\n');
        }
        return builder.toString();
    }

    private static final class ToolCallingStubChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_export_integration",
                            "function",
                            MedicalReportPdfExportConstants.EXPORT_TOOL_NAME,
                            "{\"purpose\":\"export_current_report\"}"
                    )))
                    .build();
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
    }
}
