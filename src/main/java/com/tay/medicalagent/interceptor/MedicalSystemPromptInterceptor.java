package com.tay.medicalagent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class MedicalSystemPromptInterceptor extends ModelInterceptor {

    private final UserProfileService userProfileService;

    public MedicalSystemPromptInterceptor(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Override
    public String getName() {
        return "medical_system_prompt_interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<String> promptBlocks = new ArrayList<>();
        Optional.ofNullable(request.getSystemMessage())
                .map(SystemMessage::getText)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .ifPresent(promptBlocks::add);

        resolveProfileContext(request)
                .ifPresent(promptBlocks::add);
        promptBlocks.add(MedicalPrompts.MEDICAL_RESPONSE_FORMAT_PROMPT);

        ModelRequest enhancedRequest = ModelRequest.builder(request)
                .systemMessage(new SystemMessage(String.join("\n\n", promptBlocks)))
                .build();
        return handler.call(enhancedRequest);
    }

    private Optional<String> resolveProfileContext(ModelRequest request) {
        Object userIdValue = request.getContext().get("user_id");
        if (userIdValue == null) {
            return Optional.empty();
        }

        String profileContext = userProfileService.buildProfileContext(
                userProfileService.normalizeUserId(userIdValue.toString())
        );
        if (profileContext == null || profileContext.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(profileContext.trim());
    }
}
