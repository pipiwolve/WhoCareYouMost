package com.tay.medicalagent.app.service.chat;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.tay.medicalagent.app.chat.MedicalChatResult;
import com.tay.medicalagent.app.chat.MedicalChatStreamingSession;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 医疗聊天服务抽象。
 * <p>
 * 负责统一封装普通问答、报告生成与原始模型消息访问能力。
 */
public interface MedicalChatService {

    /**
     * 执行一次完整的医疗问答。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID，可为空
     * @param userId 用户唯一标识，可为空
     * @return 统一聊天结果
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    MedicalChatResult doChat(String prompt, String threadId, String userId) throws GraphRunnerException;

    /**
     * 以流式方式执行一次完整的医疗问答。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID，可为空
     * @param userId 用户唯一标识，可为空
     * @return 流式文本片段与结束后的统一结果
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    MedicalChatStreamingSession streamChat(String prompt, String threadId, String userId) throws GraphRunnerException;

    /**
     * 执行一次聊天，并在需要时返回结构化报告。
     *
     * @param message 用户输入
     * @param chatId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 结构化医疗报告
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    MedicalDiagnosisReport doChatWithReport(String message, String chatId, String userId) throws GraphRunnerException;

    /**
     * 只返回底层模型消息，不做统一结果包装。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 模型回复消息
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    AssistantMessage doChatMessage(String prompt, String threadId, String userId) throws GraphRunnerException;

    /**
     * 基于现有线程历史生成医疗报告。
     *
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 结构化医疗报告
     */
    MedicalDiagnosisReport generateReportFromThread(String threadId, String userId);

    /**
     * 创建新的线程 ID。
     *
     * @return 新线程 ID
     */
    String createThreadId();

    /**
     * 重置底层 Agent Runtime，通常在清理内存态上下文时使用。
     */
    void resetRuntime();
}
