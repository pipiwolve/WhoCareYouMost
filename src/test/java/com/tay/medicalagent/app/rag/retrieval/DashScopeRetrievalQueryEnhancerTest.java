package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashScopeRetrievalQueryEnhancerTest {

    @Test
    void shouldRewriteQueryUsingDashScopeResponse() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(userProfileService.normalizeUserId("user_1")).thenReturn("user_1");
        when(userProfileService.buildProfileContext("user_1"))
                .thenReturn("[MEDICAL_LONG_TERM_MEMORY]\n年龄：32\n过敏史：青霉素");
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("```胸痛 20分钟 出汗 恶心```")))));

        DashScopeRetrievalQueryEnhancer enhancer =
                new DashScopeRetrievalQueryEnhancer(medicalAiModelProvider, userProfileService);

        String rewrittenQuery = enhancer.enhanceQuery("  持续胸痛20分钟，伴出汗和恶心  ", "user_1");
        assertEquals("胸痛 20分钟 出汗 恶心", rewrittenQuery);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertTrue(prompt.getSystemMessage().getText().contains(MedicalPrompts.QUERY_REWRITE_SYSTEM_PROMPT.trim()));
        assertTrue(prompt.getUserMessage().getText().contains("持续胸痛20分钟，伴出汗和恶心"));
        assertTrue(prompt.getUserMessage().getText().contains("年龄：32"));
    }

    @Test
    void shouldFallbackToOriginalQueryWhenModelReturnsBlank() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(userProfileService.normalizeUserId("user_2")).thenReturn("user_2");
        when(userProfileService.buildProfileContext("user_2")).thenReturn("");
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("   ")))));

        DashScopeRetrievalQueryEnhancer enhancer =
                new DashScopeRetrievalQueryEnhancer(medicalAiModelProvider, userProfileService);

        String rewrittenQuery = enhancer.enhanceQuery("  胸痛伴恶心  ", "user_2");
        assertEquals("胸痛伴恶心", rewrittenQuery);
    }

    @Test
    void shouldRebalanceNegationHeavyRewrite() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(userProfileService.normalizeUserId("user_4")).thenReturn("user_4");
        when(userProfileService.buildProfileContext("user_4")).thenReturn("[MEDICAL_LONG_TERM_MEMORY]\n年龄：28\n性别：男");
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("28岁男性，无发热、无胸痛，站立时症状加重")))));

        DashScopeRetrievalQueryEnhancer enhancer =
                new DashScopeRetrievalQueryEnhancer(medicalAiModelProvider, userProfileService);

        String rewrittenQuery = enhancer.enhanceQuery("没有发烧，也没有胸痛，就是站起来的时候更明显", "user_4");
        assertEquals("28岁男性，站立时症状加重；辅助条件：无发热，无胸痛", rewrittenQuery);
    }

    @Test
    void shouldSkipModelCallWhenQueryIsBlank() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        UserProfileService userProfileService = mock(UserProfileService.class);

        DashScopeRetrievalQueryEnhancer enhancer =
                new DashScopeRetrievalQueryEnhancer(medicalAiModelProvider, userProfileService);

        assertEquals("", enhancer.enhanceQuery("   ", "user_3"));
        verifyNoInteractions(medicalAiModelProvider, userProfileService);
    }
}
