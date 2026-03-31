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
            "附近门诊",
            "医院路线",
            "医院路线规划",
            "规划医院路线",
            "最近医院",
            "最近的医院",
            "附近医院怎么走",
            "去医院怎么走",
            "医院怎么去",
            "导航去医院"
    );
    private static final List<String> HOSPITAL_TARGET_WORDS = List.of("医院", "门诊", "急诊");
    private static final List<String> ADDRESS_SIGNAL_WORDS = List.of(
            "帮我找",
            "帮我查",
            "帮我搜",
            "找一下",
            "查一下",
            "搜一下",
            "查找",
            "搜索",
            "看看",
            "帮我看看",
            "推荐",
            "帮我推荐",
            "去哪家",
            "去哪个"
    );
    private static final List<String> SPECIALIZED_MEDICAL_FACILITY_PHRASES = List.of(
            "心理医院",
            "心理科",
            "精神卫生中心",
            "精神专科医院",
            "精神科医院",
            "儿科医院",
            "妇幼保健院",
            "妇产医院",
            "儿童医院",
            "口腔医院",
            "骨科医院",
            "心血管医院",
            "综合医院",
            "胸痛中心",
            "卒中中心"
    );
    private static final List<String> PSYCHIATRY_FACILITY_PHRASES = List.of(
            "心理医院",
            "心理科",
            "心理咨询",
            "精神卫生",
            "精神卫生中心",
            "精神专科医院",
            "精神科医院"
    );
    private static final List<String> PEDIATRIC_FACILITY_PHRASES = List.of("儿童医院", "儿科医院");
    private static final List<String> OBSTETRIC_FACILITY_PHRASES = List.of("妇幼保健院", "妇产医院");
    private static final List<String> DENTAL_FACILITY_PHRASES = List.of("口腔医院");
    private static final List<String> ORTHOPEDIC_FACILITY_PHRASES = List.of("骨科医院");
    private static final List<String> CARDIAC_FACILITY_PHRASES = List.of("心血管医院");
    private static final List<String> NEUROLOGY_SIGNAL_WORDS = List.of(
            "头痛",
            "头晕",
            "头昏",
            "偏瘫",
            "言语不清",
            "抽搐",
            "癫痫",
            "卒中"
    );
    private static final List<String> ROUTE_SIGNAL_WORDS = List.of("路线", "规划", "导航", "怎么走", "怎么去");
    private static final List<String> DISTANCE_SIGNAL_WORDS = List.of("附近", "就近", "最近");
    private static final int MAX_NEARBY_FACILITY_GAP_CHARS = 8;
    private static final List<String> GENERIC_HOSPITAL_DECISION_PHRASES = List.of(
            "去医院吗",
            "要不要去医院",
            "需不需要去医院",
            "需要去医院吗",
            "我需要去医院吗",
            "该不该去医院",
            "是否去医院",
            "是否要去医院"
    );
    private static final List<String> GENERIC_DECISION_PREFIXES = List.of(
            "要不要去",
            "需不需要去",
            "需要去",
            "我需要去",
            "该不该去",
            "是否去",
            "是否要去",
            "是否需要去"
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
            return isExplicitHospitalRequest(latestUserMessage);
        }
        if (medicalChatResult.reportGenerated()) {
            return true;
        }
        if (medicalChatResult.reportTriggerLevel() != null && medicalChatResult.reportTriggerLevel().isAvailable()) {
            return true;
        }
        return isExplicitHospitalRequest(latestUserMessage);
    }

    @Override
    public boolean isExplicitHospitalRequest(String latestUserMessage) {
        String normalized = normalizeForMatch(latestUserMessage);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAnyNormalized(normalized, EXPLICIT_HOSPITAL_REQUEST_PHRASES)) {
            return true;
        }
        if (containsGenericFacilityDecisionSignal(normalized)) {
            return false;
        }
        return containsFacilityRouteSignal(normalized)
                || containsNearbyFacilitySignal(normalized)
                || containsAddressFacilitySignal(normalized);
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

        boolean explicitHospitalRequest = isExplicitHospitalRequest(latestUserMessage);
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
        if (containsAny(text, PSYCHIATRY_FACILITY_PHRASES)) {
            return profile("psychiatry", "精神卫生中心", defaultTypes(), 6000, defaultTopK(), false);
        }
        if (containsAny(text, CARDIAC_FACILITY_PHRASES)) {
            return profile("cardiac", "心血管医院", "090101|090100|090102", 12000, 3, true);
        }
        if (containsAny(text, List.of("胸痛", "胸闷", "心慌", "心悸", "心前区", "冠脉", "心梗", "心脏"))) {
            return profile("cardiac", "心血管医院", "090101|090100|090102", 12000, 3, true);
        }
        if (containsAny(text, List.of("呼吸困难", "气喘", "喘", "咳嗽", "肺炎", "呼吸", "哮喘"))) {
            return profile("respiratory", "呼吸科 医院", defaultTypes(), 6000, defaultTopK(), true);
        }
        if (containsAny(text, PEDIATRIC_FACILITY_PHRASES)) {
            return profile("pediatric", "儿童医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsNeurologySignal(text)) {
            return profile("neurology", "神经内科 医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, List.of("儿童", "小孩", "宝宝", "婴儿", "儿科", "高热惊厥"))) {
            return profile("pediatric", "儿童医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, OBSTETRIC_FACILITY_PHRASES)) {
            return profile("obstetric", "妇产医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, List.of("孕", "妊娠", "产后", "阴道出血", "宫缩", "胎动", "妇产"))) {
            return profile("obstetric", "妇产医院", "090101|090100|090102", 8000, 3, true);
        }
        if (containsAny(text, ORTHOPEDIC_FACILITY_PHRASES)) {
            return profile("orthopedic", "骨科医院", defaultTypes(), 6000, defaultTopK(), false);
        }
        if (containsAny(text, List.of("骨折", "扭伤", "外伤", "关节", "腰痛", "颈椎", "背痛", "骨科"))) {
            return profile("orthopedic", "骨科医院", defaultTypes(), 6000, defaultTopK(), false);
        }
        if (containsAny(text, DENTAL_FACILITY_PHRASES)) {
            return profile("dental", "口腔医院", defaultTypes(), 5000, defaultTopK(), false);
        }
        if (containsAny(text, List.of("牙痛", "口腔", "牙龈", "牙齿", "智齿"))) {
            return profile("dental", "口腔医院", defaultTypes(), 5000, defaultTopK(), false);
        }
        if (containsAny(text, List.of("焦虑", "抑郁", "惊恐", "自杀", "失眠", "情绪", "精神"))
                || containsAny(text, PSYCHIATRY_FACILITY_PHRASES)) {
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
        return containsAnyNormalized(normalized, phrases);
    }

    private boolean containsAnyNormalized(String normalized, List<String> phrases) {
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

    private boolean containsFacilityRouteSignal(String normalized) {
        return containsMedicalFacility(normalized)
                && containsAnyNormalized(normalized, ROUTE_SIGNAL_WORDS);
    }

    private boolean containsNearbyFacilitySignal(String normalized) {
        return hasNearbyFacilityMatch(normalized, HOSPITAL_TARGET_WORDS)
                || hasNearbyFacilityMatch(normalized, SPECIALIZED_MEDICAL_FACILITY_PHRASES);
    }

    private boolean containsAddressFacilitySignal(String normalized) {
        return containsMedicalFacility(normalized)
                && containsAnyNormalized(normalized, ADDRESS_SIGNAL_WORDS);
    }

    private boolean containsGenericFacilityDecisionSignal(String normalized) {
        if (containsAnyNormalized(normalized, GENERIC_HOSPITAL_DECISION_PHRASES)) {
            return true;
        }
        for (String decisionPrefix : GENERIC_DECISION_PREFIXES) {
            String normalizedDecisionPrefix = normalizeForMatch(decisionPrefix);
            if (normalized.contains(normalizedDecisionPrefix)
                    && (containsAnyNormalized(normalized, SPECIALIZED_MEDICAL_FACILITY_PHRASES)
                    || containsAnyNormalized(normalized, HOSPITAL_TARGET_WORDS))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMedicalFacility(String normalized) {
        return containsAnyNormalized(normalized, HOSPITAL_TARGET_WORDS)
                || containsAnyNormalized(normalized, SPECIALIZED_MEDICAL_FACILITY_PHRASES);
    }

    private boolean containsNeurologySignal(String text) {
        String normalized = normalizeForMatch(text);
        return containsAnyNormalized(normalized, NEUROLOGY_SIGNAL_WORDS)
                || containsPhraseExcludingSuffix(normalized, "中风", "险");
    }

    private boolean containsPhraseExcludingSuffix(String normalized, String phrase, String forbiddenFollowingChar) {
        String normalizedPhrase = normalizeForMatch(phrase);
        String normalizedForbiddenChar = normalizeForMatch(forbiddenFollowingChar);
        if (normalizedPhrase.isBlank()) {
            return false;
        }
        int index = normalized.indexOf(normalizedPhrase);
        while (index >= 0) {
            int suffixIndex = index + normalizedPhrase.length();
            if (normalizedForbiddenChar.isBlank()
                    || suffixIndex >= normalized.length()
                    || !normalized.startsWith(normalizedForbiddenChar, suffixIndex)) {
                return true;
            }
            index = normalized.indexOf(normalizedPhrase, index + 1);
        }
        return false;
    }

    private boolean hasNearbyFacilityMatch(String normalized, List<String> facilityPhrases) {
        if (normalized.isBlank() || facilityPhrases == null || facilityPhrases.isEmpty()) {
            return false;
        }
        for (String distanceWord : DISTANCE_SIGNAL_WORDS) {
            String normalizedDistanceWord = normalizeForMatch(distanceWord);
            if (normalizedDistanceWord.isBlank()) {
                continue;
            }
            int distanceIndex = normalized.indexOf(normalizedDistanceWord);
            while (distanceIndex >= 0) {
                int searchStart = distanceIndex + normalizedDistanceWord.length();
                for (String facilityPhrase : facilityPhrases) {
                    String normalizedFacilityPhrase = normalizeForMatch(facilityPhrase);
                    if (normalizedFacilityPhrase.isBlank()) {
                        continue;
                    }
                    int facilityIndex = normalized.indexOf(normalizedFacilityPhrase, searchStart);
                    if (facilityIndex < 0) {
                        continue;
                    }
                    int gapChars = facilityIndex - searchStart;
                    if (gapChars >= 0 && gapChars <= MAX_NEARBY_FACILITY_GAP_CHARS) {
                        return true;
                    }
                }
                distanceIndex = normalized.indexOf(normalizedDistanceWord, distanceIndex + 1);
            }
        }
        return false;
    }

    private String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lowerCased = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowerCased.length());
        for (int index = 0; index < lowerCased.length(); index++) {
            char character = lowerCased.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
            }
        }
        return normalized.toString();
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
