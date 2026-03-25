package com.tay.medicalagent.app.service.profile;

import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashScopeUserProfileFactExtractorTest {

    @Test
    void shouldReturnNormalizedFactsWhenConfidenceIsHigh() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                        ```json
                        {
                          "name": "",
                          "age": 32,
                          "gender": "女性",
                          "allergies": ["青霉素", "头孢", "青霉素"],
                          "confidence": 0.91,
                          "evidence": "用户明确说自己32岁女性，对青霉素过敏。"
                        }
                        ```
                        """)))));

        DashScopeUserProfileFactExtractor extractor = new DashScopeUserProfileFactExtractor(medicalAiModelProvider);

        List<Message> messages = List.of(
                new UserMessage("32岁女性，对青霉素过敏。"),
                new SystemMessage("系统消息不应进入用户转录")
        );
        Map<String, Object> facts = extractor.extractFacts(messages, Map.of("name", "小张"));

        assertEquals(32, facts.get("age"));
        assertEquals("女", facts.get("gender"));
        assertEquals(List.of("青霉素", "头孢"), facts.get("allergies"));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertTrue(prompt.getUserMessage().getText().contains("name: 小张"));
        assertTrue(prompt.getUserMessage().getText().contains("1. 32岁女性，对青霉素过敏。"));
        assertTrue(!prompt.getUserMessage().getText().contains("系统消息不应进入用户转录"));
    }

    @Test
    void shouldReturnEmptyWhenConfidenceIsTooLow() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);
        when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                        {
                          "name": "",
                          "age": 32,
                          "gender": "女",
                          "allergies": ["青霉素"],
                          "confidence": 0.45,
                          "evidence": "表达不明确"
                        }
                        """)))));

        DashScopeUserProfileFactExtractor extractor = new DashScopeUserProfileFactExtractor(medicalAiModelProvider);
        Map<String, Object> facts = extractor.extractFacts(List.of(new UserMessage("大概32岁吧，可能对青霉素不太舒服。")), Map.of());

        assertTrue(facts.isEmpty());
    }

    @Test
    void shouldSkipModelCallWhenNoUserMessagesExist() {
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        DashScopeUserProfileFactExtractor extractor = new DashScopeUserProfileFactExtractor(medicalAiModelProvider);

        Map<String, Object> facts = extractor.extractFacts(List.of(new SystemMessage("仅系统消息")), Map.of());

        assertTrue(facts.isEmpty());
        verifyNoInteractions(medicalAiModelProvider);
    }
}
