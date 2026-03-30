package com.tay.medicalagent.web.dto;

import java.util.List;

/**
 * 医院路线展示模型。
 */
public record HospitalRouteOptionView(
        String mode,
        long distanceMeters,
        long durationMinutes,
        String summary,
        List<String> steps
) {

    public HospitalRouteOptionView(String mode, long distanceMeters, long durationMinutes, String summary) {
        this(mode, distanceMeters, durationMinutes, summary, List.of());
    }

    public HospitalRouteOptionView {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
