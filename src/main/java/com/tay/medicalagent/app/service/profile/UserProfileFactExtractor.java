package com.tay.medicalagent.app.service.profile;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * 用户长期资料抽取器。
 * <p>
 * 用于在规则抽取不足时，以结构化方式从用户消息中补充稳定资料。
 */
public interface UserProfileFactExtractor {

    /**
     * 从消息中提取用户长期资料。
     *
     * @param messages        当前消息列表
     * @param existingProfile 当前已存储资料
     * @return 结构化资料；如果无法稳定提取，应返回空 Map
     */
    Map<String, Object> extractFacts(List<Message> messages, Map<String, Object> existingProfile);
}
