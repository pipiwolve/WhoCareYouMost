package com.tay.medicalagent.web.dto;

import java.util.List;

public record StructuredReplyView(
        String riskLevel,
        String summary,
        List<String> basis,
        List<String> nextSteps,
        List<String> escalationSignals,
        List<String> followUpQuestions,
        String disclaimer
) {
}
