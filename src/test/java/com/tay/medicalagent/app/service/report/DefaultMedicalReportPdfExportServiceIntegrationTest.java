package com.tay.medicalagent.app.service.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMedicalReportPdfExportServiceIntegrationTest {

    @Test
    void exportServiceShouldGenerateRealPdfThroughLocalRendererPath() throws Exception {
        DefaultMedicalReportPdfExportService service =
                new DefaultMedicalReportPdfExportService(new MedicalReportPdfRenderer(""));

        MedicalReportPdfFile pdfFile = service.exportReportPdf(
                "sess_integration",
                "thread_integration",
                "usr_integration",
                new MedicalDiagnosisReport(
                        "李四的医疗诊断报告",
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
                    ),
                    MedicalHospitalPlanningSummary.empty()
        );

        assertEquals("李四的医疗诊断报告.pdf", pdfFile.fileName());
        assertEquals("application/pdf", pdfFile.contentType());
        assertTrue(pdfFile.content().length > 0);
        assertTrue(new String(pdfFile.content(), 0, 4).startsWith("%PDF"));

        try (PdfReader reader = new PdfReader(pdfFile.content())) {
            String extractedText = readAllText(reader);
            assertTrue(extractedText.contains("李四的医疗诊断报告"));
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

}
