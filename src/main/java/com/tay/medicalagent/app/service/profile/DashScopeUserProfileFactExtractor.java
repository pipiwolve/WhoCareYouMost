package com.tay.medicalagent.app.service.profile;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
/**
 * 基于 DashScope 的结构化用户资料抽取器。
 * <p>
 * 当规则提取遗漏年龄、性别等信息时，使用结构化输出做谨慎补充。
 */
public class DashScopeUserProfileFactExtractor implements UserProfileFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(DashScopeUserProfileFactExtractor.class);

    private static final double MIN_CONFIDENCE = 0.75d;

    private final MedicalAiModelProvider medicalAiModelProvider;

    public DashScopeUserProfileFactExtractor(MedicalAiModelProvider medicalAiModelProvider) {
        this.medicalAiModelProvider = medicalAiModelProvider;
    }

    @Override
    public Map<String, Object> extractFacts(List<Message> messages, Map<String, Object> existingProfile) {
        String userTranscript = buildUserTranscript(messages);
        if (userTranscript.isBlank()) {
            return Map.of();
        }

        BeanOutputConverter<UserProfileFactExtractionResult> outputConverter =
                new BeanOutputConverter<>(UserProfileFactExtractionResult.class);

        String extractionPrompt = """
                请从以下用户消息中提取可以长期保存的稳定资料。

                当前已有资料：
                %s

                用户消息：
                %s

                返回要求：
                1. 如果没有明确证据，请不要填写该字段。
                2. allergies 必须是字符串数组。
                3. 请严格按以下格式返回。

                %s
                """.formatted(
                formatExistingProfile(existingProfile),
                userTranscript,
                outputConverter.getFormat()
        );

        try {
            ChatResponse response = medicalAiModelProvider.getChatModel().call(new Prompt(
                    List.of(
                            new SystemMessage(MedicalPrompts.PROFILE_EXTRACTION_SYSTEM_PROMPT),
                            new UserMessage(extractionPrompt)
                    )
            ));

            String responseText = stripCodeFence(extractAssistantText(response));
            UserProfileFactExtractionResult result = outputConverter.convert(responseText);
            return normalizeResult(result);
        }
        catch (Exception ex) {
            log.warn("Failed to extract structured user profile facts, fallback to rule-based result.", ex);
            return Map.of();
        }
    }

    private Map<String, Object> normalizeResult(UserProfileFactExtractionResult result) {
        if (result == null) {
            return Map.of();
        }

        double confidence = result.confidence() == null ? 0d : result.confidence();
        if (confidence < MIN_CONFIDENCE) {
            return Map.of();
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();

        String name = safeText(result.name());
        if (!name.isEmpty()) {
            normalized.put("name", name);
        }

        Integer age = normalizeAge(result.age());
        if (age != null) {
            normalized.put("age", age);
        }

        String gender = normalizeGender(result.gender());
        if (!gender.isEmpty()) {
            normalized.put("gender", gender);
        }

        List<String> allergies = normalizeAllergies(result.allergies());
        if (!allergies.isEmpty()) {
            normalized.put("allergies", allergies);
        }

        return normalized;
    }

    private String buildUserTranscript(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (Message message : messages) {
            if (!(message instanceof UserMessage)) {
                continue;
            }
            String text = safeText(message.getText());
            if (text.isEmpty()) {
                continue;
            }
            lines.add(index + ". " + text);
            index++;
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String formatExistingProfile(Map<String, Object> existingProfile) {
        if (existingProfile == null || existingProfile.isEmpty()) {
            return MedicalPrompts.NO_PROFILE_CONTEXT;
        }
        List<String> lines = new ArrayList<>();
        existingProfile.forEach((key, value) -> {
            if (value != null) {
                lines.add(key + ": " + value);
            }
        });
        return lines.isEmpty() ? MedicalPrompts.NO_PROFILE_CONTEXT : String.join(System.lineSeparator(), lines);
    }

    private String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String stripCodeFence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private Integer normalizeAge(Integer age) {
        if (age == null || age < 0 || age > 120) {
            return null;
        }
        return age;
    }

    private String normalizeGender(String gender) {
        String text = safeText(gender);
        if ("男性".equals(text)) {
            return "男";
        }
        if ("女性".equals(text)) {
            return "女";
        }
        return ("男".equals(text) || "女".equals(text)) ? text : "";
    }

    private List<String> normalizeAllergies(List<String> allergies) {
        if (allergies == null || allergies.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String allergy : allergies) {
            String text = safeText(allergy);
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.isEmpty() ? "" : text;
    }
}
