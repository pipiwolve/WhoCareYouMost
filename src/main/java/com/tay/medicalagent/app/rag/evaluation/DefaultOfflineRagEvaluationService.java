package com.tay.medicalagent.app.rag.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeIngestionService;
import com.tay.medicalagent.app.rag.ingestion.KnowledgeManifestRepository;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.model.RagEvaluationCase;
import com.tay.medicalagent.app.rag.model.RagEvaluationCaseResult;
import com.tay.medicalagent.app.rag.model.RagEvaluationSummary;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
/**
 * 默认离线 RAG 评估实现。
 * <p>
 * 读取内置评估样本，逐条执行检索并输出命中率、MRR 与关键词覆盖度，
 * 用于在不触发完整对话生成的情况下验证知识库检索质量。
 */
public class DefaultOfflineRagEvaluationService implements OfflineRagEvaluationService {

    private final PathMatchingResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MedicalKnowledgeRetriever medicalKnowledgeRetriever;
    private final MedicalRagProperties medicalRagProperties;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final KnowledgeManifestRepository knowledgeManifestRepository;

    public DefaultOfflineRagEvaluationService(
            MedicalKnowledgeRetriever medicalKnowledgeRetriever,
            MedicalRagProperties medicalRagProperties,
            KnowledgeIngestionService knowledgeIngestionService,
            KnowledgeManifestRepository knowledgeManifestRepository
    ) {
        this.medicalKnowledgeRetriever = medicalKnowledgeRetriever;
        this.medicalRagProperties = medicalRagProperties;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.knowledgeManifestRepository = knowledgeManifestRepository;
    }

    @Override
    public RagEvaluationSummary runDefaultEvaluation() {
        ensureKnowledgeBaseReady();
        List<RagEvaluationCase> cases = loadCases();
        if (cases.isEmpty()) {
            return new RagEvaluationSummary(0, 0, 0, 0, 0, List.of());
        }

        List<RagEvaluationCaseResult> results = new ArrayList<>();
        int hitCases = 0;
        double reciprocalRankSum = 0;
        double keywordCoverageSum = 0;

        for (RagEvaluationCase evaluationCase : cases) {
            RagContext ragContext = medicalKnowledgeRetriever.retrieve(evaluationCase.question());
            List<KnowledgeSource> sources = ragContext.sources();
            List<String> retrievedSourceIds = sources.stream().map(KnowledgeSource::sourceId).toList();

            int firstRelevantRank = findFirstRelevantRank(retrievedSourceIds, evaluationCase.expectedSourceIds());
            boolean sourceHit = firstRelevantRank > 0;
            double reciprocalRank = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0;
            double keywordCoverage = computeKeywordCoverage(ragContext.contextText(), evaluationCase.expectedKeywords());

            if (sourceHit) {
                hitCases++;
            }
            reciprocalRankSum += reciprocalRank;
            keywordCoverageSum += keywordCoverage;

            results.add(new RagEvaluationCaseResult(
                    evaluationCase.id(),
                    evaluationCase.question(),
                    safeList(evaluationCase.expectedSourceIds()),
                    retrievedSourceIds,
                    sourceHit,
                    firstRelevantRank,
                    reciprocalRank,
                    keywordCoverage
            ));
        }

        int totalCases = cases.size();
        return new RagEvaluationSummary(
                totalCases,
                hitCases,
                totalCases == 0 ? 0 : (double) hitCases / totalCases,
                totalCases == 0 ? 0 : reciprocalRankSum / totalCases,
                totalCases == 0 ? 0 : keywordCoverageSum / totalCases,
                List.copyOf(results)
        );
    }

    private void ensureKnowledgeBaseReady() {
        if (!medicalRagProperties.isEnabled()) {
            return;
        }
        if (knowledgeManifestRepository.hasIndexedIds()) {
            return;
        }
        knowledgeIngestionService.reindexKnowledgeBase();
    }

    private List<RagEvaluationCase> loadCases() {
        try {
            Resource resource = resourcePatternResolver.getResource(medicalRagProperties.getEvaluation().getDatasetLocation());
            if (!resource.exists()) {
                return List.of();
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return objectMapper.readValue(inputStream, new TypeReference<>() {
                });
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("加载离线评估数据失败", ex);
        }
    }

    private int findFirstRelevantRank(List<String> retrievedSourceIds, List<String> expectedSourceIds) {
        if (retrievedSourceIds == null || expectedSourceIds == null || expectedSourceIds.isEmpty()) {
            return 0;
        }
        Set<String> expected = new HashSet<>(expectedSourceIds);
        for (int i = 0; i < retrievedSourceIds.size(); i++) {
            if (expected.contains(retrievedSourceIds.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private double computeKeywordCoverage(String contextText, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 1.0;
        }
        String haystack = contextText == null ? "" : contextText;
        int hitKeywords = 0;
        for (String keyword : expectedKeywords) {
            if (keyword != null && !keyword.isBlank() && haystack.contains(keyword)) {
                hitKeywords++;
            }
        }
        return (double) hitKeywords / expectedKeywords.size();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
