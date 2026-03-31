package com.tay.medicalagent.app.service.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalHospitalRecommendation;
import com.tay.medicalagent.app.report.MedicalHospitalRouteOption;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalReportPdfRendererTest {

    private final MedicalReportPdfRenderer renderer = new MedicalReportPdfRenderer("");

    @Test
    void rendererShouldGenerateReadablePdfWithExpectedSections() throws Exception {
        byte[] pdfBytes = renderer.render(samplePayload());

        assertTrue(pdfBytes.length > 0);
        assertTrue(new String(pdfBytes, 0, 4).startsWith("%PDF"));

        try (PdfReader reader = new PdfReader(pdfBytes)) {
            assertTrue(reader.getNumberOfPages() >= 1);
            String extractedText = readAllText(reader);

            assertContainsAll(
                    extractedText,
                    "thread-renderer的医疗诊断报告",
                    "会话ID：sess_renderer",
                    "风险等级",
                    "中风险",
                    "患者概述",
                    "发热伴咳嗽 2 天",
                    "初步判断",
                    "考虑呼吸道感染",
                    "主要依据",
                    "发热",
                    "建议下一步",
                    "补液休息",
                    "升级就医信号",
                    "持续高热",
                    "就近医院与路线规划",
                    "复旦大学附属华山医院",
                    "步行",
                    "步行 200 米前往医院门诊入口",
                    "免责声明",
                    "本报告由AI生成，仅供参考，不能替代专业医生诊断。"
            );
        }
    }

    private MedicalReportPdfPayload samplePayload() {
        return new MedicalReportPdfPayload(
                "sess_renderer",
                "thread_renderer",
                "usr_renderer",
                OffsetDateTime.parse("2026-03-27T18:00:00+08:00"),
                "medical-report-sess_renderer.pdf",
                "本报告由AI生成，仅供参考，不能替代专业医生诊断。",
                new MedicalDiagnosisReport(
                        "thread-renderer的医疗诊断报告",
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
                    ),
                    new MedicalHospitalPlanningSummary(
                        List.of(new MedicalHospitalRecommendation(
                            "复旦大学附属华山医院",
                            "上海市静安区乌鲁木齐中路12号",
                            true,
                            1800,
                            List.of(
                                new MedicalHospitalRouteOption("WALK", 1800, 24, "步行方案", List.of("步行 200 米前往医院门诊入口")),
                                new MedicalHospitalRouteOption("DRIVE", 1800, 5, "驾车方案", List.of("沿延安中路行驶后抵达医院")),
                                new MedicalHospitalRouteOption("TRANSIT", 1800, 12, "公交方案", List.of("步行至地铁站后换乘到医院"))
                            )
                        )),
                        true,
                        "",
                        "ok"
                    )
        );
    }

    private String readAllText(PdfReader reader) throws IOException {
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder builder = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            builder.append(extractor.getTextFromPage(page)).append('\n');
        }
        return builder.toString();
    }

    private void assertContainsAll(String text, String... expectedValues) {
        for (String expectedValue : expectedValues) {
            assertTrue(
                    text.contains(expectedValue),
                    () -> "Expected PDF text to contain: " + expectedValue + "\nActual text:\n" + text
            );
        }
    }
}
