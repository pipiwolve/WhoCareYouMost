package com.tay.medicalagent.app.rag.retrieval;

import com.tay.medicalagent.app.prompt.MedicalPrompts;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
/**
 * 基于 DashScope 的检索查询增强器。
 * <p>
 * 只对 RAG 检索查询做一次轻量改写，不修改原始用户消息。
 */
public class DashScopeRetrievalQueryEnhancer implements RetrievalQueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRetrievalQueryEnhancer.class);

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final UserProfileService userProfileService;

    public DashScopeRetrievalQueryEnhancer(
            MedicalAiModelProvider medicalAiModelProvider,
            UserProfileService userProfileService
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.userProfileService = userProfileService;
    }

    @Override
    public String enhanceQuery(String query, String userId) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return "";
        }

        String profileContext = userProfileService.buildProfileContext(userProfileService.normalizeUserId(userId));
        if (profileContext.isBlank()) {
            profileContext = MedicalPrompts.NO_PROFILE_CONTEXT;
        }

        String rewritePrompt = """
                请把下面的医疗问题改写成适合知识库检索的一条查询。

                原始问题：
                %s

                用户长期资料（仅在相关时使用）：
                %s

                只返回改写后的检索查询。
                """.formatted(normalizedQuery, profileContext);

        try {
            ChatResponse response = medicalAiModelProvider.getChatModel().call(new Prompt(
                    List.of(
                            new SystemMessage(MedicalPrompts.QUERY_REWRITE_SYSTEM_PROMPT),
                            new UserMessage(rewritePrompt)
                    )
            ));
            String rewrittenQuery = sanitizeResponseText(extractAssistantText(response));
            if (rewrittenQuery.isBlank()) {
                return normalizedQuery;
            }
            return rebalanceNegationHeavyQuery(normalizedQuery, rewrittenQuery);
        }
        catch (Exception ex) {
            log.warn("Failed to enhance retrieval query, fallback to original query. userId={}", userId, ex);
            return normalizedQuery;
        }
    }

    private String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String sanitizeResponseText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return normalizeQuery(text.replace("```", ""));
    }

    private String rebalanceNegationHeavyQuery(String originalQuery, String rewrittenQuery) {
        String normalizedOriginal = normalizeQuery(originalQuery);
        String normalizedRewritten = normalizeQuery(rewrittenQuery);
        if (normalizedOriginal.isBlank() || normalizedRewritten.isBlank()) {
            return normalizedOriginal.isBlank() ? normalizedRewritten : normalizedOriginal;
        }

        List<String> rewrittenFragments = splitFragments(normalizedRewritten);
        if (rewrittenFragments.isEmpty()) {
            return normalizedOriginal;
        }

        List<String> negativeFragments = rewrittenFragments.stream()
                .filter(this::isNegativeFragment)
                .toList();
        List<String> positiveFragments = rewrittenFragments.stream()
                .filter(fragment -> !isNegativeFragment(fragment))
                .toList();

        if (positiveFragments.isEmpty()) {
            return normalizedOriginal;
        }
        if (!containsNegation(normalizedOriginal) || negativeFragments.size() < positiveFragments.size()) {
            return normalizedRewritten;
        }

        String dominantPositiveClause = extractDominantPositiveClause(normalizedOriginal);
        List<String> normalizedPositiveFragments = new ArrayList<>(positiveFragments);
        if (!dominantPositiveClause.isBlank()
                && normalizedPositiveFragments.stream().noneMatch(fragment -> shareClinicalAnchor(fragment, dominantPositiveClause))) {
            normalizedPositiveFragments = List.of(dominantPositiveClause);
        }

        String rebuiltQuery = String.join("，", normalizedPositiveFragments);
        if (!negativeFragments.isEmpty()) {
            rebuiltQuery = rebuiltQuery + "；辅助条件：" + String.join("，", negativeFragments);
        }
        return normalizeQuery(rebuiltQuery);
    }

    private List<String> splitFragments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> fragments = new ArrayList<>();
        for (String fragment : text.split("[，。；,;!?！？]+")) {
            String normalizedFragment = normalizeQuery(fragment);
            if (!normalizedFragment.isBlank()) {
                fragments.add(normalizedFragment);
            }
        }
        return List.copyOf(fragments);
    }

    private boolean containsNegation(String text) {
        return text.contains("无")
                || text.contains("没有")
                || text.contains("未")
                || text.contains("否认")
                || text.contains("不伴");
    }

    private boolean isNegativeFragment(String fragment) {
        String normalized = normalizeQuery(fragment);
        return normalized.startsWith("无")
                || normalized.startsWith("没有")
                || normalized.startsWith("未")
                || normalized.startsWith("否认")
                || normalized.startsWith("不伴");
    }

    private String extractDominantPositiveClause(String originalQuery) {
        List<String> fragments = splitFragments(originalQuery);
        String bestFragment = "";
        for (String fragment : fragments) {
            if (isNegativeFragment(fragment)) {
                continue;
            }

            String normalized = normalizePositiveFragment(fragment);
            if (normalized.length() > bestFragment.length()) {
                bestFragment = normalized;
            }
        }
        return bestFragment;
    }

    private String normalizePositiveFragment(String fragment) {
        String normalized = normalizeQuery(fragment)
                .replaceFirst("^(就是|只是|但是|不过|而是|同时|另外)", "")
                .trim();
        return normalized;
    }

    private boolean shareClinicalAnchor(String fragment, String dominantPositiveClause) {
        String normalizedFragment = normalizeAnchor(fragment);
        String normalizedClause = normalizeAnchor(dominantPositiveClause);
        if (normalizedFragment.isBlank() || normalizedClause.isBlank()) {
            return false;
        }

        for (char character : normalizedClause.toCharArray()) {
            if (normalizedFragment.indexOf(character) >= 0) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAnchor(String text) {
        return normalizeQuery(text)
                .replace("的", "")
                .replace("了", "")
                .replace("是", "")
                .replace("时", "")
                .replace("候", "")
                .replace("就", "")
                .replace("也", "")
                .replace("都", "")
                .replace("和", "")
                .replace("与", "")
                .replace("并", "")
                .replace("且", "")
                .replace("症状", "")
                .replace("情况", "")
                .replace("感觉", "");
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return query.replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
