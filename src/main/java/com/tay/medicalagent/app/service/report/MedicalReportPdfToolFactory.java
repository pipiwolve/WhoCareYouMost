package com.tay.medicalagent.app.service.report;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import com.tay.medicalagent.app.report.MedicalReportPdfToolRequest;
import com.tay.medicalagent.app.report.MedicalReportPdfToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 医疗报告 PDF 导出工具工厂。
 */
@Component
public class MedicalReportPdfToolFactory {

    private final MedicalReportPdfRenderer medicalReportPdfRenderer;
    private final ToolCallback toolCallback;

    public MedicalReportPdfToolFactory(MedicalReportPdfRenderer medicalReportPdfRenderer) {
        this.medicalReportPdfRenderer = medicalReportPdfRenderer;
        this.toolCallback = FunctionToolCallback
                .builder(MedicalReportPdfExportConstants.EXPORT_TOOL_NAME, this::exportCurrentReport)
                .description("Export the current structured medical diagnosis report as a PDF file.")
                .inputType(MedicalReportPdfToolRequest.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
                .build();
    }

    public ToolCallback createToolCallback() {
        return toolCallback;
    }

    private MedicalReportPdfToolResult exportCurrentReport(
            MedicalReportPdfToolRequest request,
            ToolContext toolContext
    ) {
        MedicalReportPdfPayload payload = ToolContextHelper.getMetadata(
                        toolContext,
                        MedicalReportPdfExportConstants.REPORT_PAYLOAD_CONTEXT_KEY,
                        MedicalReportPdfPayload.class
                )
                .orElseThrow(() -> new ReportExportException("缺少报告导出上下文"));

        byte[] pdfBytes = medicalReportPdfRenderer.render(payload);
        return new MedicalReportPdfToolResult(
                payload.fileName(),
                MediaType.APPLICATION_PDF_VALUE,
                Base64.getEncoder().encodeToString(pdfBytes),
                pdfBytes.length
        );
    }
}
