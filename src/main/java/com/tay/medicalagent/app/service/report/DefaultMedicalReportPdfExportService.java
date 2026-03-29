package com.tay.medicalagent.app.service.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.returndirect.ReturnDirectModelHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportPdfPayload;
import com.tay.medicalagent.app.report.MedicalReportPdfToolResult;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * 默认医疗报告 PDF 导出服务。
 */
@Service
public class DefaultMedicalReportPdfExportService implements MedicalReportPdfExportService {

    private static final String EXPORT_PROMPT = "请导出当前医疗诊断报告为 PDF。";

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final MedicalReportPdfToolFactory medicalReportPdfToolFactory;
    private final ObjectMapper objectMapper;

    public DefaultMedicalReportPdfExportService(
            MedicalAiModelProvider medicalAiModelProvider,
            MedicalReportPdfToolFactory medicalReportPdfToolFactory,
            ObjectMapper objectMapper
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.medicalReportPdfToolFactory = medicalReportPdfToolFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public MedicalReportPdfFile exportReportPdf(
            String sessionId,
            String threadId,
            String userId,
            MedicalDiagnosisReport report
    ) {
        if (report == null || !report.shouldGenerateReport()) {
            throw new ReportNotExportableException("当前会话暂无可导出的诊断报告");
        }

        MedicalReportPdfPayload payload = new MedicalReportPdfPayload(
                normalizeSessionId(sessionId),
                normalizeText(threadId, "当前线程"),
                normalizeText(userId, "anonymous"),
                OffsetDateTime.now(),
                "medical-report-" + normalizeSessionId(sessionId) + ".pdf",
                MedicalPrompts.DEFAULT_REPORT_DISCLAIMER,
                report
        );

        try {
            ReactAgent exportAgent = ReactAgent.builder()
                    .name("medical_report_export_agent")
                    .model(medicalAiModelProvider.getChatModel())
                    .systemPrompt(MedicalPrompts.REPORT_PDF_EXPORT_AGENT_PROMPT)
                    .tools(medicalReportPdfToolFactory.createToolCallback())
                    .toolContext(Map.of(MedicalReportPdfExportConstants.REPORT_PAYLOAD_CONTEXT_KEY, payload))
                    .hooks(new ReturnDirectModelHook())
                    .saver(new MemorySaver())
                    .build();

            AssistantMessage assistantMessage = exportAgent.call(
                    EXPORT_PROMPT,
                    buildRunnableConfig(payload)
            );
            MedicalReportPdfToolResult toolResult = parseToolResult(assistantMessage.getText());
            byte[] content = Base64.getDecoder().decode(toolResult.base64Content());
            if (content.length == 0) {
                throw new ReportExportException("报告导出失败");
            }
            return new MedicalReportPdfFile(
                    normalizeText(toolResult.fileName(), payload.fileName()),
                    normalizeText(toolResult.contentType(), MediaType.APPLICATION_PDF_VALUE),
                    content
            );
        }
        catch (ReportNotExportableException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ReportExportException("报告导出失败", ex);
        }
    }

    private RunnableConfig buildRunnableConfig(MedicalReportPdfPayload payload) throws JsonProcessingException {
        return RunnableConfig.builder()
                .threadId(payload.threadId() + "-pdf-export")
                .addMetadata("session_id", payload.sessionId())
                .addMetadata("thread_id", payload.threadId())
                .addMetadata("user_id", payload.userId())
                .addMetadata("report_json", objectMapper.writeValueAsString(payload.report()))
                .build();
    }

    private MedicalReportPdfToolResult parseToolResult(String payloadText) throws JsonProcessingException {
        if (payloadText == null || payloadText.isBlank()) {
            throw new ReportExportException("报告导出失败");
        }
        return objectMapper.readValue(payloadText, MedicalReportPdfToolResult.class);
    }

    private String normalizeSessionId(String sessionId) {
        return normalizeText(sessionId, "unknown-session");
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
