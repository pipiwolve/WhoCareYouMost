package com.tay.medicalagent.app.service.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
/**
 * DashScope 模型提供器。
 * <p>
 * 统一负责 DashScope API 与 ChatModel 的惰性初始化，避免业务层直接处理模型创建细节。
 */
public class MedicalAiModelProvider {

    private static final String DEFAULT_CHAT_MODEL = "qwen-max";

    private volatile DashScopeApi dashScopeApi;
    private volatile ChatModel chatModel;

    @Value("${spring.ai.dashscope.chat.options.model:" + DEFAULT_CHAT_MODEL + "}")
    private String configuredChatModel;

    public ChatModel getChatModel() {
        if (chatModel == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = DashScopeChatModel.builder()
                            .dashScopeApi(getDashScopeApi())
                            .defaultOptions(DashScopeChatOptions.builder().model(configuredChatModel).build())
                            .build();
                }
            }
        }
        return chatModel;
    }

    public DashScopeApi getDashScopeApi() {
        if (dashScopeApi == null) {
            synchronized (this) {
                if (dashScopeApi == null) {
                    dashScopeApi = DashScopeApi.builder()
                            .apiKey(resolveApiKey())
                            .build();
                }
            }
        }
        return dashScopeApi;
    }

    private String resolveApiKey() {
        String propertyApiKey = System.getProperty("DASHSCOPE_API_KEY");
        if (propertyApiKey != null && !propertyApiKey.isBlank()) {
            return propertyApiKey;
        }

        String envApiKey = System.getenv("DASHSCOPE_API_KEY");
        if (envApiKey != null && !envApiKey.isBlank()) {
            return envApiKey;
        }

        String legacyPropertyKey = System.getProperty("AI_DASHSCOPE_API_KEY");
        if (legacyPropertyKey != null && !legacyPropertyKey.isBlank()) {
            return legacyPropertyKey;
        }

        String legacyApiKey = System.getenv("AI_DASHSCOPE_API_KEY");
        if (legacyApiKey != null && !legacyApiKey.isBlank()) {
            return legacyApiKey;
        }

        throw new MedicalModelConfigurationException(
                "缺少 DashScope API Key，请配置 DASHSCOPE_API_KEY 或 AI_DASHSCOPE_API_KEY"
        );
    }
}
