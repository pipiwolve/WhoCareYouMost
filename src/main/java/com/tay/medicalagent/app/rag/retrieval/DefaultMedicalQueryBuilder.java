package com.tay.medicalagent.app.rag.retrieval;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
/**
 * 默认医疗检索查询构建器。
 * <p>
 * 优先取最后一条用户问题，对追问场景会拼接上一轮用户问题以提高检索命中率。
 */
public class DefaultMedicalQueryBuilder implements MedicalQueryBuilder {

    @Override
    public String buildSearchQuery(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        List<String> userMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof UserMessage && message.getText() != null && !message.getText().isBlank()) {
                userMessages.add(message.getText().trim());
            }
        }

        if (userMessages.isEmpty()) {
            return "";
        }

        String current = normalizeQuery(userMessages.get(userMessages.size() - 1));
        if (current.isBlank()) {
            return "";
        }

        if (isLikelyFollowUp(current) && userMessages.size() > 1) {
            String previous = normalizeQuery(userMessages.get(userMessages.size() - 2));
            if (!previous.isBlank()) {
                return previous + "\n补充问题：" + current;
            }
        }

        return current;
    }

    @Override
    public String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return query.replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isLikelyFollowUp(String query) {
        return query.length() <= 30 && containsAny(query, "这个", "这种", "它", "那我", "那这种情况", "还需要", "还能", "什么时候");
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
