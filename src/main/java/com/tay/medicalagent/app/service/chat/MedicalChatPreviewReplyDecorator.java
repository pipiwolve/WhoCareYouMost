package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.springframework.stereotype.Component;

/**
 * 为显式医院路线请求补充面向聊天气泡的状态提示。
 */
@Component
public class MedicalChatPreviewReplyDecorator {

    private static final String ROUTE_READY_SUFFIX = "已为您规划附近医院路线，请查看下方推荐医院和路线。";
    private static final String LOCATION_MISSING_SUFFIX = "已识别到您需要附近医院路线。请授权定位后，我会继续为您规划。";
    private static final String ROUTE_FALLBACK_SUFFIX = "已为您准备附近医院候选，路线结果以卡片内状态为准。";

    public String decorateExplicitHospitalRequestReply(String reply, MedicalReportSnapshot reportPreviewSnapshot) {
        String normalizedReply = normalize(reply);
        String suffix = resolveSuffix(reportPreviewSnapshot);
        if (suffix.isBlank()) {
            return normalizedReply;
        }
        if (normalizedReply.isBlank()) {
            return suffix;
        }
        if (normalizedReply.contains(suffix)) {
            return normalizedReply;
        }
        return normalizedReply + "\n\n" + suffix;
    }

    private String resolveSuffix(MedicalReportSnapshot reportPreviewSnapshot) {
        if (reportPreviewSnapshot == null || reportPreviewSnapshot.planningSummary() == null) {
            return ROUTE_FALLBACK_SUFFIX;
        }
        if (reportPreviewSnapshot.planningSummary().routesAvailable()) {
            return ROUTE_READY_SUFFIX;
        }
        String routeStatusCode = normalize(reportPreviewSnapshot.planningSummary().routeStatusCode());
        if ("location_missing".equalsIgnoreCase(routeStatusCode)) {
            return LOCATION_MISSING_SUFFIX;
        }
        return ROUTE_FALLBACK_SUFFIX;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
