package com.tay.medicalagent.app.service.profile;

import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.tay.medicalagent.app.repository.memory.MemoryStoreUserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultUserProfileServiceTest {

    @Test
    void regexFactsShouldTakePrecedenceWhileLlmFillsMissingFields() {
        RecordingFactExtractor factExtractor = new RecordingFactExtractor(Map.of(
                "name", "错误姓名",
                "age", 88,
                "gender", "女",
                "allergies", List.of("头孢")
        ));
        DefaultUserProfileService userProfileService = new DefaultUserProfileService(
                new MemoryStoreUserProfileRepository(new MemoryStore()),
                factExtractor
        );

        List<Message> messages = List.of(new UserMessage("我叫小张，今年30岁，对青霉素过敏。"));
        userProfileService.extractAndSaveProfileFacts("user_rule_first", messages);

        Map<String, Object> profile = userProfileService.getUserProfileMemory("user_rule_first").orElseThrow();
        assertEquals("小张", profile.get("name"));
        assertEquals(30, profile.get("age"));
        assertEquals("女", profile.get("gender"));
        assertEquals(List.of("青霉素", "头孢"), profile.get("allergies"));
        assertEquals(1, factExtractor.invocationCount);
    }

    @Test
    void llmFallbackShouldFillFactsWhenRegexMissesFreeFormExpression() {
        RecordingFactExtractor factExtractor = new RecordingFactExtractor(Map.of(
                "age", 32,
                "gender", "女"
        ));
        DefaultUserProfileService userProfileService = new DefaultUserProfileService(
                new MemoryStoreUserProfileRepository(new MemoryStore()),
                factExtractor
        );

        List<Message> messages = List.of(new UserMessage("32岁女性，对青霉素过敏。"));
        userProfileService.extractAndSaveProfileFacts("user_llm_fallback", messages);

        Map<String, Object> profile = userProfileService.getUserProfileMemory("user_llm_fallback").orElseThrow();
        assertEquals(32, profile.get("age"));
        assertEquals("女", profile.get("gender"));
        assertEquals(List.of("青霉素"), profile.get("allergies"));
        assertEquals(1, factExtractor.invocationCount);
    }

    @Test
    void shouldSkipLlmFallbackWhenNoProfileHintsExist() {
        RecordingFactExtractor factExtractor = new RecordingFactExtractor(Map.of("gender", "男"));
        DefaultUserProfileService userProfileService = new DefaultUserProfileService(
                new MemoryStoreUserProfileRepository(new MemoryStore()),
                factExtractor
        );

        List<Message> messages = List.of(new UserMessage("最近胸口有点闷，还伴随轻微咳嗽。"));
        userProfileService.extractAndSaveProfileFacts("user_no_hints", messages);

        assertTrue(userProfileService.getUserProfileMemory("user_no_hints").isEmpty());
        assertEquals(0, factExtractor.invocationCount);
    }

    private static final class RecordingFactExtractor implements UserProfileFactExtractor {

        private final Map<String, Object> result;
        private int invocationCount;

        private RecordingFactExtractor(Map<String, Object> result) {
            this.result = result;
        }

        @Override
        public Map<String, Object> extractFacts(List<Message> messages, Map<String, Object> existingProfile) {
            invocationCount++;
            return result;
        }
    }
}
