package com.tay.medicalagent.controller;

import com.tay.medicalagent.app.rag.evaluation.OfflineRagEvaluationService;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeIngestionService;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.model.RagEvaluationSummary;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
/**
 * RAG 管理接口。
 * <p>
 * 提供知识库重建、手动检索验证和离线评估入口，主要面向开发调试和运维排查。
 */
public class MedicalRagAdminController {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final MedicalKnowledgeRetriever medicalKnowledgeRetriever;
    private final OfflineRagEvaluationService offlineRagEvaluationService;

    public MedicalRagAdminController(
            KnowledgeIngestionService knowledgeIngestionService,
            MedicalKnowledgeRetriever medicalKnowledgeRetriever,
            OfflineRagEvaluationService offlineRagEvaluationService
    ) {
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.medicalKnowledgeRetriever = medicalKnowledgeRetriever;
        this.offlineRagEvaluationService = offlineRagEvaluationService;
    }

    @PostMapping("/knowledge/reindex")
    public KnowledgeBaseRefreshResult reindexKnowledgeBase() {
        return knowledgeIngestionService.reindexKnowledgeBase();
    }

    @GetMapping("/knowledge/search")
    public RagContext searchKnowledge(@RequestParam String query) {
        return medicalKnowledgeRetriever.retrieve(query);
    }

    @PostMapping("/evaluation/run")
    public RagEvaluationSummary runOfflineEvaluation() {
        return offlineRagEvaluationService.runDefaultEvaluation();
    }
}
