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
}
