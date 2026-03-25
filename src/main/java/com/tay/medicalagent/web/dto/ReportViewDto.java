package com.tay.medicalagent.web.dto;

import java.util.List;

/**
 * 前端报告展示模型。
 */
public record ReportViewDto(
        String title,
        String riskLevel,
        String summary,
        String assessment,
        List<String> basis,
        List<String> recommendations,
        List<String> redFlags,
        String disclaimer
) {
}
