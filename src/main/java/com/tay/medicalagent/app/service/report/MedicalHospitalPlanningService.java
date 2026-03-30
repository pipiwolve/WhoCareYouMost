package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;

/**
 * 医院规划服务。
 */
public interface MedicalHospitalPlanningService {

    /**
     * 生成就近医院与路线规划信息。
     *
     * @param latitude 用户纬度
     * @param longitude 用户经度
     * @param report 结构化诊断报告
     * @return 医院规划汇总
     */
    MedicalHospitalPlanningSummary plan(Double latitude, Double longitude, MedicalDiagnosisReport report);

    /**
     * 生成带规划意图的就近医院与路线规划信息。
     *
     * @param latitude 用户纬度
     * @param longitude 用户经度
     * @param report 结构化诊断报告
     * @param planningIntent 后端规则解析后的规划意图
     * @return 医院规划汇总
     */
    MedicalHospitalPlanningSummary plan(
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport report,
            MedicalPlanningIntent planningIntent
    );
}
