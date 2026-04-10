package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.rag.hook.MedicalRagAgentHook;
import com.tay.medicalagent.app.rag.interceptor.MedicalRagContextInterceptor;
import com.tay.medicalagent.hook.ReReadingMessagesHook;
import com.tay.medicalagent.hook.UserProfileMemoryHook;
import com.tay.medicalagent.interceptor.MedicalSystemPromptInterceptor;
import com.tay.medicalagent.interceptor.MyLogModelInterceptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
/**
 * 默认 Agent Runtime 实现。
 * <p>
 * 负责装配 ReactAgent、MemorySaver、用户画像 Hook、RAG Hook/Interceptor 与日志拦截器。
 */
public class DefaultMedicalAgentRuntime implements MedicalAgentRuntime {

    private static final String RE_READING_ENABLED_PROPERTY = "medical.agent.rereading.enabled";
    private static final String RE_READING_ENABLED_ENV = "MEDICAL_AGENT_REREADING_ENABLED";

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final Store userProfileMemoryStore;
    private final BaseCheckpointSaver checkpointSaver;
    private final UserProfileMemoryHook userProfileMemoryHook;
    private final MedicalRagAgentHook medicalRagAgentHook;
    private final MedicalSystemPromptInterceptor medicalSystemPromptInterceptor;
    private final MedicalRagContextInterceptor medicalRagContextInterceptor;
    private final MyLogModelInterceptor myLogModelInterceptor;

    private volatile ReactAgent medicalAgent;

    public DefaultMedicalAgentRuntime(
            MedicalAiModelProvider medicalAiModelProvider,
            Store userProfileMemoryStore,
            BaseCheckpointSaver checkpointSaver,
            UserProfileMemoryHook userProfileMemoryHook,
            MedicalRagAgentHook medicalRagAgentHook,
            MedicalSystemPromptInterceptor medicalSystemPromptInterceptor,
            MedicalRagContextInterceptor medicalRagContextInterceptor,
            MyLogModelInterceptor myLogModelInterceptor
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.userProfileMemoryStore = userProfileMemoryStore;
        this.checkpointSaver = checkpointSaver;
        this.userProfileMemoryHook = userProfileMemoryHook;
        this.medicalRagAgentHook = medicalRagAgentHook;
        this.medicalSystemPromptInterceptor = medicalSystemPromptInterceptor;
        this.medicalRagContextInterceptor = medicalRagContextInterceptor;
        this.myLogModelInterceptor = myLogModelInterceptor;
    }

    @Override
    public AssistantMessage doChatMessage(String prompt, String threadId, String userId) throws GraphRunnerException {
        return getAgent().call(prompt, buildRunnableConfig(prompt, threadId, userId));
    }

    @Override
    public Flux<String> streamChatText(String prompt, String threadId, String userId) throws GraphRunnerException {
        RunnableConfig runnableConfig = buildRunnableConfig(prompt, threadId, userId);
        AtomicBoolean sawStreamingChunk = new AtomicBoolean(false);
        return getAgent().stream(prompt, runnableConfig)
                .handle((nodeOutput, sink) -> emitStreamingText(nodeOutput, sink, sawStreamingChunk));
    }

    @Override
    public String createThreadId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public synchronized void reset() {
        medicalAgent = null;
    }

    private RunnableConfig buildRunnableConfig(String prompt, String threadId, String userId) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("thread_id", threadId)
                .addMetadata("user_id", userId)
                .addMetadata("latest_user_prompt", prompt)
                .store(userProfileMemoryStore)
                .build();
    }

    private void emitStreamingText(
            NodeOutput nodeOutput,
            reactor.core.publisher.SynchronousSink<String> sink,
            AtomicBoolean sawStreamingChunk
    ) {
        if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }
        if (!(streamingOutput.message() instanceof AssistantMessage assistantMessage) || assistantMessage.hasToolCalls()) {
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            String chunk = streamingOutput.chunk();
            if (chunk != null && !chunk.isBlank()) {
                sawStreamingChunk.set(true);
                sink.next(chunk);
            }
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED && !sawStreamingChunk.get()) {
            String text = assistantMessage.getText();
            if (text != null && !text.isBlank()) {
                sink.next(text);
            }
        }
    }

    private ReactAgent getAgent() {
        if (medicalAgent == null) {
            synchronized (this) {
                if (medicalAgent == null) {
                    medicalAgent = ReactAgent.builder()
                            .name("medical_agent")
                            .model(medicalAiModelProvider.getChatModel())
                            .systemPrompt(MedicalPrompts.MEDICAL_AGENT_INSTRUCTION)
                            .hooks(buildAgentHooks())
                            .interceptors(medicalSystemPromptInterceptor, medicalRagContextInterceptor, myLogModelInterceptor)
                            .saver(checkpointSaver)
                            .build();
                }
            }
        }
        return medicalAgent;
    }

    private List<Hook> buildAgentHooks() {
        List<Hook> hooks = new ArrayList<>();
        hooks.add(medicalRagAgentHook);
        hooks.add(userProfileMemoryHook);
        if (isReReadingEnabled()) {
            hooks.add(new ReReadingMessagesHook());
        }
        return hooks;
    }

    private boolean isReReadingEnabled() {
        String propertyValue = System.getProperty(RE_READING_ENABLED_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Boolean.parseBoolean(propertyValue);
        }

        String envValue = System.getenv(RE_READING_ENABLED_ENV);
        if (envValue != null && !envValue.isBlank()) {
            return Boolean.parseBoolean(envValue);
        }

        return false;
    }
}
