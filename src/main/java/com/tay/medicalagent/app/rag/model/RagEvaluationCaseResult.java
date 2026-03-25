package com.tay.medicalagent.app.rag.model;

import java.util.List;

/**
 * 单条离线评估样本的检索结果。
 */
public record RagEvaluationCaseResult(
        String caseId,
        String question,
        List<String> expectedSourceIds,
        List<String> retrievedSourceIds,
        boolean sourceHit,
        int firstRelevantRank,
        double reciprocalRank,
        double keywordCoverage
) {
}
