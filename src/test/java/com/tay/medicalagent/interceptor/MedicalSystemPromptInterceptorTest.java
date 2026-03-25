package com.tay.medicalagent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalSystemPromptInterceptorTest {

    @Test
    void shouldAppendProfileContextAndResponseFormatIntoSystemPrompt() {
        UserProfileService userProfileService = mock(UserProfileService.class);
        when(userProfileService.normalizeUserId("user_1")).thenReturn("user_1");
        when(userProfileService.buildProfileContext("user_1"))
                .thenReturn("[MEDICAL_LONG_TERM_MEMORY]\n年龄：28\n性别：男");

        MedicalSystemPromptInterceptor interceptor = new MedicalSystemPromptInterceptor(userProfileService);
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("基础医疗提示"))
                .messages(List.of(new UserMessage("我头晕")))
                .context(new HashMap<>(java.util.Map.of("user_id", "user_1")))
                .build();

        AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
        ModelResponse response = interceptor.interceptModel(request, modelRequest -> {
            capturedRequest.set(modelRequest);
            return ModelResponse.of(new AssistantMessage("ok"));
        });

        assertNotNull(response);
        assertNotNull(capturedRequest.get());
        assertEquals(1, capturedRequest.get().getMessages().size());
        assertEquals("我头晕", capturedRequest.get().getMessages().get(0).getText());
        assertTrue(capturedRequest.get().getSystemMessage().getText().contains("基础医疗提示"));
        assertTrue(capturedRequest.get().getSystemMessage().getText().contains("[MEDICAL_LONG_TERM_MEMORY]"));
        assertTrue(capturedRequest.get().getSystemMessage().getText().contains(MedicalPrompts.MEDICAL_RESPONSE_FORMAT_PROMPT.trim()));
    }
}
