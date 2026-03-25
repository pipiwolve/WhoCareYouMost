package com.tay.medicalagent.app.rag.ingestion;

import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
/**
 * 对官方 KeywordMetadataEnricher 的懒加载包装，按配置决定是否执行关键字富化。
 */
public class KeywordMetadataEnricherTransformer implements DocumentTransformer {

    private final MedicalAiModelProvider medicalAiModelProvider;
    private final MedicalRagProperties medicalRagProperties;

    public KeywordMetadataEnricherTransformer(
            MedicalAiModelProvider medicalAiModelProvider,
            MedicalRagProperties medicalRagProperties
    ) {
        this.medicalAiModelProvider = medicalAiModelProvider;
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        MedicalRagProperties.Ingestion.Keyword keyword = medicalRagProperties.getIngestion().getKeyword();
        if (!keyword.isEnabled() || documents.isEmpty()) {
            return documents;
        }

        KeywordMetadataEnricher enricher;
        if (StringUtils.hasText(keyword.getTemplate())) {
            enricher = KeywordMetadataEnricher.builder(medicalAiModelProvider.getChatModel())
                    .keywordsTemplate(new PromptTemplate(keyword.getTemplate()))
                    .build();
        }
        else {
            enricher = KeywordMetadataEnricher.builder(medicalAiModelProvider.getChatModel())
                    .keywordCount(keyword.getCount())
                    .build();
        }
        return enricher.apply(documents);
    }
}
