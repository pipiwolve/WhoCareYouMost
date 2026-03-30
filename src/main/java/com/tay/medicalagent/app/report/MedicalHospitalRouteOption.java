package com.tay.medicalagent.app.report;

import java.util.List;

/**
 * 医院路线方案。
 */
public record MedicalHospitalRouteOption(
        String mode,
        long distanceMeters,
        long durationMinutes,
        String summary,
        List<String> steps
) {

    public MedicalHospitalRouteOption(String mode, long distanceMeters, long durationMinutes, String summary) {
        this(mode, distanceMeters, durationMinutes, summary, List.of());
    }

    public MedicalHospitalRouteOption {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
