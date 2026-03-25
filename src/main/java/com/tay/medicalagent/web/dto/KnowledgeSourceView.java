package com.tay.medicalagent.web.dto;

/**
 * 前端知识来源视图。
 */
public record KnowledgeSourceView(
        String sourceId,
        String title,
        String section,
        Double score
) {
}
