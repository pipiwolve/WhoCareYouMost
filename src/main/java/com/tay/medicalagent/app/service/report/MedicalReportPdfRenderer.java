package com.tay.medicalagent.app.service.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 医疗报告 PDF 渲染器。
 */
@Component
public class MedicalReportPdfRenderer {

    private static final String DEFAULT_FONT_RESOURCE = "fonts/HiraginoSansGB.ttc";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String configuredFontPath;

    private volatile String bundledFontPath;

    public MedicalReportPdfRenderer(@Value("${medical.report.pdf.font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
    }

    public byte[] render(MedicalReportPdfPayload payload) {
        if (payload == null || payload.report() == null) {
            throw new ReportExportException("缺少可用于导出的报告内容");
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 48, 48, 60, 48);
            PdfWriter.getInstance(document, outputStream);
            document.open();

            BaseFont baseFont = createBaseFont();
            Font titleFont = new Font(baseFont, 20, Font.BOLD);
            Font labelFont = new Font(baseFont, 13, Font.BOLD);
            Font bodyFont = new Font(baseFont, 11, Font.NORMAL);
            Font metaFont = new Font(baseFont, 10, Font.NORMAL);

            MedicalDiagnosisReport report = payload.report();
            document.addTitle(report.reportTitle());

            Paragraph title = new Paragraph(report.reportTitle(), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(12f);
            document.add(title);

            Paragraph meta = new Paragraph(
                    "生成时间：" + DATE_TIME_FORMATTER.format(payload.generatedAt()) + "\n"
                            + "会话ID：" + payload.sessionId(),
                    metaFont
            );
            meta.setSpacingAfter(18f);
            document.add(meta);

            addTextSection(document, "报告标题", report.reportTitle(), labelFont, bodyFont);
            addTextSection(document, "风险等级", report.currentRiskLevel(), labelFont, bodyFont);
            addTextSection(document, "患者概述", report.patientSummary(), labelFont, bodyFont);
            addTextSection(document, "初步判断", report.preliminaryAssessment(), labelFont, bodyFont);
            addListSection(document, "主要依据", report.mainBasis(), labelFont, bodyFont);
            addListSection(document, "建议下一步", report.nextStepSuggestions(), labelFont, bodyFont);
            addListSection(document, "升级就医信号", report.escalationCriteria(), labelFont, bodyFont);
            addTextSection(document, "免责声明", payload.disclaimer(), labelFont, bodyFont);

            document.close();
            return outputStream.toByteArray();
        }
        catch (IOException | DocumentException ex) {
            throw new ReportExportException("报告导出失败", ex);
        }
    }

    private void addTextSection(
            Document document,
            String title,
            String content,
            Font labelFont,
            Font bodyFont
    ) throws DocumentException {
        addSectionTitle(document, title, labelFont);
        Paragraph paragraph = new Paragraph(normalizeText(content), bodyFont);
        paragraph.setSpacingAfter(12f);
        document.add(paragraph);
    }

    private void addListSection(
            Document document,
            String title,
            List<String> values,
            Font labelFont,
            Font bodyFont
    ) throws DocumentException {
        addSectionTitle(document, title, labelFont);
        if (values == null || values.isEmpty()) {
            Paragraph empty = new Paragraph("暂无", bodyFont);
            empty.setSpacingAfter(12f);
            document.add(empty);
            return;
        }

        for (String value : values) {
            Paragraph item = new Paragraph("- " + normalizeText(value), bodyFont);
            item.setIndentationLeft(12f);
            item.setSpacingAfter(4f);
            document.add(item);
        }
        Paragraph spacer = new Paragraph("", bodyFont);
        spacer.setSpacingAfter(8f);
        document.add(spacer);
    }

    private void addSectionTitle(Document document, String title, Font labelFont) throws DocumentException {
        Paragraph heading = new Paragraph(title, labelFont);
        heading.setSpacingBefore(4f);
        heading.setSpacingAfter(6f);
        document.add(heading);
    }

    private BaseFont createBaseFont() throws IOException, DocumentException {
        String fontPath = resolveFontPath();
        String fontSpec = fontPath.endsWith(".ttc") ? fontPath + ",0" : fontPath;
        return BaseFont.createFont(fontSpec, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
    }

    private String resolveFontPath() throws IOException {
        if (!configuredFontPath.isBlank()) {
            return configuredFontPath;
        }

        if (bundledFontPath == null) {
            synchronized (this) {
                if (bundledFontPath == null) {
                    bundledFontPath = extractBundledFont();
                }
            }
        }
        return bundledFontPath;
    }

    private String extractBundledFont() throws IOException {
        ClassPathResource resource = new ClassPathResource(DEFAULT_FONT_RESOURCE);
        if (!resource.exists()) {
            throw new ReportExportException("未找到默认 PDF 中文字体资源");
        }

        Path tempFile = Files.createTempFile("medical-report-font-", ".ttc");
        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        tempFile.toFile().deleteOnExit();
        return tempFile.toAbsolutePath().toString();
    }

    private String normalizeText(String value) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? "暂无" : text;
    }
}
