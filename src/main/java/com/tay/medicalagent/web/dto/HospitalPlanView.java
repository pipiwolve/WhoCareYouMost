package com.tay.medicalagent.web.dto;

import java.util.List;

/**
 * 医院规划展示模型。
 */
public record HospitalPlanView(
        String name,
        String address,
        boolean tier3a,
        long distanceMeters,
        List<HospitalRouteOptionView> routes
) {
}
