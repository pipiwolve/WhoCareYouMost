package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.KnowledgeBaseRefreshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medical.rag", name = "bootstrap-on-startup", havingValue = "true")
/**
 * 应用启动时的 RAG 知识库预热任务。
 * <p>
 * 当显式开启配置后，会在 Spring Boot 完成启动后自动执行一次知识库重建。
 */
public class MedicalRagBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MedicalRagBootstrapRunner.class);

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final MedicalRagProperties medicalRagProperties;

    public MedicalRagBootstrapRunner(
            KnowledgeIngestionService knowledgeIngestionService,
            MedicalRagProperties medicalRagProperties
    ) {
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!medicalRagProperties.isEnabled()) {
            log.info("Skip RAG bootstrap because medical.rag.enabled=false");
            return;
        }

        KnowledgeBaseRefreshResult result = knowledgeIngestionService.reindexKnowledgeBase();
        log.info(
                "RAG knowledge base bootstrapped. sourceFileCount={}, documentCount={}, vectorStoreType={}",
                result.sourceFileCount(),
                result.documentCount(),
                result.vectorStoreType()
        );
    }
}
