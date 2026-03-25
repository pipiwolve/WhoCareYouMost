package com.tay.medicalagent.app.rag.model;

import java.util.List;

/**
 * 单条离线评估样本定义。
 */
public record RagEvaluationCase(
        String id,
        String question,
        List<String> expectedSourceIds,
        List<String> expectedKeywords
) {
}
