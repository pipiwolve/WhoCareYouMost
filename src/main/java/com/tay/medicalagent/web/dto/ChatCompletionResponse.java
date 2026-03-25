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
        boolean reportGenerated,
        ReportViewDto report,
        boolean ragApplied,
        List<KnowledgeSourceView> sources
) {
}
