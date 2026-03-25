package com.tay.medicalagent.app.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
/**
 * 默认 RAG 触发策略。
 * <p>
 * 通过轻量规则过滤问候语、画像类输入和报告请求，避免无意义检索。
 */
public class DefaultRagTriggerPolicy implements RagTriggerPolicy {

    private static final Pattern REPORT_REQUEST_PATTERN =
            Pattern.compile(".*(生成|整理|导出|输出|给我|帮我做).{0,8}(诊断报告|医疗报告|分诊报告|报告|总结).*");

    @Override
    public boolean shouldRetrieve(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }

        String normalized = query.trim();
        if (REPORT_REQUEST_PATTERN.matcher(normalized).matches()) {
            return false;
        }

        if (containsAny(normalized, "你好", "谢谢", "再见", "早上好", "晚上好")) {
            return false;
        }

        if (containsAny(normalized, "我的名字", "我叫什么", "长期信息", "记住这些信息")) {
            return false;
        }

        return normalized.length() >= 4;
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
