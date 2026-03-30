package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 基于风险等级与症状关键词的医院规划意图解析器。
 */
@Component
public class DefaultMedicalPlanningIntentResolver implements MedicalPlanningIntentResolver {

    private static final List<String> EXPLICIT_HOSPITAL_REQUEST_PHRASES = List.of(
            "附近医院",
            "附近的医院",
            "去哪家医院",
            "去哪个医院",
            "帮我规划医院",
            "帮我规划路线",
            "帮我找医院",
            "就近医院",
            "附近急诊",
            "附近门诊"
    );

    private static final List<String> URGENT_PHRASES = List.of(
            "立即急诊",
            "立即就医",
            "尽快就医",
            "建议急诊",
            "马上急诊",
            "胸痛中心",
            "卒中中心"
    );

    private final MedicalReportPlanningProperties planningProperties;

    public DefaultMedicalPlanningIntentResolver(MedicalReportPlanningProperties planningProperties) {
        this.planningProperties = planningProperties;
    }

    @Override
    public boolean shouldPrepareChatPreview(String latestUserMessage, MedicalChatResult medicalChatResult) {
        if (medicalChatResult == null) {
            return containsAny(latestUserMessage, EXPLICIT_HOSPITAL_REQUEST_PHRASES);
        }
        if (medicalChatResult.reportGenerated()) {
            return true;
        }
        if (medicalChatResult.reportTriggerLevel() != null && medicalChatResult.reportTriggerLevel().isAvailable()) {
            return true;
        }
        return containsAny(latestUserMessage, EXPLICIT_HOSPITAL_REQUEST_PHRASES);
    }

    @Override
    public MedicalPlanningIntent resolve(
            MedicalDiagnosisReport report,
            StructuredMedicalReply structuredMedicalReply,
            String latestUserMessage,
            ReportTriggerLevel reportTriggerLevel
    ) {
        if (report == null) {
            return MedicalPlanningIntent.disabled("无可用于规划的报告");
        }

        boolean explicitHospitalRequest = containsAny(latestUserMessage, EXPLICIT_HOSPITAL_REQUEST_PHRASES);
        boolean planningRequested = explicitHospitalRequest
                || (reportTriggerLevel != null && reportTriggerLevel.isAvailable())
                || report.shouldGenerateReport();

        if (!planningRequested) {
            return MedicalPlanningIntent.disabled("当前问诊阶段未触发医院规划");
        }

        Profile profile = resolveProfile(report, structuredMedicalReply, latestUserMessage, reportTriggerLevel);
        String reason = explicitHospitalRequest
                ? "用户明确请求附近医院或路线规划"
                : "当前问诊已达到报告/就医建议阈值";

        return new MedicalPlanningIntent(
                true,
                explicitHospitalRequest,
                reason,
                profile.id,
                profile.keyword,
                profile.types,
                profile.radiusMeters,
                profile.topK,
                profile.preferTier3a
        );
    }

    @Override
    public MedicalPlanningIntent resolve(MedicalDiagnosisReport report) {
        ReportTriggerLevel level = report != null && report.shouldGenerateReport()
                ? ReportTriggerLevel.RECOMMENDED
                : ReportTriggerLevel.NONE;
        return resolve(report, StructuredMedicalReply.empty(""), "", level);
    }

    private Profile resolveProfile(
            MedicalDiagnosisReport report,
            StructuredMedicalReply structuredMedicalReply,
            String latestUserMessage,
            ReportTriggerLevel reportTriggerLevel
    ) {
        String text = normalizeForMatch(latestUserMessage)
                + " " + normalizeForMatch(report == null ? "" : report.patientSummary())
                + " " + normalizeForMatch(report == null ? "" : report.preliminaryAssessment())
                + " " + normalizeForMatch(report == null ? "" : report.currentRiskLevel())
                + " " + normalizeForMatch(structuredMedicalReply == null ? "" : structuredMedicalReply.summary())
                + " " + normalizeForMatch(structuredMedicalReply == null ? "" : structuredMedicalReply.riskLevel())
                + " " + normalizeForMatch(report == null ? "" : report.assistantReply());

        if (reportTriggerLevel == ReportTriggerLevel.URGENT || containsAny(text, URGENT_PHRASES)) {
            return profile("emergency", "急诊", "090101|090100|090102", 10000, 3, true);
        }
        if (containsAny(text, List.of("胸痛", "胸闷", "心慌", "心悸", "心前区", "冠脉", "心梗", "心脏"))) {
            return profile("cardiac", "心血管医院", "090101|090100|090102", 12000, 3, true);
        }
        if (containsAny(text, List.of("呼吸困难", "气喘", "喘", "咳嗽", "肺炎", "呼吸", "哮喘"))) {
            return profile("respiratory", "呼吸科 医院", defaultTypes(), 6000, defaultTopK(), true);
        }
        if (containsAny(text, List.of("头痛", "头晕", "头昏", "偏瘫", "言语不清", "抽搐", "癫痫", "卒中", "中风"))) {
            return profile("neurology", "神经内科 医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, List.of("儿童", "小孩", "宝宝", "婴儿", "儿科", "高热惊厥"))) {
            return profile("pediatric", "儿童医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, List.of("孕", "妊娠", "产后", "阴道出血", "宫缩", "胎动", "妇产"))) {
            return profile("obstetric", "妇产医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, List.of("骨折", "扭伤", "外伤", "关节", "腰痛", "颈椎", "背痛", "骨科"))) {
            return profile("orthopedic", "骨科医院", defaultTypes(), 6000, defaultTopK(), false);
        }
        if (containsAny(text, List.of("牙痛", "口腔", "牙龈", "牙齿", "智齿"))) {
            return profile("dental", "口腔医院", defaultTypes(), 5000, defaultTopK(), false);
        }
        if (containsAny(text, List.of("焦虑", "抑郁", "惊恐", "自杀", "失眠", "情绪", "精神"))) {
            return profile("psychiatry", "精神卫生中心", defaultTypes(), 6000, defaultTopK(), false);
        }
        return profile("default", defaultKeyword(), defaultTypes(), defaultRadius(), defaultTopK(), false);
    }

    private Profile profile(
            String id,
            String keyword,
            String hospitalTypes,
            int radiusMeters,
            int topK,
            boolean preferTier3a
    ) {
        return new Profile(id, keyword, hospitalTypes, radiusMeters, topK, preferTier3a);
    }

    private boolean containsAny(String text, List<String> phrases) {
        String normalized = normalizeForMatch(text);
        if (normalized.isBlank()) {
            return false;
        }
        for (String phrase : phrases) {
            if (normalized.contains(normalizeForMatch(phrase))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String defaultKeyword() {
        return safeText(planningProperties.getMcp().getHospitalKeyword(), "医院");
    }

    private String defaultTypes() {
        return safeText(planningProperties.getMcp().getHospitalTypes(), "090100|090101|090102|090400");
    }

    private int defaultRadius() {
        return Math.max(1, planningProperties.getMcp().getAroundRadiusMeters());
    }

    private int defaultTopK() {
        return Math.max(1, planningProperties.getTopK());
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record Profile(
            String id,
            String keyword,
            String types,
            int radiusMeters,
            int topK,
            boolean preferTier3a
    ) {
    }
}
