package com.tay.medicalagent.app.chat;

import java.util.List;

public record StructuredMedicalReply(
        String riskLevel,
        String summary,
        List<String> basis,
        List<String> nextSteps,
        List<String> escalationSignals,
        List<String> followUpQuestions,
        String disclaimer
) {

    public static StructuredMedicalReply empty(String disclaimer) {
        return new StructuredMedicalReply("", "", List.of(), List.of(), List.of(), List.of(), disclaimer == null ? "" : disclaimer);
    }
}
