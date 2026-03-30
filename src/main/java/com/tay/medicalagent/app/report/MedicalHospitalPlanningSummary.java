package com.tay.medicalagent.app.report;

import java.util.List;

/**
 * 医院规划汇总。
 */
public record MedicalHospitalPlanningSummary(
        List<MedicalHospitalRecommendation> hospitals,
        boolean routesAvailable,
        String routeStatusMessage,
        String routeStatusCode
) {

    public static MedicalHospitalPlanningSummary empty() {
        return new MedicalHospitalPlanningSummary(List.of(), false, "", "none");
    }
}
