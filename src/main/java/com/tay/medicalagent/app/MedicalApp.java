package com.tay.medicalagent.app;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import com.tay.medicalagent.app.report.MedicalReportPdfFile;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.service.chat.MedicalChatService;
import com.tay.medicalagent.app.service.chat.ThreadConversationService;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.app.service.report.MedicalHospitalPlanningService;
import com.tay.medicalagent.app.service.report.MedicalChatPreviewReportFactory;
import com.tay.medicalagent.app.service.report.MedicalPlanningIntentResolver;
import com.tay.medicalagent.app.service.report.MedicalReportPdfExportService;
import com.tay.medicalagent.app.service.report.MedicalReportSnapshotService;
import com.tay.medicalagent.app.service.report.ReportTriggerLevel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
/**
 * 应用层统一门面。
 * <p>
 * 该类对外暴露聊天、报告、用户画像和线程管理的核心能力，
 * 让上层调用方无需直接感知底层的 Agent Runtime、RAG 或存储细节。
 */
public class MedicalApp {

    private final MedicalChatService medicalChatService;
    private final UserProfileService userProfileService;
    private final ThreadConversationService threadConversationService;
    private final MedicalRagContextHolder medicalRagContextHolder;
    private final MedicalReportPdfExportService medicalReportPdfExportService;
    private final MedicalHospitalPlanningService medicalHospitalPlanningService;
    private final MedicalReportSnapshotService medicalReportSnapshotService;
    private final MedicalPlanningIntentResolver medicalPlanningIntentResolver;
    private final MedicalChatPreviewReportFactory medicalChatPreviewReportFactory;

    public MedicalApp(
            MedicalChatService medicalChatService,
            UserProfileService userProfileService,
            ThreadConversationService threadConversationService,
            MedicalRagContextHolder medicalRagContextHolder,
            MedicalReportPdfExportService medicalReportPdfExportService,
            MedicalHospitalPlanningService medicalHospitalPlanningService,
            MedicalReportSnapshotService medicalReportSnapshotService,
            MedicalPlanningIntentResolver medicalPlanningIntentResolver,
            MedicalChatPreviewReportFactory medicalChatPreviewReportFactory
    ) {
        this.medicalChatService = medicalChatService;
        this.userProfileService = userProfileService;
        this.threadConversationService = threadConversationService;
        this.medicalRagContextHolder = medicalRagContextHolder;
        this.medicalReportPdfExportService = medicalReportPdfExportService;
        this.medicalHospitalPlanningService = medicalHospitalPlanningService;
        this.medicalReportSnapshotService = medicalReportSnapshotService;
        this.medicalPlanningIntentResolver = medicalPlanningIntentResolver;
        this.medicalChatPreviewReportFactory = medicalChatPreviewReportFactory;
    }

    /**
     * 使用默认线程和默认用户上下文发起一次聊天。
     *
     * @param prompt 用户输入
     * @return 聊天结果
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalChatResult doChat(String prompt) throws GraphRunnerException {
        return medicalChatService.doChat(prompt, null, null);
    }

    /**
     * 在指定线程下发起一次聊天。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @return 聊天结果
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalChatResult doChat(String prompt, String threadId) throws GraphRunnerException {
        return medicalChatService.doChat(prompt, threadId, null);
    }

    /**
     * 在指定线程和用户上下文下发起一次聊天。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 聊天结果
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalChatResult doChat(String prompt, String threadId, String userId) throws GraphRunnerException {
        return medicalChatService.doChat(prompt, threadId, userId);
    }

    /**
     * 兼容旧命名的聊天接口，当前行为与 {@link #doChat(String, String, String)} 一致。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 聊天结果
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalChatResult doChatWithMemory(String prompt, String threadId, String userId)
            throws GraphRunnerException {
        return doChat(prompt, threadId, userId);
    }

    /**
     * 基于当前线程对话生成医疗报告。
     *
     * @param message 当前输入
     * @param chatId 会话线程 ID
     * @return 结构化医疗报告
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalDiagnosisReport doChatWithReport(String message, String chatId) throws GraphRunnerException {
        return medicalChatService.doChatWithReport(message, chatId, null);
    }

    /**
     * 基于指定线程和用户上下文生成医疗报告。
     *
     * @param message 当前输入
     * @param chatId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 结构化医疗报告
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public MedicalDiagnosisReport doChatWithReport(String message, String chatId, String userId)
            throws GraphRunnerException {
        return medicalChatService.doChatWithReport(message, chatId, userId);
    }

    /**
     * 获取一次原始模型回答消息。
     *
     * @param prompt 用户输入
     * @return 模型回复消息
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public AssistantMessage doChatMessage(String prompt) throws GraphRunnerException {
        return medicalChatService.doChatMessage(prompt, null, null);
    }

    /**
     * 获取指定线程下的一次原始模型回答消息。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @return 模型回复消息
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public AssistantMessage doChatMessage(String prompt, String threadId) throws GraphRunnerException {
        return medicalChatService.doChatMessage(prompt, threadId, null);
    }

    /**
     * 获取指定线程和用户上下文下的一次原始模型回答消息。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 模型回复消息
     * @throws GraphRunnerException Agent 运行失败时抛出
     */
    public AssistantMessage doChatMessage(String prompt, String threadId, String userId)
            throws GraphRunnerException {
        return medicalChatService.doChatMessage(prompt, threadId, userId);
    }

    /**
     * 基于当前线程历史生成医疗报告。
     *
     * @param threadId 会话线程 ID
     * @return 结构化医疗报告
     */
    public MedicalDiagnosisReport generateReportFromThread(String threadId) {
        return medicalChatService.generateReportFromThread(threadId, null);
    }

    /**
     * 基于指定线程和用户上下文生成医疗报告。
     *
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 结构化医疗报告
     */
    public MedicalDiagnosisReport generateReportFromThread(String threadId, String userId) {
        return medicalChatService.generateReportFromThread(threadId, userId);
    }

    /**
     * 导出指定会话的 PDF 诊断报告。
     *
     * @param sessionId 前端会话 ID
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return PDF 文件结果
     */
    public MedicalReportPdfFile exportReportPdf(String sessionId, String threadId, String userId) {
        return exportReportPdf(sessionId, threadId, userId, null, null);
    }

    public MedicalReportPdfFile exportReportPdf(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        MedicalReportSnapshot snapshot = medicalReportSnapshotService.getOrCreateSnapshot(
                normalizeSessionId(sessionId),
                threadId,
                userId,
                latitude,
                longitude
        );
        return medicalReportPdfExportService.exportReportPdf(
                sessionId,
                threadId,
                userId,
                snapshot.report(),
                snapshot.planningSummary()
        );
    }

    public MedicalHospitalPlanningSummary planHospitalsForReport(
            Double latitude,
            Double longitude,
            MedicalDiagnosisReport report
    ) {
        return medicalHospitalPlanningService.plan(latitude, longitude, report);
    }

    public MedicalReportSnapshot getOrCreateReportSnapshot(
            String sessionId,
            String threadId,
            String userId,
            Double latitude,
            Double longitude
    ) {
        return medicalReportSnapshotService.getOrCreateSnapshot(
                normalizeSessionId(sessionId),
                threadId,
                userId,
                latitude,
                longitude
        );
    }

    public Optional<MedicalReportSnapshot> prepareReportPreview(
            String sessionId,
            String prompt,
            String threadId,
            String userId,
            Double latitude,
            Double longitude,
            MedicalChatResult medicalChatResult
    ) {
        if (!medicalPlanningIntentResolver.shouldPrepareChatPreview(prompt, medicalChatResult)) {
            return Optional.empty();
        }

        boolean explicitHospitalRequest = medicalPlanningIntentResolver.isExplicitHospitalRequest(prompt);
        MedicalDiagnosisReport report = medicalChatResult != null
                && medicalChatResult.reportGenerated()
                && medicalChatResult.report() != null
                ? medicalChatResult.report()
                : resolvePreviewReport(threadId, userId, prompt, medicalChatResult, explicitHospitalRequest);
        if (report == null || !report.shouldGenerateReport()) {
            return Optional.empty();
        }

        MedicalPlanningIntent planningIntent = medicalPlanningIntentResolver.resolve(
                report,
                medicalChatResult == null ? null : medicalChatResult.structuredReply(),
                prompt,
                medicalChatResult == null || medicalChatResult.reportTriggerLevel() == null
                        ? ReportTriggerLevel.NONE
                        : medicalChatResult.reportTriggerLevel()
        );
        return Optional.of(medicalReportSnapshotService.getOrCreateSnapshot(
                normalizeSessionId(sessionId),
                threadId,
                userId,
                latitude,
                longitude,
                report,
                planningIntent
        ));
    }

    public boolean isExplicitHospitalPlanningRequest(String prompt) {
        return medicalPlanningIntentResolver.isExplicitHospitalRequest(prompt);
    }

    public void invalidateReportSnapshot(String sessionId) {
        medicalReportSnapshotService.invalidate(normalizeSessionId(sessionId));
    }

    /**
     * 手动写入用户长期画像。
     *
     * @param userId 用户唯一标识
     * @param updates 画像更新字段
     */
    public void saveUserProfileMemory(String userId, Map<String, Object> updates) {
        userProfileService.saveUserProfileMemory(userId, updates);
    }

    /**
     * 读取当前用户长期画像。
     *
     * @param userId 用户唯一标识
     * @return 画像内容
     */
    public Optional<Map<String, Object>> getUserProfileMemory(String userId) {
        return userProfileService.getUserProfileMemory(userId);
    }

    /**
     * 清理应用内存态状态。
     * <p>
     * 包括用户画像、线程对话、RAG 上下文与底层 Agent Runtime 缓存。
     */
    public void clearMemory() {
        userProfileService.clearMemory();
        threadConversationService.clearConversations();
        medicalRagContextHolder.clear();
        medicalReportSnapshotService.clear();
        medicalChatService.resetRuntime();
    }

    /**
     * 创建新的会话线程 ID。
     *
     * @return 新线程 ID
     */
    public String createThreadId() {
        return medicalChatService.createThreadId();
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "unknown-session";
        }
        return sessionId.trim();
    }

    private MedicalDiagnosisReport resolvePreviewReport(
            String threadId,
            String userId,
            String prompt,
            MedicalChatResult medicalChatResult,
            boolean explicitHospitalRequest
    ) {
        if (explicitHospitalRequest) {
            return medicalChatPreviewReportFactory.build(threadId, userId, prompt, medicalChatResult);
        }
        return medicalChatService.generateReportFromThread(threadId, userId);
    }
}
