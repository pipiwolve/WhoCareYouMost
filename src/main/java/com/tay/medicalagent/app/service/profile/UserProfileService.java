package com.tay.medicalagent.app.service.profile;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户长期画像服务抽象。
 * <p>
 * 负责用户标识规范化、画像的存取，以及在模型调用前后做画像上下文注入与事实提取。
 */
public interface UserProfileService {

    /**
     * 规范化用户标识，保证画像存储键稳定可复用。
     *
     * @param userId 原始用户标识
     * @return 规范化后的用户标识
     */
    String normalizeUserId(String userId);

    /**
     * 保存或合并用户长期画像。
     *
     * @param userId 用户唯一标识
     * @param updates 画像增量字段
     */
    void saveUserProfileMemory(String userId, Map<String, Object> updates);

    /**
     * 查询用户长期画像。
     *
     * @param userId 用户唯一标识
     * @return 画像内容
     */
    Optional<Map<String, Object>> getUserProfileMemory(String userId);

    /**
     * 将用户画像以系统消息形式合并到当前消息列表中。
     *
     * @param userId 用户唯一标识
     * @param messages 当前消息列表
     * @return 注入画像后的消息列表
     */
    List<Message> mergeProfileIntoMessages(String userId, List<Message> messages);

    /**
     * 从用户消息中抽取稳定事实并回写到长期画像。
     *
     * @param userId 用户唯一标识
     * @param messages 本轮消息列表
     */
    void extractAndSaveProfileFacts(String userId, List<Message> messages);

    /**
     * 生成可供模型直接理解的用户画像文本。
     *
     * @param userId 用户唯一标识
     * @return 画像上下文文本
     */
    String buildProfileContext(String userId);

    /**
     * 清空全部用户画像记忆。
     */
    void clearMemory();
}
