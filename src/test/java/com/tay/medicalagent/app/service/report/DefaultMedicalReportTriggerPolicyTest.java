package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMedicalReportTriggerPolicyTest {

    private final DefaultMedicalReportTriggerPolicy policy = new DefaultMedicalReportTriggerPolicy();

    @Test
    void shouldReturnUrgentForHighRiskReply() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "高风险",
                        "存在急危重风险",
                        List.of("持续胸痛"),
                        List.of("立即急诊"),
                        List.of("呼吸困难"),
                        List.of(),
                        "免责声明"
                ),
                "风险等级：高风险\n建议下一步：立即急诊",
                conversation("我胸痛")
        );

        assertEquals(ReportTriggerLevel.URGENT, decision.level());
        assertEquals("风险较高，可立即生成诊断报告协助就医。", decision.actionText());
    }

    @Test
    void shouldReturnRecommendedForMediumRiskWithCompleteAssessment() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "中风险",
                        "考虑呼吸道感染",
                        List.of("发热", "咳嗽"),
                        List.of("补液休息"),
                        List.of("持续高热"),
                        List.of(),
                        "免责声明"
                ),
                "风险等级：中风险\n核心判断：考虑呼吸道感染",
                conversation("我发烧两天了")
        );

        assertEquals(ReportTriggerLevel.RECOMMENDED, decision.level());
    }

    @Test
    void shouldReturnRecommendedForShortMediumRiskLabelWithCompleteAssessment() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "中",
                        "考虑呼吸道感染",
                        List.of("发热", "咳嗽"),
                        List.of("补液休息"),
                        List.of("持续高热"),
                        List.of(),
                        "免责声明"
                ),
                "风险等级：中\n核心判断：考虑呼吸道感染",
                conversation("我发烧两天了")
        );

        assertEquals(ReportTriggerLevel.RECOMMENDED, decision.level());
    }

    @Test
    void shouldReturnNoneForLowRiskWhenConversationTooShort() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "低风险",
                        "考虑轻度疲劳",
                        List.of("近期加班"),
                        List.of("多休息"),
                        List.of(),
                        List.of(),
                        "免责声明"
                ),
                "风险等级：低风险\n核心判断：考虑轻度疲劳",
                conversation("我有点头晕")
        );

        assertEquals(ReportTriggerLevel.NONE, decision.level());
    }

    @Test
    void shouldReturnSuggestedForLowRiskWhenConversationIsComplete() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "低风险",
                        "考虑睡眠不足",
                        List.of("近期熬夜", "无红旗症状"),
                        List.of("补觉休息"),
                        List.of(),
                        List.of(),
                        "免责声明"
                ),
                "风险等级：低风险\n核心判断：考虑睡眠不足",
                conversation(
                        "我头晕一天了",
                        "好的，我继续描述一下，最近总是熬夜"
                )
        );

        assertEquals(ReportTriggerLevel.SUGGESTED, decision.level());
    }

    @Test
    void shouldReturnNoneForInformationGatheringTurn() {
        ReportTriggerDecision decision = policy.evaluate(
                new StructuredMedicalReply(
                        "",
                        "还需要更多信息",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("请补充持续时间"),
                        "免责声明"
                ),
                "还需要更多信息，请补充持续时间。",
                conversation("我有点不舒服", "已经一天了")
        );

        assertEquals(ReportTriggerLevel.NONE, decision.level());
    }

    private List<Message> conversation(String... userMessages) {
        java.util.ArrayList<Message> messages = new java.util.ArrayList<>();
        for (String userMessage : userMessages) {
            messages.add(new UserMessage(userMessage));
            messages.add(new AssistantMessage("收到"));
        }
        return messages;
    }
}
