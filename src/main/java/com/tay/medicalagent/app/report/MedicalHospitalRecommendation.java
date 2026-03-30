package com.tay.medicalagent.app.report;

import java.util.List;

/**
 * 推荐医院信息。
 */
public record MedicalHospitalRecommendation(
        String name,
        String address,
        boolean tier3a,
        long distanceMeters,
        List<MedicalHospitalRouteOption> routes
) {
}
