package com.tay.medicalagent.app.report;

/**
 * 报告生成相关上下文的轻量指纹快照。
 * <p>
 * 用于判断异步预热任务是否仍然对应同一份问诊与画像上下文。
 */
public record MedicalReportContextSnapshot(
        String conversationFingerprint,
        String profileFingerprint,
        String locationFingerprint
) {

    public boolean matchesReportInputs(MedicalReportContextSnapshot other) {
        if (other == null) {
            return false;
        }
        return safeText(conversationFingerprint).equals(safeText(other.conversationFingerprint()))
                && safeText(profileFingerprint).equals(safeText(other.profileFingerprint()));
    }

    public boolean matchesLocation(MedicalReportContextSnapshot other) {
        if (other == null) {
            return false;
        }
        return safeText(locationFingerprint).equals(safeText(other.locationFingerprint()));
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
