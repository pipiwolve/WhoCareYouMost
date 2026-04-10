package com.tay.medicalagent.app.service.chat;

import com.tay.medicalagent.app.chat.NormalizedMedicalReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalReplyFormatterTest {

    private final MedicalReplyFormatter formatter = new MedicalReplyFormatter();

    @Test
    void shouldNormalizeMarkdownAndExtractStructuredReply() {
        String rawReply = """
                **风险等级：**低风险
                
                # 核心判断：睡眠不足和轻度脱水可能性较大
                
                主要依据：
                - 头晕仅 2 天
                - 暂未见明确红旗信号
                
                建议下一步：
                - 先补充水分
                - 今晚尽量早点休息
                
                何时就医：
                - 出现胸痛或意识模糊时立即就医
                """;

        NormalizedMedicalReply normalizedReply = formatter.normalize(rawReply);

        assertFalse(normalizedReply.reply().contains("**"));
        assertFalse(normalizedReply.reply().contains("# "));
        assertTrue(normalizedReply.reply().contains("风险等级：低风险"));
        assertEquals("低风险", normalizedReply.structuredReply().riskLevel());
        assertEquals("睡眠不足和轻度脱水可能性较大", normalizedReply.structuredReply().summary());
        assertEquals(2, normalizedReply.structuredReply().basis().size());
        assertEquals(2, normalizedReply.structuredReply().nextSteps().size());
        assertEquals(1, normalizedReply.structuredReply().escalationSignals().size());
        assertTrue(normalizedReply.reply().contains("免责声明："));
    }

    @Test
    void shouldFallbackWhenNoStructuredLabelsExist() {
        NormalizedMedicalReply normalizedReply = formatter.normalize("请先补充水分并注意休息。");

        assertTrue(normalizedReply.reply().contains("请先补充水分并注意休息。"));
        assertEquals("请先补充水分并注意休息。", normalizedReply.structuredReply().summary());
        assertTrue(normalizedReply.reply().contains("免责声明："));
    }

    @Test
    void shouldSplitConcatenatedLabelsIntoSeparateStructuredFields() {
        String rawReply = """
                风险等级：中核心判断：胸闷可能有多种原因，需进一步了解情况
                主要依据：
                - 胸闷是一个非特异性症状，可能与心脏、肺部或其他因素有关
                建议下一步：
                - 休息，避免剧烈运动
                免责声明：本回答由AI生成，仅供健康信息参考，不能替代医生面诊。
                """;

        NormalizedMedicalReply normalizedReply = formatter.normalize(rawReply);

        assertEquals("中", normalizedReply.structuredReply().riskLevel());
        assertEquals("胸闷可能有多种原因，需进一步了解情况", normalizedReply.structuredReply().summary());
        assertTrue(normalizedReply.reply().contains("风险等级：中\n\n核心判断：胸闷可能有多种原因，需进一步了解情况"));
        assertFalse(normalizedReply.reply().contains("风险等级：中核心判断："));
    }
}
