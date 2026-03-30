package com.tay.medicalagent.app.report;

import java.time.Instant;

/**
 * 会话级冻结报告快照。
 * <p>
 * 同一会话内的聊天预览、报告查询与 PDF 导出统一读取该快照，
 * 以保证内容一致并避免重复调用模型与地图规划。
 */
public record MedicalReportSnapshot(
        String sessionId,
        String threadId,
        String userId,
        Instant generatedAt,
        String conversationFingerprint,
        String profileFingerprint,
        String locationFingerprint,
        MedicalDiagnosisReport report,
        MedicalHospitalPlanningSummary planningSummary
) {

    public boolean isFresh(
            String currentConversationFingerprint,
            String currentProfileFingerprint,
            String currentLocationFingerprint
    ) {
        return safeText(conversationFingerprint).equals(safeText(currentConversationFingerprint))
                && safeText(profileFingerprint).equals(safeText(currentProfileFingerprint))
                && safeText(locationFingerprint).equals(safeText(currentLocationFingerprint));
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
