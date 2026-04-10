package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.chat.NormalizedMedicalReply;
import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import com.tay.medicalagent.app.prompt.MedicalPrompts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class MedicalReplyFormatter {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```+");
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s*");
    private static final String LABEL_TOKENS =
            "风险等级|当前风险等级|核心判断|初步判断|初步评估|主要依据|判断依据|依据|建议下一步|下一步建议|建议|建议处理|处理建议|何时就医|何时必须升级就医|必须升级就医|需要补充|还需补充|需补充|补充信息|免责声明";
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "^(" + LABEL_TOKENS + ")[:：]\\s*(.*)$"
    );
    private static final Pattern INLINE_LABEL_SPLIT_PATTERN = Pattern.compile(
            "([^\\n])\\s*(" + LABEL_TOKENS + ")([:：])"
    );

    public NormalizedMedicalReply normalize(String rawReply) {
        String cleanedReply = sanitize(rawReply);
        if (cleanedReply.isBlank()) {
            return new NormalizedMedicalReply(
                    MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER,
                    StructuredMedicalReply.empty(MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER)
            );
        }

        Map<String, List<String>> sections = parseSections(cleanedReply);
        StructuredMedicalReply structuredReply = buildStructuredReply(sections, cleanedReply);
        String normalizedReply = buildNormalizedReply(structuredReply, cleanedReply, !sections.isEmpty());
        return new NormalizedMedicalReply(normalizedReply, structuredReply);
    }

    private Map<String, List<String>> parseSections(String cleanedReply) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        for (String line : cleanedReply.split("\\n")) {
            String normalizedLine = normalizeLine(line);
            if (normalizedLine.isBlank()) {
                continue;
            }

            var matcher = LABEL_PATTERN.matcher(normalizedLine);
            if (matcher.matches()) {
                currentSection = resolveSectionKey(matcher.group(1));
                sections.computeIfAbsent(currentSection, key -> new ArrayList<>());
                String inlineValue = normalizeLine(matcher.group(2));
                if (!inlineValue.isBlank()) {
                    sections.get(currentSection).add(inlineValue);
                }
                continue;
            }

            if (currentSection == null) {
                sections.computeIfAbsent("summary", key -> new ArrayList<>()).add(normalizedLine);
                continue;
            }
            sections.computeIfAbsent(currentSection, key -> new ArrayList<>()).add(normalizedLine);
        }

        return sections;
    }

    private StructuredMedicalReply buildStructuredReply(Map<String, List<String>> sections, String cleanedReply) {
        String riskLevel = extractSingleValue(sections, "riskLevel");
        String summary = extractSingleValue(sections, "summary");
        if (summary.isBlank()) {
            summary = extractFallbackSummary(cleanedReply);
        }

        List<String> basis = toItems(sections.get("basis"));
        List<String> nextSteps = toItems(sections.get("nextSteps"));
        List<String> escalationSignals = toItems(sections.get("escalationSignals"));
        List<String> followUpQuestions = toItems(sections.get("followUpQuestions"));
        String disclaimer = extractSingleValue(sections, "disclaimer");
        if (disclaimer.isBlank()) {
            disclaimer = MedicalPrompts.DEFAULT_MEDICAL_DISCLAIMER;
        }

        return new StructuredMedicalReply(
                riskLevel,
                summary,
                basis,
                nextSteps,
                escalationSignals,
                followUpQuestions,
                disclaimer
        );
    }

    private String buildNormalizedReply(StructuredMedicalReply structuredReply, String cleanedReply, boolean parsed) {
        if (!parsed) {
            if (cleanedReply.contains("免责声明：")) {
                return cleanedReply;
            }
            return cleanedReply + "\n\n免责声明：" + structuredReply.disclaimer();
        }

        List<String> blocks = new ArrayList<>();
        if (!structuredReply.riskLevel().isBlank()) {
            blocks.add("风险等级：" + structuredReply.riskLevel());
        }
        if (!structuredReply.summary().isBlank()) {
            blocks.add("核心判断：" + structuredReply.summary());
        }
        appendListBlock(blocks, "主要依据：", structuredReply.basis());
        appendListBlock(blocks, "建议下一步：", structuredReply.nextSteps());
        appendListBlock(blocks, "何时就医：", structuredReply.escalationSignals());
        appendListBlock(blocks, "需要补充：", structuredReply.followUpQuestions());
        blocks.add("免责声明：" + structuredReply.disclaimer());
        return String.join("\n\n", blocks);
    }

    private void appendListBlock(List<String> blocks, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder(title);
        for (String item : items) {
            builder.append("\n- ").append(item);
        }
        blocks.add(builder.toString());
    }

    private String extractFallbackSummary(String cleanedReply) {
        for (String line : cleanedReply.split("\\n")) {
            String normalizedLine = normalizeLine(line);
            if (!normalizedLine.isBlank()) {
                return normalizedLine;
            }
        }
        return "";
    }

    private String extractSingleValue(Map<String, List<String>> sections, String key) {
        List<String> values = sections.get(key);
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(" ", toItems(values));
    }

    private List<String> toItems(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        List<String> items = new ArrayList<>();
        for (String line : lines) {
            String normalized = normalizeLine(line);
            if (normalized.isBlank()) {
                continue;
            }
            items.add(normalized);
        }
        return List.copyOf(items);
    }

    private String sanitize(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return "";
        }

        String text = rawReply.replace("\r\n", "\n").replace('\r', '\n');
        text = CODE_FENCE_PATTERN.matcher(text).replaceAll("");
        text = HEADING_PATTERN.matcher(text).replaceAll("");
        text = INLINE_LABEL_SPLIT_PATTERN.matcher(text).replaceAll("$1\n$2$3");
        text = text.replace("**", "")
                .replace("__", "")
                .replace("`", "");

        List<String> normalizedLines = new ArrayList<>();
        boolean previousBlank = false;
        for (String line : text.split("\\n")) {
            String normalizedLine = normalizeLine(line);
            if (normalizedLine.isBlank()) {
                if (!previousBlank) {
                    normalizedLines.add("");
                    previousBlank = true;
                }
                continue;
            }
            normalizedLines.add(normalizedLine);
            previousBlank = false;
        }

        while (!normalizedLines.isEmpty() && normalizedLines.get(0).isBlank()) {
            normalizedLines.remove(0);
        }
        while (!normalizedLines.isEmpty() && normalizedLines.get(normalizedLines.size() - 1).isBlank()) {
            normalizedLines.remove(normalizedLines.size() - 1);
        }
        return String.join("\n", normalizedLines).trim();
    }

    private String normalizeLine(String line) {
        if (line == null) {
            return "";
        }

        String normalized = line.trim()
                .replaceFirst("^[>\\-*+]+\\s*", "")
                .replaceFirst("^[✅⚠️❗🔍➡️→•]+\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.startsWith("- ")) {
            normalized = normalized.substring(2).trim();
        }
        return normalized;
    }

    private String resolveSectionKey(String label) {
        return switch (label) {
            case "风险等级", "当前风险等级" -> "riskLevel";
            case "核心判断", "初步判断", "初步评估" -> "summary";
            case "主要依据", "判断依据", "依据" -> "basis";
            case "建议下一步", "下一步建议", "建议", "建议处理", "处理建议" -> "nextSteps";
            case "何时就医", "何时必须升级就医", "必须升级就医" -> "escalationSignals";
            case "需要补充", "还需补充", "需补充", "补充信息" -> "followUpQuestions";
            case "免责声明" -> "disclaimer";
            default -> "summary";
        };
    }
}
