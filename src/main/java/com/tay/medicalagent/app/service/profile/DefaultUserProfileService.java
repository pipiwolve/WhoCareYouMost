package com.tay.medicalagent.app.service.profile;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.repository.UserProfileRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
/**
 * 默认用户长期画像服务实现。
 * <p>
 * 使用规则抽取从用户消息中提炼稳定资料，并将其以系统消息形式回注到模型上下文。
 */
public class DefaultUserProfileService implements UserProfileService {

    private static final String DEFAULT_USER_ID = "default_user";

    private static final Pattern NAME_PATTERN =
            Pattern.compile("(?:我叫|我的名字叫|你可以叫我)([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,19})");
    private static final Pattern AGE_PATTERN = Pattern.compile("(?:今年|我)(\\d{1,3})岁");
    private static final Pattern GENDER_PATTERN = Pattern.compile("(?:我是|性别是)(男|女|男性|女性)");
    private static final Pattern ALLERGY_PATTERN =
            Pattern.compile("(?:我对|对|我有)?([^，。；,;\\s]{1,20})过敏");
    private static final List<String> STRUCTURED_EXTRACTION_HINTS = List.of(
            "我叫", "名字", "称呼", "岁", "男性", "女性", "男", "女", "过敏"
    );

    private final UserProfileRepository userProfileRepository;
    private final UserProfileFactExtractor userProfileFactExtractor;

    public DefaultUserProfileService(
            UserProfileRepository userProfileRepository,
            UserProfileFactExtractor userProfileFactExtractor
    ) {
        this.userProfileRepository = userProfileRepository;
        this.userProfileFactExtractor = userProfileFactExtractor;
    }

    @Override
    public String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER_ID;
        }
        return userId.trim();
    }

    @Override
    public void saveUserProfileMemory(String userId, Map<String, Object> updates) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        Map<String, Object> normalizedUpdates = normalizeProfileUpdates(updates);
        if (normalizedUpdates.isEmpty()) {
            return;
        }

        LinkedHashMap<String, Object> mergedProfile =
                new LinkedHashMap<>(getUserProfileMemory(userId).orElse(Map.of()));

        normalizedUpdates.forEach((key, value) -> {
            if ("allergies".equals(key)) {
                mergedProfile.put(key, mergeStringCollection(mergedProfile.get(key), value));
            }
            else {
                mergedProfile.put(key, value);
            }
        });

        userProfileRepository.save(userId, mergedProfile);
    }

    @Override
    public Optional<Map<String, Object>> getUserProfileMemory(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return userProfileRepository.findByUserId(userId);
    }

    @Override
    public List<Message> mergeProfileIntoMessages(String userId, List<Message> messages) {
        String memoryContext = buildProfileContext(userId);
        if (memoryContext.isBlank()) {
            return messages;
        }
        return mergeMemoryIntoMessages(messages, memoryContext);
    }

    @Override
    public void extractAndSaveProfileFacts(String userId, List<Message> messages) {
        Map<String, Object> extractedFacts = extractStableProfileFacts(messages);
        if (shouldAttemptStructuredExtraction(messages, extractedFacts)) {
            Map<String, Object> llmExtractedFacts =
                    userProfileFactExtractor.extractFacts(messages, getUserProfileMemory(userId).orElse(Map.of()));
            extractedFacts = mergeExtractedFacts(extractedFacts, llmExtractedFacts);
        }
        if (!extractedFacts.isEmpty()) {
            saveUserProfileMemory(userId, extractedFacts);
        }
    }

    @Override
    public String buildProfileContext(String userId) {
        return getUserProfileMemory(userId)
                .map(this::buildProfileContext)
                .orElse("");
    }

    @Override
    public void clearMemory() {
        userProfileRepository.clear();
    }

    private String buildProfileContext(Map<String, Object> profile) {
        List<String> lines = new ArrayList<>();
        addContextLine(lines, "姓名", profile.get("name"));
        addContextLine(lines, "年龄", profile.get("age"));
        addContextLine(lines, "性别", profile.get("gender"));
        addContextLine(lines, "过敏史", profile.get("allergies"));
        if (lines.isEmpty()) {
            return "";
        }
        return MedicalPrompts.MEMORY_SYSTEM_MARKER + "\n" + String.join("\n", lines);
    }

    private void addContextLine(List<String> lines, String label, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            List<String> values = toStringList(collection);
            if (!values.isEmpty()) {
                lines.add(label + "：" + String.join("、", values));
            }
            return;
        }
        String text = safeText(value);
        if (!text.isEmpty()) {
            lines.add(label + "：" + text);
        }
    }

    private List<Message> mergeMemoryIntoMessages(List<Message> messages, String memoryContext) {
        List<Message> sanitizedMessages = new ArrayList<>();
        int firstSystemMessageIndex = -1;

        for (Message message : messages) {
            if (isGeneratedMemoryMessage(message)) {
                continue;
            }
            if (firstSystemMessageIndex < 0 && message instanceof SystemMessage) {
                firstSystemMessageIndex = sanitizedMessages.size();
            }
            sanitizedMessages.add(message);
        }

        if (firstSystemMessageIndex >= 0) {
            SystemMessage existing = (SystemMessage) sanitizedMessages.get(firstSystemMessageIndex);
            sanitizedMessages.set(
                    firstSystemMessageIndex,
                    new SystemMessage(existing.getText() + "\n\n" + memoryContext)
            );
        }
        else {
            sanitizedMessages.add(0, new SystemMessage(memoryContext));
        }

        return sanitizedMessages;
    }

    private boolean isGeneratedMemoryMessage(Message message) {
        return message instanceof SystemMessage && message.getText().contains(MedicalPrompts.MEMORY_SYSTEM_MARKER);
    }

    private Map<String, Object> extractStableProfileFacts(List<Message> messages) {
        LinkedHashMap<String, Object> extracted = new LinkedHashMap<>();
        List<String> allergies = new ArrayList<>();

        for (Message message : messages) {
            if (!(message instanceof UserMessage)) {
                continue;
            }
            String text = message.getText();
            matchFirst(NAME_PATTERN, text).ifPresent(name -> extracted.put("name", name));
            matchFirst(AGE_PATTERN, text).ifPresent(age -> extracted.put("age", Integer.parseInt(age)));
            matchFirst(GENDER_PATTERN, text).ifPresent(gender -> extracted.put("gender", normalizeGender(gender)));
            matchFirst(ALLERGY_PATTERN, text).ifPresent(allergies::add);
        }

        if (!allergies.isEmpty()) {
            extracted.put("allergies", allergies);
        }
        return extracted;
    }

    private boolean shouldAttemptStructuredExtraction(List<Message> messages, Map<String, Object> regexFacts) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (containsAllPrimaryFields(regexFacts)) {
            return false;
        }

        StringBuilder userText = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof UserMessage && message.getText() != null) {
                userText.append(message.getText()).append('\n');
            }
        }

        String combinedUserText = userText.toString().trim();
        if (combinedUserText.isEmpty()) {
            return false;
        }

        for (String hint : STRUCTURED_EXTRACTION_HINTS) {
            if (combinedUserText.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAllPrimaryFields(Map<String, Object> facts) {
        return facts.containsKey("name")
                && facts.containsKey("age")
                && facts.containsKey("gender")
                && facts.containsKey("allergies");
    }

    private Map<String, Object> mergeExtractedFacts(Map<String, Object> ruleFacts, Map<String, Object> llmFacts) {
        if (llmFacts == null || llmFacts.isEmpty()) {
            return ruleFacts;
        }

        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(ruleFacts);
        llmFacts.forEach((key, value) -> {
            if (value == null) {
                return;
            }

            if ("allergies".equals(key)) {
                if (merged.containsKey(key)) {
                    merged.put(key, mergeStringCollection(merged.get(key), value));
                }
                else {
                    merged.put(key, value);
                }
                return;
            }

            merged.putIfAbsent(key, value);
        });
        return merged;
    }

    private Map<String, Object> normalizeProfileUpdates(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }

            switch (key) {
                case "name" -> {
                    String text = safeText(value);
                    if (!text.isEmpty()) {
                        normalized.put(key, text);
                    }
                }
                case "age" -> {
                    Integer age = normalizeAge(value);
                    if (age != null) {
                        normalized.put(key, age);
                    }
                }
                case "gender" -> {
                    String gender = normalizeGender(safeText(value));
                    if (!gender.isEmpty()) {
                        normalized.put(key, gender);
                    }
                }
                case "allergies" -> {
                    List<String> allergies = toStringList(value);
                    if (!allergies.isEmpty()) {
                        normalized.put(key, allergies);
                    }
                }
                default -> {
                    if (value instanceof Collection<?> collection) {
                        List<String> values = toStringList(collection);
                        if (!values.isEmpty()) {
                            normalized.put(key, values);
                        }
                    }
                    else {
                        String text = safeText(value);
                        if (!text.isEmpty()) {
                            normalized.put(key, text);
                        }
                    }
                }
            }
        });
        return normalized;
    }

    private Integer normalizeAge(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = safeText(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> mergeStringCollection(Object existingValue, Object newValue) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(toStringList(existingValue));
        merged.addAll(toStringList(newValue));
        return List.copyOf(merged);
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return toStringList(collection);
        }
        String text = safeText(value);
        return text.isEmpty() ? List.of() : List.of(text);
    }

    private List<String> toStringList(Collection<?> values) {
        List<String> normalized = new ArrayList<>();
        for (Object value : values) {
            String text = safeText(value);
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }
        return List.copyOf(new LinkedHashSet<>(normalized));
    }

    private Optional<String> matchFirst(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(value -> !value.isEmpty());
        }
        return Optional.empty();
    }

    private String normalizeGender(String gender) {
        if ("男性".equals(gender)) {
            return "男";
        }
        if ("女性".equals(gender)) {
            return "女";
        }
        return gender == null ? "" : gender.trim();
    }

    private String safeText(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().trim();
        return text.isEmpty() ? "" : text;
    }
}
