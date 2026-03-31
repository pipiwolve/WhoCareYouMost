package com.tay.medicalagent.app;

import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.MedicalChatService;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalChatPreviewReportFactory;
import com.tay.medicalagent.app.service.report.MedicalHospitalPlanningService;
import com.tay.medicalagent.app.service.report.MedicalPlanningIntentResolver;
import com.tay.medicalagent.app.service.report.MedicalReportPdfExportService;
import com.tay.medicalagent.app.service.report.MedicalReportSnapshotService;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalAppPreviewTest {

    @Test
    void prepareReportPreviewShouldUseLightweightPreviewReportForExplicitHospitalRequest() {
        MedicalChatService medicalChatService = mock(MedicalChatService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        MedicalRagContextHolder medicalRagContextHolder = new MedicalRagContextHolder();
        MedicalReportPdfExportService medicalReportPdfExportService = mock(MedicalReportPdfExportService.class);
        MedicalHospitalPlanningService medicalHospitalPlanningService = mock(MedicalHospitalPlanningService.class);
        MedicalReportSnapshotService medicalReportSnapshotService = mock(MedicalReportSnapshotService.class);
        MedicalPlanningIntentResolver medicalPlanningIntentResolver = mock(MedicalPlanningIntentResolver.class);
        MedicalChatPreviewReportFactory medicalChatPreviewReportFactory = mock(MedicalChatPreviewReportFactory.class);

        MedicalApp medicalApp = new MedicalApp(
                medicalChatService,
                userProfileService,
                threadConversationService,
                medicalRagContextHolder,
                medicalReportPdfExportService,
                medicalHospitalPlanningService,
                medicalReportSnapshotService,
                medicalPlanningIntentResolver,
                medicalChatPreviewReportFactory
        );

        MedicalChatResult chatResult = new MedicalChatResult(
                "thread_preview",
                "usr_preview",
                "建议尽快线下评估。",
                true,
                "已按您的请求准备附近医院路线，可查看详情。",
                ReportTriggerLevel.RECOMMENDED,
                "已按您的请求准备附近医院路线，可查看详情。",
                false,
                null,
                false,
                List.of(),
                StructuredMedicalReply.empty("本回答由AI生成，仅供健康信息参考，不能替代医生面诊。")
        );
        MedicalDiagnosisReport previewReport = new MedicalDiagnosisReport(
                "路线预览报告",
                true,
                "INSUFFICIENT_INFORMATION",
                "",
                "当前主要诉求：规划附近医院路线。",
                "当前缺少充分病情信息，已先按通用医院路线需求处理；补充症状后可优化医院匹配。",
                "",
                List.of("用户明确请求规划附近医院路线"),
                List.of("如已授权定位，可查看附近医院与路线"),
                List.of(),
                "建议尽快线下评估。"
        );
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                true,
                "用户明确请求附近医院或路线规划",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                "sess_preview",
                "thread_preview",
                "usr_preview",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                previewReport,
                MedicalHospitalPlanningSummary.empty()
        );

        when(medicalPlanningIntentResolver.shouldPrepareChatPreview("帮我规划附近医院路线", chatResult)).thenReturn(true);
        when(medicalPlanningIntentResolver.isExplicitHospitalRequest("帮我规划附近医院路线")).thenReturn(true);
        when(medicalChatPreviewReportFactory.build("thread_preview", "usr_preview", "帮我规划附近医院路线", chatResult))
                .thenReturn(previewReport);
        when(medicalPlanningIntentResolver.resolve(previewReport, chatResult.structuredReply(), "帮我规划附近医院路线", ReportTriggerLevel.RECOMMENDED))
                .thenReturn(planningIntent);
        when(medicalReportSnapshotService.getOrCreateSnapshot(
                "sess_preview",
                "thread_preview",
                "usr_preview",
                null,
                null,
                previewReport,
                planningIntent
        )).thenReturn(snapshot);

        Optional<MedicalReportSnapshot> preview = medicalApp.prepareReportPreview(
                "sess_preview",
                "帮我规划附近医院路线",
                "thread_preview",
                "usr_preview",
                null,
                null,
                chatResult
        );

        assertTrue(preview.isPresent());
        verify(medicalChatPreviewReportFactory).build("thread_preview", "usr_preview", "帮我规划附近医院路线", chatResult);
        verify(medicalChatService, never()).generateReportFromThread(any(), any());
        verify(medicalReportSnapshotService).getOrCreateSnapshot(
                eq("sess_preview"),
                eq("thread_preview"),
                eq("usr_preview"),
                isNull(),
                isNull(),
                eq(previewReport),
                eq(planningIntent)
        );
    }
}
