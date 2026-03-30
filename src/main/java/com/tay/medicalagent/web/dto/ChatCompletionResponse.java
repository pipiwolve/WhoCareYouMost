package com.tay.medicalagent.web.dto;

import java.util.List;

/**
 * 医疗问答响应。
 */
public record ChatCompletionResponse(
        String sessionId,
        String reply,
        StructuredReplyView structuredReply,
        boolean reportAvailable,
        String reportReason,
        String reportTriggerLevel,
        String reportActionText,
        boolean reportGenerated,
        ReportViewDto report,
        ReportViewDto reportPreview,
        boolean ragApplied,
        List<KnowledgeSourceView> sources
) {
}
