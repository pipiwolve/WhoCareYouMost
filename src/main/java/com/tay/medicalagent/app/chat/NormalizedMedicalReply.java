package com.tay.medicalagent.app.chat;

public record NormalizedMedicalReply(
        String reply,
        StructuredMedicalReply structuredReply
) {
}
