package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

/**
 * 医疗 Agent Runtime 抽象。
 * <p>
 * 屏蔽底层 ReactAgent 构建与执行细节，让上层服务只依赖最小运行能力。
 */
public interface MedicalAgentRuntime {

    /**
     * 执行一次底层模型问答。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 模型回复消息
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    AssistantMessage doChatMessage(String prompt, String threadId, String userId) throws GraphRunnerException;

    /**
     * 以流式方式执行一次底层模型问答。
     *
     * @param prompt 用户输入
     * @param threadId 会话线程 ID
     * @param userId 用户唯一标识
     * @return 模型实时文本分片
     * @throws GraphRunnerException Agent 执行失败时抛出
     */
    Flux<String> streamChatText(String prompt, String threadId, String userId) throws GraphRunnerException;

    /**
     * 生成新的线程 ID。
     *
     * @return 新线程 ID
     */
    String createThreadId();

    /**
     * 清理缓存中的 Agent 实例，强制下次重新装配。
     */
    void reset();
}
