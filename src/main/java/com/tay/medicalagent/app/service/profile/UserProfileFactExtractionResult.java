package com.tay.medicalagent.app.service.profile;

import java.util.List;

/**
 * 结构化用户资料抽取结果。
 *
 * @param name       姓名
 * @param age        年龄
 * @param gender     性别
 * @param allergies  过敏史
 * @param confidence 整体抽取置信度
 * @param evidence   依据说明
 */
public record UserProfileFactExtractionResult(
        String name,
        Integer age,
        String gender,
        List<String> allergies,
        Double confidence,
        String evidence
) {
}
