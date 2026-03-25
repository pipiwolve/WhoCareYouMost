package com.tay.medicalagent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
/**
 * 用户长期画像 Hook。
 * <p>
 * 在模型调用前注入长期画像，在模型调用后回收本轮用户消息中的稳定事实。
 */
public class UserProfileMemoryHook extends ModelHook {

    private final UserProfileService userProfileService;

    public UserProfileMemoryHook(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Override
    public String getName() {
        return "medical_user_profile_memory";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.AFTER_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        String userId = getUserId(config);
        if (userId == null || userId.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        userProfileService.extractAndSaveProfileFacts(
                userProfileService.normalizeUserId(userId),
                messagesFromState(state)
        );
        return CompletableFuture.completedFuture(Map.of());
    }

    private String getUserId(RunnableConfig config) {
        return config.metadata("user_id")
                .map(Object::toString)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Message> messagesFromState(OverAllState state) {
        Optional<List> rawMessages = state.value("messages", List.class);
        if (rawMessages.isEmpty()) {
            return List.of();
        }
        return (List<Message>) rawMessages.get();
    }
}
