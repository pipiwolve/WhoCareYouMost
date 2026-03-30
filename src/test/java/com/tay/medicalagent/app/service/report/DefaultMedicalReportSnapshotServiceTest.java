package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMedicalReportSnapshotServiceTest {

    @Test
    void shouldReuseFreshSnapshotWithoutRebuildingReportAndPlanning() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository();
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        MedicalHospitalPlanningSummary planningSummary = new MedicalHospitalPlanningSummary(List.of(), false, "location_missing", "location_missing");

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of())).thenReturn("user:胸痛\nassistant:建议尽快就医");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent)).thenReturn(planningSummary);

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver
        );

        var first = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        var second = service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        assertSame(first, second);
        verify(reportService, times(1)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(1)).plan(31.2, 121.4, report, planningIntent);
    }

    @Test
    void shouldRebuildSnapshotWhenConversationFingerprintChanges() {
        MedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository();
        MedicalReportService reportService = mock(MedicalReportService.class);
        MedicalHospitalPlanningService planningService = mock(MedicalHospitalPlanningService.class);
        ThreadConversationService threadConversationService = mock(ThreadConversationService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        MedicalPlanningIntentResolver planningIntentResolver = mock(MedicalPlanningIntentResolver.class);

        MedicalDiagnosisReport report = exportableReport();
        MedicalPlanningIntent planningIntent = new MedicalPlanningIntent(
                true,
                false,
                "trigger",
                "default",
                "医院",
                "090100|090101",
                5000,
                3,
                false
        );
        MedicalHospitalPlanningSummary planningSummary = MedicalHospitalPlanningSummary.empty();

        when(threadConversationService.getThreadConversation("thread_snapshot")).thenReturn(List.of());
        when(threadConversationService.buildConversationTranscript(List.of()))
                .thenReturn("user:胸痛\nassistant:建议尽快就医")
                .thenReturn("user:胸痛加重\nassistant:建议立即急诊");
        when(userProfileService.buildProfileContext("usr_snapshot")).thenReturn("姓名：测试用户");
        when(reportService.generateReportFromThread("thread_snapshot", "usr_snapshot")).thenReturn(report);
        when(planningIntentResolver.resolve(report)).thenReturn(planningIntent);
        when(planningService.plan(31.2, 121.4, report, planningIntent)).thenReturn(planningSummary);

        DefaultMedicalReportSnapshotService service = new DefaultMedicalReportSnapshotService(
                repository,
                reportService,
                planningService,
                threadConversationService,
                userProfileService,
                planningIntentResolver
        );

        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);
        service.getOrCreateSnapshot("sess_snapshot", "thread_snapshot", "usr_snapshot", 31.2, 121.4);

        verify(reportService, times(2)).generateReportFromThread("thread_snapshot", "usr_snapshot");
        verify(planningService, times(2)).plan(31.2, 121.4, report, planningIntent);
    }

    private MedicalDiagnosisReport exportableReport() {
        return new MedicalDiagnosisReport(
                "诊断报告",
                true,
                "CONFIRMED",
                "中风险",
                "胸痛",
                "建议尽快线下评估",
                "",
                List.of("胸痛"),
                List.of("尽快就医"),
                List.of("胸痛持续加重"),
                "建议尽快线下评估"
        );
    }
}
