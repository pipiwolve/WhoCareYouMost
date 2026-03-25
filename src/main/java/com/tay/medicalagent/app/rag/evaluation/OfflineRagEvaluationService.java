package com.tay.medicalagent.app.rag.evaluation;

import com.tay.medicalagent.app.rag.model.RagEvaluationSummary;

/**
 * RAG 离线评估服务抽象。
 */
public interface OfflineRagEvaluationService {

    /**
     * 运行默认评估数据集对应的离线检索评估。
     *
     * @return 评估汇总结果
     */
    RagEvaluationSummary runDefaultEvaluation();
}
