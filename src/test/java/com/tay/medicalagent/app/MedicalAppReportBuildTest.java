package com.tay.medicalagent.app;

import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportBuildState;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.MedicalChatService;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalChatPreviewReportFactory;
import com.tay.medicalagent.app.service.report.MedicalHospitalPlanningService;
import com.tay.medicalagent.app.service.report.MedicalPlanningIntentResolver;
import com.tay.medicalagent.app.service.report.MedicalReportPdfExportService;
import com.tay.medicalagent.app.service.report.MedicalReportSnapshotService;
import com.tay.medicalagent.app.service.report.ReportBuildCoordinator;
import com.tay.medicalagent.app.service.runtime.MedicalAgentRuntimePersistenceCleaner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalAppReportBuildTest {

    @Test
    void exportReportPdfShouldUseCoordinatorSnapshotInsteadOfBypassingIt() {
        MedicalChatService medicalChatService = mock(MedicalChatService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        MedicalRagContextHolder medicalRagContextHolder = new MedicalRagContextHolder();
        MedicalReportPdfExportService medicalReportPdfExportService = mock(MedicalReportPdfExportService.class);
        MedicalHospitalPlanningService medicalHospitalPlanningService = mock(MedicalHospitalPlanningService.class);
        MedicalReportSnapshotService medicalReportSnapshotService = mock(MedicalReportSnapshotService.class);
        MedicalPlanningIntentResolver medicalPlanningIntentResolver = mock(MedicalPlanningIntentResolver.class);
        MedicalChatPreviewReportFactory medicalChatPreviewReportFactory = mock(MedicalChatPreviewReportFactory.class);
        ReportBuildCoordinator reportBuildCoordinator = mock(ReportBuildCoordinator.class);

        MedicalApp medicalApp = new MedicalApp(
                medicalChatService,
                userProfileService,
                threadConversationService,
                medicalRagContextHolder,
                medicalReportPdfExportService,
                medicalHospitalPlanningService,
                medicalReportSnapshotService,
                medicalPlanningIntentResolver,
                medicalChatPreviewReportFactory,
                reportBuildCoordinator,
                mock(MedicalAgentRuntimePersistenceCleaner.class)
        );

        MedicalDiagnosisReport report = new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "胸闷",
                "建议尽快线下评估",
                "",
                List.of("胸闷"),
                List.of("尽快就医"),
                List.of("胸痛加重"),
                "建议尽快线下评估"
        );
        MedicalHospitalPlanningSummary planningSummary = new MedicalHospitalPlanningSummary(List.of(), false, "", "none");
        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                "sess_pdf",
                "thread_pdf",
                "usr_pdf",
                Instant.now(),
                "conversation",
                "profile",
                "location",
                report,
                planningSummary
        );
        MedicalReportPdfFile pdfFile = new MedicalReportPdfFile("medical-report.pdf", "application/pdf", "%PDF".getBytes());

        when(reportBuildCoordinator.getOrStartFinalReport("sess_pdf", "thread_pdf", "usr_pdf", 31.23, 121.47))
                .thenReturn(MedicalReportBuildState.ready(snapshot));
        when(medicalReportPdfExportService.exportReportPdf("sess_pdf", "thread_pdf", "usr_pdf", report, planningSummary))
                .thenReturn(pdfFile);

        MedicalReportPdfFile result = medicalApp.exportReportPdf("sess_pdf", "thread_pdf", "usr_pdf", 31.23, 121.47);

        assertSame(pdfFile, result);
        verify(reportBuildCoordinator).getOrStartFinalReport("sess_pdf", "thread_pdf", "usr_pdf", 31.23, 121.47);
        verify(medicalReportSnapshotService, never()).getOrCreateSnapshot("sess_pdf", "thread_pdf", "usr_pdf", 31.23, 121.47);
    }
}
