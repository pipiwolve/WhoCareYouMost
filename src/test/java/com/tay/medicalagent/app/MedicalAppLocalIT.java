package com.tay.medicalagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "medical.rag.enabled=false",
        "medical.report.planning.mode=off",
        "spring.ai.mcp.client.enabled=false"
})
class MedicalAppLocalIT {

    @Resource
    private MedicalApp medicalApp;

    @BeforeEach
    void clearMemory() {
        medicalApp.clearMemory();
    }

    @Test
    void saveUserProfileMemoryShouldMergeStableFacts() {
        medicalApp.saveUserProfileMemory("user_001", Map.of(
                "name", "小张",
                "age", 30
        ));
        medicalApp.saveUserProfileMemory("user_001", Map.of(
                "allergies", List.of("青霉素"),
                "gender", "男"
        ));

        Map<String, Object> profile = medicalApp.getUserProfileMemory("user_001").orElseThrow();
        assertEquals("小张", profile.get("name"));
        assertEquals(30, profile.get("age"));
        assertEquals("男", profile.get("gender"));
        assertEquals(List.of("青霉素"), profile.get("allergies"));
    }

    @Test
    void differentUsersShouldHaveIsolatedLongTermMemory() {
        medicalApp.saveUserProfileMemory("user_a", Map.of("name", "小王"));
        medicalApp.saveUserProfileMemory("user_b", Map.of("name", "小李"));

        Map<String, Object> userAProfile = medicalApp.getUserProfileMemory("user_a").orElseThrow();
        Map<String, Object> userBProfile = medicalApp.getUserProfileMemory("user_b").orElseThrow();

        assertEquals("小王", userAProfile.get("name"));
        assertEquals("小李", userBProfile.get("name"));
        assertFalse(userAProfile.equals(userBProfile));
    }

    @Test
    void clearMemoryShouldRemoveStoredProfiles() {
        medicalApp.saveUserProfileMemory("user_memory", Map.of("name", "小周"));
        assertTrue(medicalApp.getUserProfileMemory("user_memory").isPresent());

        medicalApp.clearMemory();

        assertTrue(medicalApp.getUserProfileMemory("user_memory").isEmpty());
    }
}
