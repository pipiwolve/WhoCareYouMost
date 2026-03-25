package com.tay.medicalagent.app.repository;

import java.util.Map;
import java.util.Optional;

/**
 * 用户画像仓储抽象。
 */
public interface UserProfileRepository {

    /**
     * 按用户 ID 查询画像。
     *
     * @param userId 用户唯一标识
     * @return 画像内容
     */
    Optional<Map<String, Object>> findByUserId(String userId);

    /**
     * 保存用户画像。
     *
     * @param userId 用户唯一标识
     * @param profile 画像内容
     */
    void save(String userId, Map<String, Object> profile);

    /**
     * 清空全部画像数据。
     */
    void clear();
}
