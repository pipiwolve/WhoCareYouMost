package com.tay.medicalagent.app.report;

/**
 * 医院规划意图。
 * <p>
 * 封装后端规则解析后的搜索关键词、医院类型与规划半径等参数，
 * 供 MCP Agent 与降级规划链路复用。
 */
public record MedicalPlanningIntent(
        boolean planningRequested,
        boolean explicitHospitalRequest,
        String triggerReason,
        String profileId,
        String hospitalKeyword,
        String hospitalTypes,
        int aroundRadiusMeters,
        int topK,
        boolean preferTier3a
) {

    public MedicalPlanningIntent {
        triggerReason = safeText(triggerReason);
        profileId = safeText(profileId);
        hospitalKeyword = safeText(hospitalKeyword);
        hospitalTypes = safeText(hospitalTypes);
        aroundRadiusMeters = Math.max(1, aroundRadiusMeters);
        topK = Math.max(1, topK);
    }

    public static MedicalPlanningIntent disabled(String reason) {
        return new MedicalPlanningIntent(
                false,
                false,
                reason,
                "disabled",
                "",
                "",
                1,
                1,
                false
        );
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
