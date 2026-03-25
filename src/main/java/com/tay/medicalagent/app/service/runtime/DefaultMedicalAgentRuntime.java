package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final MemoryStore userProfileMemoryStore;
    private final UserProfileMemoryHook userProfileMemoryHook;
    private final MedicalRagAgentHook medicalRagAgentHook;
    private final MedicalSystemPromptInterceptor medicalSystemPromptInterceptor;
    private final MedicalRagContextInterceptor medicalRagContextInterceptor;
    private final MyLogModelInterceptor myLogModelInterceptor;

    private volatile ReactAgent medicalAgent;

    public DefaultMedicalAgentRuntime(
            MedicalAiModelProvider medicalAiModelProvider,
            MemoryStore userProfileMemoryStore,
            UserProfileMemoryHook userProfileMemoryHook,
            MedicalRagAgentHook medicalRagAgentHook,
            MedicalSystemPromptInterceptor medicalSystemPromptInterceptor,
            MedicalRagContextInterceptor medicalRagContextInterceptor,
            MyLogModelInterceptor myLogModelInterceptor
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.userProfileMemoryStore = userProfileMemoryStore;
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
                            .saver(new MemorySaver())
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
