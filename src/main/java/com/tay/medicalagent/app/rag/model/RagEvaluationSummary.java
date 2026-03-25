package com.tay.medicalagent.app.rag.model;

import java.util.List;

/**
 * 一次离线评估任务的汇总结果。
 */
public record RagEvaluationSummary(
        int totalCases,
        int hitCases,
        double hitRate,
        double meanReciprocalRank,
        double averageKeywordCoverage,
        List<RagEvaluationCaseResult> results
) {
}
