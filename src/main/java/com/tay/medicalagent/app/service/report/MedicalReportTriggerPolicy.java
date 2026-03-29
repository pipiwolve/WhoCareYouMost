package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.chat.StructuredMedicalReply;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 医疗报告触发策略。
 */
public interface MedicalReportTriggerPolicy {

    /**
     * 根据结构化回复、原始回复和当前线程上下文判断是否向前端开放报告入口。
     *
     * @param structuredMedicalReply 结构化回复
     * @param assistantReply 助手归一化回复
     * @param conversation 当前线程完整对话
     * @return 触发决策
     */
    ReportTriggerDecision evaluate(
            StructuredMedicalReply structuredMedicalReply,
            String assistantReply,
            List<Message> conversation
    );
}
